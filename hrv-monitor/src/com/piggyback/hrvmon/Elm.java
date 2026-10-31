package com.piggyback.hrvmon;

import android.os.SystemClock;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Conversa com o ELM327: envia um comando e lê até o prompt '>'. */
final class Elm {
    /** O adaptador não respondeu dentro do prazo. */
    static final class Timeout extends IOException {
        Timeout(String msg) {
            super(msg);
        }
    }

    private final InputStream in;
    private final OutputStream out;
    private final byte[] buf = new byte[512];

    Elm(InputStream in, OutputStream out) {
        this.in = in;
        this.out = out;
    }

    String cmd(String c, long timeoutMs) throws IOException {
        drain();
        out.write((c + "\r").getBytes(StandardCharsets.US_ASCII));
        out.flush();

        long t0 = SystemClock.uptimeMillis();
        StringBuilder sb = new StringBuilder(64);
        if (!readUntilPrompt(sb, timeoutMs)) {
            Session.log("→ " + c + "   ⏱ sem resposta em " + timeoutMs + " ms" + received(sb));
            throw new Timeout("sem resposta para " + c);
        }
        Session.log("→ " + c + "   ← " + oneLine(sb) + "  (" + (SystemClock.uptimeMillis() - t0) + " ms)");
        return sb.toString();
    }

    /**
     * Depois de um timeout: espera mais um pouco pelo '>' e, se não vier, manda um caractere
     * para interromper o adaptador (o ELM327 responde "STOPPED" e volta ao prompt).
     */
    void recover() throws IOException {
        StringBuilder sb = new StringBuilder();
        if (readUntilPrompt(sb, 1000)) {
            Session.log("   recuperação: chegou atrasado" + received(sb));
        } else {
            // "ATI" e não só "\r": com o adaptador livre, "\r" sozinho repete o último comando.
            out.write("ATI\r".getBytes(StandardCharsets.US_ASCII));
            out.flush();
            sb.setLength(0);
            boolean ok = readUntilPrompt(sb, 1500);
            Session.log("   recuperação: interrompido" + (ok ? "" : ", sem prompt") + received(sb));
        }
        try {
            Thread.sleep(150);
        } catch (InterruptedException e) {
            throw new IOException("interrompido");
        }
        drain();
    }

    /** Descarta sobras de uma resposta anterior que chegou atrasada. */
    private void drain() throws IOException {
        int pending;
        while ((pending = in.available()) > 0) {
            if (in.read(buf, 0, Math.min(pending, buf.length)) < 0) throw new IOException("conexão fechada");
        }
    }

    private boolean readUntilPrompt(StringBuilder sb, long timeoutMs) throws IOException {
        long deadline = SystemClock.uptimeMillis() + timeoutMs;
        while (true) {
            int n = in.available();
            if (n > 0) {
                int r = in.read(buf, 0, Math.min(n, buf.length));
                if (r < 0) throw new IOException("conexão fechada");
                for (int i = 0; i < r; i++) {
                    char ch = (char) (buf[i] & 0xFF);
                    if (ch == '>') return true;
                    if (ch != 0) sb.append(ch);
                }
            } else {
                if (SystemClock.uptimeMillis() > deadline) return false;
                try {
                    Thread.sleep(1);
                } catch (InterruptedException e) {
                    throw new IOException("interrompido");
                }
            }
        }
    }

    private static String oneLine(CharSequence s) {
        String t = s.toString().trim().replaceAll("[\r\n]+", " | ");
        return t.length() > 160 ? t.substring(0, 160) + "…" : t;
    }

    private static String received(CharSequence s) {
        return s.toString().trim().isEmpty() ? "" : "; recebido: " + oneLine(s);
    }

    /**
     * Separa a resposta em mensagens. Quadros ISO-TP ("00E", "0:..", "1:..") viram uma
     * mensagem só; linhas hexadecimais soltas são mensagens de ECUs diferentes.
     * Textos de erro (NO DATA, CAN ERROR...) são ignorados; a lista volta vazia.
     */
    static List<int[]> parse(String raw) {
        List<int[]> msgs = new ArrayList<>();
        StringBuilder multi = null;
        int multiLen = -1;
        for (String line : raw.toUpperCase(Locale.US).replace(" ", "").split("[\r\n]+")) {
            if (line.isEmpty()) continue;
            if (line.matches("[0-9A-F]{3}")) {
                if (multi != null) msgs.add(bytes(multi, multiLen));
                multi = new StringBuilder();
                multiLen = Integer.parseInt(line, 16);
            } else if (line.matches("[0-9A-F]:[0-9A-F]+")) {
                if (multi == null) {
                    multi = new StringBuilder();
                    multiLen = -1;
                }
                multi.append(line, 2, line.length());
            } else if (line.matches("[0-9A-F]+") && line.length() % 2 == 0) {
                msgs.add(bytes(line, -1));
            }
        }
        if (multi != null && multi.length() > 0) msgs.add(bytes(multi, multiLen));
        return msgs;
    }

    private static int[] bytes(CharSequence hex, int limit) {
        int n = hex.length() / 2;
        if (limit >= 0) n = Math.min(n, limit);
        int[] b = new int[n];
        for (int i = 0; i < n; i++) {
            b[i] = Character.digit(hex.charAt(2 * i), 16) * 16 + Character.digit(hex.charAt(2 * i + 1), 16);
        }
        return b;
    }
}
