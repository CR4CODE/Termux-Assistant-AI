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
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
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

    private String currentDevFileName = null;
    private String lastDevReply = "";
    private int stableCount = 0;

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
        android.util.Log.i("Dev", "cycle4");

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
                    toast("Введи задачу в поле или скопируй в буфер");
                    return;
                }
                final String finalTask = text.trim();
                showFilePicker(finalTask);
            }
        });
    }

    private void showFilePicker(final String task) {
        // Обновляем source через HTTP
        httpTask("export_source:", new HttpCallback() {
            @Override public void onResult(String status, String output, int rc, double elapsed) { }
        });

        if (statusLabel != null) statusLabel.setText("Обновляю исходники...");

        handler.postDelayed(new Runnable() {
            @Override public void run() {
                pickFileDelayed(task);
            }
        }, 2000);
    }

    private void pickFileDelayed(final String task) {
        java.io.File dir = new java.io.File("/sdcard/ai-tasker/source/src/com/termux/assistant");
        if (!dir.exists()) {
            toast("Проект не найден");
            return;
        }

        java.io.File[] files = dir.listFiles(new java.io.FilenameFilter() {
            @Override public boolean accept(java.io.File d, String name) {
                return name.endsWith(".java");
            }
        });

        if (files == null || files.length == 0) {
            toast("Нет Java-файлов");
            return;
        }

        java.util.Arrays.sort(files, new java.util.Comparator<java.io.File>() {
            @Override public int compare(java.io.File a, java.io.File b) {
                return a.getName().compareTo(b.getName());
            }
        });

        final String[] names = new String[files.length];
        for (int i = 0; i < files.length; i++) names[i] = files[i].getName();

        new AlertDialog.Builder(this)
            .setTitle("Какой файл изменить?")
            .setItems(names, new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface d, int which) {
                    runDevTaskOnFile(task, names[which]);
                }
            })
            .setNegativeButton("Отмена", new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface d, int w) { setActiveMode("none"); }
            })
            .show();
    }

    private void runDevTaskOnFile(String task, String fileName) {
        String content = "";
        try {
            java.io.File f = new java.io.File("/sdcard/ai-tasker/source/src/com/termux/assistant/" + fileName);
            java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(f));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append("\n");
            r.close();
            content = sb.toString();
        } catch (Exception e) {
            toast("Не могу прочитать: " + e.getMessage());
            return;
        }

        currentDevFileName = fileName;
        lastDevReply = "";
        stableCount = 0;

        if (statusLabel != null) statusLabel.setText("Отправлено в DeepSeek (" + fileName + ")");

        StringBuilder prompt = new StringBuilder();
        prompt.append("Файл проекта: src/com/termux/assistant/").append(fileName).append("\n\n");
        prompt.append("Текущий код:\n```java\n").append(content).append("\n```\n\n");
        prompt.append("Задача: ").append(task).append("\n\n");
        prompt.append("Верни ПОЛНЫЙ обновлённый файл целиком, начиная с package. ");
        prompt.append("Один блок кода:\n");
        prompt.append("```java src/com/termux/assistant/").append(fileName).append("\n");
        prompt.append("<полный код>\n```\n");
        prompt.append("Никаких объяснений.");

        String raw = prompt.toString();

        // Раскрываем литеральные escape-последовательности в реальные символы
        raw = raw.replace("\\\n", "\n").replace("\\\r", "\r").replace("\\\t", "\t");

        // Правильно экранируем для JS-строки
        String escaped = raw.replace("\\", "\\\\").replace("\"", "\\\"").replace("'", "\\'").replace("\\n", "\\\\n").replace("\\r", "\\\\r");

        webView.evaluateJavascript("window.TermuxSend('" + escaped + "');", null);

        waitForDevReply(0);
    }

    private void waitForDevReply(final int attempt) {
        if (attempt > 80) { // ~240 секунд
            if (statusLabel != null) statusLabel.setText("Таймаут 240 сек");
            setActiveMode("none");
            return;
        }

        webView.postDelayed(new Runnable() {
            @Override public void run() {
                if (statusLabel != null) statusLabel.setText("Ждём ответа... (" + (attempt * 3) + " сек)");

                webView.evaluateJavascript("window.TermuxReadLast();",
                    new android.webkit.ValueCallback<String>() {
                    @Override public void onReceiveValue(String value) {
                        String reply = unescapeJs(value);
                        if (reply == null) reply = "";
                        reply = reply.trim();

                        // Слишком короткий ответ — ждём ещё
                        if (reply.length() < 500) {
                            waitForDevReply(attempt + 1);
                            return;
                        }

                        boolean hasCode = reply.contains("package ") || reply.contains("public class ") || reply.contains("import android");
                        if (!hasCode) {
                            waitForDevReply(attempt + 1);
                            return;
                        }

                        // Минимум 15 секунд ожидания
                        if (attempt < 5) {
                            lastDevReply = reply;
                            stableCount = 0;
                            waitForDevReply(attempt + 1);
                            return;
                        }

                        // Проверка завершения: последний непустой символ — }
                        String tail = reply;
                        while (tail.length() > 0 && Character.isWhitespace(tail.charAt(tail.length() - 1))) {
                            tail = tail.substring(0, tail.length() - 1);
                        }
                        boolean looksComplete = tail.endsWith("}");

                        // Стабильность: длина не меняется несколько циклов подряд
                        if (reply.equals(lastDevReply)) {
                            stableCount++;
                        } else {
                            stableCount = 0;
                            lastDevReply = reply;
                        }

                        // Готово, если ответ завершён и стабилен 2 цикла
                        if (looksComplete && stableCount >= 2) {
                            parseAndSaveFiles(reply);
                            return;
                        }

                        // Аварийный выход: долго ждём, ответ большой и стабилен 5 циклов
                        if (attempt >= 40 && stableCount >= 5 && reply.length() > 2000) {
                            parseAndSaveFiles(reply);
                            return;
                        }

                        waitForDevReply(attempt + 1);
                    }
                });
            }
        }, 3000);
    }

    private void parseAndSaveFiles(String reply) {
        try {
            String content = reply;

            // Ищем начало кода — с "package com.termux"
            int pkgIdx = content.indexOf("package com.termux");
            if (pkgIdx < 0) pkgIdx = content.indexOf("package ");
            if (pkgIdx < 0) pkgIdx = content.indexOf("public class ");
            if (pkgIdx < 0) pkgIdx = content.indexOf("<?xml");
            if (pkgIdx < 0) {
                new AlertDialog.Builder(MainActivity.this)
                    .setTitle("Не нашёл начало кода")
                    .setMessage("В ответе нет package/class/xml.\n\nНачало:\n" + content.substring(0, Math.min(200, content.length())))
                    .setPositiveButton("OK", null)
                    .show();
                setActiveMode("none");
                return;
            }

            content = content.substring(pkgIdx).trim();

            if (content.length() < 500) {
                new AlertDialog.Builder(MainActivity.this)
                    .setTitle("Слишком коротко")
                    .setMessage("Найдено " + content.length() + " симв. Это слишком мало.\n\nПопробуй ещё раз в новом чате.")
                    .setPositiveButton("OK", null)
                    .show();
                setActiveMode("none");
                return;
            }

            // Сохраняем как выбранный пользователем файл
            java.io.File pending = new java.io.File("/sdcard/ai-tasker/pending");
            pending.mkdirs();
            java.io.File[] oldFiles = pending.listFiles();
            if (oldFiles != null) for (java.io.File f : oldFiles) f.delete();

            String fileName = (currentDevFileName != null) ? currentDevFileName : "NewFile.java";
            String relPath = fileName.endsWith(".xml")
                ? "res/layout/" + fileName
                : "src/com/termux/assistant/" + fileName;

            // САНИТАЙЗЕР: чиним экранированные кавычки
            // DeepSeek получает наш код с \" вместо " (баг экранирования JS).
            // Обратно заменяем \" -> " ТОЛЬКО если их много (признак сбоя)
            int dqCount = content.split("\\\\\"", -1).length - 1;
            int realDq = content.split("\"", -1).length - 1;
            if (dqCount > 10 && dqCount > realDq / 2) {
                content = content.replace("\\\\\"", "\"");
                android.util.Log.i("DevSanitize", "Заменено кавычек: " + dqCount);
            }

            java.io.File dest = new java.io.File(pending, relPath);
            dest.getParentFile().mkdirs();
            java.io.FileWriter w = new java.io.FileWriter(dest);
            w.write(content);
            w.close();

            new AlertDialog.Builder(MainActivity.this)
                .setTitle("Найдено: " + fileName)
                .setMessage("Размер: " + content.length() + " симв.\n\nПуть: " + relPath + "\n\nПрименить?")
                .setPositiveButton("Применить и собрать", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int wi) { applyAndBuild(); }
                })
                .setNegativeButton("Отмена", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int wi) { setActiveMode("none"); }
                })
                .show();

            if (statusLabel != null) statusLabel.setText("Найдено: " + fileName + " (" + content.length() + ")");
        } catch (Exception e) {
            toast("Ошибка: " + e.getMessage());
        }
    }

    public interface HttpCallback {
        void onResult(String status, String output, int rc, double elapsed);
    }

    private void httpTask(final String task, final HttpCallback cb) {
        new Thread(new Runnable() {
            @Override public void run() {
                HttpURLConnection conn = null;
                try {
                    URL url = new URL("http://127.0.0.1:8767/task");
                    conn = (HttpURLConnection) url.openConnection();
                    conn.setRequestMethod("POST");
                    conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                    conn.setDoOutput(true);
                    conn.setConnectTimeout(5000);
                    conn.setReadTimeout(1800000);

                    String body;
                    try {
                        org.json.JSONObject jo = new org.json.JSONObject();
                        jo.put("task", task);
                        body = jo.toString();
                    } catch (Exception je) {
                        body = "{\"task\":\"\"}";
                    }
                    byte[] payload = body.getBytes("UTF-8");
                    conn.setFixedLengthStreamingMode(payload.length);
                    OutputStream os = conn.getOutputStream();
                    os.write(payload);
                    os.close();

                    int code = conn.getResponseCode();
                    java.io.InputStream is = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
                    BufferedReader r = new BufferedReader(new InputStreamReader(is, "UTF-8"));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = r.readLine()) != null) sb.append(line).append("\n");
                    r.close();

                    org.json.JSONObject o = new org.json.JSONObject(sb.toString());
                    final String status = o.optString("status", "error");
                    final String output = o.optString("output", "");
                    final int rc = o.optInt("exit_code", -1);
                    final double elapsed = o.optDouble("elapsed", 0.0);
                    webView.post(new Runnable() {
                        @Override public void run() { cb.onResult(status, output, rc, elapsed); }
                    });
                } catch (final Exception e) {
                    webView.post(new Runnable() {
                        @Override public void run() { cb.onResult("error", "HTTP: " + e.getMessage(), -1, 0.0); }
                    });
                } finally {
                    if (conn != null) conn.disconnect();
                }
            }
        }).start();
    }

    private void applyAndBuild() {
        if (statusLabel != null) statusLabel.setText("Применяю и собираю APK...");
        toast("Применяю файлы и собираю APK");
        final long startedAt = System.currentTimeMillis();
        final Runnable tick = new Runnable() {
            @Override public void run() {
                long elapsed = (System.currentTimeMillis() - startedAt) / 1000;
                if (statusLabel != null) statusLabel.setText("Сборка APK... (" + elapsed + " сек)");
            }
        };
        final android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
        h.postDelayed(tick, 0);

        httpTask("apply_patches:", new HttpCallback() {
            @Override public void onResult(String status, String output, int rc, double elapsed) {
                h.removeCallbacks(tick);
                if ("success".equals(status)) {
                    String apkPath = "/sdcard/Download/";
                    int ai = output.lastIndexOf("APK:");
                    if (ai >= 0) {
                        int nl = output.indexOf('\n', ai);
                        apkPath = (nl > 0 ? output.substring(ai + 4, nl) : output.substring(ai + 4)).trim();
                    }
                    if (statusLabel != null) statusLabel.setText("APK готов: " + apkPath);
                    new AlertDialog.Builder(MainActivity.this)
                        .setTitle("APK готов")
                        .setMessage(apkPath)
                        .setPositiveButton("OK", null)
                        .show();
                } else {
                    if (statusLabel != null) statusLabel.setText("Сборка упала");
                    String tail = output.length() > 1500 ? output.substring(output.length() - 1500) : output;
                    new AlertDialog.Builder(MainActivity.this)
                        .setTitle("Сборка упала")
                        .setMessage(tail)
                        .setPositiveButton("OK", null)
                        .show();
                }
                setActiveMode("none");
            }
        });
    }

    private void pollApplyResult(final String id, final int attempt, final long startedAt) {
        final long elapsed = (System.currentTimeMillis() - startedAt) / 1000;
        if (elapsed > 300) {
            if (statusLabel != null) statusLabel.setText("Таймаут сборки (5 мин)");
            setActiveMode("none");
            return;
        }

        if (statusLabel != null) {
            statusLabel.setText("Сборка APK... (" + elapsed + " сек)");
        }

        webView.postDelayed(new Runnable() {
            @Override public void run() {
                java.io.File out = new java.io.File("/sdcard/ai-tasker/outbox/task-" + id + ".json");
                if (out.exists()) {
                    try {
                        java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(out));
                        StringBuilder sb = new StringBuilder();
                        String line;
                        while ((line = r.readLine()) != null) sb.append(line).append("\n");
                        r.close();
                        String raw = sb.toString();

                        String status = "";
                        int si = raw.indexOf("\"status\"");
                        if (si >= 0) {
                            int ci = raw.indexOf(':', si);
                            int q1 = raw.indexOf('"', ci);
                            int q2 = raw.indexOf('"', q1 + 1);
                            if (q1 > 0 && q2 > q1) status = raw.substring(q1 + 1, q2);
                        }

                        String output = "";
                        int oi = raw.indexOf("\"output\":");
                        if (oi >= 0) {
                            int q1 = raw.indexOf('"', oi + 9);
                            if (q1 > 0) {
                                StringBuilder ob = new StringBuilder();
                                int k = q1 + 1;
                                while (k < raw.length()) {
                                    char c = raw.charAt(k);
                                    if (c == '\\' && k + 1 < raw.length()) {
                                        char n = raw.charAt(k + 1);
                                        if (n == 'n') { ob.append('\n'); k += 2; continue; }
                                        if (n == 't') { ob.append('\t'); k += 2; continue; }
                                        if (n == 'r') { k += 2; continue; }
                                        if (n == '"') { ob.append('"'); k += 2; continue; }
                                        if (n == '\\') { ob.append('\\'); k += 2; continue; }
                                        if (n == '/') { ob.append('/'); k += 2; continue; }
                                        if (n == 'u' && k + 5 < raw.length()) {
                                            try {
                                                ob.append((char) Integer.parseInt(raw.substring(k + 2, k + 6), 16));
                                                k += 6; continue;
                                            } catch (Exception e2) {}
                                        }
                                        ob.append(n); k += 2; continue;
                                    }
                                    if (c == '"') break;
                                    ob.append(c); k++;
                                }
                                output = ob.toString();
                            }
                        }

                        if ("success".equals(status)) {
                            String apkPath = "/sdcard/Download/";
                            int ai = output.lastIndexOf("APK:");
                            if (ai >= 0) {
                                int nl = output.indexOf('\n', ai);
                                apkPath = (nl > 0 ? output.substring(ai + 4, nl) : output.substring(ai + 4)).trim();
                            }

                            if (statusLabel != null) statusLabel.setText("APK готов: " + apkPath);
                            new AlertDialog.Builder(MainActivity.this)
                                .setTitle("APK готов")
                                .setMessage(apkPath)
                                .setPositiveButton("OK", null)
                                .show();
                            setActiveMode("none");
                            return;
                        }

                        if ("error".equals(status)) {
                            if (statusLabel != null) statusLabel.setText("Сборка упала");
                            final String errOut = output;
                            new AlertDialog.Builder(MainActivity.this)
                                .setTitle("Сборка упала")
                                .setMessage(errOut.length() > 1500 ? errOut.substring(errOut.length() - 1500) : errOut)
                                .setPositiveButton("OK", null)
                                .show();
                            setActiveMode("none");
                            return;
                        }
                    } catch (Exception e) {
                        // файл ещё пишется — ждём дальше
                    }
                }
                pollApplyResult(id, attempt + 1, startedAt);
            }
        }, 3000);
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

            String escaped = org.json.JSONObject.quote(text);
            webView.evaluateJavascript("window.TermuxInsertText(" + escaped + ");", null);
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
        cycleStartTime = System.currentTimeMillis();
        if (statusLabel != null) {
            statusLabel.setText(prefix.equals("dev:") ? "\uD83D\uDEE0 Отправлено в ai-dev\u2026" : "\u23F3 Выполняется в Termux\u2026");
        }

        final long startedAt = System.currentTimeMillis();
        cycleRunnable = new Runnable() {
            @Override public void run() {
                long elapsed = (System.currentTimeMillis() - startedAt) / 1000;
                if (statusLabel != null) statusLabel.setText("\u23F3 Выполняется... (" + elapsed + " сек)");
                handler.postDelayed(this, 1000);
            }
        };
        handler.postDelayed(cycleRunnable, 1000);

        httpTask(prefix + taskText, new HttpCallback() {
            @Override public void onResult(final String status, final String output, int rc, double elapsed) {
                handler.removeCallbacks(cycleRunnable);
                cycleRunnable = null;

                if ("error".equals(status) || output == null || output.isEmpty()) {
                    String err = (output == null || output.isEmpty()) ? "Пусто (rc=" + rc + ")" : output;
                    if (statusLabel != null) statusLabel.setText("\u2717 " + err);
                    setActiveMode("none");
                    return;
                }

                webView.evaluateJavascript("window.TermuxClearInput();", null);
                String escaped = org.json.JSONObject.quote(output);
                webView.evaluateJavascript("window.TermuxInsertText(" + escaped + ");", null);

                if (statusLabel != null) {
                    String prefixName = prefix.equals("dev:") ? "\uD83D\uDEE0" : "\u2713";
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
            }
        });
    }

    private String readResultFromJson(File f) {
        try {
            java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(f));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
            r.close();

            org.json.JSONObject o = new org.json.JSONObject(sb.toString());
            String status = o.optString("status", "");
            if ("running".equals(status)) return null;
            String output = o.optString("output", "");
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
        String s = value.trim();
        if (s.length() >= 2 && s.charAt(0) == '"' && s.charAt(s.length() - 1) == '"') {
            s = s.substring(1, s.length() - 1);
        }

        StringBuilder out = new StringBuilder(s.length());
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char n = s.charAt(i + 1);
                switch (n) {
                    case 'n': out.append('\n'); i += 2; break;
                    case 'r': out.append('\r'); i += 2; break;
                    case 't': out.append('\t'); i += 2; break;
                    case '"': out.append('"'); i += 2; break;
                    case '\\': out.append('\\'); i += 2; break;
                    case '/': out.append('/'); i += 2; break;
                    case 'b': out.append('\b'); i += 2; break;
                    case 'f': out.append('\f'); i += 2; break;
                    case 'u':
                        if (i + 5 < s.length()) {
                            try {
                                out.append((char) Integer.parseInt(s.substring(i + 2, i + 6), 16));
                                i += 6;
                            } catch (Exception e) { out.append(c); i++; }
                        } else { out.append(c); i++; }
                        break;
                    default:
                        out.append(c); i++; break;
                }
            } else {
                out.append(c); i++;
            }
        }
        return out.toString();
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
        + "};"
        + "window.TermuxSend = function(text){"
        + "try{"
        + "var ins=window.TermuxInsertText(text);"
        + "if(ins!=='ok')return ins;"
        + "setTimeout(function(){window.TermuxFindAndClick();},500);"
        + "return 'ok';"
        + "}catch(e){return 'err:'+e;}"
        + "};"
        + "window.TermuxIsGenerating = function(){"
        + "try{"
        + "var ta=document.querySelector('textarea');"
        + "if(!ta)return false;"
        + "var taR=ta.getBoundingClientRect();"
        + "var all=document.querySelectorAll('[role=button],button');"
        + "for(var i=0;i<all.length;i++){"
        + "var b=all[i];var r=b.getBoundingClientRect();"
        + "if(r.width<20||r.width>80)continue;"
        + "if(r.height<20||r.height>80)continue;"
        + "if(r.left<taR.left-30)continue;"
        + "if(r.top<taR.top-30)continue;"
        + "if(r.top>taR.bottom+150)continue;"
        + "if(r.right<window.innerWidth*0.8)continue;" // самая правая кнопка
        + "var svg=b.querySelector('svg');"
        + "if(!svg)continue;"
        + "var cls=String(svg.getAttribute('class')||'')+' '+String(svg.innerHTML||'');"
        + "if(cls.indexOf('stop')>=0||cls.indexOf('square')>=0)return true;"
        + "}"
        + "return false;"
        + "}catch(e){return false;}"
        + "};"
        + "window.TermuxReadLast = function(){"
        + "var all=document.querySelectorAll('[class*=markdown]');"
        + "if(all.length===0)return '';"
        + "var last=null;"
        + "for(var i=0;i<all.length;i++){"
        + "var r=all[i].getBoundingClientRect();"
        + "if(r.width<50)continue;"
        + "var t=(all[i].innerText||'').trim();"
        + "if(t.length<200)continue;"
        + "if(t.startsWith('Android Java проект'))continue;"
        + "if(t.startsWith('We need'))continue;"
        + "if(t.startsWith('Need to'))continue;"
        + "if(t.startsWith('Let\\'s'))continue;"
        + "if(t.indexOf('package com.termux')<0 && t.indexOf('import android')<0 && t.indexOf('public class')<0)continue;"
        + "last=t;"
        + "}"
        + "return last||'';"
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