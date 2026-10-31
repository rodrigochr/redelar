package com.piggyback.hrvmon;

import java.util.ArrayList;
import java.util.List;

/** Amostras da sessão. Cada linha é {tempo_s, canal0, canal1, ...} e nunca é alterada depois de gravada. */
final class DataStore {
    private final ArrayList<float[]> rows = new ArrayList<>();

    synchronized void clear() {
        rows.clear();
    }

    synchronized void add(float t, float[] state) {
        float[] r = new float[Ch.COUNT + 1];
        r[0] = t;
        System.arraycopy(state, 0, r, 1, Ch.COUNT);
        rows.add(r);
    }

    synchronized List<float[]> all() {
        return new ArrayList<>(rows);
    }

    /** Últimos {@code seconds} segundos; 0 = tudo. */
    synchronized List<float[]> window(float seconds) {
        int n = rows.size();
        if (seconds <= 0 || n == 0) return new ArrayList<>(rows);
        float from = rows.get(n - 1)[0] - seconds;
        int lo = 0, hi = n - 1;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (rows.get(mid)[0] < from) lo = mid + 1; else hi = mid;
        }
        return new ArrayList<>(rows.subList(Math.max(0, lo - 1), n));
    }
}
