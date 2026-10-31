package com.piggyback.hrvmon;

import android.os.SystemClock;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Thread que conecta ao ELM327, descobre os PIDs suportados e lê os dados em laço.
 * Os PIDs rápidos (rotação, λ medido e pedido, pressão do turbo, avanço, rail, wastegate,
 * borboleta) são lidos em toda volta; os lentos (temperaturas, trims, etanol...) se revezam a
 * cada 3 voltas.
 *
 * Os pedidos vão só para a central do motor. Com o pedido para todos os módulos, câmbio e
 * outros respondem junto, e clones do ELM327 embaralham as respostas de vários quadros.
 *
 * Pede até 6 PIDs por vez. Alguns clones do ELM327 travam ou voltam vazio com pedidos
 * grandes; nesse caso cai para 3 e depois para 1 PID por pedido.
 */
final class Poller implements Runnable {
    private static final int[] REQUEST_SIZES = {6, 4, 3, 1};
    /** Tempos limite testados (× 4 ms), do menor para o maior; o padrão do ELM327 é 0x32 = 200 ms. */
    private static final int[] TIMEOUTS = {0x10, 0x19};
    private static final int MAX_TIMEOUTS = 5;
    private static final int LAMBDA = -1, BOOST = -2, RAIL = -3;
    private static final int[] FAST = {0x0C, LAMBDA, 0x44, BOOST, 0x0E, RAIL, 0x72, 0x11};
    private static final int[] SLOW = {0x0B, 0x87, 0x49, 0x68, 0x0F, 0x67, 0x05, 0x06, 0x07,
        0x33, 0x52, 0x0D, 0x04, 0x66, 0x10};
    /** Valores mais velhos que isso não entram na análise das puxadas. */
    private static final float STALE_S = 2f;

    private final Links.Factory factory;
    private volatile boolean running = true;
    private volatile boolean polling;
    private volatile Links.Link link;
    private Elm elm;

    private final float[] state = new float[Ch.COUNT];
    private final float[] stamp = new float[Ch.COUNT];      // quando cada canal foi lido (s)
    private final float[] decoded = new float[Ch.COUNT];
    private final float[] fresh = new float[Ch.COUNT];
    private long t0 = SystemClock.elapsedRealtime();
    private final boolean[] sup = new boolean[256];
    private final List<Integer> fastPids = new ArrayList<>();
    private final List<Integer> slowPids = new ArrayList<>();
    // Reatribuídas (não alteradas) ao mudar o tamanho do pedido, para não quebrar a iteração em curso.
    private List<int[]> fast = new ArrayList<>();
    private List<int[]> slow = new ArrayList<>();
    private int sizeIdx;
    private int okAtSize;
    private int emptyAtSize;
    private int timeouts;
    private boolean countSuffix;
    // Com cabeçalhos ligados, ID da central do motor cujos quadros o app separa dos demais.
    private String frameFilter;
    private final int[] pidOk = new int[256];
    private final int[] pidEmpty = new int[256];

    Poller(Links.Factory factory) {
        this.factory = factory;
    }

    void stop() {
        running = false;
        Links.closeQuietly(link);
    }

    boolean isPolling() {
        return polling;
    }

    @Override
    public void run() {
        String reason = "Desconectado";
        try {
            Session.status = "Conectando em " + factory.describe() + "…";
            link = factory.open();
            if (!running) return;
            elm = new Elm(link.in(), link.out());
            init();
            loop();
        } catch (IOException | RuntimeException e) {
            if (running) reason = "Erro: " + e.getMessage();
        } finally {
            running = false;
            polling = false;
            Links.closeQuietly(link);
            if (Session.poller == this) Session.poller = null;
            Session.status = reason;
        }
    }

    private void init() throws IOException {
        Session.status = "Inicializando o ELM327…";
        String version = "ELM327";
        try {
            Matcher m = Pattern.compile("ELM327\\s*V?([0-9.]+)").matcher(elm.cmd("ATZ", 4000).toUpperCase(Locale.US));
            if (m.find()) version = "ELM327 v" + m.group(1);
        } catch (Elm.Timeout ignored) {
            // Alguns clones não respondem ao reset; segue com a configuração.
        }
        elm.cmd("ATE0", 1500);
        elm.cmd("ATL0", 1500);
        elm.cmd("ATS0", 1500);
        elm.cmd("ATH0", 1500);
        elm.cmd("ATAT2", 1500);

        Session.status = "Procurando a ECU…";
        if (!probe("ATSP6", 4000) && !probe("ATSP0", 12000)) {
            throw new IOException("a ECU não respondeu. A ignição está ligada?");
        }
        String dp = elm.cmd("ATDPN", 1500).replaceAll("[^0-9A-Fa-f]", "").toUpperCase(Locale.US);
        String proto = dp.isEmpty() ? "?" : dp.substring(dp.length() - 1);

        Session.status = "Procurando a central do motor…";
        String header = selectEngineEcu(proto);

        Session.status = "Lendo PIDs suportados…";
        readSupported();
        buildGroups();
        Pids.resetScale();
        String scale = "";
        if (sup[0x4F]) {
            try {
                query(new int[]{0x4F}, 1500);
                scale = " · escala 0x4F: λ até " + Fmt.f(Pids.lambdaMax, 0) + ", MAP até " + Fmt.f(Pids.mapMax, 0) + " kPa";
            } catch (Elm.Timeout e) {
                elm.recover();
            }
        }

        try {
            List<int[]> r = messages(elm.cmd("010C1", 1500));
            countSuffix = !r.isEmpty() && r.get(0).length >= 2 && r.get(0)[0] == 0x41 && r.get(0)[1] == 0x0C;
        } catch (Elm.Timeout e) {
            countSuffix = false;
            elm.recover();
        }

        Arrays.fill(state, Float.NaN);
        if (sup[0x33]) {
            try {
                query(new int[]{0x33}, 1500);
            } catch (Elm.Timeout e) {
                elm.recover();
            }
        }

        Session.info = version + " · protocolo " + protoName(proto) + " · " + header + scale;
        Session.supported = sup.clone();
    }

    /**
     * Liga os cabeçalhos, vê quem responde ao 0100 e passa a falar só com a central do motor
     * (7E8 → 7E0 em 11 bits; 18DAF110 → DA10F1 em 29 bits), ou pelo menos a ouvir só ela.
     * Se nada disso der, fica no endereço funcional e pede um PID por vez, porque aí as
     * respostas de vários módulos vêm misturadas.
     */
    private String selectEngineEcu(String proto) throws IOException {
        boolean ext = proto.equals("7") || proto.equals("9");
        if (!ext && !proto.equals("6") && !proto.equals("8")) return "endereço padrão";
        int hdrLen = ext ? 8 : 3;

        String best = null;
        int bestBits = -1;
        int responders = 0;
        elm.cmd("ATH1", 1500);
        try {
            String r = elm.cmd("0100", 3000);
            for (String line : r.toUpperCase(Locale.US).replace(" ", "").split("[\r\n]+")) {
                if (line.contains("4100")) responders++;
                if (!line.matches("[0-9A-F]+") || line.length() < hdrLen + 14) continue;
                String hdr = line.substring(0, hdrLen);
                String data = line.substring(hdrLen + 2);  // pula o byte de tamanho (PCI)
                if (!data.startsWith("4100")) continue;
                int bits = Long.bitCount(Long.parseLong(data.substring(4, 12), 16));
                boolean engine = hdr.equals("7E8") || hdr.equals("18DAF110");
                if (engine) bits += 1000;
                if (bits > bestBits) {
                    bestBits = bits;
                    best = hdr;
                }
            }
        } catch (Elm.Timeout e) {
            elm.recover();
        }
        elm.cmd("ATH0", 1500);

        String functional = ext ? "ATSHDB33F1" : "ATSH7DF";
        if (best != null) {
            String target = ext ? "DA" + best.substring(6, 8) + "F1"
                    : Integer.toHexString(Integer.parseInt(best, 16) - 8).toUpperCase(Locale.US);
            // 1) Pedido direto para a central, com o filtro de recepção explícito: alguns clones
            //    não ajustam o filtro sozinhos em 29 bits e respondem NO DATA.
            if (ext) elm.cmd("ATCP" + best.substring(0, 2), 1500);
            elm.cmd("ATSH" + target, 1500);
            elm.cmd("ATCRA" + best, 1500);
            if (probeSingle()) {
                Session.log("   central do motor: responde em " + best + ", pedidos para " + target);
                return "central do motor " + target;
            }
            // 2) Pedido para todos, mas o adaptador só ouve a central; os outros módulos são ignorados.
            elm.cmd(functional, 1500);
            if (probeSingle()) {
                Session.log("   pedido funcional, ouvindo só " + best);
                return "funcional, ouvindo só a central " + best;
            }
            elm.cmd("ATCRA", 1500);
            // 3) Pedido para todos com cabeçalhos ligados: o próprio app separa os quadros da central,
            //    mesmo que o adaptador intercale os de vários módulos.
            elm.cmd("ATH1", 1500);
            frameFilter = best;
            if (probeSingle()) {
                Session.log("   pedido funcional, separando pelos cabeçalhos os quadros de " + best);
                return "funcional, separando os quadros da central " + best;
            }
            frameFilter = null;
            elm.cmd("ATH0", 1500);
            Session.log("   não deu para isolar a central " + best + "; voltando ao endereço funcional");
        }
        elm.cmd(functional, 1500);
        if (responders <= 1) {
            Session.log("   só um módulo responde; endereço funcional basta");
            return "endereço funcional (um módulo responde)";
        }
        sizeIdx = REQUEST_SIZES.length - 1;
        Session.log("   sem endereço da central do motor: um PID por pedido");
        return "todos os módulos (um PID por pedido)";
    }

    /** Mensagens de uma resposta: separadas por cabeçalho, se ligado, ou no formato padrão. */
    private List<int[]> messages(String raw) {
        return frameFilter != null ? Elm.parseFrames(raw, frameFilter) : Elm.parse(raw);
    }

    /** Verdadeiro se exatamente um módulo respondeu ao 0100. */
    private boolean probeSingle() throws IOException {
        try {
            int n = 0;
            for (int[] m : messages(elm.cmd("0100", 3000))) {
                if (m.length >= 2 && m[0] == 0x41 && m[1] == 0x00) n++;
            }
            return n == 1;
        } catch (Elm.Timeout e) {
            elm.recover();
            return false;
        }
    }

    private boolean probe(String protocolCmd, long timeoutMs) throws IOException {
        if (protocolCmd != null) elm.cmd(protocolCmd, 1500);
        try {
            for (int[] m : messages(elm.cmd("0100", timeoutMs))) {
                if (m.length >= 2 && m[0] == 0x41 && m[1] == 0x00) return true;
            }
        } catch (Elm.Timeout e) {
            elm.recover();
        }
        return false;
    }

    private void readSupported() throws IOException {
        for (int base = 0; base <= 0xC0; base += 0x20) {
            if (base != 0 && !sup[base]) break;
            String r;
            try {
                r = elm.cmd("01" + Fmt.hex2(base), 3000);
            } catch (Elm.Timeout e) {
                elm.recover();
                break;
            }
            for (int[] m : messages(r)) {
                if (m.length < 6 || m[0] != 0x41 || m[1] != base) continue;
                for (int i = 0; i < 32; i++) {
                    if ((m[2 + i / 8] & (0x80 >> (i % 8))) != 0) sup[base + i + 1] = true;
                }
            }
        }
    }

    private void buildGroups() {
        int lambda = sup[0x24] ? 0x24 : sup[0x34] ? 0x34 : 0;
        int rail = sup[0x23] ? 0x23 : sup[0x59] ? 0x59 : 0;
        // Fonte da pressão do turbo: sensor de boost (0x70) > coletor faixa ampla (0x87) > coletor (0x0B).
        int boost = sup[0x70] ? 0x70 : sup[0x87] ? 0x87 : 0x0B;
        fastPids.clear();
        slowPids.clear();
        for (int pid : FAST) {
            if (pid == LAMBDA) pid = lambda;
            if (pid == BOOST) pid = boost;
            if (pid == RAIL) pid = rail;
            addIfSupported(pid, fastPids);
        }
        for (int pid : SLOW) addIfSupported(pid, slowPids);
        regroup();
    }

    private void addIfSupported(int pid, List<Integer> out) {
        if (pid > 0 && sup[pid] && Pids.LEN[pid] > 0 && !fastPids.contains(pid) && !slowPids.contains(pid)) out.add(pid);
    }

    private void regroup() {
        int size = REQUEST_SIZES[sizeIdx];
        fast = chunk(fastPids, size);
        slow = chunk(slowPids, size);
        Session.pidsPerRequest = size;
        int[] u = new int[fastPids.size() + slowPids.size()];
        int i = 0;
        for (int pid : fastPids) u[i++] = pid;
        for (int pid : slowPids) u[i++] = pid;
        Session.used = u;
    }

    private static List<int[]> chunk(List<Integer> pids, int size) {
        List<int[]> out = new ArrayList<>();
        for (int i = 0; i < pids.size(); i += size) {
            int n = Math.min(size, pids.size() - i);
            int[] g = new int[n];
            for (int k = 0; k < n; k++) g[k] = pids.get(i + k);
            out.add(g);
        }
        return out;
    }

    private void loop() throws IOException {
        t0 = SystemClock.elapsedRealtime();
        Arrays.fill(stamp, -1e9f);
        long rateT = t0;
        int rateN = 0;
        int cycle = 0;
        int slowIdx = 0;
        polling = true;
        Session.status = "Conectado";
        while (running) {
            List<int[]> f = fast;
            // Se o tamanho do pedido mudar no meio da volta, os grupos antigos são abandonados.
            for (int i = 0; i < f.size() && f == fast; i++) request(f.get(i));
            List<int[]> sl = slow;
            if (!sl.isEmpty() && cycle % 3 == 0) request(sl.get(slowIdx++ % sl.size()));
            cycle++;
            if (cycle == 15) tuneTimeout();
            derive();
            float t = now();
            Session.store.add(t, state);
            // As puxadas só veem leituras recentes; a tela e o CSV mostram o último valor lido.
            for (int k = 0; k < Ch.COUNT; k++) fresh[k] = t - stamp[k] <= STALE_S ? state[k] : Float.NaN;
            Session.pulls.feed(t, fresh);

            rateN++;
            long now = SystemClock.elapsedRealtime();
            if (now - rateT >= 1000) {
                Session.rateHz = rateN * 1000f / (now - rateT);
                rateN = 0;
                rateT = now;
            }
        }
    }

    private void request(int[] g) throws IOException {
        int n;
        try {
            n = query(g, g.length > 1 ? 2500 : 1500);
            timeouts = 0;
        } catch (Elm.Timeout e) {
            elm.recover();
            if (++timeouts >= MAX_TIMEOUTS) throw new IOException("o adaptador parou de responder");
            n = -1;
        }
        if (g.length > 1) {
            if (n > 0) {
                okAtSize++;
                return;
            }
            // Pedido grande travou (uma vez basta) ou voltou vazio 3 vezes sem nunca ter funcionado.
            if (okAtSize == 0 && (n < 0 || ++emptyAtSize >= 3)) shrink();
            return;
        }
        int pid = g[0];
        if (n > 0) {
            pidOk[pid]++;
            pidEmpty[pid] = 0;
        } else if (pidOk[pid] == 0 && ++pidEmpty[pid] >= 5) {
            // A ECU diz que suporta, mas nunca responde: para de perguntar.
            Session.log("   PID " + Fmt.hex2(pid) + " não responde; removido da leitura");
            fastPids.remove(Integer.valueOf(pid));
            slowPids.remove(Integer.valueOf(pid));
            regroup();
        }
    }

    /**
     * Com vários módulos respondendo, o adaptador espera o tempo limite inteiro depois de cada
     * resposta (no HR-V, ~170 ms por pedido). Testa tempos menores e fica com o menor que ainda
     * traz o pedido completo da central em todas as tentativas.
     */
    private void tuneTimeout() throws IOException {
        if (fast.isEmpty()) return;
        int[] g = fast.get(0);
        long base = timeQuery(g);
        if (base < 0) return;
        if (elm.cmd("ATAT0", 1500).contains("?")) return;
        for (int st : TIMEOUTS) {
            if (elm.cmd("ATST" + Fmt.hex2(st), 1500).contains("?")) break;
            long ms = timeQuery(g);
            if (ms > 0 && ms < base * 0.8f) {
                Session.log("   tempo limite " + st * 4 + " ms: " + ms + " ms por pedido (antes " + base + ")");
                Session.info += " · tempo limite " + st * 4 + " ms";
                return;
            }
        }
        elm.cmd("ATST32", 1500);
        elm.cmd("ATAT2", 1500);
        Session.log("   tempo limite padrão mantido (" + base + " ms por pedido)");
    }

    /** Tempo médio de 6 pedidos completos do grupo, ou -1 se algum vier incompleto. */
    private long timeQuery(int[] g) throws IOException {
        long t = SystemClock.uptimeMillis();
        for (int i = 0; i < 6; i++) {
            try {
                if (query(g, 1500) < g.length) return -1;
            } catch (Elm.Timeout e) {
                elm.recover();
                return -1;
            }
        }
        return (SystemClock.uptimeMillis() - t) / 6;
    }

    private void shrink() {
        if (sizeIdx >= REQUEST_SIZES.length - 1) return;
        sizeIdx++;
        okAtSize = 0;
        emptyAtSize = 0;
        Session.log("   passando para " + REQUEST_SIZES[sizeIdx] + " PID(s) por pedido");
        regroup();
    }

    /** Envia um pedido do modo 01 e decodifica o que vier. Devolve quantos PIDs foram lidos. */
    private int query(int[] pids, long timeoutMs) throws IOException {
        StringBuilder c = new StringBuilder("01");
        for (int pid : pids) c.append(Fmt.hex2(pid));
        if (pids.length == 1 && countSuffix) c.append('1');
        int n = 0;
        String r = elm.cmd(c.toString(), timeoutMs);
        float t = now();
        for (int[] m : messages(r)) {
            if (m.length < 2 || m[0] != 0x41) continue;
            int i = 1;
            while (i < m.length) {
                int pid = m[i];
                int len = Pids.LEN[pid];
                if (len == 0 || i + 1 + len > m.length || !contains(pids, pid)) break;
                Arrays.fill(decoded, Float.NaN);
                Pids.decode(pid, m, i + 1, decoded);
                for (int k = 0; k < Ch.COUNT; k++) {
                    if (Float.isNaN(decoded[k])) continue;
                    state[k] = decoded[k];
                    stamp[k] = t;
                }
                n++;
                i += 1 + len;
            }
        }
        return n;
    }

    private float now() {
        return (SystemClock.elapsedRealtime() - t0) / 1000f;
    }

    private void derive() {
        // Mesma ordem de preferência da fonte de pressão do turbo em buildGroups().
        int src = Fmt.ok(state[Ch.MAP_70]) ? Ch.MAP_70 : Fmt.ok(state[Ch.MAP_87]) ? Ch.MAP_87 : Ch.MAP_0B;
        float baro = Fmt.ok(state[Ch.BARO]) ? state[Ch.BARO] : 101.3f;
        state[Ch.MAP] = state[src];
        state[Ch.BOOST] = (state[src] - baro) / 100f;
        state[Ch.BOOST_CMD] = (state[Ch.BOOST_CMD_ABS] - baro) / 100f;
        stamp[Ch.MAP] = stamp[Ch.BOOST] = stamp[src];
        stamp[Ch.BOOST_CMD] = stamp[Ch.BOOST_CMD_ABS];
    }

    private static boolean contains(int[] a, int v) {
        for (int x : a) if (x == v) return true;
        return false;
    }

    private static String protoName(String p) {
        switch (p) {
            case "6": return "CAN 11 bits 500k";
            case "7": return "CAN 29 bits 500k";
            case "8": return "CAN 11 bits 250k";
            case "9": return "CAN 29 bits 250k";
            default: return p;
        }
    }
}
