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
 * Os PIDs rápidos (rotação, λ, MAP, avanço, rail) são lidos em toda volta; os lentos
 * (temperaturas, trims, etanol...) se revezam a cada 3 voltas.
 *
 * Pede até 6 PIDs por vez. Alguns clones do ELM327 travam ou voltam vazio com pedidos
 * grandes; nesse caso cai para 3 e depois para 1 PID por pedido.
 */
final class Poller implements Runnable {
    private static final int[] REQUEST_SIZES = {6, 3, 1};
    private static final int MAX_TIMEOUTS = 5;
    private static final int[] FAST = {0x0C, -1 /* λ */, 0x0B, 0x87, 0x0E, -2 /* rail */, 0x11};
    private static final int[] SLOW = {0x44, 0x70, 0x0F, 0x05, 0x06, 0x07, 0x33, 0x52, 0x0D, 0x04, 0x10, 0x49};

    private final Links.Factory factory;
    private volatile boolean running = true;
    private volatile boolean polling;
    private volatile Links.Link link;
    private Elm elm;

    private final float[] state = new float[Ch.COUNT];
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

        // Endereça só a ECU do motor (7E0), assim o câmbio não responde junto.
        String header = "7DF (todas as ECUs)";
        if (proto.equals("6") || proto.equals("8")) {
            elm.cmd("ATSH7E0", 1500);
            if (probe(null, 3000)) {
                header = "7E0 (motor)";
            } else {
                elm.cmd("ATSH7DF", 1500);
            }
        }

        Session.status = "Lendo PIDs suportados…";
        readSupported();
        buildGroups();

        try {
            List<int[]> r = Elm.parse(elm.cmd("010C1", 1500));
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

        Session.info = version + " · protocolo " + protoName(proto) + " · cabeçalho " + header;
        Session.supported = sup.clone();
    }

    private boolean probe(String protocolCmd, long timeoutMs) throws IOException {
        if (protocolCmd != null) elm.cmd(protocolCmd, 1500);
        try {
            for (int[] m : Elm.parse(elm.cmd("0100", timeoutMs))) {
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
            for (int[] m : Elm.parse(r)) {
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
        fastPids.clear();
        slowPids.clear();
        for (int pid : FAST) {
            if (pid == -1) pid = lambda;
            if (pid == -2) pid = rail;
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
        long t0 = SystemClock.elapsedRealtime();
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
            derive();
            float t = (SystemClock.elapsedRealtime() - t0) / 1000f;
            Session.store.add(t, state);
            Session.pulls.feed(t, state);

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
        int decoded = 0;
        for (int[] m : Elm.parse(elm.cmd(c.toString(), timeoutMs))) {
            if (m.length < 2 || m[0] != 0x41) continue;
            int i = 1;
            while (i < m.length) {
                int pid = m[i];
                int len = Pids.LEN[pid];
                if (len == 0 || i + 1 + len > m.length || !contains(pids, pid)) break;
                Pids.decode(pid, m, i + 1, state);
                decoded++;
                i += 1 + len;
            }
        }
        return decoded;
    }

    private void derive() {
        float map = Fmt.ok(state[Ch.MAP_87]) ? state[Ch.MAP_87]
                : Fmt.ok(state[Ch.MAP_70]) ? state[Ch.MAP_70] : state[Ch.MAP_0B];
        float baro = Fmt.ok(state[Ch.BARO]) ? state[Ch.BARO] : 101.3f;
        state[Ch.MAP] = map;
        state[Ch.BOOST] = (map - baro) / 100f;
        state[Ch.BOOST_CMD] = (state[Ch.BOOST_CMD_ABS] - baro) / 100f;
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
