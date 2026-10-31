package android.os;

/** Substituto do SystemClock do Android para rodar os testes na JVM. */
public final class SystemClock {
    public static long uptimeMillis() {
        return System.nanoTime() / 1_000_000L;
    }

    public static long elapsedRealtime() {
        return System.nanoTime() / 1_000_000L;
    }

    private SystemClock() {}
}
