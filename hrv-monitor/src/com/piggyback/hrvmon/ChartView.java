package com.piggyback.hrvmon;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

import java.util.Collections;
import java.util.List;

/** Gráfico de linhas no tempo para um ou mais canais. Com muitos pontos, desenha o envelope mín/máx por pixel. */
final class ChartView extends View {
    private final String title;
    private final int[] channels;
    private final int[] colors;
    private final String[] names;
    private final int decimals;
    private final float dp;
    private float fixedMin = Float.NaN, fixedMax = Float.NaN, minSpan = 1f;
    private float[] refs = new float[0];
    private int[] refColors = new int[0];

    private List<float[]> data = Collections.emptyList();
    private float window;
    private float lo, hi, top, bottom;

    private final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint grid = new Paint();
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ref = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();

    ChartView(Context ctx, String title, int[] channels, int[] colors, String[] names, int decimals) {
        super(ctx);
        this.title = title;
        this.channels = channels;
        this.colors = colors;
        this.names = names;
        this.decimals = decimals;
        dp = ctx.getResources().getDisplayMetrics().density;
        bg.setColor(0xFF161C22);
        grid.setColor(0xFF2A333D);
        grid.setStrokeWidth(1);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(2 * dp);
        line.setStrokeJoin(Paint.Join.ROUND);
        ref.setStyle(Paint.Style.STROKE);
        ref.setStrokeWidth(1.5f * dp);
        ref.setPathEffect(new DashPathEffect(new float[]{6 * dp, 4 * dp}, 0));
        text.setTextSize(11 * dp);
    }

    ChartView range(float min, float max) {
        fixedMin = min;
        fixedMax = max;
        return this;
    }

    ChartView span(float s) {
        minSpan = s;
        return this;
    }

    ChartView refs(float[] values, int[] colors) {
        refs = values;
        refColors = colors;
        return this;
    }

    void setData(List<float[]> d, float windowSeconds) {
        data = d;
        window = windowSeconds;
        invalidate();
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        setMeasuredDimension(MeasureSpec.getSize(widthSpec), (int) (150 * dp));
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        c.drawRoundRect(0, 0, w, h, 10 * dp, 10 * dp, bg);
        float left = 44 * dp, right = w - 8 * dp;
        top = 28 * dp;
        bottom = h - 10 * dp;

        text.setColor(0xFFB0BEC5);
        text.setTextSize(13 * dp);
        c.drawText(title, 10 * dp, 19 * dp, text);
        text.setTextSize(11 * dp);

        int n = data.size();
        if (n < 2) {
            c.drawText("sem dados", left, (top + bottom) / 2, text);
            return;
        }
        float tEnd = data.get(n - 1)[0];
        float tStart = window > 0 ? tEnd - window : data.get(0)[0];
        if (tEnd - tStart < 1f) tStart = tEnd - 1f;
        int i0 = 0;
        while (i0 < n - 1 && data.get(i0 + 1)[0] < tStart) i0++;

        computeRange(i0);

        // Grade e escala
        for (int g = 0; g <= 4; g++) {
            float v = lo + (hi - lo) * g / 4f;
            float y = y(v);
            c.drawLine(left, y, right, y, grid);
            c.drawText(Fmt.f(v, decimals), 6 * dp, y + 4 * dp, text);
        }
        for (int k = 0; k < refs.length; k++) {
            if (refs[k] < lo || refs[k] > hi) continue;
            ref.setColor(refColors[k]);
            float y = y(refs[k]);
            c.drawLine(left, y, right, y, ref);
        }

        float xs = (right - left) / (tEnd - tStart);
        int cols = Math.max(1, (int) (right - left));
        boolean envelope = (n - i0) > cols * 2;
        for (int k = 0; k < channels.length; k++) {
            int ch = channels[k] + 1;
            line.setColor(colors[k]);
            if (envelope) {
                float[] mn = new float[cols + 1], mx = new float[cols + 1];
                java.util.Arrays.fill(mn, Float.NaN);
                for (int i = i0; i < n; i++) {
                    float[] r = data.get(i);
                    float v = r[ch];
                    if (!Fmt.ok(v)) continue;
                    int col = Math.max(0, Math.min(cols, (int) ((r[0] - tStart) * xs)));
                    if (Float.isNaN(mn[col])) {
                        mn[col] = mx[col] = v;
                    } else {
                        mn[col] = Math.min(mn[col], v);
                        mx[col] = Math.max(mx[col], v);
                    }
                }
                for (int col = 0; col <= cols; col++) {
                    if (Float.isNaN(mn[col])) continue;
                    float x = left + col;
                    c.drawLine(x, y(mn[col]) + dp, x, y(mx[col]) - dp, line);
                }
            } else {
                path.reset();
                boolean pen = false;
                for (int i = i0; i < n; i++) {
                    float[] r = data.get(i);
                    float v = r[ch];
                    if (!Fmt.ok(v)) {
                        pen = false;
                        continue;
                    }
                    float x = Math.max(left, left + (r[0] - tStart) * xs);
                    if (pen) path.lineTo(x, y(v)); else path.moveTo(x, y(v));
                    pen = true;
                }
                c.drawPath(path, line);
            }
        }

        // Valores atuais no canto superior direito
        float[] last = data.get(n - 1);
        float x = right;
        text.setTextSize(13 * dp);
        for (int k = channels.length - 1; k >= 0; k--) {
            String s = (names[k].isEmpty() ? "" : names[k] + " ") + Fmt.f(last[channels[k] + 1], decimals);
            x -= text.measureText(s);
            text.setColor(colors[k]);
            c.drawText(s, x, 19 * dp, text);
            x -= 12 * dp;
        }
    }

    private void computeRange(int i0) {
        if (!Float.isNaN(fixedMin)) {
            lo = fixedMin;
            hi = fixedMax;
            return;
        }
        lo = Float.POSITIVE_INFINITY;
        hi = Float.NEGATIVE_INFINITY;
        for (int i = i0; i < data.size(); i++) {
            float[] r = data.get(i);
            for (int ch : channels) {
                float v = r[ch + 1];
                if (!Fmt.ok(v)) continue;
                lo = Math.min(lo, v);
                hi = Math.max(hi, v);
            }
        }
        for (float v : refs) {
            lo = Math.min(lo, v);
            hi = Math.max(hi, v);
        }
        if (lo > hi) {
            lo = 0;
            hi = minSpan;
        }
        float span = hi - lo;
        if (span < minSpan) {
            float mid = (lo + hi) / 2;
            lo = mid - minSpan / 2;
            hi = mid + minSpan / 2;
        } else {
            lo -= span * 0.08f;
            hi += span * 0.08f;
        }
    }

    private float y(float v) {
        float y = bottom - (v - lo) / (hi - lo) * (bottom - top);
        return Math.max(top, Math.min(bottom, y));
    }
}
