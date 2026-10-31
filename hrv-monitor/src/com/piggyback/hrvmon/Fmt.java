package com.piggyback.hrvmon;

import java.util.Locale;

/** Formatação de números no padrão brasileiro (vírgula decimal). */
final class Fmt {
    static final Locale BR = new Locale("pt", "BR");

    static boolean ok(float v) {
        return !Float.isNaN(v) && !Float.isInfinite(v);
    }

    static String f(float v, int decimals) {
        return ok(v) ? String.format(BR, "%." + decimals + "f", v) : "—";
    }

    static String signed(float v) {
        return ok(v) ? String.format(BR, "%+.0f", v) : "—";
    }

    static String hex2(int v) {
        return String.format(Locale.US, "%02X", v);
    }

    private Fmt() {}
}
