package com.piggyback.hrvmon;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Detecta "puxadas" (aceleração forte com turbo) e resume o que importa em cada uma:
 * pressão máxima, λ sob carga, avanço e pressão do rail.
 */
final class PullDetector {
    /** Acima disso, sob turbo, a mistura está mais pobre que o original (~0,80). */
    static final float LAMBDA_LEAN = 0.86f;
    static final float BOOST_START = 0.25f;  // bar
    private static final float START_AFTER = 0.8f;  // s acima de BOOST_START
    private static final float END_AFTER = 0.7f;    // s abaixo de BOOST_START
    private static final float MIN_DURATION = 1.5f;
    private static final float TAIL_TRIM = 0.5f;    // s ignorados no fim para λ, rail e avanço

    static final class Pull {
        int number;
        long wallStart;
        float t0, t1;
        float rpmStart = Float.NaN, rpmMax = Float.NEGATIVE_INFINITY;
        float boostMax = Float.NEGATIVE_INFINITY, mapMax = Float.NEGATIVE_INFINITY;
        float boostCmdMax = Float.NEGATIVE_INFINITY;
        float lambdaMin = Float.POSITIVE_INFINITY, lambdaMaxLoaded = Float.NEGATIVE_INFINITY;
        float lambdaCmdMin = Float.POSITIVE_INFINITY;
        float timingMin = Float.POSITIVE_INFINITY, timingMax = Float.NEGATIVE_INFINITY;
        int timingDrops;
        float railPeak = Float.NEGATIVE_INFINITY, railMinAfterPeak = Float.POSITIVE_INFINITY;
        float iatMax = Float.NEGATIVE_INFINITY, ethanol = Float.NaN, speedMax = Float.NEGATIVE_INFINITY;
        boolean mapSaturated;
        final List<String> flags = new ArrayList<>();

        String summary() {
            StringBuilder b = new StringBuilder();
            b.append("Puxada #").append(number).append(" · ").append(Fmt.f(t1 - t0, 1)).append(" s · ")
                    .append(new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date(wallStart))).append('\n');
            b.append("Rotação: ").append(Fmt.f(rpmStart, 0)).append(" → ").append(Fmt.f(rpmMax, 0)).append(" rpm\n");
            b.append("Turbo máx: ").append(Fmt.f(boostMax, 2)).append(" bar (MAP ").append(Fmt.f(mapMax, 0)).append(" kPa)");
            if (Fmt.ok(boostCmdMax)) b.append(" · alvo ").append(Fmt.f(boostCmdMax, 2)).append(" bar");
            b.append('\n');
            b.append("λ: mín ").append(Fmt.f(lambdaMin, 2)).append(" · máx sob carga ").append(Fmt.f(lambdaMaxLoaded, 2));
            if (Fmt.ok(lambdaCmdMin)) b.append(" · comandado mín ").append(Fmt.f(lambdaCmdMin, 2));
            b.append('\n');
            b.append("Avanço: ").append(Fmt.f(timingMin, 1)).append("° a ").append(Fmt.f(timingMax, 1)).append("°\n");
            if (Fmt.ok(railPeak)) {
                b.append("Rail: pico ").append(Fmt.f(railPeak, 0)).append(" bar · mín depois do pico ")
                        .append(Fmt.f(railMinAfterPeak, 0)).append(" bar\n");
            }
            b.append("Ar de admissão máx: ").append(Fmt.f(iatMax, 0)).append(" °C");
            if (Fmt.ok(ethanol)) b.append(" · etanol ").append(Fmt.f(ethanol, 0)).append('%');
            if (Fmt.ok(speedMax)) b.append(" · ").append(Fmt.f(speedMax, 0)).append(" km/h");
            for (String f : flags) b.append('\n').append(f);
            return b.toString();
        }
    }

    private final List<Pull> done = new ArrayList<>();
    private final List<float[]> pending = new ArrayList<>();
    private final List<float[]> samples = new ArrayList<>();
    private Pull cur;
    private float highSince = -1, lowSince = -1;

    synchronized void clear() {
        done.clear();
        pending.clear();
        samples.clear();
        cur = null;
        highSince = lowSince = -1;
    }

    synchronized List<Pull> list() {
        return new ArrayList<>(done);
    }

    synchronized int count() {
        return done.size();
    }

    synchronized void feed(float t, float[] s) {
        float boost = s[Ch.BOOST];
        float load = s[Ch.LOAD];
        boolean high = Fmt.ok(boost) ? boost > BOOST_START : Fmt.ok(load) && load > 85f;

        if (cur == null) {
            if (high) {
                if (highSince < 0) {
                    highSince = t;
                    pending.clear();
                }
                pending.add(sample(t, s));
                if (t - highSince >= START_AFTER) {
                    cur = new Pull();
                    cur.t0 = highSince;
                    cur.wallStart = System.currentTimeMillis() - (long) ((t - highSince) * 1000);
                    samples.clear();
                    samples.addAll(pending);
                    lowSince = -1;
                }
            } else {
                highSince = -1;
            }
            return;
        }
        // Só guarda amostras com carga; a folga do fim serve apenas para não partir a puxada em duas.
        if (high) {
            samples.add(sample(t, s));
            lowSince = -1;
        } else {
            if (lowSince < 0) lowSince = t;
            if (t - lowSince >= END_AFTER) finish();
        }
    }

    private static float[] sample(float t, float[] s) {
        float[] r = new float[Ch.COUNT + 1];
        r[0] = t;
        System.arraycopy(s, 0, r, 1, Ch.COUNT);
        return r;
    }

    private void finish() {
        Pull p = cur;
        cur = null;
        highSince = -1;
        p.t1 = lowSince;
        lowSince = -1;
        if (p.t1 - p.t0 < MIN_DURATION) return;

        // λ, rail e avanço ignoram o primeiro segundo (o turbo ainda está enchendo) e o último
        // meio segundo (ao tirar o pé, cada leitura cai num instante diferente).
        float coreStart = p.t0 + 1f, coreEnd = p.t1 - TAIL_TRIM;
        float prevTiming = Float.NaN, prevRpm = Float.NaN;
        for (float[] r : samples) {
            float t = r[0];
            float rpm = r[Ch.RPM + 1], boost = r[Ch.BOOST + 1];
            if (!Fmt.ok(p.rpmStart)) p.rpmStart = rpm;
            if (Fmt.ok(rpm)) p.rpmMax = Math.max(p.rpmMax, rpm);
            if (Fmt.ok(boost)) p.boostMax = Math.max(p.boostMax, boost);
            if (Fmt.ok(r[Ch.MAP + 1])) p.mapMax = Math.max(p.mapMax, r[Ch.MAP + 1]);
            if (Fmt.ok(r[Ch.BOOST_CMD + 1])) p.boostCmdMax = Math.max(p.boostCmdMax, r[Ch.BOOST_CMD + 1]);
            if (r[Ch.MAP_0B + 1] >= 254.5f && !Fmt.ok(r[Ch.MAP_87 + 1]) && !Fmt.ok(r[Ch.MAP_70 + 1])) p.mapSaturated = true;
            if (Fmt.ok(r[Ch.IAT + 1])) p.iatMax = Math.max(p.iatMax, r[Ch.IAT + 1]);
            if (Fmt.ok(r[Ch.ETHANOL + 1])) p.ethanol = r[Ch.ETHANOL + 1];
            if (Fmt.ok(r[Ch.SPEED + 1])) p.speedMax = Math.max(p.speedMax, r[Ch.SPEED + 1]);
            if (Fmt.ok(r[Ch.LAMBDA_CMD + 1])) p.lambdaCmdMin = Math.min(p.lambdaCmdMin, r[Ch.LAMBDA_CMD + 1]);

            float lam = r[Ch.LAMBDA + 1];
            if (Fmt.ok(lam) && lam < 1.5f) {  // 2,0 = corte de combustível, não é mistura
                p.lambdaMin = Math.min(p.lambdaMin, lam);
            }
            float timing = r[Ch.TIMING + 1];
            if (Fmt.ok(timing)) {
                p.timingMin = Math.min(p.timingMin, timing);
                p.timingMax = Math.max(p.timingMax, timing);
            }
            if (t > coreEnd) continue;

            if (Fmt.ok(lam) && lam < 1.5f && t >= coreStart && Fmt.ok(boost) && boost > 0.3f) {
                p.lambdaMaxLoaded = Math.max(p.lambdaMaxLoaded, lam);
            }
            // Queda brusca com a rotação subindo = a ECU tirando avanço (detonação).
            if (Fmt.ok(timing) && Fmt.ok(prevTiming) && timing < prevTiming - 3f
                    && Fmt.ok(rpm) && Fmt.ok(prevRpm) && rpm >= prevRpm - 50f) {
                p.timingDrops++;
            }
            prevTiming = timing;
            prevRpm = rpm;
            float rail = r[Ch.RAIL + 1];
            if (Fmt.ok(rail)) {
                if (rail > p.railPeak) {
                    p.railPeak = rail;
                    p.railMinAfterPeak = rail;
                } else {
                    p.railMinAfterPeak = Math.min(p.railMinAfterPeak, rail);
                }
            }
        }
        samples.clear();

        if (p.lambdaMaxLoaded > LAMBDA_LEAN) {
            p.flags.add("⚠ Mistura empobreceu sob carga: λ máx " + Fmt.f(p.lambdaMaxLoaded, 2)
                    + " (limite " + Fmt.f(LAMBDA_LEAN, 2) + ")");
        }
        if (Fmt.ok(p.railPeak) && p.railPeak > 0 && p.railMinAfterPeak < p.railPeak * 0.85f) {
            p.flags.add("⚠ Pressão do rail caiu " + Fmt.f((1 - p.railMinAfterPeak / p.railPeak) * 100, 0)
                    + "% durante a puxada (" + Fmt.f(p.railPeak, 0) + " → " + Fmt.f(p.railMinAfterPeak, 0) + " bar)");
        }
        if (p.timingDrops > 0) {
            p.flags.add("⚠ " + p.timingDrops + " queda(s) brusca(s) de avanço: possível correção de detonação");
        }
        if (p.mapSaturated) {
            p.flags.add("ℹ MAP no limite do PID 0x0B (255 kPa): a pressão real pode ser maior");
        }
        if (p.flags.isEmpty()) p.flags.add("✓ Nada anormal nesta puxada");
        p.number = done.size() + 1;
        done.add(p);
    }
}
