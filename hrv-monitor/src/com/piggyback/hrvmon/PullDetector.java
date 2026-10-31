package com.piggyback.hrvmon;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Detecta "puxadas" (aceleração forte com turbo) e resume o que importa em cada uma:
 * pressão máxima, λ medido contra o pedido pela central, avanço e pressão do rail.
 *
 * Recebe só leituras recentes (valores velhos chegam como NaN). λ, rail e avanço são
 * avaliados no "miolo" da puxada: depois do primeiro segundo e antes de a borboleta fechar.
 */
final class PullDetector {
    /** Medido acima do pedido por mais que isso, sob turbo, é mistura mais pobre que a planejada. */
    static final float LEAN_DEVIATION = 0.05f;
    static final float BOOST_START = 0.25f;  // bar
    private static final float START_AFTER = 0.8f;  // s acima de BOOST_START
    private static final float END_AFTER = 0.7f;    // s abaixo de BOOST_START
    private static final float MIN_DURATION = 1.5f;
    private static final float TAIL_TRIM = 0.5f;    // s ignorados no fim, mesmo sem dado da borboleta
    private static final float LIFT_RATIO = 0.6f;   // borboleta abaixo disso do máximo = tirou o pé
    private static final float MAX_GAP = 0.35f;     // s entre leituras para comparar avanço

    static final class Pull {
        int number;
        long wallStart;
        float t0, t1;
        float rpmStart = Float.NaN, rpmMax = Float.NEGATIVE_INFINITY;
        float boostMax = Float.NEGATIVE_INFINITY, mapMax = Float.NEGATIVE_INFINITY;
        float boostCmdMax = Float.NEGATIVE_INFINITY;
        float lambdaMin = Float.POSITIVE_INFINITY, lambdaMaxLoaded = Float.NEGATIVE_INFINITY;
        float lambdaCmdMin = Float.POSITIVE_INFINITY;
        float leanDevMax = Float.NEGATIVE_INFINITY;
        int leanRun;
        float timingMin = Float.POSITIVE_INFINITY, timingMax = Float.NEGATIVE_INFINITY;
        int timingDrops;
        float railPeak = Float.NEGATIVE_INFINITY, railMinAfterPeak = Float.POSITIVE_INFINITY;
        float iatMax = Float.NEGATIVE_INFINITY, iat2Max = Float.NEGATIVE_INFINITY;
        float ethanol = Float.NaN, speedMax = Float.NEGATIVE_INFINITY;
        float wgMin = Float.POSITIVE_INFINITY, wgMax = Float.NEGATIVE_INFINITY;
        int samples;
        boolean mapSaturated;
        final List<String> flags = new ArrayList<>();

        String summary() {
            StringBuilder b = new StringBuilder();
            b.append("Puxada #").append(number).append(" · ").append(Fmt.f(t1 - t0, 1)).append(" s · ")
                    .append(samples).append(" leituras · ")
                    .append(new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date(wallStart))).append('\n');
            b.append("Rotação: ").append(Fmt.f(rpmStart, 0)).append(" → ").append(Fmt.f(rpmMax, 0)).append(" rpm\n");
            b.append("Turbo máx: ").append(Fmt.f(boostMax, 2)).append(" bar (MAP ").append(Fmt.f(mapMax, 0)).append(" kPa)");
            if (Fmt.ok(boostCmdMax)) b.append(" · alvo ").append(Fmt.f(boostCmdMax, 2)).append(" bar");
            b.append('\n');
            b.append("λ medido: mín ").append(Fmt.f(lambdaMin, 2)).append(" · máx com turbo ").append(Fmt.f(lambdaMaxLoaded, 2));
            if (Fmt.ok(lambdaCmdMin)) b.append(" · pedido mín ").append(Fmt.f(lambdaCmdMin, 2));
            b.append('\n');
            if (Fmt.ok(leanDevMax)) {
                b.append("λ medido − pedido, máx: ").append(leanDevMax >= 0 ? "+" : "").append(Fmt.f(leanDevMax, 2)).append('\n');
            }
            b.append("Avanço: ").append(Fmt.f(timingMin, 1)).append("° a ").append(Fmt.f(timingMax, 1)).append("°\n");
            if (Fmt.ok(railPeak)) {
                b.append("Rail: pico ").append(Fmt.f(railPeak, 0)).append(" bar · mín depois do pico ")
                        .append(Fmt.f(railMinAfterPeak, 0)).append(" bar\n");
            }
            if (Fmt.ok(wgMax)) {
                b.append("Wastegate: ").append(Fmt.f(wgMin, 0)).append("% a ").append(Fmt.f(wgMax, 0)).append("%\n");
            }
            b.append("Ar de admissão máx: ").append(Fmt.f(iatMax, 0));
            if (Fmt.ok(iat2Max)) b.append(" / ").append(Fmt.f(iat2Max, 0)).append(" (2º sensor)");
            b.append(" °C");
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

    /** {@code s} traz só leituras recentes; valores velhos vêm como NaN. */
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
            } else if (Fmt.ok(boost) || Fmt.ok(load)) {
                highSince = -1;
            }
            return;
        }
        // Só guarda amostras com carga; a folga do fim serve apenas para não partir a puxada em duas.
        if (high) {
            samples.add(sample(t, s));
            lowSince = -1;
        } else if (Fmt.ok(boost) || Fmt.ok(load)) {
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

    private static float v(float[] r, int ch) {
        return r[ch + 1];
    }

    private void finish() {
        Pull p = cur;
        cur = null;
        highSince = -1;
        p.t1 = lowSince;
        lowSince = -1;
        if (p.t1 - p.t0 < MIN_DURATION) return;

        // Miolo: depois do primeiro segundo (turbo enchendo) e antes de tirar o pé.
        float coreStart = p.t0 + 1f, coreEnd = p.t1 - TAIL_TRIM;
        float thrMax = Float.NEGATIVE_INFINITY;
        for (float[] r : samples) {
            float thr = v(r, Ch.THROTTLE);
            if (!Fmt.ok(thr)) continue;
            if (thrMax > 20f && thr < thrMax * LIFT_RATIO) {
                coreEnd = Math.min(coreEnd, r[0] - 0.01f);
                break;
            }
            thrMax = Math.max(thrMax, thr);
        }

        float prevT = Float.NaN, prevTiming = Float.NaN, prevRpm = Float.NaN;
        int run = 0;
        for (float[] r : samples) {
            float t = r[0];
            float rpm = v(r, Ch.RPM), boost = v(r, Ch.BOOST);
            p.samples++;
            if (!Fmt.ok(p.rpmStart)) p.rpmStart = rpm;
            if (Fmt.ok(rpm)) p.rpmMax = Math.max(p.rpmMax, rpm);
            if (Fmt.ok(boost)) p.boostMax = Math.max(p.boostMax, boost);
            if (Fmt.ok(v(r, Ch.MAP))) p.mapMax = Math.max(p.mapMax, v(r, Ch.MAP));
            if (Fmt.ok(v(r, Ch.BOOST_CMD))) p.boostCmdMax = Math.max(p.boostCmdMax, v(r, Ch.BOOST_CMD));
            if (v(r, Ch.MAP_0B) >= Pids.mapMax - 0.5f && !Fmt.ok(v(r, Ch.MAP_87)) && !Fmt.ok(v(r, Ch.MAP_70))) {
                p.mapSaturated = true;
            }
            if (Fmt.ok(v(r, Ch.IAT))) p.iatMax = Math.max(p.iatMax, v(r, Ch.IAT));
            if (Fmt.ok(v(r, Ch.IAT2))) p.iat2Max = Math.max(p.iat2Max, v(r, Ch.IAT2));
            if (Fmt.ok(v(r, Ch.WG))) {
                p.wgMin = Math.min(p.wgMin, v(r, Ch.WG));
                p.wgMax = Math.max(p.wgMax, v(r, Ch.WG));
            }
            if (Fmt.ok(v(r, Ch.ETHANOL))) p.ethanol = v(r, Ch.ETHANOL);
            if (Fmt.ok(v(r, Ch.SPEED))) p.speedMax = Math.max(p.speedMax, v(r, Ch.SPEED));

            // 2,0 = corte de combustível, não é mistura.
            float lam = v(r, Ch.LAMBDA), cmd = v(r, Ch.LAMBDA_CMD);
            boolean lamOk = Fmt.ok(lam) && lam < 1.5f, cmdOk = Fmt.ok(cmd) && cmd < 1.5f;
            if (lamOk) p.lambdaMin = Math.min(p.lambdaMin, lam);
            if (cmdOk) p.lambdaCmdMin = Math.min(p.lambdaCmdMin, cmd);
            float timing = v(r, Ch.TIMING);
            if (Fmt.ok(timing)) {
                p.timingMin = Math.min(p.timingMin, timing);
                p.timingMax = Math.max(p.timingMax, timing);
            }
            if (t < coreStart || t > coreEnd) continue;

            boolean loaded = Fmt.ok(boost) && boost > 0.3f;
            if (lamOk && loaded) p.lambdaMaxLoaded = Math.max(p.lambdaMaxLoaded, lam);
            if (lamOk && cmdOk && loaded) {
                float dev = lam - cmd;
                p.leanDevMax = Math.max(p.leanDevMax, dev);
                run = dev > LEAN_DEVIATION ? run + 1 : 0;
                p.leanRun = Math.max(p.leanRun, run);
            }
            // Queda brusca entre leituras próximas, com a rotação subindo = a central tirando avanço.
            if (Fmt.ok(timing) && Fmt.ok(prevTiming) && t - prevT <= MAX_GAP && timing < prevTiming - 3f
                    && Fmt.ok(rpm) && Fmt.ok(prevRpm) && rpm >= prevRpm - 50f) {
                p.timingDrops++;
            }
            if (Fmt.ok(timing)) {
                prevT = t;
                prevTiming = timing;
                prevRpm = rpm;
            }
            float rail = v(r, Ch.RAIL);
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

        if (p.leanRun >= 2) {
            p.flags.add("⚠ Mistura mais pobre que a pedida pela central: λ medido até "
                    + Fmt.f(p.leanDevMax, 2) + " acima do pedido");
        }
        if (Fmt.ok(p.railPeak) && p.railPeak > 0 && p.railMinAfterPeak < p.railPeak * 0.85f) {
            p.flags.add("⚠ Pressão do rail caiu " + Fmt.f((1 - p.railMinAfterPeak / p.railPeak) * 100, 0)
                    + "% com o pé no fundo (" + Fmt.f(p.railPeak, 0) + " → " + Fmt.f(p.railMinAfterPeak, 0) + " bar)");
        }
        if (p.timingDrops > 0) {
            p.flags.add("⚠ " + p.timingDrops + " queda(s) brusca(s) de avanço: possível correção de detonação");
        }
        if (p.mapSaturated) {
            p.flags.add("ℹ MAP no limite do PID 0x0B (" + Fmt.f(Pids.mapMax, 0) + " kPa): a pressão real pode ser maior");
        }
        if (p.samples < 8) {
            p.flags.add("ℹ Poucas leituras (" + p.samples + "): os alertas desta puxada são pouco confiáveis");
        }
        if (p.flags.isEmpty()) p.flags.add("✓ Nada anormal nesta puxada");
        p.number = done.size() + 1;
        done.add(p);
    }
}
