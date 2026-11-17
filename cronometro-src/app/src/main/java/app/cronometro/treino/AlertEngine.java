package app.cronometro.treino;

import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Dispara som e vibração no fim de cada fase do treino, sem depender do WebView
 * (que o Android pausa com a tela desligada). A página envia o cronograma completo
 * dos próximos alertas; um serviço em primeiro plano mantém o processo vivo.
 */
final class AlertEngine {
    private static final long[] VIB_START = {250, 120, 250, 120, 400};
    private static final long[] VIB_END = {300, 100, 300};
    private static final long[] VIB_DONE = {400, 150, 400};

    private static Context app;
    private static Handler handler;
    private static final Object TOKEN = new Object();
    private static final Object STOP_TOKEN = new Object();
    private static byte[] wavStart, wavEnd, wavDone, wavTick;
    private static boolean vibrate = true;

    private AlertEngine() {}

    static synchronized void init(Context ctx) {
        if (handler != null) return;
        app = ctx.getApplicationContext();
        HandlerThread th = new HandlerThread("alert-engine");
        th.start();
        handler = new Handler(th.getLooper());
    }

    static synchronized void config(String json) {
        try {
            JSONObject o = new JSONObject(json);
            wavStart = decode(o.optString("start"));
            wavEnd = decode(o.optString("end"));
            wavDone = decode(o.optString("done"));
            wavTick = decode(o.optString("tick"));
            vibrate = o.optBoolean("vibrate", true);
        } catch (Exception ignored) {
        }
    }

    private static byte[] decode(String b64) {
        if (b64 == null || b64.isEmpty()) return null;
        return Base64.decode(b64, Base64.DEFAULT);
    }

    /** Substitui todo o cronograma. events: [{t: epochMs, k: "start"|"end"|"done"}]. */
    static void schedule(String json) {
        if (handler == null) return;
        final JSONArray arr;
        try {
            arr = new JSONArray(json);
        } catch (Exception e) {
            return;
        }
        handler.post(new Runnable() {
            @Override
            public void run() {
                handler.removeCallbacksAndMessages(TOKEN);
                handler.removeCallbacksAndMessages(STOP_TOKEN);
                long wallNow = System.currentTimeMillis();
                long upNow = SystemClock.uptimeMillis();
                long last = 0;
                int count = 0;
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject e = arr.optJSONObject(i);
                    if (e == null) continue;
                    long t = (long) e.optDouble("t", 0);
                    final String k = e.optString("k");
                    long delay = t - wallNow;
                    if (delay < (("tick".equals(k)) ? -300 : -2000)) continue;   // já passou há tempo demais
                    final long at = upNow + Math.max(0, delay);
                    handler.postAtTime(new Runnable() {
                        @Override
                        public void run() {
                            fire(k);
                        }
                    }, TOKEN, at);
                    last = Math.max(last, at);
                    count++;
                }
                if (count > 0) {
                    startService();
                    // depois do último alerta, deixa o som terminar e encerra o serviço
                    handler.postAtTime(new Runnable() {
                        @Override
                        public void run() {
                            stopService();
                        }
                    }, STOP_TOKEN, last + 6000);
                } else {
                    stopService();
                }
            }
        });
    }

    static void cancelAll() {
        if (handler == null) return;
        handler.removeCallbacksAndMessages(TOKEN);
        handler.removeCallbacksAndMessages(STOP_TOKEN);
        stopService();
    }

    /** Alerta imediato (início do treino, fim por toque no botão). */
    static void alertNow(final String kind) {
        if (handler == null) return;
        handler.post(new Runnable() {
            @Override
            public void run() {
                fire(kind);
            }
        });
    }

    private static void fire(String kind) {
        byte[] wav;
        long[] pat;
        if ("tick".equals(kind)) { playWav(wavTick); return; }   // contagem final: só som
        if ("end".equals(kind)) { wav = wavEnd; pat = VIB_END; }
        else if ("done".equals(kind)) { wav = wavDone; pat = VIB_DONE; }
        else { wav = wavStart; pat = VIB_START; }
        if (vibrate) vibrate(pat);
        playWav(wav);
    }

    static void vibrateCsv(String csv) {
        if (csv == null || csv.trim().isEmpty()) return;
        String[] parts = csv.split(",");
        long[] p = new long[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try {
                p[i] = Long.parseLong(parts[i].trim());
            } catch (NumberFormatException e) {
                return;
            }
        }
        vibrate(p);
    }

    /** pattern no formato da web: vibra, pausa, vibra... */
    static void vibrate(long[] pattern) {
        try {
            if (app == null) return;
            Vibrator v = (Vibrator) app.getSystemService(Context.VIBRATOR_SERVICE);
            if (v == null || !v.hasVibrator()) return;
            long[] t = new long[pattern.length + 1];       // formato nativo começa com uma pausa
            for (int i = 0; i < pattern.length; i++) t[i + 1] = Math.max(0, Math.min(5000, pattern[i]));
            // Atributo de alarme: vibra mesmo com a tela desligada e com "vibração ao toque" desligada.
            AudioAttributes attrs = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build();
            v.vibrate(VibrationEffect.createWaveform(t, -1), attrs);
        } catch (Exception ignored) {
        }
    }

    private static void playWav(byte[] wav) {
        if (wav == null || wav.length <= 44 || app == null) return;
        try {
            final int rate = (wav[24] & 0xFF) | (wav[25] & 0xFF) << 8 | (wav[26] & 0xFF) << 16 | (wav[27] & 0xFF) << 24;
            final int len = wav.length - 44;
            final AudioManager am = (AudioManager) app.getSystemService(Context.AUDIO_SERVICE);
            AudioAttributes aa = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build();
            // Música tocando no aparelho só abaixa o volume durante o bipe.
            final AudioFocusRequest fr = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                    .setAudioAttributes(aa).build();
            am.requestAudioFocus(fr);
            final AudioTrack track = new AudioTrack.Builder()
                    .setAudioAttributes(aa)
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(rate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setBufferSizeInBytes(len)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build();
            track.write(wav, 44, len);
            final Runnable release = new Runnable() {
                @Override
                public void run() {
                    try { track.release(); } catch (Exception ignored) {}
                    try { am.abandonAudioFocusRequest(fr); } catch (Exception ignored) {}
                }
            };
            handler.postDelayed(release, (long) len * 500L / rate + 400L);   // duração + folga
            track.play();
        } catch (Exception ignored) {
        }
    }

    private static void startService() {
        try {
            app.startForegroundService(new Intent(app, TimerService.class));
        } catch (Exception ignored) {
        }
    }

    private static void stopService() {
        try {
            app.stopService(new Intent(app, TimerService.class));
        } catch (Exception ignored) {
        }
    }
}
