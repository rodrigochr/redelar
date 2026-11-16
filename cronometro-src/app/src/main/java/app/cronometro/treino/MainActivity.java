package app.cronometro.treino;

import android.app.Activity;
import android.content.res.Configuration;
import android.os.Bundle;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

/** Casca nativa mínima: abre a página offline (assets/index.html) em tela cheia, com a tela sempre ligada. */
public class MainActivity extends Activity {
    private WebView web;

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
        if (web != null) {
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }
}
