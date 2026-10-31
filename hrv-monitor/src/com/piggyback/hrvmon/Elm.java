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
        // Descarta sobras de uma resposta anterior que chegou atrasada.
        int pending;
        while ((pending = in.available()) > 0) {
            if (in.read(buf, 0, Math.min(pending, buf.length)) < 0) throw new IOException("conexão fechada");
        }
        out.write((c + "\r").getBytes(StandardCharsets.US_ASCII));
        out.flush();

        StringBuilder sb = new StringBuilder(64);
        long deadline = SystemClock.uptimeMillis() + timeoutMs;
        while (true) {
            int n = in.available();
            if (n > 0) {
                int r = in.read(buf, 0, Math.min(n, buf.length));
                if (r < 0) throw new IOException("conexão fechada");
                for (int i = 0; i < r; i++) {
                    char ch = (char) (buf[i] & 0xFF);
                    if (ch == '>') return sb.toString();
                    if (ch != 0) sb.append(ch);
                }
            } else {
                if (SystemClock.uptimeMillis() > deadline) throw new Timeout("sem resposta para " + c);
                try {
                    Thread.sleep(1);
                } catch (InterruptedException e) {
                    throw new IOException("interrompido");
                }
            }
        }
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
