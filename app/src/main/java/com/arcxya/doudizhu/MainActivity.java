package com.arcxya.doudizhu;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.webkit.JsResult;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import java.io.ByteArrayInputStream;
import java.io.IOException;

/** Packaged local assets only. There is no native JavaScript bridge or network permission. */
public final class MainActivity extends Activity {
    private static final String ORIGIN = "https://offline.invalid/";
    private WebView web;
    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        web = new WebView(this);
        web.setBackgroundColor(0xff102c24);
        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setBlockNetworkLoads(true);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) { return true; }
            @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                if (url.startsWith(ORIGIN)) {
                    String path = url.substring(ORIGIN.length());
                    if (path.equals("index.html") || path.equals("style.css") || path.equals("engine.js") || path.equals("app.js") || path.equals("audio.js")) {
                        String mime = path.endsWith("html") ? "text/html" : path.endsWith("css") ? "text/css" : "application/javascript";
                        try { return new WebResourceResponse(mime, "UTF-8", getAssets().open(path)); }
                        catch (IOException ignored) { }
                    }
                }
                return new WebResourceResponse("text/plain", "UTF-8", 403, "Blocked", null, new ByteArrayInputStream(new byte[0]));
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onJsConfirm(WebView view, String url, String message, JsResult result) {
                new AlertDialog.Builder(MainActivity.this).setMessage(message)
                    .setPositiveButton("重新开局", (d,w) -> result.confirm())
                    .setNegativeButton("继续本局", (d,w) -> result.cancel())
                    .setOnCancelListener(d -> result.cancel()).show();
                return true;
            }
        });
        setContentView(web);
        web.loadUrl(ORIGIN + "index.html");
    }
    @Override protected void onPause() {
        if (web != null) { web.evaluateJavascript("window.ddzPause && window.ddzPause()", null); web.onPause(); }
        super.onPause();
    }
    @Override protected void onResume() {
        super.onResume();
        if (web != null) { web.onResume(); web.evaluateJavascript("window.ddzResume && window.ddzResume()", null); }
    }
    @Override public void onBackPressed() {
        new AlertDialog.Builder(this).setTitle("暂时离开牌桌？").setMessage("牌局会自动保存，下次打开继续。")
            .setPositiveButton("离开", (d,w) -> finish()).setNegativeButton("继续玩", null).show();
    }
    @Override protected void onDestroy() {
        if (web != null) { web.destroy(); web = null; }
        super.onDestroy();
    }
}
