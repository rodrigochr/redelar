package com.piggyback.hrvmon;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Estado da sessão, compartilhado entre a thread de leitura e a tela. */
final class Session {
    static final String VERSION = "0.1.1";

    static final DataStore store = new DataStore();
    static final PullDetector pulls = new PullDetector();
    static volatile Poller poller;
    static volatile String status = "Desconectado";
    static volatile String info = "";
    static volatile boolean[] supported;
    static volatile int[] used = new int[0];
    static volatile float rateHz;
    static volatile int pidsPerRequest = 6;

    // Registro da comunicação com o ELM327: o começo de cada conexão e as últimas trocas.
    private static final int LOG_HEAD = 60, LOG_TAIL = 60;
    private static final List<String> logHead = new ArrayList<>();
    private static final ArrayDeque<String> logTail = new ArrayDeque<>();
    private static String previousLog = "";

    static void reset() {
        store.clear();
        pulls.clear();
        info = "";
        supported = null;
        used = new int[0];
        rateHz = 0;
        pidsPerRequest = 6;
        synchronized (logHead) {
            String cur = logText();
            if (!cur.isEmpty()) previousLog = cur;
            logHead.clear();
            logTail.clear();
        }
    }

    static void log(String line) {
        String stamped = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(new Date()) + " " + line;
        synchronized (logHead) {
            if (logHead.size() < LOG_HEAD) {
                logHead.add(stamped);
                return;
            }
            logTail.addLast(stamped);
            while (logTail.size() > LOG_TAIL) logTail.removeFirst();
        }
    }

    private static String logText() {
        synchronized (logHead) {
            StringBuilder b = new StringBuilder();
            for (String l : logHead) b.append(l).append('\n');
            if (!logTail.isEmpty()) {
                b.append("   (…)\n");
                for (String l : logTail) b.append(l).append('\n');
            }
            return b.toString();
        }
    }

    static boolean isUsed(int pid) {
        for (int p : used) if (p == pid) return true;
        return false;
    }

    static String report() {
        StringBuilder b = new StringBuilder();
        b.append("HR-V Monitor ").append(VERSION).append(" — relatório\n");
        b.append(info.isEmpty() ? "Sem conexão nesta sessão" : info).append('\n');
        b.append("PIDs por pedido: ").append(pidsPerRequest)
                .append(" · leituras/s: ").append(Fmt.f(rateHz, 1)).append('\n');
        b.append("Status: ").append(status).append('\n');
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
        String log = logText();
        if (!log.isEmpty()) b.append("\nComunicação com o adaptador:\n").append(log);
        if (!previousLog.isEmpty()) b.append("\nConexão anterior:\n").append(previousLog);
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
