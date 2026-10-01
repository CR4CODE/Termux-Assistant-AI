package com.termux.assistant;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
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
import android.widget.Toast;

import java.io.File;
import java.io.FileWriter;

public class DeepSeekWebActivity extends Activity {

    private static final String START_URL = "https://chat.deepseek.com/";
    private static final String INBOX = "/sdcard/ai-tasker/inbox";
    private static final String OUTBOX = "/sdcard/ai-tasker/outbox";
    private static final long PAUSE_BETWEEN_MS = 5000;

    private WebView webView;
    private ProgressBar progressBar;
    private TextView statusLabel;
    private long lastSendTime = 0;
    private long cycleStartTime = 0;
    private String cycleTaskId = null;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable cycleRunnable;

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
                @Override public void onClick(View v) { sendTest(); }
            });
        }

        Button readBtn = findViewById(R.id.btn_ds_read);
        if (readBtn != null) {
            readBtn.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { readReply(); }
            });
        }

        Button fromClipBtn = findViewById(R.id.btn_ds_from_clip);
        if (fromClipBtn != null) {
            fromClipBtn.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { insertFromClipboard(); }
            });
        }

        Button cycleBtn = findViewById(R.id.btn_ds_cycle);
        if (cycleBtn != null) {
            cycleBtn.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { startCycle(); }
            });
        }

        Button clearBtn = findViewById(R.id.btn_ds_clear);
        if (clearBtn != null) {
            clearBtn.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { clearInput(); }
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
        "window.TermuxInsertText = function(text){"
        + "try{"
        + "var ta=document.querySelector('textarea');"
        + "if(!ta)return 'no_ta';"
        + "ta.focus();"
        + "var setter=Object.getOwnPropertyDescriptor(window.HTMLTextAreaElement.prototype,'value').set;"
        + "setter.call(ta,text);"
        + "ta.dispatchEvent(new Event('input',{bubbles:true}));"
        + "ta.dispatchEvent(new Event('change',{bubbles:true}));"
        + "return 'ok';"
        + "}catch(e){return 'err:'+e;}"
        + "};"
        + "window.TermuxFindAndClick=function(){"
        + "var ta=document.querySelector('textarea');"
        + "if(!ta)return 'no_ta';"
        + "var taR=ta.getBoundingClientRect();"
        + "var all=document.querySelectorAll('[role=button],button');"
        + "var best=null,bestRight=-1;"
        + "for(var i=0;i<all.length;i++){"
        + "var b=all[i];var r=b.getBoundingClientRect();"
        + "if(r.width<20||r.width>80)continue;"
        + "if(r.height<20||r.height>80)continue;"
        + "if(r.left<taR.left-30)continue;"
        + "if(r.top<taR.top-30)continue;"
        + "if(r.top>taR.bottom+150)continue;"
        + "if(!b.querySelector('svg'))continue;"
        + "if(r.right>bestRight){bestRight=r.right;best=b;}"
        + "}"
        + "if(!best)return 'no_btn';"
        + "best.click();"
        + "return 'clicked';"
        + "};"
        + "window.TermuxSend = function(text){"
        + "try{"
        + "var ins=window.TermuxInsertText(text);"
        + "if(ins!=='ok')return ins;"
        + "setTimeout(function(){"
        + "var r=window.TermuxFindAndClick();"
        + "TermuxBridge.log('send: '+r);"
        + "},500);"
        + "return 'ok';"
        + "}catch(e){return 'err:'+e;}"
        + "};"
        + "window.TermuxGetInput = function(){"
        + "var ta=document.querySelector('textarea');"
        + "return ta?ta.value:'';"
        + "};"
        + "window.TermuxReadLast = function(){"
        + "var b=document.querySelectorAll('[class*=markdown]');"
        + "if(b.length===0)return '';"
        + "for(var i=b.length-1;i>=0;i--){"
        + "var r=b[i].getBoundingClientRect();"
        + "if(r.width<50)continue;"
        + "var t=(b[i].innerText||'').trim();"
        + "if(t.length<1)continue;"
        + "return t;"
        + "}"
        + "return '';"
        + "};"
        + "window.TermuxClearInput = function(){"
        + "try{"
        + "var ta=document.querySelector('textarea');"
        + "if(!ta)return 'no_ta';"
        + "var setter=Object.getOwnPropertyDescriptor(window.HTMLTextAreaElement.prototype,'value').set;"
        + "setter.call(ta,'');"
        + "ta.dispatchEvent(new Event('input',{bubbles:true}));"
        + "return 'ok';"
        + "}catch(e){return 'err:'+e;}"
        + "};";

    private void insertFromClipboard() {
        try {
            String text = readClipboard();
            if (text == null || text.isEmpty()) {
                toast("Буфер пуст");
                return;
            }
            String escaped = text.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n");
            webView.evaluateJavascript("window.TermuxInsertText('" + escaped + "');", null);
            if (statusLabel != null) statusLabel.setText("Вставлено из буфера (" + text.length() + " симв.)");
        } catch (Exception e) {
            toast("Ошибка: " + e.getMessage());
        }
    }

    private void clearInput() {
        webView.evaluateJavascript("window.TermuxClearInput();", null);
        if (statusLabel != null) statusLabel.setText("Поле очищено");
    }

    private void sendTest() {
        long now = System.currentTimeMillis();
        if (now - lastSendTime < PAUSE_BETWEEN_MS) {
            long wait = (PAUSE_BETWEEN_MS - (now - lastSendTime)) / 1000;
            toast("Подожди ещё " + wait + " сек");
            return;
        }
        lastSendTime = now;
        String text = "Привет! Ответь одним словом ОК";
        String escaped = text.replace("\\", "\\\\").replace("'", "\\'");
        webView.evaluateJavascript("window.TermuxSend('" + escaped + "');", null);
    }

    private void readReply() {
        webView.evaluateJavascript("window.TermuxReadLast();",
            new android.webkit.ValueCallback<String>() {
            @Override public void onReceiveValue(String value) {
                String cleaned = unescapeJs(value);
                new AlertDialog.Builder(DeepSeekWebActivity.this)
                    .setTitle("Ответ DeepSeek")
                    .setMessage(cleaned != null && !cleaned.isEmpty() ? cleaned : "(пусто)")
                    .setPositiveButton("OK", null)
                    .show();
            }
        });
    }

    private void startCycle() {
        long now = System.currentTimeMillis();
        if (now - lastSendTime < PAUSE_BETWEEN_MS) {
            long wait = (PAUSE_BETWEEN_MS - (now - lastSendTime)) / 1000;
            toast("Подожди ещё " + wait + " сек");
            return;
        }

        webView.evaluateJavascript("window.TermuxGetInput();",
            new android.webkit.ValueCallback<String>() {
            @Override public void onReceiveValue(String value) {
                String text = unescapeJs(value);
                if (text != null && !text.trim().isEmpty()) {
                    runFullCycle(text.trim());
                    return;
                }
                String fromClip = readClipboard();
                if (fromClip == null || fromClip.trim().isEmpty()) {
                    toast("Поле пусто и буфер пуст — скопируй код из DeepSeek");
                    return;
                }
                // НЕ вставляем в поле — сразу выполняем
                runFullCycle(fromClip.trim());
            }
        });
    }

    private void runFullCycle(final String taskText) {
        lastSendTime = System.currentTimeMillis();

        try {
            File dir = new File(INBOX);
            dir.mkdirs();
            cycleTaskId = "buf" + System.currentTimeMillis();
            File f = new File(dir, "task-" + cycleTaskId + ".txt");
            FileWriter w = new FileWriter(f);
            w.write("auto:" + taskText);
            w.close();
        } catch (Exception e) {
            toast("Ошибка записи: " + e.getMessage());
            return;
        }

        if (statusLabel != null) statusLabel.setText("⏳ Выполняется в Termux…");
        cycleStartTime = System.currentTimeMillis();

        cycleRunnable = new Runnable() {
            @Override public void run() {
                long elapsed = System.currentTimeMillis() - cycleStartTime;
                if (elapsed > 120000) {
                    if (statusLabel != null) statusLabel.setText("✗ Таймаут 2 мин");
                    return;
                }
                File out = new File(OUTBOX, "task-" + cycleTaskId + ".json");
                if (out.exists()) {
                    String result = readResultFromJson(out);
                    if (result != null) {
                        webView.evaluateJavascript("window.TermuxClearInput();", null);
                        String escaped = result.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n");
                        webView.evaluateJavascript("window.TermuxInsertText('" + escaped + "');", null);
                        if (statusLabel != null) statusLabel.setText("✓ Готово (" + result.length() + " симв.) — жми отправить");
                        toast("Готово. Проверь поле");
                        return;
                    }
                }
                handler.postDelayed(this, 1500);
            }
        };
        handler.postDelayed(cycleRunnable, 1500);
    }

    private String readResultFromJson(File f) {
        try {
            java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(f));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
            r.close();
            org.json.JSONObject o = new org.json.JSONObject(sb.toString());
            String output = o.optString("output", "");
            String status = o.optString("status", "");
            if (output != null && !output.isEmpty()) return output;
            return "(пусто, статус: " + status + ")";
        } catch (Exception e) {
            return null;
        }
    }

    private String readClipboard() {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm == null || !cm.hasPrimaryClip()) return null;
            ClipData clip = cm.getPrimaryClip();
            if (clip == null || clip.getItemCount() == 0) return null;
            CharSequence seq = clip.getItemAt(0).coerceToText(this);
            return seq != null ? seq.toString() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private String unescapeJs(String value) {
        if (value == null) return null;
        String s = value;
        if (s.startsWith("\"") && s.endsWith("\"")) {
            s = s.substring(1, s.length() - 1);
        }
        s = s.replace("\\n", "\n").replace("\\\"", "\"").replace("\\\\", "\\");
        return s;
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (cycleRunnable != null) handler.removeCallbacks(cycleRunnable);
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
