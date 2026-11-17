package app.cronometro.treino;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.os.Build;
import android.content.res.Configuration;
import android.os.Bundle;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

/** Casca nativa mínima: abre a página offline (assets/index.html) em tela cheia, com a tela sempre ligada. */
public class MainActivity extends Activity {
    private WebView web;

    /** Ponte para a página: som e vibração nativos (WebView é pausado com a tela desligada). */
    private class VibeBridge {
        @JavascriptInterface
        public void vibrate(String csv) { AlertEngine.vibrateCsv(csv); }

        /** JSON {start,end,done: wav em base64, vibrate: bool}. */
        @JavascriptInterface
        public void config(String json) { AlertEngine.config(json); }

        /** JSON [{t: epochMs, k: "start"|"end"|"done"}]: substitui o cronograma de alertas. */
        @JavascriptInterface
        public void schedule(String json) { AlertEngine.schedule(json); }

        @JavascriptInterface
        public void alertNow(String kind) { AlertEngine.alertNow(kind); }
    }

    private boolean isNight() {
        int mode = getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return mode == Configuration.UI_MODE_NIGHT_YES;
    }

    /** Cores das barras do sistema iguais ao fundo da página (claro ou escuro). */
    private void applyChrome() {
        boolean night = isNight();
        int bg = night ? 0xFF161617 : 0xFFEFEDE8;
        Window w = getWindow();
        w.setStatusBarColor(bg);
        w.setNavigationBarColor(bg);
        View decor = w.getDecorView();
        int flags = decor.getSystemUiVisibility();
        int light = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        decor.setSystemUiVisibility(night ? (flags & ~light) : (flags | light));
        if (web != null) {
            web.setBackgroundColor(bg);
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        AlertEngine.init(this);
        // Android 13+: sem esta permissão o aviso "treino em andamento" fica oculto (o serviço funciona do mesmo jeito).
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }

        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);                    // localStorage: guarda configurações e treinos
        s.setMediaPlaybackRequiresUserGesture(false);    // permite os sons do cronômetro
        s.setAllowContentAccess(false);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setTextZoom(100);
        web.setWebViewClient(new WebViewClient());
        web.setWebChromeClient(new WebChromeClient());
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        web.addJavascriptInterface(new VibeBridge(), "AndroidVibe");

        setContentView(web);
        applyChrome();
        web.loadUrl("file:///android_asset/index.html?t=" + (isNight() ? "dark" : "light"));
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        applyChrome();
        web.evaluateJavascript(
                "document.documentElement.setAttribute('data-theme','" + (isNight() ? "dark" : "light") + "')", null);
    }

    /** Voltar: a página decide (fecha diálogo, pergunta se quer encerrar); se não tratar, o app vai para segundo plano sem encerrar o treino. */
    @Override
    public void onBackPressed() {
        web.evaluateJavascript("(window.__onBack&&window.__onBack())?'1':'0'", new ValueCallback<String>() {
            @Override
            public void onReceiveValue(String value) {
                if (!"\"1\"".equals(value)) {
                    moveTaskToBack(true);
                }
            }
        });
    }

    @Override
    protected void onDestroy() {
        AlertEngine.cancelAll();
        if (web != null) {
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }
}
