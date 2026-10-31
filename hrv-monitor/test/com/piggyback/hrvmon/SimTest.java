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
 * incluindo respostas ISO-TP de vários quadros e uma puxada de 2 s a 6 s.
 * Uso: SimTest [multi|single|problemas]
 */
public final class SimTest {
    static final int[] SUPPORTED = {0x04, 0x05, 0x06, 0x07, 0x0B, 0x0C, 0x0D, 0x0E, 0x0F, 0x10, 0x11,
        0x20, 0x23, 0x24, 0x33, 0x40, 0x44, 0x49, 0x52};

    public static void main(String[] args) throws Exception {
        String mode = args.length == 0 ? "multi" : args[0];
        final boolean multiCapable = !mode.equals("single");
        final boolean problems = mode.equals("problemas");
        final Sim sim = new Sim(multiCapable, problems);
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
        Thread.sleep(9000);
        p.stop();
        t.join(2000);

        List<float[]> rows = Session.store.all();
        System.out.println("status: " + Session.status);
        System.out.println("amostras: " + rows.size() + " · leituras/s: " + Session.rateHz);
        System.out.println(Session.report());
        float[] mid = null;
        for (float[] r : rows) if (r[0] > 4.5f) { mid = r; break; }
        if (mid != null) {
            StringBuilder b = new StringBuilder("amostra em t=" + mid[0] + ":");
            for (int i = 0; i < Ch.COUNT; i++) b.append(' ').append(Ch.CSV[i]).append('=').append(mid[i + 1]);
            System.out.println(b);
        }
        String csv = Session.csv(rows);
        System.out.println("csv: " + csv.split("\n").length + " linhas; cabeçalho: " + csv.substring(0, csv.indexOf('\n')));

        check(rows.size() > 20, "poucas amostras");
        check(Session.pulls.count() == 1, "esperava 1 puxada, veio " + Session.pulls.count());
        PullDetector.Pull pull = Session.pulls.list().get(0);
        check(Math.abs(pull.boostMax - 1.29f) < 0.05f, "boost máx " + pull.boostMax);
        check(Math.abs(pull.lambdaMin - 0.80f) < 0.02f, "λ mín " + pull.lambdaMin);
        check(pull.rpmMax > 5500, "rpm máx " + pull.rpmMax);
        check(Math.abs(pull.railPeak - 200f) < 1f, "rail pico " + pull.railPeak);
        check(Session.multi == multiCapable, "modo multi-PID " + Session.multi);
        if (problems) {
            check(pull.flags.size() == 3, "esperava 3 alertas (λ, rail, avanço): " + pull.flags);
        } else {
            check(pull.flags.size() == 1 && pull.flags.get(0).startsWith("✓"), "alertas inesperados: " + pull.flags);
        }
        System.out.println("OK");
    }

    static void check(boolean ok, String msg) {
        if (!ok) {
            System.out.println("FALHOU: " + msg);
            System.exit(1);
        }
    }

    /** ELM327 + ECU simulados sobre pipes. */
    static final class Sim implements Links.Link {
        final PipedInputStream appIn = new PipedInputStream(1 << 16);
        final PipedOutputStream simOut;
        final PipedOutputStream appOut = new PipedOutputStream();
        final PipedInputStream simIn;
        final boolean multiCapable;
        final boolean problems;
        final long start = System.nanoTime();
        volatile boolean closed;
        boolean echo = true, spaces = true;

        Sim(boolean multiCapable, boolean problems) throws IOException {
            this.multiCapable = multiCapable;
            this.problems = problems;
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
                    Thread.sleep(8);  // latência do Bluetooth + ECU
                    String resp = respond(req);
                    String out = (echo ? req + "\r" : "") + resp + "\r\r>";
                    simOut.write(out.getBytes(StandardCharsets.US_ASCII));
                    simOut.flush();
                }
            } catch (IOException | InterruptedException ignored) {
            }
        }

        String respond(String req) {
            if (req.startsWith("AT")) {
                if (req.equals("ATZ")) {
                    echo = true;
                    spaces = true;
                    return "\rELM327 v1.5";
                }
                if (req.equals("ATE0")) echo = false;
                if (req.equals("ATS0")) spaces = false;
                if (req.equals("ATDPN")) return "A6";
                return "OK";
            }
            if (!req.startsWith("01")) return "?";
            String body = req.substring(2);
            boolean suffix = body.length() % 2 == 1;
            if (suffix) body = body.substring(0, body.length() - 1);
            List<Integer> pids = new ArrayList<>();
            for (int i = 0; i + 2 <= body.length(); i += 2) pids.add(Integer.parseInt(body.substring(i, i + 2), 16));
            if (pids.size() > 1 && !multiCapable) return "NO DATA";
            List<Integer> data = new ArrayList<>();
            data.add(0x41);
            for (int pid : pids) {
                int[] v = value(pid);
                if (v == null) continue;
                data.add(pid);
                for (int x : v) data.add(x);
            }
            if (data.size() == 1) return "NO DATA";
            return format(data);
        }

        /** Formata como o ELM327 com ATH0: um quadro, ou "LLL / 0: / 1: ..." para ISO-TP. */
        String format(List<Integer> d) {
            String sep = spaces ? " " : "";
            if (d.size() <= 7) return hex(d, 0, d.size(), sep);
            StringBuilder b = new StringBuilder(String.format("%03X", d.size())).append('\r');
            b.append("0:").append(sep).append(hex(d, 0, 6, sep)).append('\r');
            int idx = 1;
            for (int i = 6; i < d.size(); i += 7, idx++) {
                List<Integer> frame = new ArrayList<>(d.subList(i, Math.min(d.size(), i + 7)));
                while (frame.size() < 7) frame.add(0x55);  // preenchimento do quadro
                b.append(Integer.toHexString(idx % 16).toUpperCase(Locale.US)).append(':').append(sep)
                        .append(hex(frame, 0, 7, sep)).append('\r');
            }
            return b.toString().trim();
        }

        static String hex(List<Integer> d, int from, int to, String sep) {
            StringBuilder b = new StringBuilder();
            for (int i = from; i < to; i++) {
                if (i > from) b.append(sep);
                b.append(String.format("%02X", d.get(i)));
            }
            return b.toString();
        }

        int[] value(int pid) {
            float t = (System.nanoTime() - start) / 1e9f;
            boolean pull = t > 2f && t < 6f;
            float k = pull ? Math.min(1f, (t - 2f) / 1.2f) : 0f;  // rampa da pressão
            float rpm = pull ? 2000 + (t - 2f) * 950 : 1800;
            float map = pull ? 92 + k * 129 : 60;          // até 221 kPa = 1,29 bar com baro 92
            float lambda = pull && t > 2.6f ? 0.80f : 1.0f;
            float rail = pull ? 150 + k * 50 : 120;        // bar
            float timing = 12;
            if (problems && pull && t > 4.5f) {
                lambda = 0.90f;   // empobreceu
                rail = 160;       // bomba de alta não segurou
                timing = 6;       // ECU tirou avanço
            }
            if ((pid & 0x1F) == 0 && pid <= 0x40) return bitmap(pid);
            switch (pid) {
                case 0x04: return new int[]{pull ? 230 : 60};
                case 0x05: return new int[]{90 + 40};
                case 0x06: return new int[]{128};
                case 0x07: return new int[]{130};
                case 0x0B: return new int[]{Math.round(map)};
                case 0x0C: return u16(Math.round(rpm * 4));
                case 0x0D: return new int[]{80};
                case 0x0E: return new int[]{Math.round((timing + 64) * 2)};
                case 0x0F: return new int[]{35 + 40};
                case 0x10: return u16(Math.round((pull ? 90 : 12) * 100));
                case 0x11: return new int[]{pull ? 230 : 40};
                case 0x23: return u16(Math.round(rail * 10));
                case 0x24: {
                    int[] l = u16(Math.round(lambda * 32768));
                    return new int[]{l[0], l[1], 0x80, 0x00};
                }
                case 0x33: return new int[]{92};
                case 0x44: return u16(Math.round((pull ? 0.80f : 1f) * 32768));
                case 0x49: return new int[]{pull ? 200 : 40};
                case 0x52: return new int[]{Math.round(27 * 2.55f)};
                default: return null;
            }
        }

        static int[] u16(int v) {
            return new int[]{(v >> 8) & 0xFF, v & 0xFF};
        }

        static int[] bitmap(int base) {
            int[] b = new int[4];
            for (int pid : SUPPORTED) {
                if (pid <= base || pid > base + 0x20) continue;
                int i = pid - base - 1;
                b[i / 8] |= 0x80 >> (i % 8);
            }
            return b;
        }
    }
}
