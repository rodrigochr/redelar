package com.piggyback.hrvmon;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public final class MainActivity extends Activity {
    private static final int REQ_BT = 1;
    private static final String WIFI = "wifi";

    private static final int BG = 0xFF0E1216;
    private static final int CARD = 0xFF1A2129;
    private static final int TEXT = 0xFFECEFF1;
    private static final int MUTED = 0xFF90A4AE;
    private static final int BLUE = 0xFF4FC3F7;
    private static final int AMBER = 0xFFFFC107;
    private static final int GREEN = 0xFF66BB6A;
    private static final int RED = 0xFFFF5252;
    private static final int PURPLE = 0xFFB388FF;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private float dp;
    private TextView status, pullView, pidsView;
    private Button connectBtn;
    private final Button[] windowBtns = new Button[3];
    private final float[] windowValues = {20f, 60f, 0f};
    private Tile tRpm, tLambda, tBoost, tTiming, tRail, tLambdaCmd, tIat, tTrim, tEthanol;
    private ChartView[] charts;
    private float window = 20f;
    private int shownPulls = -1;
    private boolean[] shownSupported;
    private String shownInfo;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            refresh();
            ui.postDelayed(this, 150);
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        dp = getResources().getDisplayMetrics().density;
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        setContentView(buildUi());
    }

    @Override
    protected void onResume() {
        super.onResume();
        ui.post(tick);
    }

    @Override
    protected void onPause() {
        super.onPause();
        ui.removeCallbacks(tick);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (isFinishing()) {
            Poller p = Session.poller;
            if (p != null) p.stop();
        }
    }

    // ---------------------------------------------------------------- tela

    private View buildUi() {
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(BG);
        LinearLayout root = vbox();
        root.setPadding(px(12), px(12), px(12), px(28));
        sv.addView(root);

        TextView title = text("HR-V Monitor", 22, TEXT);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);
        status = text("", 14, MUTED);
        root.addView(status, lp(4));

        LinearLayout btns = hbox();
        connectBtn = button("Conectar", v -> onConnectClick());
        btns.addView(connectBtn, weight());
        btns.addView(button("Salvar log", v -> exportFiles()), weight());
        btns.addView(button("Copiar relatório", v -> copyReport()), weight());
        root.addView(btns, lp(8));

        tRpm = new Tile("Rotação");
        tLambda = new Tile("λ medido");
        tBoost = new Tile("Turbo (bar)");
        tTiming = new Tile("Avanço (°)");
        tRail = new Tile("Rail (bar)");
        tLambdaCmd = new Tile("λ comandado");
        tIat = new Tile("Ar admissão (°C)");
        tTrim = new Tile("Trim curto/longo");
        tEthanol = new Tile("Etanol (%)");
        root.addView(row(tRpm, tLambda, tBoost), lp(10));
        root.addView(row(tTiming, tRail, tLambdaCmd), lp(6));
        root.addView(row(tIat, tTrim, tEthanol), lp(6));

        LinearLayout wsel = hbox();
        wsel.setGravity(Gravity.CENTER_VERTICAL);
        wsel.addView(text("Janela:", 14, MUTED));
        String[] labels = {"20 s", "60 s", "Tudo"};
        for (int i = 0; i < 3; i++) {
            final float w = windowValues[i];
            windowBtns[i] = button(labels[i], v -> {
                window = w;
                styleWindowButtons();
                refresh();
            });
            wsel.addView(windowBtns[i], weight());
        }
        styleWindowButtons();
        root.addView(wsel, lp(12));

        charts = new ChartView[]{
            new ChartView(this, "Lambda", new int[]{Ch.LAMBDA, Ch.LAMBDA_CMD}, new int[]{BLUE, AMBER},
                    new String[]{"medido", "cmd"}, 2)
                    .range(0.6f, 1.2f)
                    .refs(new float[]{1f, PullDetector.LAMBDA_LEAN}, new int[]{MUTED, RED}),
            new ChartView(this, "Pressão do turbo (bar)", new int[]{Ch.BOOST, Ch.BOOST_CMD}, new int[]{GREEN, AMBER},
                    new String[]{"real", "alvo"}, 2)
                    .span(1.5f)
                    .refs(new float[]{0f}, new int[]{MUTED}),
            new ChartView(this, "Avanço de ignição (°)", new int[]{Ch.TIMING}, new int[]{PURPLE},
                    new String[]{""}, 1).span(10f),
            new ChartView(this, "Pressão do rail (bar)", new int[]{Ch.RAIL}, new int[]{RED},
                    new String[]{""}, 0).span(20f),
            new ChartView(this, "Rotação", new int[]{Ch.RPM}, new int[]{TEXT},
                    new String[]{""}, 0).range(0f, 7000f),
        };
        for (ChartView c : charts) root.addView(c, lp(10));

        root.addView(section("Última puxada"), lp(16));
        pullView = text("Nenhuma puxada ainda.\nO app registra uma puxada quando a pressão do turbo fica acima de "
                + Fmt.f(PullDetector.BOOST_START, 2) + " bar por mais de 0,8 s.", 14, TEXT);
        root.addView(card(pullView), lp(6));

        root.addView(section("Conexão e PIDs suportados"), lp(16));
        pidsView = text("Conecte para ver o que a ECU informa.", 13, MUTED);
        root.addView(card(pidsView), lp(6));

        root.addView(text("Não mexa no celular dirigindo. Deixe gravando e confira depois, "
                + "ou peça para um passageiro acompanhar.", 12, MUTED), lp(16));
        root.addView(text("v" + Session.VERSION, 11, MUTED), lp(4));
        return sv;
    }

    private void refresh() {
        Poller p = Session.poller;
        connectBtn.setText(p != null ? "Desconectar" : "Conectar");
        String st = Session.status;
        if (p != null && p.isPolling()) st += " · " + Fmt.f(Session.rateHz, 1) + " leituras/s";
        status.setText(st);

        List<float[]> snap = Session.store.window(window);
        float[] r = snap.isEmpty() ? null : snap.get(snap.size() - 1);

        tRpm.set(v(r, Ch.RPM), 0, TEXT, 0x0C);
        float lam = v(r, Ch.LAMBDA), boost = v(r, Ch.BOOST);
        boolean lean = Fmt.ok(lam) && lam < 1.5f && Fmt.ok(boost) && boost > 0.3f && lam > PullDetector.LAMBDA_LEAN;
        if (Fmt.ok(lam) && lam >= 1.5f) {
            tLambda.setText("corte", MUTED);
        } else {
            tLambda.set(lam, 2, lean ? RED : BLUE, 0x24, 0x34);
        }
        tBoost.set(boost, 2, GREEN, 0x0B, 0x87, 0x70);
        tTiming.set(v(r, Ch.TIMING), 1, PURPLE, 0x0E);
        tRail.set(v(r, Ch.RAIL), 0, RED, 0x23, 0x59);
        tLambdaCmd.set(v(r, Ch.LAMBDA_CMD), 2, AMBER, 0x44);
        float iat = v(r, Ch.IAT);
        tIat.set(iat, 0, Fmt.ok(iat) && iat > 60 ? AMBER : TEXT, 0x0F);
        if (unsupported(0x06)) {
            tTrim.setText("n/d", MUTED);
        } else {
            tTrim.setText(Fmt.signed(v(r, Ch.STFT)) + " / " + Fmt.signed(v(r, Ch.LTFT)), TEXT);
        }
        tEthanol.set(v(r, Ch.ETHANOL), 0, TEXT, 0x52);

        for (ChartView c : charts) c.setData(snap, window);

        int n = Session.pulls.count();
        if (n != shownPulls) {
            shownPulls = n;
            if (n > 0) {
                List<PullDetector.Pull> ps = Session.pulls.list();
                String s = ps.get(ps.size() - 1).summary();
                if (n > 1) s += "\n\n" + n + " puxadas nesta sessão (todas vão no relatório).";
                pullView.setText(s);
            }
        }

        boolean[] sup = Session.supported;
        String info = Session.info;
        if (sup != shownSupported || !info.equals(shownInfo)) {
            shownSupported = sup;
            shownInfo = info;
            if (sup != null) {
                StringBuilder b = new StringBuilder(info).append('\n')
                        .append("PIDs por pedido: ").append(Session.pidsPerRequest).append("\n\n");
                for (int pid = 1; pid < 256; pid++) {
                    if (!sup[pid] || pid % 0x20 == 0) continue;
                    b.append(Session.isUsed(pid) ? "★ " : "    ").append(Fmt.hex2(pid)).append("  ")
                            .append(Pids.name(pid)).append('\n');
                }
                b.append("\n★ = lido pelo app");
                pidsView.setText(b.toString());
            }
        }
    }

    private static float v(float[] row, int ch) {
        return row == null ? Float.NaN : row[ch + 1];
    }

    private boolean unsupported(int... pids) {
        boolean[] sup = Session.supported;
        if (sup == null) return false;
        for (int p : pids) if (sup[p]) return false;
        return true;
    }

    private void styleWindowButtons() {
        for (int i = 0; i < 3; i++) {
            boolean on = windowValues[i] == window;
            windowBtns[i].setTextColor(on ? BG : TEXT);
            windowBtns[i].setBackground(round(on ? BLUE : CARD));
        }
    }

    // ---------------------------------------------------------------- conexão

    private void onConnectClick() {
        Poller p = Session.poller;
        if (p != null) {
            p.stop();
            return;
        }
        if (Build.VERSION.SDK_INT >= 31
                && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT}, REQ_BT);
            return;
        }
        chooseDevice(true);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        if (code != REQ_BT) return;
        boolean granted = results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED;
        if (!granted) toast("Sem permissão de Bluetooth: só a opção Wi-Fi fica disponível.");
        chooseDevice(granted);
    }

    private void chooseDevice(boolean bluetoothAllowed) {
        final List<String> labels = new ArrayList<>();
        final List<Object> targets = new ArrayList<>();
        final SharedPreferences prefs = getSharedPreferences("hrv", MODE_PRIVATE);
        String last = prefs.getString("last", "");

        if (bluetoothAllowed) {
            BluetoothManager bm = getSystemService(BluetoothManager.class);
            BluetoothAdapter adapter = bm == null ? null : bm.getAdapter();
            if (adapter == null) {
                toast("Este celular não tem Bluetooth.");
            } else if (!adapter.isEnabled()) {
                toast("Ligue o Bluetooth para ver o ELM327 pareado.");
            } else {
                try {
                    for (BluetoothDevice d : adapter.getBondedDevices()) {
                        String name = d.getName() == null ? "(sem nome)" : d.getName();
                        String label = name + "\n" + d.getAddress();
                        if (d.getAddress().equals(last)) {
                            labels.add(0, label + "  · último usado");
                            targets.add(0, d);
                        } else {
                            labels.add(label);
                            targets.add(d);
                        }
                    }
                } catch (SecurityException e) {
                    toast("Sem permissão de Bluetooth.");
                }
            }
        }
        String wifiLabel = "ELM327 Wi-Fi (192.168.0.10:35000)";
        if (WIFI.equals(last)) {
            labels.add(0, wifiLabel + "  · último usado");
            targets.add(0, WIFI);
        } else {
            labels.add(wifiLabel);
            targets.add(WIFI);
        }

        new AlertDialog.Builder(this)
                .setTitle("Escolha o adaptador OBD")
                .setItems(labels.toArray(new String[0]), (dlg, which) -> {
                    Object t = targets.get(which);
                    Links.Factory f;
                    if (t instanceof BluetoothDevice) {
                        BluetoothDevice d = (BluetoothDevice) t;
                        String name;
                        try {
                            name = d.getName() == null ? d.getAddress() : d.getName();
                        } catch (SecurityException e) {
                            name = d.getAddress();
                        }
                        f = Links.bluetooth(d, name);
                        prefs.edit().putString("last", d.getAddress()).apply();
                    } else {
                        f = Links.wifi(getApplicationContext(), "192.168.0.10", 35000);
                        prefs.edit().putString("last", WIFI).apply();
                    }
                    start(f);
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private void start(Links.Factory f) {
        Session.reset();
        shownPulls = -1;
        pullView.setText("Nenhuma puxada ainda.");
        Poller p = new Poller(f);
        Session.poller = p;
        Thread t = new Thread(p, "obd");
        t.setDaemon(true);
        t.start();
    }

    // ---------------------------------------------------------------- exportação

    private void copyReport() {
        ClipboardManager cm = getSystemService(ClipboardManager.class);
        if (cm == null) return;
        cm.setPrimaryClip(ClipData.newPlainText("HR-V Monitor", Session.report()));
        toast("Relatório copiado. Cole na conversa.");
    }

    private void exportFiles() {
        final List<float[]> rows = Session.store.all();
        if (rows.isEmpty()) {
            toast("Nada gravado ainda. Conecte primeiro.");
            return;
        }
        toast("Salvando…");
        new Thread(() -> {
            try {
                String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
                final Uri csv = save("hrv_" + stamp + ".csv", "text/csv", Session.csv(rows));
                final Uri txt = save("hrv_" + stamp + "_resumo.txt", "text/plain", Session.report());
                ui.post(() -> {
                    toast("Salvo em Downloads/HRV Monitor");
                    ArrayList<Uri> uris = new ArrayList<>();
                    uris.add(csv);
                    uris.add(txt);
                    Intent i = new Intent(Intent.ACTION_SEND_MULTIPLE);
                    i.setType("text/*");
                    i.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
                    i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    startActivity(Intent.createChooser(i, "Compartilhar log"));
                });
            } catch (IOException | RuntimeException e) {
                final String msg = e.getMessage();
                ui.post(() -> toast("Erro ao salvar: " + msg));
            }
        }, "export").start();
    }

    private Uri save(String name, String mime, String content) throws IOException {
        ContentValues cv = new ContentValues();
        cv.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        cv.put(MediaStore.MediaColumns.MIME_TYPE, mime);
        cv.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/HRV Monitor");
        ContentResolver cr = getContentResolver();
        Uri uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
        if (uri == null) throw new IOException("não foi possível criar " + name);
        try (OutputStream os = cr.openOutputStream(uri)) {
            if (os == null) throw new IOException("não foi possível abrir " + name);
            os.write(content.getBytes(StandardCharsets.UTF_8));
        }
        return uri;
    }

    // ---------------------------------------------------------------- componentes

    private final class Tile {
        final LinearLayout box = vbox();
        final TextView value;

        Tile(String label) {
            box.setPadding(px(10), px(8), px(10), px(8));
            box.setBackground(round(CARD));
            box.addView(text(label, 12, MUTED));
            value = text("—", 24, TEXT);
            value.setTypeface(Typeface.DEFAULT_BOLD);
            value.setMaxLines(1);
            value.setAutoSizeTextTypeUniformWithConfiguration(12, 24, 1, TypedValue.COMPLEX_UNIT_SP);
            box.addView(value, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(34)));
        }

        void set(float v, int decimals, int color, int... pids) {
            if (unsupported(pids)) {
                setText("n/d", MUTED);
            } else {
                setText(Fmt.f(v, decimals), color);
            }
        }

        void setText(String s, int color) {
            value.setText(s);
            value.setTextColor(color);
        }
    }

    private LinearLayout row(Tile... tiles) {
        LinearLayout r = hbox();
        for (Tile t : tiles) {
            LinearLayout.LayoutParams p = weight();
            p.setMargins(px(3), 0, px(3), 0);
            r.addView(t.box, p);
        }
        return r;
    }

    private View section(String s) {
        TextView t = text(s, 16, TEXT);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private View card(View content) {
        LinearLayout c = vbox();
        c.setPadding(px(12), px(10), px(12), px(10));
        c.setBackground(round(CARD));
        c.addView(content);
        return c;
    }

    private LinearLayout vbox() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    private LinearLayout hbox() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.HORIZONTAL);
        return l;
    }

    private TextView text(String s, float sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        return t;
    }

    private Button button(String s, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextSize(13);
        b.setOnClickListener(l);
        return b;
    }

    private GradientDrawable round(int color) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(10 * dp);
        return d;
    }

    private LinearLayout.LayoutParams lp(int topDp) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.topMargin = px(topDp);
        return p;
    }

    private LinearLayout.LayoutParams weight() {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
    }

    private int px(float dpValue) {
        return (int) (dpValue * dp + 0.5f);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }
}
