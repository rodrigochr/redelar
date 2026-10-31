package com.piggyback.hrvmon;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Teste na JVM: um ELM327 simulado com uma ECU que responde como a de um 1.5 turbo,
 * incluindo respostas ISO-TP de vários quadros e uma puxada de 4 s.
 * Modos:
 *   multi      ECU aceita 6 PIDs por pedido
 *   single     ECU só aceita 1 PID por pedido
 *   problemas  puxada com mistura pobre, queda de rail e de avanço
 *   clone      adaptador trava com mais de 3 PIDs por pedido
 *   honda      como o HR-V 2024 real: CAN 29 bits, três módulos respondendo ao endereço
 *              funcional, clone v2.1 que embaralha quadros de módulos diferentes e mostra o
 *              preenchimento 55, PIDs 0x4F/0x67/0x68/0x70/0x72. O pedido direto à central
 *              só funciona com o filtro de recepção (ATCRA) ajustado
 *   honda-filtro    igual, mas o pedido direto nunca funciona; só "pedir para todos e ouvir a central"
 *   honda-velho     igual, mas o adaptador não aceita ATCRA: o app separa os quadros pelos cabeçalhos
 *   honda-sem-cabecalho  nem ATCRA nem cabeçalhos: um PID por pedido
 * Em todos, ao fim da puxada a borboleta fecha e o rail cai enquanto o turbo ainda está cheio.
 */
public final class SimTest {
    static final int[] ECM_BASIC = {0x04, 0x05, 0x06, 0x07, 0x0B, 0x0C, 0x0D, 0x0E, 0x0F, 0x10, 0x11,
        0x20, 0x23, 0x24, 0x33, 0x40, 0x44, 0x49, 0x52};
    // Mapas de suporte do relatório real do HR-V (31/10/2026).
    static final int[] ECM_HONDA = {0x01, 0x03, 0x04, 0x06, 0x0B, 0x0C, 0x0D, 0x0E, 0x11, 0x13, 0x15, 0x1C,
        0x1F, 0x20, 0x21, 0x23, 0x24, 0x2E, 0x30, 0x31, 0x33, 0x3C, 0x40, 0x42, 0x43, 0x44, 0x47, 0x49, 0x4A,
        0x4F, 0x51, 0x52, 0x55, 0x56, 0x60, 0x66, 0x67, 0x68, 0x6C, 0x70, 0x72, 0x80, 0x93, 0x9F, 0xA0, 0xA3};
    static final int[] ECU_B_HONDA = {0x01, 0x04, 0x0C, 0x0D, 0x11, 0x1C, 0x20, 0x21, 0x31, 0x33, 0x40, 0x41,
        0x42, 0x49, 0x60, 0x67, 0x6C, 0x80, 0x93};
    static final int[] ECU_C_HONDA = {0x01, 0x04, 0x0C, 0x0D, 0x11, 0x1C, 0x1F, 0x20, 0x40, 0x42, 0x60, 0x67,
        0x80, 0x93};
    static final float BARO = 92f;
    static final int MAP_MAX_TENS = 26;  // 0x4F: MAP até 260 kPa

    public static void main(String[] args) throws Exception {
        String mode = args.length == 0 ? "multi" : args[0];
        final boolean multiCapable = !mode.equals("single");
        final boolean problems = mode.equals("problemas");
        final boolean clone = mode.equals("clone");
        final boolean honda = mode.startsWith("honda");
        final boolean craSupported = !mode.equals("honda-velho") && !mode.equals("honda-sem-cabecalho");
        final boolean headersSupported = !mode.equals("honda-sem-cabecalho");
        final boolean physicalWorks = mode.equals("honda");
        // Nos modos clone/honda a puxada começa mais tarde: o app gasta alguns segundos se adaptando
        // (cada pedido grande que trava custa ~4 s).
        final float pullStart = clone || honda ? 12f : 2f;
        final Sim sim = new Sim(multiCapable, problems, clone, honda, pullStart, craSupported, physicalWorks,
                headersSupported);
        Links.Factory f = new Links.Factory() {
            @Override
            public Links.Link open() {
                return sim;
            }

            @Override
            public String describe() {
                return "simulador";
            }
        };
        Session.reset();
        Poller p = new Poller(f);
        Session.poller = p;
        Thread t = new Thread(p);
        t.start();
        Thread.sleep((long) ((pullStart + 7f) * 1000));
        p.stop();
        t.join(2000);

        List<float[]> rows = Session.store.all();
        System.out.println("status: " + Session.status);
        System.out.println("amostras: " + rows.size() + " · leituras/s: " + Session.rateHz);
        System.out.println(Session.report());
        // Amostra no platô da pressão (1,5 s depois de chegar a 1,28 bar), quando até os PIDs lentos
        // já foram relidos com o turbo cheio. O relógio do app começa depois da inicialização.
        float plateau = Float.NaN;
        float[] mid = null;
        for (float[] r : rows) {
            if (Float.isNaN(plateau) && r[Ch.BOOST + 1] >= 1.28f) plateau = r[0];
            if (!Float.isNaN(plateau) && r[0] >= plateau + 1.5f) { mid = r; break; }
        }
        check(mid != null, "sem amostra no meio da puxada");
        StringBuilder b = new StringBuilder("amostra em t=" + mid[0] + ":");
        for (int i = 0; i < Ch.COUNT; i++) b.append(' ').append(Ch.CSV[i]).append('=').append(mid[i + 1]);
        System.out.println(b);
        String csv = Session.csv(rows);
        System.out.println("csv: " + csv.split("\n").length + " linhas; cabeçalho: " + csv.substring(0, csv.indexOf('\n')));

        check(rows.size() > 20, "poucas amostras");
        check(!Session.status.startsWith("Erro"), "desconectou: " + Session.status);
        check(Session.pulls.count() == 1, "esperava 1 puxada, veio " + Session.pulls.count());
        PullDetector.Pull pull = Session.pulls.list().get(0);
        check(Math.abs(pull.boostMax - 1.29f) < 0.05f, "boost máx " + pull.boostMax);
        check(Math.abs(pull.lambdaMin - 0.80f) < 0.02f, "λ mín " + pull.lambdaMin);
        check(pull.rpmMax > 5500, "rpm máx " + pull.rpmMax);
        check(Math.abs(pull.railPeak - 200f) < 1f, "rail pico " + pull.railPeak);
        check(Math.abs(mid[Ch.TIMING + 1] - (problems ? 6f : 12f)) < 0.01f, "avanço " + mid[Ch.TIMING + 1]);
        // Separando pelos cabeçalhos, os três módulos respondem e o clone trava com mais de 3 PIDs.
        int expectedSize = clone || (honda && !craSupported && headersSupported) ? 3
                : !multiCapable || !headersSupported ? 1 : 6;
        check(Session.pidsPerRequest == expectedSize, "PIDs por pedido " + Session.pidsPerRequest);
        if (problems) {
            check(pull.flags.size() == 3, "esperava 3 alertas (λ, rail, avanço): " + pull.flags);
        } else {
            check(pull.flags.size() == 1 && pull.flags.get(0).startsWith("✓"), "alertas inesperados: " + pull.flags);
        }
        if (honda) {
            String expected = physicalWorks ? "central do motor DA10F1"
                    : craSupported ? "ouvindo só a central 18DAF110"
                    : headersSupported ? "separando os quadros da central 18DAF110" : "um PID por pedido";
            check(Session.info.contains(expected), "endereçamento: " + Session.info);
            if (headersSupported) {
                check(Session.info.contains("tempo limite 100 ms"), "tempo limite não ajustado: " + Session.info);
            }
            check(Pids.mapMax == MAP_MAX_TENS * 10f, "escala do MAP " + Pids.mapMax);
            // Com um PID por pedido os lentos (0x0B, 0x68) levam ~10 s para serem relidos.
            if (Session.pidsPerRequest > 1) {
                check(Math.abs(mid[Ch.MAP_0B + 1] - mid[Ch.MAP_70 + 1]) < 1.5f,
                        "MAP 0x0B com escala errada: " + mid[Ch.MAP_0B + 1] + " vs " + mid[Ch.MAP_70 + 1]);
                check(mid[Ch.IAT2 + 1] == 48f, "2º sensor de ar " + mid[Ch.IAT2 + 1]);
            }
            check(mid[Ch.IAT + 1] == 31f, "1º sensor de ar " + mid[Ch.IAT + 1]);
            check(mid[Ch.ECT + 1] == 90f, "temperatura do motor " + mid[Ch.ECT + 1]);
            check(Math.abs(mid[Ch.WG + 1] - 78.4f) < 0.5f, "wastegate " + mid[Ch.WG + 1]);
            boolean[] sup = Session.supported;
            check(sup[0x4F] && (!headersSupported || !sup[0x41]), "PIDs suportados misturaram outros módulos");
        }
        System.out.println("OK");
    }

    static void check(boolean ok, String msg) {
        if (!ok) {
            System.out.println("FALHOU: " + msg);
            System.exit(1);
        }
    }

    /** Um módulo na rede CAN. */
    static final class Ecu {
        final int addr;          // 29 bits: 0x10 = motor; 11 bits: resposta em 0x7E8 + índice
        final int index;
        final boolean[] sup = new boolean[256];
        final boolean engine;

        Ecu(int addr, int index, int[] pids, boolean engine) {
            this.addr = addr;
            this.index = index;
            this.engine = engine;
            for (int p : pids) sup[p] = true;
        }
    }

    /** ELM327 + ECUs simulados sobre pipes. */
    static final class Sim implements Links.Link {
        final PipedInputStream appIn = new PipedInputStream(1 << 16);
        final PipedOutputStream simOut;
        final PipedOutputStream appOut = new PipedOutputStream();
        final PipedInputStream simIn;
        final boolean multiCapable, problems, clone, honda, craSupported, physicalWorks, headersSupported;
        final float pullStart;
        String cra;
        final List<Ecu> ecus = new ArrayList<>();
        final long start = System.nanoTime();
        volatile boolean closed;
        boolean echo = true, spaces = true, headers, physical, hung;
        int timeoutMs = 200;
        String protocol = "0";

        Sim(boolean multiCapable, boolean problems, boolean clone, boolean honda, float pullStart,
                boolean craSupported, boolean physicalWorks, boolean headersSupported) throws IOException {
            this.headersSupported = headersSupported;
            this.craSupported = craSupported;
            this.physicalWorks = physicalWorks;
            this.multiCapable = multiCapable;
            this.problems = problems;
            this.clone = clone;
            this.honda = honda;
            this.pullStart = pullStart;
            if (honda) {
                // Endereços que o HR-V real mostrou com ATH1.
                ecus.add(new Ecu(0xEF, 1, ECU_B_HONDA, false));
                ecus.add(new Ecu(0x10, 0, ECM_HONDA, true));
                ecus.add(new Ecu(0x1E, 2, ECU_C_HONDA, false));
            } else {
                ecus.add(new Ecu(0x10, 0, ECM_BASIC, true));
            }
            simOut = new PipedOutputStream(appIn);
            simIn = new PipedInputStream(appOut, 1 << 16);
            Thread th = new Thread(this::serve);
            th.setDaemon(true);
            th.start();
        }

        @Override
        public InputStream in() {
            return appIn;
        }

        @Override
        public OutputStream out() {
            return appOut;
        }

        @Override
        public void close() {
            closed = true;
        }

        void serve() {
            StringBuilder cmd = new StringBuilder();
            try {
                while (!closed) {
                    int c = simIn.read();
                    if (c < 0) return;
                    if (c != '\r') {
                        cmd.append((char) c);
                        continue;
                    }
                    String req = cmd.toString().trim().toUpperCase(Locale.US);
                    cmd.setLength(0);
                    // Latência do Bluetooth + ECU; no HR-V o adaptador ainda espera o tempo limite
                    // depois da última resposta, porque vários módulos respondem.
                    Thread.sleep(8 + (honda ? timeoutMs / 4 : 0));
                    if (hung) {
                        // Como o ELM327: qualquer caractere interrompe e ele volta ao prompt.
                        hung = false;
                        simOut.write("STOPPED\r\r>".getBytes(StandardCharsets.US_ASCII));
                        simOut.flush();
                        continue;
                    }
                    String resp = respond(req);
                    if (resp == null) continue;  // travado: não responde nada
                    String out = (echo ? req + "\r" : "") + resp + "\r\r>";
                    simOut.write(out.getBytes(StandardCharsets.US_ASCII));
                    simOut.flush();
                }
            } catch (IOException | InterruptedException ignored) {
            }
        }

        String respond(String req) {
            if (req.startsWith("AT")) return at(req.substring(2));
            if (!req.startsWith("01")) return "?";
            if (honda && protocol.equals("6")) return "CAN ERROR";
            String body = req.substring(2);
            if (body.length() % 2 == 1) body = body.substring(0, body.length() - 1);  // sufixo de contagem
            List<Integer> pids = new ArrayList<>();
            for (int i = 0; i + 2 <= body.length(); i += 2) pids.add(Integer.parseInt(body.substring(i, i + 2), 16));
            if (pids.size() > 1 && !multiCapable) return "NO DATA";
            // A central do HR-V leva ~70 ms para responder vários PIDs; tempo limite menor corta a resposta.
            if (honda && pids.size() > 1 && timeoutMs < 70) return "NO DATA";
            // Clone v2.1: pedido direto à central sem o filtro certo não volta nada.
            if (honda && physical && !(physicalWorks && "18DAF110".equals(cra))) return "NO DATA";
            List<Ecu> heard = new ArrayList<>();
            for (Ecu e : ecus) {
                if (physical && !e.engine) continue;
                if (cra != null && !cra.equals(responseId(e))) continue;
                heard.add(e);
            }
            if (pids.size() > 3 && (clone || (honda && heard.size() > 1))) {
                hung = true;
                return null;
            }

            List<Ecu> who = new ArrayList<>();
            List<List<Integer>> answers = new ArrayList<>();
            for (Ecu e : heard) {
                List<Integer> data = new ArrayList<>();
                data.add(0x41);
                for (int pid : pids) {
                    int[] v = (pid & 0x1F) == 0 ? bitmap(e, pid) : e.sup[pid] ? value(pid) : null;
                    if (v == null) continue;
                    data.add(pid);
                    for (int x : v) data.add(x);
                }
                if (data.size() > 1) {
                    who.add(e);
                    answers.add(data);
                }
            }
            if (who.isEmpty()) return "NO DATA";
            return format(who, answers);
        }

        String at(String c) {
            switch (c) {
                case "Z":
                    echo = true;
                    spaces = true;
                    headers = false;
                    physical = false;
                    cra = null;
                    return "\rELM327 " + (honda ? "v2.1" : "v1.5");
                case "I": return "ELM327 " + (honda ? "v2.1" : "v1.5");
                case "E0": echo = false; return "OK";
                case "S0": spaces = false; return "OK";
                case "H0": headers = false; return "OK";
                case "H1": headers = headersSupported; return "OK";
                case "DPN": return honda ? (protocol.equals("6") ? "6" : "A7") : "A6";
                default:
                    if (c.startsWith("SP")) protocol = c.substring(2);
                    if (c.startsWith("ST")) timeoutMs = Integer.parseInt(c.substring(2), 16) * 4;
                    if (c.startsWith("CRA")) {
                        if (!craSupported) return "?";
                        cra = c.length() > 3 ? c.substring(3) : null;
                    }
                    if (c.startsWith("SH")) physical = c.substring(2).equals(honda ? "DA10F1" : "7E0");
                    return "OK";
            }
        }

        String responseId(Ecu e) {
            return honda ? String.format("18DAF1%02X", e.addr) : String.format("%03X", 0x7E8 + e.index);
        }

        /** Monta os quadros CAN (8 bytes, preenchimento 0x55) de uma resposta. */
        static List<int[]> frames(List<Integer> d) {
            List<int[]> out = new ArrayList<>();
            if (d.size() <= 7) {
                int[] f = pad(new int[8]);
                f[0] = d.size();
                for (int i = 0; i < d.size(); i++) f[1 + i] = d.get(i);
                out.add(f);
                return out;
            }
            int[] ff = pad(new int[8]);
            ff[0] = 0x10 | (d.size() >> 8);
            ff[1] = d.size() & 0xFF;
            for (int i = 0; i < 6; i++) ff[2 + i] = d.get(i);
            out.add(ff);
            int seq = 1;
            for (int i = 6; i < d.size(); i += 7, seq++) {
                int[] cf = pad(new int[8]);
                cf[0] = 0x20 | (seq & 0x0F);
                for (int k = 0; k < 7 && i + k < d.size(); k++) cf[1 + k] = d.get(i + k);
                out.add(cf);
            }
            return out;
        }

        static int[] pad(int[] f) {
            java.util.Arrays.fill(f, 0x55);
            return f;
        }

        String format(List<Ecu> who, List<List<Integer>> answers) {
            String sep = spaces ? " " : "";
            List<List<int[]>> all = new ArrayList<>();
            boolean anyMulti = false;
            for (List<Integer> a : answers) {
                List<int[]> fr = frames(a);
                anyMulti |= fr.size() > 1;
                all.add(fr);
            }
            StringBuilder b = new StringBuilder();
            if (headers) {
                for (int e = 0; e < who.size(); e++) {
                    String hdr = responseId(who.get(e));
                    for (int[] f : all.get(e)) b.append(hdr).append(sep).append(hex(f, 0, 8, sep)).append('\r');
                }
                return b.toString().trim();
            }
            if (who.size() == 1 || !anyMulti) {
                for (int e = 0; e < who.size(); e++) {
                    List<int[]> fr = all.get(e);
                    if (fr.size() == 1) {
                        // O clone v2.1 mostra o preenchimento; o ELM original, só os dados.
                        int len = honda ? 7 : fr.get(0)[0];
                        b.append(hex(fr.get(0), 1, 1 + len, sep)).append('\r');
                    } else {
                        b.append(String.format("%03X", answers.get(e).size())).append('\r');
                        b.append("0:").append(sep).append(hex(fr.get(0), 2, 8, sep)).append('\r');
                        for (int i = 1; i < fr.size(); i++) {
                            b.append(Integer.toHexString(i % 16).toUpperCase(Locale.US)).append(':').append(sep)
                                    .append(hex(fr.get(i), 1, 8, sep)).append('\r');
                        }
                    }
                }
                return b.toString().trim();
            }
            // Clone v2.1 com vários módulos e algum multi-quadro: intercala os quadros, formata o
            // primeiro como se fosse o início de um multi-quadro e numera os demais.
            List<int[]> mixed = new ArrayList<>();
            int max = 0;
            for (List<int[]> fr : all) max = Math.max(max, fr.size());
            for (int i = 0; i < max; i++) for (List<int[]> fr : all) if (i < fr.size()) mixed.add(fr.get(i));
            int[] first = mixed.get(0);
            b.append(Integer.toHexString(first[0] & 0x0F).toUpperCase(Locale.US)).append(String.format("%02X", first[1])).append('\r');
            b.append("0:").append(sep).append(hex(first, 2, 8, sep)).append('\r');
            for (int i = 1; i < mixed.size(); i++) {
                b.append(Integer.toHexString(i % 16).toUpperCase(Locale.US)).append(':').append(sep)
                        .append(hex(mixed.get(i), 1, 8, sep)).append('\r');
            }
            return b.toString().trim();
        }

        static String hex(int[] d, int from, int to, String sep) {
            StringBuilder b = new StringBuilder();
            for (int i = from; i < to; i++) {
                if (i > from) b.append(sep);
                b.append(String.format("%02X", d[i]));
            }
            return b.toString();
        }

        int[] value(int pid) {
            float t = (System.nanoTime() - start) / 1e9f;
            float tp = t - pullStart;
            boolean pull = tp > 0f && tp < 4f;
            // Tirou o pé: a borboleta fecha e o rail cai na hora, mas o turbo leva 0,6 s para esvaziar.
            boolean lift = tp >= 4f && tp < 4.6f;
            float k = pull ? Math.min(1f, tp / 1.2f) : 0f;  // rampa da pressão
            float rpm = pull ? 2000 + tp * 950 : 1800;
            float map = pull ? BARO + k * 129 : lift ? BARO + 129 * (1 - (tp - 4f) / 0.6f) : 60;
            float lambda = pull && tp > 0.6f ? 0.80f : 1.0f;
            float rail = pull ? 150 + k * 50 : lift ? 50 : 120;  // bar
            float timing = 12;
            if (problems && pull && tp > 2.5f) {
                lambda = 0.90f;   // empobreceu
                rail = 160;       // bomba de alta não segurou
                timing = 6;       // ECU tirou avanço
            }
            switch (pid) {
                case 0x04: return new int[]{pull ? 230 : 60};
                case 0x05: return new int[]{90 + 40};
                case 0x06: return new int[]{128};
                case 0x07: return new int[]{130};
                case 0x0B: return new int[]{Math.round(honda ? map * 255f / (MAP_MAX_TENS * 10) : map)};
                case 0x0C: return u16(Math.round(rpm * 4));
                case 0x0D: return new int[]{80};
                case 0x0E: return new int[]{Math.round((timing + 64) * 2)};
                case 0x0F: return new int[]{35 + 40};
                case 0x10: return u16(Math.round((pull ? 90 : 12) * 100));
                case 0x11: return new int[]{pull ? 230 : 40};  // 90% / 16%
                case 0x23: return u16(Math.round(rail * 10));
                case 0x24: {
                    int[] l = u16(Math.round(lambda * 32768));
                    return new int[]{l[0], l[1], 0x80, 0x00};
                }
                case 0x33: return new int[]{Math.round(BARO)};
                case 0x44: return u16(Math.round((pull ? 0.80f : 1f) * 32768));
                case 0x49: return new int[]{pull ? 200 : 40};
                case 0x4F: return new int[]{2, 8, 128, MAP_MAX_TENS};
                case 0x52: return new int[]{Math.round(27 * 2.55f)};
                case 0x66: {
                    int[] m = u16(Math.round((pull ? 90 : 12) * 32));
                    return new int[]{0x01, m[0], m[1], 0, 0};
                }
                case 0x67: return new int[]{0x01, 90 + 40, 0};
                case 0x68: return new int[]{0x03, 31 + 40, (pull ? 48 : 38) + 40, 0, 0, 0, 0};
                case 0x70: {
                    int[] m = u16(Math.round(map * 32));
                    return new int[]{0x03, m[0], m[1], m[0], m[1], 0, 0, 0, 0, 0};
                }
                case 0x72: return new int[]{0x03, pull ? 200 : 60, pull ? 200 : 60, 0, 0};
                default: return null;
            }
        }

        static int[] u16(int v) {
            return new int[]{(v >> 8) & 0xFF, v & 0xFF};
        }

        static int[] bitmap(Ecu e, int base) {
            if (base != 0 && !e.sup[base]) return null;
            int[] b = new int[4];
            for (int pid = base + 1; pid <= base + 0x20 && pid < 256; pid++) {
                if (!e.sup[pid]) continue;
                int i = pid - base - 1;
                b[i / 8] |= 0x80 >> (i % 8);
            }
            return b;
        }
    }
}
