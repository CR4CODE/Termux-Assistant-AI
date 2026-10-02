package com.termux.assistant;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
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
import java.io.FileReader;
import org.json.JSONObject;

public class MainActivity extends Activity {

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
        public void showDialog(final String title, final String msg) {
            runOnUiThread(new Runnable() {
                @Override public void run() {
                    new AlertDialog.Builder(MainActivity.this)
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
        setContentView(R.layout.activity_main);

        webView = findViewById(R.id.ds_webview);
        progressBar = findViewById(R.id.ds_progress);
        statusLabel = findViewById(R.id.ds_status);

        Button menuBtn = findViewById(R.id.btn_menu);
        if (menuBtn != null) {
            menuBtn.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { openMenu(); }
            });
        }

        Button fromClipBtn = findViewById(R.id.btn_ds_from_clip);
        if (fromClipBtn != null) {
            fromClipBtn.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { insertFromClipboard(); }
            });
        }

        Button freeBtn = findViewById(R.id.btn_ds_free);
        if (freeBtn != null) {
            freeBtn.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { startCycle(true, "auto:"); }
            });
        }

        Button devBtn = findViewById(R.id.btn_ds_dev);
        if (devBtn != null) {
            devBtn.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { startDevDialog(); }
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
                    statusLabel.setText("DeepSeek · готов");
                }
                v.evaluateJavascript(JS_INIT, null);
                v.evaluateJavascript(JS_THEME, null);
                v.postDelayed(new Runnable() { @Override public void run() {
                    v.evaluateJavascript("window.__termuxApplyTheme&&window.__termuxApplyTheme()", null);
                }}, 2000);
            }
        });

        if (savedInstanceState == null) {
            webView.loadUrl(START_URL);
        } else {
            webView.restoreState(savedInstanceState);
        }
    }

    private void debugBlue() {
        String js = "(function(){"
            + "var root=getComputedStyle(document.documentElement);"
            + "var vars=[];"
            + "for(var i=0;i<root.length;i++){"
            + "var n=root[i];"
            + "var v=root.getPropertyValue(n);"
            + "if(v.indexOf('77')>=0||v.indexOf('107')>=0||v.indexOf('109')>=0||v.indexOf('4D6BFE')>=0||v.indexOf('rgb(77, 107, 254)')>=0){"
            + "vars.push(n+': '+v);"
            + "}"
            + "}"
            + "var all=document.querySelectorAll('*');"
            + "var found=[];"
            + "for(var i=0;i<all.length;i++){"
            + "var e=all[i];"
            + "var cs=getComputedStyle(e);"
            + "var bg=cs.backgroundColor;"
            + "var col=cs.color;"
            + "var fill=cs.fill;"
            + "if(bg&&(bg.indexOf('77, 107')>=0||bg.indexOf('77,107')>=0)){found.push('bg '+bg+' cls='+String(e.className||'-').substring(0,40));}"
            + "if(fill&&(fill.indexOf('77, 107')>=0||fill.indexOf('77,107')>=0)){found.push('fill '+fill+' cls='+String(e.className||'-').substring(0,40));}"
            + "}"
            + "var out='--- CSS vars ---\\n'+vars.join('\\n')+'\\n\\n--- Elements (max 20) ---\\n'+found.slice(0,20).join('\\n');"
            + "TermuxBridge.showDialog('Диагностика синего', out);"
            + "return out;"
            + "})();";
        webView.evaluateJavascript(js, null);
    }

    private void startDevDialog() {
        setActiveMode("dev");
        webView.evaluateJavascript("window.TermuxGetInput();",
            new android.webkit.ValueCallback<String>() {
            @Override public void onReceiveValue(String value) {
                String text = unescapeJs(value);
                if (text == null || text.trim().isEmpty()) {
                    text = readClipboard();
                }
                if (text == null || text.trim().isEmpty()) {
                    toast("Введи задачу в поле DeepSeek или скопируй в буфер");
                    return;
                }
                final String task = text.trim();
                new AlertDialog.Builder(MainActivity.this)
                    .setTitle("Режим разработки")
                    .setMessage("Задача:\n\n" + task + "\n\nDeepSeek ответит файлами, мы применим и соберём APK.")
                    .setPositiveButton("Запустить", new DialogInterface.OnClickListener() {
                        @Override public void onClick(DialogInterface d, int w) { runDevTask(task); }
                    })
                    .setNegativeButton("Отмена", null)
                    .show();
            }
        });
    }

    private void runDevTask(String task) {
        if (statusLabel != null) statusLabel.setText("Отправлено в DeepSeek...");

        String prompt =
            "Ты разработчик Android-приложения на Java (не Kotlin).\n"
            + "Проект: Termux Assistant AI.\n"
            + "Структура: src/com/termux/assistant/*.java, res/layout/*.xml, res/values/*.xml, AndroidManifest.xml\n\n"
            + "Задача: " + task + "\n\n"
            + "Отвечай ТОЛЬКО файлами в таком формате (без объяснений, без патчей):\n\n"
            + "```java src/com/termux/assistant/File.java\n<полный код файла>\n```\n"
            + "```xml res/layout/file.xml\n<полный xml>\n```";

        String escaped = prompt.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n");
        webView.evaluateJavascript("window.TermuxSend('" + escaped + "');", null);

        waitForDevReply(0);
    }

    private void waitForDevReply(final int attempt) {
        if (attempt > 40) {
            if (statusLabel != null) statusLabel.setText("Таймаут - DeepSeek не ответил");
            return;
        }
        webView.postDelayed(new Runnable() {
            @Override public void run() {
                webView.evaluateJavascript("window.TermuxReadLast();",
                    new android.webkit.ValueCallback<String>() {
                    @Override public void onReceiveValue(String value) {
                        String reply = unescapeJs(value);
                        if (reply == null || reply.trim().isEmpty()) {
                            if (statusLabel != null) statusLabel.setText("Ждём ответа... (" + (attempt * 3) + " сек)");
                            waitForDevReply(attempt + 1);
                            return;
                        }
                        parseAndSaveFiles(reply);
                    }
                });
            }
        }, 3000);
    }

    private void parseAndSaveFiles(String reply) {
        try {
            java.util.regex.Pattern p = java.util.regex.Pattern.compile(
                "```(\\w+)\\s+([^\\s`\\n]+)\\s*\\n([\\s\\S]*?)```");
            java.util.regex.Matcher m = p.matcher(reply);
            java.util.List<String> paths = new java.util.ArrayList<>();

            java.io.File pending = new java.io.File("/sdcard/ai-tasker/pending");
            pending.mkdirs();
            java.io.File[] oldFiles = pending.listFiles();
            if (oldFiles != null) for (java.io.File f : oldFiles) f.delete();

            while (m.find()) {
                String path = m.group(2).trim();
                String content = m.group(3);
                if (!path.endsWith(".java") && !path.endsWith(".xml")) continue;
                java.io.File dest = new java.io.File(pending, path);
                dest.getParentFile().mkdirs();
                java.io.FileWriter w = new java.io.FileWriter(dest);
                w.write(content);
                w.close();
                paths.add(path);
            }

            if (paths.isEmpty()) {
                if (statusLabel != null) statusLabel.setText("Не нашёл файлов в ответе");
                new AlertDialog.Builder(MainActivity.this)
                    .setTitle("Пусто")
                    .setMessage("DeepSeek не вернул файлов в правильном формате.")
                    .setPositiveButton("OK", null)
                    .show();
                return;
            }

            StringBuilder list = new StringBuilder();
            for (String pth : paths) list.append("  ").append(pth).append("\n");

            new AlertDialog.Builder(MainActivity.this)
                .setTitle("Найдено " + paths.size() + " файлов")
                .setMessage("DeepSeek предлагает применить:\n\n" + list.toString())
                .setPositiveButton("Применить и собрать", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) { applyAndBuild(); }
                })
                .setNegativeButton("Отмена", null)
                .show();

            if (statusLabel != null) statusLabel.setText("Найдено " + paths.size() + " файлов");

        } catch (Exception e) {
            toast("Ошибка парсинга: " + e.getMessage());
        }
    }

    private void applyAndBuild() {
        try {
            java.io.File dir = new java.io.File(INBOX);
            dir.mkdirs();
            String id = "dev" + System.currentTimeMillis();
            java.io.File f = new java.io.File(dir, "task-" + id + ".txt");
            java.io.FileWriter w = new java.io.FileWriter(f);
            w.write("apply_patches:");
            w.close();
            if (statusLabel != null) statusLabel.setText("Применяю и собираю APK...");
            toast("Применяю файлы и собираю APK");
        } catch (Exception e) {
            toast("Ошибка: " + e.getMessage());
        }
    }

    private void setActiveMode(String mode) {
        Button freeBtn = findViewById(R.id.btn_ds_free);
        Button devBtn = findViewById(R.id.btn_ds_dev);
        if (freeBtn == null || devBtn == null) return;

        float freeTarget, devTarget;
        if ("free".equals(mode)) {
            freeTarget = 1.15f; devTarget = 0.85f;
        } else if ("dev".equals(mode)) {
            freeTarget = 0.85f; devTarget = 1.15f;
        } else {
            freeTarget = 1f; devTarget = 1f;
        }
        animateWeights(freeBtn, devBtn, freeTarget, devTarget);
    }

    private void animateWeights(final Button free, final Button dev, final float freeTarget, final float devTarget) {
        final android.widget.LinearLayout.LayoutParams pFree = (android.widget.LinearLayout.LayoutParams) free.getLayoutParams();
        final android.widget.LinearLayout.LayoutParams pDev = (android.widget.LinearLayout.LayoutParams) dev.getLayoutParams();
        final float fStart = pFree.weight;
        final float dStart = pDev.weight;

        android.animation.ValueAnimator anim = android.animation.ValueAnimator.ofFloat(0f, 1f);
        anim.setDuration(400);
        anim.addUpdateListener(new android.animation.ValueAnimator.AnimatorUpdateListener() {
            @Override public void onAnimationUpdate(android.animation.ValueAnimator a) {
                float t = a.getAnimatedFraction();
                pFree.weight = fStart + (freeTarget - fStart) * t;
                pDev.weight = dStart + (devTarget - dStart) * t;
                free.setLayoutParams(pFree);
                dev.setLayoutParams(pDev);
            }
        });
        anim.start();
    }

    private void openMenu() {
        final String[] items = new String[]{
            "📋  История задач",
            "📖  Инструкция",
            "⚙️  Настройки"
        };
        new AlertDialog.Builder(this)
            .setTitle("Меню")
            .setItems(items, new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface d, int which) {
                    Intent i = null;
                    if (which == 0) i = new Intent(MainActivity.this, HistoryActivity.class);
                    else if (which == 1) i = new Intent(MainActivity.this, DocsActivity.class);
                    else if (which == 2) i = new Intent(MainActivity.this, SettingsActivity.class);
                    if (i != null) startActivity(i);
                }
            })
            .show();
    }

    private void insertFromClipboard() {
        try {
            String text = readClipboard();
            if (text == null || text.isEmpty()) {
                toast("Буфер пуст");
                return;
            }
            String escaped = text.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n");
            webView.evaluateJavascript("window.TermuxInsertText('" + escaped + "');", null);
            if (statusLabel != null) statusLabel.setText("Вставлено (" + text.length() + " симв.)");
        } catch (Exception e) {
            toast("Ошибка: " + e.getMessage());
        }
    }

    private void clearInput() {
        webView.evaluateJavascript("window.TermuxClearInput();", null);
        if (statusLabel != null) statusLabel.setText("Поле очищено");
    }

    private void startCycle(final boolean autoSend, final String prefix) {
        setActiveMode("free");
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
                    runFullCycle(text.trim(), autoSend, prefix);
                    return;
                }
                String fromClip = readClipboard();
                if (fromClip == null || fromClip.trim().isEmpty()) {
                    toast("Поле и буфер пусты — скопируй код из DeepSeek");
                    return;
                }
                runFullCycle(fromClip.trim(), autoSend, prefix);
            }
        });
    }

    private void runFullCycle(final String taskText, final boolean autoSend, final String prefix) {
        lastSendTime = System.currentTimeMillis();

        try {
            File dir = new File(INBOX);
            dir.mkdirs();
            cycleTaskId = (prefix.equals("dev:") ? "dev" : "buf") + System.currentTimeMillis();
            File f = new File(dir, "task-" + cycleTaskId + ".txt");
            FileWriter w = new FileWriter(f);
            w.write(prefix + taskText);
            w.close();
        } catch (Exception e) {
            toast("Ошибка записи: " + e.getMessage());
            return;
        }

        if (statusLabel != null) {
            statusLabel.setText(prefix.equals("dev:") ? "🛠 Отправлено в ai-dev…" : "⏳ Выполняется в Termux…");
        }
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
                        if (statusLabel != null) {
                            String prefixName = prefix.equals("dev:") ? "🛠" : "✓";
                            statusLabel.setText(autoSend
                                ? prefixName + " Готово — отправляю"
                                : prefixName + " Готово — жми отправить");
                        }
                        if (autoSend) {
                            webView.postDelayed(new Runnable() {
                                @Override public void run() {
                                    webView.evaluateJavascript("window.TermuxFindAndClick();", null);
                                }
                            }, 600);
                        } else {
                            toast("Готово. Проверь поле");
                        }
                        webView.postDelayed(new Runnable() {
                            @Override public void run() { setActiveMode("none"); }
                        }, 2000);
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

    private static final String JS_THEME =
        "(function(){"
        + "if(window.__termuxThemeInit) return 'already';"
        + "window.__termuxThemeInit = true;"
        + "window.__termuxRepaint = function(){"
        + "  var blueParts=['77, 107','77,107','76, 108','76,108','46, 107'];"
        + "  function isBlue(v){if(!v)return false;for(var k=0;k<blueParts.length;k++){if(v.indexOf(blueParts[k])>=0)return true;}return false;}"
        + "  var all=document.querySelectorAll('*');"
        + "  for(var i=0;i<all.length;i++){"
        + "    var e=all[i];"
        + "    var txt=(e.childElementCount===0?(e.textContent||''):'').trim();"
        + "    if(txt==='Get App'||txt==='Получить приложение'||txt==='Get the App'){"
        + "      var target=null;var p=e;"
        + "      var maxW=window.innerWidth*0.5;"
        + "      for(var lvl=0;lvl<8&&p;lvl++){"
        + "        var r=p.getBoundingClientRect();"
        + "        if(r.width>maxW)break;"
        + "        target=p;"
        + "        p=p.parentElement;"
        + "      }"
        + "      if(target)target.style.setProperty('display','none','important');"
        + "    }"

        + "    var cs=getComputedStyle(e);"
        + "    if(isBlue(cs.backgroundColor)) e.style.setProperty('background-color','#22C55E','important');"
        + "    if(isBlue(cs.color)) e.style.setProperty('color','#22C55E','important');"
        + "    if(isBlue(cs.borderTopColor)) e.style.setProperty('border-color','#22C55E','important');"
        + "    if(e.tagName.toLowerCase()==='svg'||e.tagName.toLowerCase()==='path'){"
        + "      if(isBlue(e.getAttribute('fill'))) e.setAttribute('fill','#22C55E');"
        + "      if(isBlue(e.getAttribute('stroke'))) e.setAttribute('stroke','#22C55E');"
        + "    }"
        + "  }"
        + "};"
        + "window.__termuxApplyTheme = function(){"
        + "  if(!document.getElementById('termux-theme')){"
        + "    var s=document.createElement('style');"
        + "    s.id='termux-theme';"
        + "    s.textContent='html,body{background:#121214 !important;color:#E8E5DF !important;}';"
        + "    document.head.appendChild(s);"
        + "  }"
        + "  window.__termuxRepaint();"
        + "};"
        + "window.__termuxApplyTheme();"
        + "if(!window.__termuxObs){"
        + "  window.__termuxObs=new MutationObserver(function(){"
        + "    clearTimeout(window.__termuxT);"
        + "    window.__termuxT=setTimeout(window.__termuxApplyTheme,300);"
        + "  });"
        + "  window.__termuxObs.observe(document.documentElement,{childList:true,subtree:true,attributes:true,attributeFilter:['class','style','fill','stroke']});"
        + "}"
        + "return 'theme_ready';"
        + "})();";

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
        + "window.TermuxGetInput = function(){"
        + "var ta=document.querySelector('textarea');"
        + "return ta?ta.value:'';"
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


    static TaskItem parseTaskFile(File f) {
        try {
            FileReader r = new FileReader(f);
            StringBuilder sb = new StringBuilder();
            char[] buf = new char[4096];
            int n;
            while ((n = r.read(buf)) > 0) sb.append(buf, 0, n);
            r.close();

            JSONObject o = new JSONObject(sb.toString());
            TaskItem t = new TaskItem();
            t.id = o.optString("id", "");
            t.task = o.optString("task", "");
            t.status = o.optString("status", "pending");
            t.output = o.optString("output", "");
            t.started = System.currentTimeMillis();
            long startedMs = 0;
            long finishedMs = 0;
            String startedStr = o.optString("started", "");
            String finishedStr = o.optString("finished", "");
            java.text.SimpleDateFormat fmt = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss");
            try { if (startedStr.length() >= 19) startedMs = fmt.parse(startedStr).getTime(); } catch (Exception ignored) {}
            try { if (finishedStr != null && finishedStr.length() >= 19) finishedMs = fmt.parse(finishedStr).getTime(); } catch (Exception ignored) {}
            if (startedMs > 0) t.started = startedMs;
            if (startedMs > 0 && finishedMs > 0) t.duration = finishedMs - startedMs;
            t.filePath = f.getAbsolutePath();
            return t;
        } catch (Exception e) {
            return null;
        }
    }

}
