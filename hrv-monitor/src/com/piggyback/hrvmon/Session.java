package com.piggyback.hrvmon;

import java.util.List;

/** Estado da sessão, compartilhado entre a thread de leitura e a tela. */
final class Session {
    static final String VERSION = "0.1.0";

    static final DataStore store = new DataStore();
    static final PullDetector pulls = new PullDetector();
    static volatile Poller poller;
    static volatile String status = "Desconectado";
    static volatile String info = "";
    static volatile boolean[] supported;
    static volatile int[] used = new int[0];
    static volatile float rateHz;
    static volatile boolean multi = true;

    static void reset() {
        store.clear();
        pulls.clear();
        info = "";
        supported = null;
        used = new int[0];
        rateHz = 0;
        multi = true;
    }

    static boolean isUsed(int pid) {
        for (int p : used) if (p == pid) return true;
        return false;
    }

    static String report() {
        StringBuilder b = new StringBuilder();
        b.append("HR-V Monitor ").append(VERSION).append(" — relatório\n");
        b.append(info.isEmpty() ? "Sem conexão nesta sessão" : info).append('\n');
        b.append("Multi-PID: ").append(multi ? "sim" : "não (um PID por pedido)")
                .append(" · leituras/s: ").append(Fmt.f(rateHz, 1)).append('\n');
        List<float[]> all = store.all();
        if (!all.isEmpty()) {
            b.append("Amostras: ").append(all.size()).append(" em ")
                    .append(Fmt.f(all.get(all.size() - 1)[0], 0)).append(" s\n");
        }
        boolean[] sup = supported;
        if (sup != null) {
            b.append("\nPIDs suportados pela ECU do motor (★ = lido pelo app):\n");
            for (int pid = 1; pid < 256; pid++) {
                if (!sup[pid] || pid % 0x20 == 0) continue;
                b.append(isUsed(pid) ? "★ " : "   ").append(Fmt.hex2(pid)).append("  ").append(Pids.name(pid)).append('\n');
            }
        }
        List<PullDetector.Pull> ps = pulls.list();
        b.append("\nPuxadas registradas: ").append(ps.size()).append('\n');
        for (PullDetector.Pull p : ps) b.append('\n').append(p.summary()).append('\n');
        return b.toString();
    }

    static String csv(List<float[]> rows) {
        StringBuilder b = new StringBuilder(rows.size() * 120 + 256);
        b.append("tempo_s");
        for (String k : Ch.CSV) b.append(',').append(k);
        b.append('\n');
        for (float[] r : rows) {
            for (int i = 0; i < r.length; i++) {
                if (i > 0) b.append(',');
                if (Fmt.ok(r[i])) b.append(Math.round(r[i] * 1000f) / 1000f);
            }
            b.append('\n');
        }
        return b.toString();
    }

    private Session() {}
}
