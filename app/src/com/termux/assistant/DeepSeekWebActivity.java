package com.termux.assistant;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;

public class DeepSeekWebActivity extends Activity {

    private static final String START_URL = "https://chat.deepseek.com/";
    private static final long PAUSE_BETWEEN_MS = 5000;

    private WebView webView;
    private ProgressBar progressBar;
    private TextView statusLabel;
    private long lastSendTime = 0;

    public class JsBridge {
        @JavascriptInterface
        public void log(String msg) {
            android.util.Log.i("DeepSeekWeb", msg);
        }

        @JavascriptInterface
        public void onPageInfo(final String info) {
            runOnUiThread(new Runnable() {
                @Override public void run() {
                    if (statusLabel != null) statusLabel.setText(info);
                }
            });
        }

        @JavascriptInterface
        public void showDialog(final String title, final String msg) {
            runOnUiThread(new Runnable() {
                @Override public void run() {
                    new AlertDialog.Builder(DeepSeekWebActivity.this)
                        .setTitle(title)
                        .setMessage(msg)
                        .setPositiveButton("OK", null)
                        .show();
                }
            });
        }

        @JavascriptInterface
        public void onSendSuccess() {
            runOnUiThread(new Runnable() {
                @Override public void run() {
                    if (statusLabel != null) statusLabel.setText("Кнопка нажата — проверь поле ввода");
                }
            });
        }

        @JavascriptInterface
        public void onSendFail(final int delta) {
            runOnUiThread(new Runnable() {
                @Override public void run() {
                    if (statusLabel != null) statusLabel.setText("✗ Не ушло (delta=" + delta + ")");
                }
            });
        }

        @JavascriptInterface
        public void onTextInserted() {
            runOnUiThread(new Runnable() {
                @Override public void run() {
                    pressEnterFallback();
                }
            });
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        SharedPreferences _t = getSharedPreferences("app_prefs", MODE_PRIVATE);
        String _th = _t.getString("theme", "system");
        if ("light".equals(_th)) setTheme(R.style.AppTheme_Light);
        else setTheme(R.style.AppTheme_Dark);

        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_deepseek_web);

        webView = findViewById(R.id.ds_webview);
        progressBar = findViewById(R.id.ds_progress);
        statusLabel = findViewById(R.id.ds_status);

        Button close = findViewById(R.id.btn_ds_close);
        if (close != null) {
            close.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { finish(); }
            });
        }

        Button debugBtn = findViewById(R.id.btn_ds_debug);
        if (debugBtn != null) {
            debugBtn.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { testSend(); }
            });
        }

        Button readBtn = findViewById(R.id.btn_ds_read);
        if (readBtn != null) {
            readBtn.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { readReply(); }
            });
        }

        WebSettings ws = webView.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setDatabaseEnabled(true);
        ws.setLoadWithOverviewMode(true);
        ws.setUseWideViewPort(true);
        ws.setBuiltInZoomControls(false);
        ws.setDisplayZoomControls(false);
        ws.setMediaPlaybackRequiresUserGesture(false);
        if (Build.VERSION.SDK_INT >= 21) {
            ws.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        }

        webView.addJavascriptInterface(new JsBridge(), "TermuxBridge");

        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        if (Build.VERSION.SDK_INT >= 21) {
            cm.setAcceptThirdPartyCookies(webView, true);
        }

        webView.setWebChromeClient(new WebChromeClient() {
            @Override public void onProgressChanged(WebView v, int newProgress) {
                if (progressBar != null) {
                    if (newProgress < 100) {
                        progressBar.setVisibility(View.VISIBLE);
                        progressBar.setProgress(newProgress);
                    } else {
                        progressBar.setVisibility(View.GONE);
                    }
                }
            }
        });

        webView.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView v, String url) {
                return false;
            }

            @Override public void onPageFinished(WebView v, String url) {
                if (statusLabel != null) {
                    statusLabel.setText("Загружено: " + url);
                }
                v.evaluateJavascript(JS_INIT, null);
            }
        });

        if (savedInstanceState == null) {
            webView.loadUrl(START_URL);
        } else {
            webView.restoreState(savedInstanceState);
        }
    }

    private static final String JS_INIT =
        "window.TermuxSend = function(text){"
        + "try{"
        + "var ta=document.querySelector('textarea');"
        + "if(!ta){TermuxBridge.showDialog('Debug','no textarea');return 'no_ta';}"
        + "ta.focus();"
        + "var setter=Object.getOwnPropertyDescriptor(window.HTMLTextAreaElement.prototype,'value').set;"
        + "setter.call(ta,text);"
        + "ta.dispatchEvent(new Event('input',{bubbles:true}));"
        + "ta.dispatchEvent(new Event('change',{bubbles:true}));"
        + "setTimeout(function(){"
        + "var r=window.TermuxFindAndClick();"
        + "TermuxBridge.log('click: '+r);"
        + "},400);"
        + "return 'ok';"
        + "}catch(e){TermuxBridge.log('err '+e);return 'err:'+e;}"
        + "};"
        + "window.TermuxFindAndClick=function(){"
        + "var ta=document.querySelector('textarea');"
        + "if(!ta)return 'no_ta';"
        + "var taR=ta.getBoundingClientRect();"
        + "var all=document.querySelectorAll('[role=button],button');"
        + "var best=null,bestRight=-1;"
        + "var cands=[];"
        + "for(var i=0;i<all.length;i++){"
        + "var b=all[i];var r=b.getBoundingClientRect();"
        + "if(r.width<20||r.width>80)continue;"
        + "if(r.height<20||r.height>80)continue;"
        + "if(r.left<taR.left-30)continue;"
        + "if(r.top<taR.top-30)continue;"
        + "if(r.top>taR.bottom+150)continue;"
        + "if(!b.querySelector('svg'))continue;"
        + "cands.push({x:Math.round(r.left),y:Math.round(r.top),right:r.right,w:r.width});"
        + "if(r.right>bestRight){bestRight=r.right;best=b;}"
        + "}"
        + "if(!best){TermuxBridge.showDialog('Debug','no button. zone: ta.top='+Math.round(taR.top)+' ta.bottom='+Math.round(taR.bottom)+' total='+all.length);return 'no_btn';}"
        + "var info='найдено: '+cands.length+'\\n';"
        + "for(var j=0;j<cands.length&&j<5;j++){info=info+cands[j].x+','+cands[j].y+' w='+cands[j].w+'\\n';}"
        + "TermuxBridge.log(info);"
        + "best.click();"
        + "TermuxBridge.showDialog('Debug', info+'клик по x='+Math.round(bestRight-17));"
        + "return 'clicked';"
        + "};";

    private void pressEnterFallback() {
        try {
            webView.requestFocus();
            webView.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER));
            webView.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER));
        } catch (Exception e) {
            android.util.Log.i("DeepSeekWeb", "enter err: " + e);
        }
    }

    private void testSend() {
        long now = System.currentTimeMillis();
        if (now - lastSendTime < PAUSE_BETWEEN_MS) {
            long wait = (PAUSE_BETWEEN_MS - (now - lastSendTime)) / 1000;
            new AlertDialog.Builder(this)
                .setTitle("Слишком часто")
                .setMessage("Подожди ещё " + wait + " сек.")
                .setPositiveButton("OK", null)
                .show();
            return;
        }
        lastSendTime = now;
        String text = "Привет! Ответь одним словом ОК";
        String escaped = text.replace("\\", "\\\\").replace("'", "\\'");
        webView.evaluateJavascript("window.TermuxSend('" + escaped + "');", null);
    }

    private void readReply() {
        String js = "(function(){"
            + "var b=document.querySelectorAll('[class*=markdown]');"
            + "if(b.length===0)return '';"
            + "for(var i=b.length-1;i>=0;i--){"
            + "var r=b[i].getBoundingClientRect();"
            + "if(r.width<50)continue;"
            + "var t=(b[i].innerText||'').trim();"
            + "if(t.length<1)continue;"
            + "if(t.indexOf('Привет! Ответь одним словом ОК')===0)continue;"
            + "if(t==='OK')continue;"
            + "return t;"
            + "}"
            + "for(var i=b.length-1;i>=0;i--){"
            + "var t=(b[i].innerText||'').trim();"
            + "if(t.length>0)return t;"
            + "}"
            + "return '';"
            + "})();";

        webView.evaluateJavascript(js, new android.webkit.ValueCallback<String>() {
            @Override public void onReceiveValue(String value) {
                String cleaned = value;
                if (cleaned != null && cleaned.startsWith("\"") && cleaned.endsWith("\"")) {
                    cleaned = cleaned.substring(1, cleaned.length() - 1);
                }
                if (cleaned != null) {
                    cleaned = cleaned.replace("\\n", "\n").replace("\\\"", "\"");
                }
                new AlertDialog.Builder(DeepSeekWebActivity.this)
                    .setTitle("Ответ DeepSeek")
                    .setMessage(cleaned != null && !cleaned.isEmpty() ? cleaned : "(пусто)")
                    .setPositiveButton("OK", null)
                    .show();
            }
        });
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (webView != null) webView.saveState(outState);
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }
}
