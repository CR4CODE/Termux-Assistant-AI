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

    private LockableWebView webView;
    private ProgressBar progressBar;
    private TextView statusLabel;
    private volatile String lastContextError = "";
    private volatile boolean contextPollerRunning = false;

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

        ensureAllFilesAccess();

        webView = findViewById(R.id.ds_webview);
        progressBar = findViewById(R.id.ds_progress);
        statusLabel = findViewById(R.id.ds_status);

        Button topClearBtn = findViewById(R.id.btn_top_clear);
        if (topClearBtn != null) {
            topClearBtn.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { clearInput(); }
            });
        }

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
                @Override public void onClick(View v) { vibeRun(); }
            });
        }

        Button clearBtn = findViewById(R.id.btn_ds_clear);
        if (clearBtn != null) {
            clearBtn.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { copyFirstBash(); }
            });
        }

        final Button kbdBtn = findViewById(R.id.btn_ds_kbd);
        if (kbdBtn != null) {
            kbdBtn.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    boolean locked = !webView.isKeyboardLocked();
                    webView.setKeyboardLocked(locked);
                    SharedPreferences sp = getSharedPreferences("app", MODE_PRIVATE);
                    sp.edit().putBoolean("kbd_locked", locked).apply();
                    updateKbdButton(kbdBtn, locked);
                    webView.evaluateJavascript("window.TermuxSetKbdLocked(" + (locked ? "true" : "false") + ");", null);
                    if (locked) {
                        android.view.inputmethod.InputMethodManager imm =
                            (android.view.inputmethod.InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
                        if (imm != null) {
                            imm.hideSoftInputFromWindow(webView.getWindowToken(), 0);
                        }
                        webView.clearFocus();
                        toast("Клавиатура заблокирована");
                    } else {
                        toast("Клавиатура разблокирована");
                    }
                }
            });
            SharedPreferences sp = getSharedPreferences("app", MODE_PRIVATE);
            boolean locked = sp.getBoolean("kbd_locked", false);
            webView.setKeyboardLocked(locked);
            updateKbdButton(kbdBtn, locked);
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
                // Скрытие размышлений, если включено в меню
                boolean _hide = getSharedPreferences("app_prefs", MODE_PRIVATE).getBoolean("hide_thinking", false);
                if (_hide) {
                    v.postDelayed(new Runnable() { @Override public void run() {
                        v.evaluateJavascript("window.__termuxHideThinkingLoop&&window.__termuxHideThinkingLoop()", null);
                    }}, 2500);
                }
                // startContextPoller(); // отключено: ложные срабатывания
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

    private void copyFirstBash() {
        webView.evaluateJavascript("window.TermuxCopyFirstBash?window.TermuxCopyFirstBash():'no_fn'",
            new android.webkit.ValueCallback<String>() {
            @Override public void onReceiveValue(String val) {
                String raw = unescapeJs(val);
                if (raw == null || raw.isEmpty() || raw.startsWith("no_") || raw.startsWith("err:")) {
                    toast("Bash-блок не найден: " + raw);
                    return;
                }
                String code = raw;
                if (code.startsWith("```")) {
                    int nl = code.indexOf('\n');
                    if (nl > 0) code = code.substring(nl + 1);
                    if (code.endsWith("```")) code = code.substring(0, code.length() - 3);
                }
                android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                cm.setPrimaryClip(android.content.ClipData.newPlainText("bash", code));
                String esc = org.json.JSONObject.quote(code);
                webView.evaluateJavascript("window.TermuxClearInput();", null);
                webView.evaluateJavascript("window.TermuxInsertText(" + esc + ");", null);
                toast("Bash в поле и в буфере — жми \uD83D\uDE80 Free");
            }
        });
    }

    private void ensureAllFilesAccess() {
        try {
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                if (android.os.Environment.isExternalStorageManager()) return;
                android.content.Intent i = new android.content.Intent(
                    android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                i.setData(android.net.Uri.parse("package:" + getPackageName()));
                startActivity(i);
            }
        } catch (Exception e) {
            android.util.Log.w("Main", "all files access request failed: " + e);
        }
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
        boolean hideThinking = getSharedPreferences("app_prefs", MODE_PRIVATE).getBoolean("hide_thinking", false);
        String thinkingLabel = (hideThinking ? "✅  " : "⬜  ") + "Скрывать размышления";
        final String[] items = new String[]{
            "📋  История задач",
            "📖  Инструкция",
            "⚙️  Настройки",
            "📢  ВК-постинг",
            "🚀  Релиз",
            "💾  Сохранить контекст",
            thinkingLabel
        };

        new AlertDialog.Builder(this)
            .setTitle("Меню")
            .setItems(items, new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface d, int which) {
                    Intent i = null;
                    if (which == 0) i = new Intent(MainActivity.this, HistoryActivity.class);
                    else if (which == 1) i = new Intent(MainActivity.this, DocsActivity.class);
                    else if (which == 2) i = new Intent(MainActivity.this, SettingsActivity.class);
                    else if (which == 3) { openVkMenu(); return; }
                    else if (which == 4) { releaseDialog(); return; }
                    else if (which == 5) { saveContext(); return; }
                    else if (which == 6) { toggleHideThinking(); return; }
                    if (i != null) startActivity(i);
                }
            })
            .show();
    }

    private void toggleHideThinking() {
        boolean cur = getSharedPreferences("app_prefs", MODE_PRIVATE).getBoolean("hide_thinking", false);
        boolean next = !cur;
        getSharedPreferences("app_prefs", MODE_PRIVATE).edit().putBoolean("hide_thinking", next).apply();
        toast(next ? "Размышления скрыты" : "Размышления показаны");
        if (next) {
            webView.evaluateJavascript("window.__termuxHideThinkingLoop&&window.__termuxHideThinkingLoop()", null);
        } else {
            webView.evaluateJavascript("window.__termuxShowThinking&&window.__termuxShowThinking()", null);
        }
    }

    private void saveContext() {
        new AlertDialog.Builder(this)
            .setTitle("Сохранить контекст")
            .setMessage("Отправить в DeepSeek запрос на сводку, скопировать ответ, открыть новый чат и вставить сводку первым сообщением?")
            .setPositiveButton("Поехали", new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface d, int w) {
                    saveContextRun();
                }
            })
            .setNegativeButton("Отмена", null)
            .show();
    }

    private void saveContextRun() {
        if (statusLabel != null) statusLabel.setText("Готовлю сводку контекста...");
        String prompt = "Составь сводку для продолжения работы в НОВОМ чате. "
            + "Я вставлю её первым сообщением. "
            + "ВАЖНО: ответь ТОЛЬКО сводкой, без рассуждений. "
            + "Начни сразу с первой строки: === КОНТЕКСТ ДЛЯ ПРОДОЛЖЕНИЯ === "
            + "Секции: ПРОЕКТ / СТЕК / ПУТИ / ПРАВИЛА / АРХИТЕКТУРА / ЧТО СДЕЛАНО / ЧТО ОСТАЛОСЬ / ТЕКУЩАЯ ЗАДАЧА / ГРАБЛИ. "
            + "Последняя строка: === КОНЕЦ ===. Только суть, без воды.";
        String escaped = prompt.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("'", "\\'")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t");
        webView.evaluateJavascript("window.TermuxSend('" + escaped + "');", null);
        saveContextWait(0);
    }

    private void saveContextWait(final int attempt) {
        if (attempt > 60) {
            if (statusLabel != null) statusLabel.setText("Таймаут ожидания сводки");
            return;
        }
        if (statusLabel != null) statusLabel.setText("Ждём сводку... (" + (attempt * 2) + "с)");
        webView.postDelayed(new Runnable() {
            @Override public void run() {
                webView.evaluateJavascript("window.TermuxReadSummary();",
                    new android.webkit.ValueCallback<String>() {
                    @Override public void onReceiveValue(String v) {
                        String reply = (v == null) ? "" : v;
                        if (reply.length() >= 2 && reply.startsWith("\"") && reply.endsWith("\"")) {
                            reply = reply.substring(1, reply.length() - 1);
                        }
                        reply = reply.replace("\\n", "\n").replace("\\r", "\r")
                                     .replace("\\t", "\t").replace("\\\"", "\"")
                                     .replace("\\\\", "\\");
                        if (reply.length() < 200 || reply.equals("null")
                                || reply.indexOf("=== КОНТЕКСТ ДЛЯ ПРОДОЛЖЕНИЯ") < 0) {
                            saveContextWait(attempt + 1);
                            return;
                        }
                        if (reply.startsWith("err:")) {
                            if (statusLabel != null) statusLabel.setText("Ошибка чтения: " + reply);
                            return;
                        }
                        final String summary = reply;
                        android.content.ClipboardManager cm = (android.content.ClipboardManager)
                            getSystemService(android.content.Context.CLIPBOARD_SERVICE);
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("context", summary));
                        if (statusLabel != null) statusLabel.setText("Сводка скопирована. Открываю новый чат...");
                        webView.postDelayed(new Runnable() {
                            @Override public void run() {
                                webView.evaluateJavascript("window.TermuxNewChat();", null);
                                webView.postDelayed(new Runnable() {
                                    @Override public void run() {
                                        String esc = summary.replace("\\", "\\\\")
                                                            .replace("\"", "\\\"")
                                                            .replace("'", "\\'")
                                                            .replace("\n", "\\n")
                                                            .replace("\r", "\\r")
                                                            .replace("\t", "\\t");
                                        webView.evaluateJavascript("window.TermuxInsertText('" + esc + "');", null);
                                        if (statusLabel != null) statusLabel.setText("Сводка в новом чате — проверь и отправь");
                                        android.widget.Toast.makeText(MainActivity.this,
                                            "Сводка в поле нового чата. Проверь и нажми отправить.",
                                            android.widget.Toast.LENGTH_LONG).show();
                                    }
                                }, 3500);
                            }
                        }, 1500);
                    }
                });
            }
        }, 2000);
    }

    private void startContextPoller() {
        if (contextPollerRunning) return;
        contextPollerRunning = true;
        lastContextError = "";
        webView.postDelayed(new Runnable() { @Override public void run() { contextPollerTick(); } }, 8000);
    }

    private void contextPollerTick() {
        if (!contextPollerRunning || webView == null) return;
        webView.evaluateJavascript("window.TermuxDetectContextError&&window.TermuxDetectContextError();",
            new android.webkit.ValueCallback<String>() {
            @Override public void onReceiveValue(String v) {
                String err = (v == null) ? "" : v;
                if (err.startsWith("\"") && err.endsWith("\"") && err.length() >= 2) {
                    err = err.substring(1, err.length() - 1);
                }
                err = err.replace("\\n", "\n").replace("\\\"", "\"");
                if (err.equals("null")) err = "";
                if (!err.isEmpty() && !err.equals(lastContextError) && !err.startsWith("err:")) {
                    lastContextError = err;
                    showContextErrorDialog(err);
                }
                webView.postDelayed(new Runnable() { @Override public void run() { contextPollerTick(); } }, 3000);
            }
        });
    }

    private void showContextErrorDialog(final String err) {
        if (isFinishing()) return;
        new AlertDialog.Builder(MainActivity.this)
            .setTitle("DeepSeek: лимит контекста")
            .setMessage("Похоже, чат упёрся в лимит контекста.\n\nСохранить сводку и открыть новый чат?")
            .setPositiveButton("Сохранить и открыть", new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface d, int w) {
                    saveContextRun();
                }
            })
            .setNeutralButton("Позже", null)
            .show();
    }

    private void openVkMenu() {
        final String[] items = new String[]{
            "✨  Сгенерировать пост из истории проекта",
            "📝  Написать вручную",
            "🔧  Настройки ВК",
            "🌐  Открыть сообщество"
        };
        new AlertDialog.Builder(this)
            .setTitle("ВК-постинг")
            .setItems(items, new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface d, int which) {
                    if (which == 0) vkGenerate();
                    else if (which == 1) vkComposeManual();
                    else if (which == 2) vkSettings();
                    else if (which == 3) {
                        try {
                            startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://vk.com/termuxai")));
                        } catch (Exception e) { toast("Не могу открыть: " + e.getMessage()); }
                    }
                }
            })
            .show();
    }

    private void releaseDialog() {
        final android.widget.EditText ver = new android.widget.EditText(this);
        ver.setHint("Версия (например 2.5)");
        String cur = getSharedPreferences("app", MODE_PRIVATE).getString("last_release_version", "2.4");
        try {
            String[] p = cur.split("\\.");
            cur = p[0] + "." + (Integer.parseInt(p[1]) + 1);
        } catch (Exception e) {}
        ver.setText(cur);
        int pad = (int)(16 * getResources().getDisplayMetrics().density);
        android.widget.LinearLayout ll = new android.widget.LinearLayout(this);
        ll.setOrientation(android.widget.LinearLayout.VERTICAL);
        ll.setPadding(pad, pad, pad, pad);
        ll.addView(ver);
        new AlertDialog.Builder(this)
            .setTitle("Релиз")
            .setMessage("Сгенерирую через DeepSeek:\n• Release notes\n• CHANGELOG-запись\n• Пост в ВК\n\nПотом покажу превью — сможешь править. Далее auto-release (сборка, git, GitHub Release).")
            .setView(ll)
            .setPositiveButton("Сгенерировать", new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface d, int w) {
                    String v = ver.getText().toString().trim();
                    if (v.isEmpty()) { toast("Введи версию"); return; }
                    getSharedPreferences("app", MODE_PRIVATE).edit().putString("last_release_version", v).apply();
                    releaseGenerate(v);
                }
            })
            .setNegativeButton("Отмена", null)
            .show();
    }

    private String releaseVersion = "";
    private String relNotes = "";
    private String relChangelog = "";
    private String relVk = "";
    private int relStep = 0;

    private void releaseGenerate(String version) {
        releaseVersion = version;
        relNotes = "";
        relChangelog = "";
        relVk = "";
        relStep = 0;
        if (statusLabel != null) statusLabel.setText("Собираю факты для v" + version + "...");
        httpTask("release_facts:" + version, new HttpCallback() {
            @Override public void onResult(String status, String output, int rc, double elapsed) {
                if (output == null || output.isEmpty()) {
                    toast("Не могу собрать факты");
                    return;
                }
                releaseFacts = output;
                relStep = 1;
                releaseAskNotes();
            }
        });
    }

    private String releaseFacts = "";

    private void releaseAskNotes() {
        if (statusLabel != null) statusLabel.setText("Генерирую release notes...");
        String p = "Составь Release Notes для GitHub релиза v" + releaseVersion + " проекта Termux Assistant AI.\n"
            + "На основе фактов ниже. Формат markdown.\n"
            + "Структура: ## v" + releaseVersion + " — <краткий заголовок>, затем разделы ### Главное / ### Исправлено / ### Добавлено (только те, что есть в фактах).\n"
            + "Длина 800-1500 знаков. Пиши человеческим языком для конечного пользователя.\n"
            + "ЗАПРЕЩЕНО: хеши коммитов, префиксы feat/fix/chore/docs/revert, статистика файлов/строк, "
            + "слова 'commit', 'коммит', технические идентификаторы.\n"
            + "Пиши что пользователь получит: 'Добавили...', 'Исправили...', 'Теперь работает...'.\n"
            + "Используй ТОЛЬКО то, что есть в разделе 'Коммиты после последнего релиза'.\n"
            + "Верни ТОЛЬКО текст, без пояснений.\n\nФАКТЫ:\n" + releaseFacts;
        webView.evaluateJavascript("window.TermuxClearInput();", null);
        webView.evaluateJavascript("window.TermuxInsertText(" + org.json.JSONObject.quote(p) + ");", null);
        webView.postDelayed(new Runnable() {
            @Override public void run() { webView.evaluateJavascript("window.TermuxFindAndClick();", null); }
        }, 700);
        releaseWaitReply(0, "notes");
    }

    private void releaseWaitReply(final int attempt, final String kind) {
        if (attempt > 60) {
            if (statusLabel != null) statusLabel.setText("Таймаут (" + kind + ")");
            return;
        }
        if (statusLabel != null) statusLabel.setText("Ждём " + kind + "... (" + (attempt * 3) + "с)");
        webView.postDelayed(new Runnable() {
            @Override public void run() {
                webView.evaluateJavascript("window.TermuxReadPost();",
                    new android.webkit.ValueCallback<String>() {
                    @Override public void onReceiveValue(String value) {
                        String reply = unescapeJs(value);
                        if (reply == null) reply = "";
                        reply = reply.trim();
                        if (reply.length() < 200) { releaseWaitReply(attempt + 1, kind); return; }
                        if (reply.equals(releaseLast)) releaseStable++;
                        else { releaseStable = 0; releaseLast = reply; }
                        if (releaseStable >= 3 && attempt >= 5) {
                            if ("notes".equals(kind)) {
                                relNotes = reply;
                                releaseAskChangelog();
                            } else if ("changelog".equals(kind)) {
                                relChangelog = reply;
                                releaseAskVk();
                            } else if ("vk".equals(kind)) {
                                relVk = reply;
                                releasePreview();
                            }
                            return;
                        }
                        releaseWaitReply(attempt + 1, kind);
                    }
                });
            }
        }, 3000);
    }

    private String releaseLast = "";
    private int releaseStable = 0;

    private void releaseAskChangelog() {
        releaseLast = ""; releaseStable = 0;
        if (statusLabel != null) statusLabel.setText("Генерирую CHANGELOG...");
        String p = "Составь запись для CHANGELOG.md (markdown) о релизе v" + releaseVersion + " проекта Termux Assistant AI.\n"
            + "Начни с '## [" + releaseVersion + ".0] — YYYY-MM-DD' (дата сегодня).\n"
            + "Разделы: ### Добавлено / ### Изменено / ### Исправлено (только те, что есть в фактах).\n"
            + "Каждый пункт — короткая строка с описанием. Длина 600-1200 знаков.\n"
            + "ВАЖНО: используй ТОЛЬКО то, что есть в разделе 'Коммиты после последнего релиза'. "
            + "Не выдумывай хеши и версии.\n"
            + "Верни ТОЛЬКО текст CHANGELOG-записи.\n\nФАКТЫ:\n" + releaseFacts;
        webView.evaluateJavascript("window.TermuxClearInput();", null);
        webView.evaluateJavascript("window.TermuxInsertText(" + org.json.JSONObject.quote(p) + ");", null);
        webView.postDelayed(new Runnable() {
            @Override public void run() { webView.evaluateJavascript("window.TermuxFindAndClick();", null); }
        }, 700);
        releaseWaitReply(0, "changelog");
    }

    private void releaseAskVk() {
        releaseLast = ""; releaseStable = 0;
        if (statusLabel != null) statusLabel.setText("Генерирую пост в ВК...");
        String p = "Ты — SMM-редактор сообщества ВК проекта Termux Assistant AI.\n"
            + "Составь пост для сообщества ВК про релиз v" + releaseVersion + ".\n"
            + "Структура (соблюдай строго, в этом порядке):\n"
            + "1) Строка: <эмодзи> <ЗАГОЛОВОК КАПСОМ>\n"
            + "2) Пустая строка, затем РОВНО строка из 18 символов ━ (скопируй буквально: ━━━━━━━━━━━━━━━━━━), пустая строка.\n"
            + "3) Вступление: 1-2 короткие строки.\n"
            + "4) Строка: ━━━━━━━━━━━━━━━━━━\n"
            + "5) Блок \"🆕 Что нового:\" — 3-5 пунктов, каждый с эмодзи и дефисом.\n"
            + "6) Строка: ━━━━━━━━━━━━━━━━━━\n"
            + "7) Блок \"📦 Что было ранее:\" — 2-3 пункта.\n"
            + "8) Строка: ━━━━━━━━━━━━━━━━━━\n"
            + "9) Ссылки: две строки (лендинг + github).\n"
            + "10) Хэштеги одной строкой.\n"
            + "ЖЁСТКИЕ ТРЕБОВАНИЯ К СИМВОЛАМ:\n"
            + "- Разделитель — ТОЛЬКО ━ (U+2501), ровно 18 штук, без пробелов. НЕ используй - = _ * и не сокращай.\n"
            + "- Эмодзи обязательны в начале заголовка и в подзаголовках 🆕/📦. Не убирай и не заменяй их.\n"
            + "- Не используй markdown (##, **, - без эмодзи). ВК не рендерит markdown.\n"
            + "Длина 900-1300 знаков. Хэштеги: #termux #android #ai #deepseek #opensource #программирование\n"
            + "Ссылки: https://cr4code.github.io/Termux-Assistant-AI/ и https://github.com/CR4CODE/Termux-Assistant-AI\n"
            + "Пиши для широкой аудитории (не для программистов).\n"
            + "ЗАПРЕЩЕНО: хеши, feat/fix/chore-префиксы, статистика файлов, слова 'коммит', 'commit'.\n"
            + "Перескажи что изменилось простыми словами: 'Добавили кнопку Релиз — теперь релиз запускается в один тап из приложения'.\n"
            + "Используй ТОЛЬКО то, что есть в разделе 'Коммиты после последнего релиза'.\n"
            + "Верни ТОЛЬКО текст поста.\n\nФАКТЫ:\n" + releaseFacts;
        webView.evaluateJavascript("window.TermuxClearInput();", null);
        webView.evaluateJavascript("window.TermuxInsertText(" + org.json.JSONObject.quote(p) + ");", null);
        webView.postDelayed(new Runnable() {
            @Override public void run() { webView.evaluateJavascript("window.TermuxFindAndClick();", null); }
        }, 700);
        releaseWaitReply(0, "vk");
    }

    private void releasePreview() {
        if (statusLabel != null) statusLabel.setText("Готово — проверь и подтверди");
        String msg = "Notes: " + relNotes.length() + " симв.\n"
            + "CHANGELOG: " + relChangelog.length() + " симв.\n"
            + "ВК-пост: " + relVk.length() + " симв.\n\n"
            + "Запустить полный релиз v" + releaseVersion + "?\n\n"
            + "(Сборка APK, sync, git commit, tag, GitHub Release, пост в ВК)";
        new AlertDialog.Builder(this)
            .setTitle("Релиз v" + releaseVersion)
            .setMessage(msg)
            .setPositiveButton("Релиз!", new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface d, int w) { releaseRun(); }
            })
            .setNeutralButton("Показать тексты", new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface d, int w) { releaseShowTexts(); }
            })
            .setNegativeButton("Отмена", null)
            .show();
    }

    private void releaseShowTexts() {
        final String[] tabs = {"Notes", "CHANGELOG", "ВК"};
        new AlertDialog.Builder(this)
            .setTitle("Выбери текст для просмотра")
            .setItems(tabs, new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface d, int which) {
                    final String text = which == 0 ? relNotes : which == 1 ? relChangelog : relVk;
                    final android.widget.EditText et = new android.widget.EditText(MainActivity.this);
                    et.setText(text);
                    et.setMinLines(12);
                    et.setGravity(android.view.Gravity.TOP);
                    int pad = (int)(16 * getResources().getDisplayMetrics().density);
                    et.setPadding(pad, pad, pad, pad);
                    new AlertDialog.Builder(MainActivity.this)
                        .setTitle(tabs[which])
                        .setView(et)
                        .setPositiveButton("Сохранить правки", new DialogInterface.OnClickListener() {
                            @Override public void onClick(DialogInterface d2, int w2) {
                                String edited = et.getText().toString();
                                if (which == 0) relNotes = edited;
                                else if (which == 1) relChangelog = edited;
                                else relVk = edited;
                                toast("Сохранено");
                                releasePreview();
                            }
                        })
                        .setNegativeButton("Отмена", new DialogInterface.OnClickListener() {
                            @Override public void onClick(DialogInterface d2, int w2) { releasePreview(); }
                        })
                        .show();
                }
            })
            .show();
    }

    private void releaseRun() {
        if (statusLabel != null) statusLabel.setText("Отправляю тексты на сервер...");
        // base64-кодируем тексты и отправляем три запроса, потом release_run
        try {
            android.util.Base64.encodeToString(relNotes.getBytes("UTF-8"), android.util.Base64.NO_WRAP);
        } catch (Exception e) {}
        final String bNotes = b64(relNotes);
        final String bCh = b64(relChangelog);
        final String bVk = b64(relVk);

        httpTask("release_notes:" + releaseVersion + ":" + bNotes, new HttpCallback() {
            @Override public void onResult(String s1, String o1, int r1, double e1) {
                httpTask("release_changelog:" + releaseVersion + ":" + bCh, new HttpCallback() {
                    @Override public void onResult(String s2, String o2, int r2, double e2) {
                        httpTask("release_vk:" + releaseVersion + ":" + bVk, new HttpCallback() {
                            @Override public void onResult(String s3, String o3, int r3, double e3) {
                                releaseDoRun();
                            }
                        });
                    }
                });
            }
        });
    }

    private String b64(String text) {
        try {
            return android.util.Base64.encodeToString(text.getBytes("UTF-8"), android.util.Base64.NO_WRAP);
        } catch (Exception e) {
            return "";
        }
    }

    private void releaseDoRun() {
        if (statusLabel != null) statusLabel.setText("Релиз запущен (сборка + git + GitHub + ВК)...");
        httpTask("release_run:" + releaseVersion, new HttpCallback() {
            @Override public void onResult(String status, String output, int rc, double elapsed) {
                if (rc == 0) {
                    if (statusLabel != null) statusLabel.setText("Релиз v" + releaseVersion + " опубликован");
                    new AlertDialog.Builder(MainActivity.this)
                        .setTitle("Релиз v" + releaseVersion + " готов")
                        .setMessage(output.length() > 1500 ? output.substring(output.length() - 1500) : output)
                        .setPositiveButton("OK", null)
                        .show();
                } else {
                    if (statusLabel != null) statusLabel.setText("Релиз упал");
                    new AlertDialog.Builder(MainActivity.this)
                        .setTitle("Релиз упал")
                        .setMessage(output.length() > 1500 ? output.substring(output.length() - 1500) : output)
                        .setPositiveButton("OK", null)
                        .show();
                }
            }
        });
    }

    private void vkSettings() {
        SharedPreferences sp = getSharedPreferences("vk", MODE_PRIVATE);
        final android.widget.EditText tok = new android.widget.EditText(this);
        tok.setHint("access_token");
        tok.setText(sp.getString("token", ""));
        final android.widget.EditText gid = new android.widget.EditText(this);
        gid.setHint("group_id (число)");
        gid.setText(String.valueOf(sp.getInt("group_id", 0) == 0 ? "" : sp.getInt("group_id", 0)));
        android.widget.LinearLayout ll = new android.widget.LinearLayout(this);
        ll.setOrientation(android.widget.LinearLayout.VERTICAL);
        int pad = (int)(16 * getResources().getDisplayMetrics().density);
        ll.setPadding(pad, pad, pad, pad);
        ll.addView(tok);
        ll.addView(gid);
        new AlertDialog.Builder(this)
            .setTitle("Настройки ВК")
            .setMessage("Токен сообщества: vk.com/termuxai → Управление → Работа с API")
            .setView(ll)
            .setPositiveButton("Сохранить", new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface d, int w) {
                    String t = tok.getText().toString().trim();
                    String g = gid.getText().toString().trim();
                    int gidN = 0;
                    try { gidN = Integer.parseInt(g); } catch (Exception e) {}
                    SharedPreferences sp = getSharedPreferences("vk", MODE_PRIVATE);
                    sp.edit().putString("token", t).putInt("group_id", gidN).apply();
                    toast("Сохранено");
                    // Отправляем на сервер для обновления конфига
                    syncVkConfig(t, gidN);
                }
            })
            .setNegativeButton("Отмена", null)
            .show();
    }

    private void syncVkConfig(String token, int gid) {
        // Отправляем токен на локальный сервер, чтобы vk-post мог им пользоваться
        final String task = "vk_config:" + gid + ":" + token;
        httpTask(task, new HttpCallback() {
            @Override public void onResult(String status, String output, int rc, double elapsed) {
                // молча
            }
        });
    }

    private void vkComposeManual() {
        final android.widget.EditText et = new android.widget.EditText(this);
        et.setHint("Текст поста...");
        et.setMinLines(6);
        et.setGravity(android.view.Gravity.TOP);
        int pad = (int)(16 * getResources().getDisplayMetrics().density);
        et.setPadding(pad, pad, pad, pad);
        new AlertDialog.Builder(this)
            .setTitle("Новый пост в ВК")
            .setView(et)
            .setPositiveButton("Опубликовать", new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface d, int w) {
                    String text = et.getText().toString().trim();
                    if (text.isEmpty()) { toast("Пусто"); return; }
                    vkPublish(text);
                }
            })
            .setNegativeButton("Отмена", null)
            .show();
    }

    private void vkPublish(final String text) {
        if (text.contains("SMM-редактор") || text.contains("ФАКТЫ О ПРОЕКТЕ")) {
            toast("Это промпт, а не пост. Жди ответа DeepSeek.");
            return;
        }
        if (statusLabel != null) statusLabel.setText("Публикую в ВК...");
        httpTask("vk_post:" + text, new HttpCallback() {
            @Override public void onResult(String status, String output, int rc, double elapsed) {
                if ("success".equals(status)) {
                    if (statusLabel != null) statusLabel.setText("✓ Опубликовано в ВК");
                    new AlertDialog.Builder(MainActivity.this)
                        .setTitle("Пост опубликован")
                        .setMessage(output)
                        .setPositiveButton("OK", null)
                        .show();
                } else {
                    if (statusLabel != null) statusLabel.setText("✗ ВК: ошибка");
                    new AlertDialog.Builder(MainActivity.this)
                        .setTitle("Ошибка публикации")
                        .setMessage(output)
                        .setPositiveButton("OK", null)
                        .show();
                }
            }
        });
    }

    private void vkGenerate() {
        if (statusLabel != null) statusLabel.setText("Собираю факты...");
        httpTask("vk_facts:", new HttpCallback() {
            @Override public void onResult(String status, String output, int rc, double elapsed) {
                if (output == null || output.isEmpty() || !"success".equals(status)) {
                    if (statusLabel != null) statusLabel.setText("✗ Не могу собрать факты");
                    toast("Не могу собрать факты: " + output);
                    return;
                }
                final String facts = output;
                String prompt = "Ты — SMM-редактор сообщества ВК проекта Termux Assistant AI.\n"
                    + "На основе фактов ниже напиши пост для сообщества ВК.\n\n"
                    + "Структура (соблюдай строго, в этом порядке):\n"
                    + "1) Строка: <эмодзи> <ЗАГОЛОВОК КАПСОМ>\n"
                    + "2) Пустая строка, затем РОВНО строка из 18 символов ━ (скопируй буквально: ━━━━━━━━━━━━━━━━━━), пустая строка.\n"
                    + "3) Вступление: 2-3 короткие строки — что за проект и зачем.\n"
                    + "4) Строка: ━━━━━━━━━━━━━━━━━━\n"
                    + "5) Блок \"🆕 Что нового:\" — 4-6 пунктов, каждый с эмодзи и дефисом, 1-2 предложения.\n"
                    + "6) Строка: ━━━━━━━━━━━━━━━━━━\n"
                    + "7) Блок \"📦 Что было ранее:\" — 2-3 пункта с эмодзи.\n"
                    + "8) Строка: ━━━━━━━━━━━━━━━━━━\n"
                    + "9) Ссылки: две строки (🌐 лендинг + 💻 github).\n"
                    + "10) Хэштеги одной строкой: #termux #android #ai #deepseek #opensource #программирование\n\n"
                    + "ЖЁСТКИЕ ТРЕБОВАНИЯ К СИМВОЛАМ:\n"
                    + "- Разделитель — ТОЛЬКО ━ (U+2501), ровно 18 штук, без пробелов. НЕ используй - = _ * и не сокращай.\n"
                    + "- Эмодзи обязательны в начале заголовка и в подзаголовках 🆕/📦. Не убирай и не заменяй их.\n"
                    + "- Не используй markdown (##, **, - без эмодзи). ВК не рендерит markdown.\n"
                    + "- Длина: 900-1300 знаков. Пиши развёрнуто, но без воды.\n\n"
                    + "ЗАПРЕЩЕНО: упоминать то, чего нет в фактах; пояснения от себя.\n"
                    + "Верни ТОЛЬКО готовый текст поста.\n\n"
                    + "ФАКТЫ О ПРОЕКТЕ:\n" + facts;
                if (statusLabel != null) statusLabel.setText("Отправляю в DeepSeek...");
                String escaped = org.json.JSONObject.quote(prompt);
                webView.evaluateJavascript("window.TermuxClearInput();", null);
                webView.evaluateJavascript("window.TermuxInsertText(" + escaped + ");", null);
                webView.postDelayed(new Runnable() {
                    @Override public void run() {
                        webView.evaluateJavascript("window.TermuxFindAndClick();", null);
                    }
                }, 700);
                vkWaitReply(0);
            }
        });
    }

    private String vkLastReply = "";
    private int vkStable = 0;

    private void vkWaitReply(final int attempt) {
        if (attempt > 80) {
            if (statusLabel != null) statusLabel.setText("Таймаут генерации (4 мин)");
            return;
        }
        if (statusLabel != null) statusLabel.setText("Ждём ответа DeepSeek... (" + (attempt * 3) + " сек)");
        webView.postDelayed(new Runnable() {
            @Override public void run() {
                webView.evaluateJavascript("window.TermuxReadPost();",
                    new android.webkit.ValueCallback<String>() {
                    @Override public void onReceiveValue(String value) {
                        String reply = unescapeJs(value);
                        if (reply == null) reply = "";
                        reply = reply.trim();
                        if (reply.length() < 300) {
                            vkWaitReply(attempt + 1);
                            return;
                        }
                        if (reply.equals(vkLastReply)) {
                            vkStable++;
                        } else {
                            vkStable = 0;
                            vkLastReply = reply;
                        }
                        if (vkStable >= 3 && attempt >= 8) {
                            vkShowPreview(reply);
                            return;
                        }
                        vkWaitReply(attempt + 1);
                    }
                });
            }
        }, 3000);
    }

    private void vkShowPreview(final String text) {
        if (statusLabel != null) statusLabel.setText("Пост готов — проверь превью");
        final android.widget.EditText et = new android.widget.EditText(this);
        et.setText(text);
        et.setMinLines(10);
        et.setGravity(android.view.Gravity.TOP);
        int pad = (int)(16 * getResources().getDisplayMetrics().density);
        et.setPadding(pad, pad, pad, pad);
        new AlertDialog.Builder(this)
            .setTitle("Пост от DeepSeek (" + text.length() + " симв.)")
            .setView(et)
            .setPositiveButton("Опубликовать", new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface d, int w) {
                    vkPublish(et.getText().toString().trim());
                }
            })
            .setNeutralButton("Перегенерировать", new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface d, int w) { vkGenerate(); }
            })
            .setNegativeButton("Отмена", null)
            .show();
    }

    private void updateKbdButton(Button btn, boolean locked) {
        if (btn == null) return;
        if (locked) {
            btn.setText("⌨");
            btn.setAlpha(0.5f);
        } else {
            btn.setText("⌨️");
            btn.setAlpha(1.0f);
        }
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

                logFreeResult(taskText, status, rc, elapsed, output);

                // Настоящая HTTP-ошибка: соединения нет, или сервер вернул мусор
                boolean httpErr = "error".equals(status) && (output == null || output.isEmpty()
                    || output.startsWith("HTTP:") || output.startsWith("(ошибка"));
                if (httpErr) {
                    String err = (output == null || output.isEmpty()) ? "Пусто (rc=" + rc + ")" : output;
                    if (statusLabel != null) statusLabel.setText("\u2717 \u041E\u0442\u043F\u0440\u0430\u0432\u0438\u043B \u0432 \u0447\u0430\u0442 \u0434\u043B\u044F \u0440\u0430\u0437\u0431\u043E\u0440\u0430");
                    sendErrorToChat(taskText, status, rc, err);
                    setActiveMode("none");
                    return;
                }

                if (isErrorResult(taskText, status, rc, output)) {
                    if (statusLabel != null) statusLabel.setText("\u2717 \u041E\u0448\u0438\u0431\u043A\u0430 \u2014 \u043E\u0442\u043F\u0440\u0430\u0432\u0438\u043B \u0432 \u0447\u0430\u0442");
                    sendErrorToChat(taskText, status, rc, output);
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

    private static final String FREE_LOG_DIR = "/sdcard/ai-tasker/logs";
    private static final String FREE_LOG_FILE = FREE_LOG_DIR + "/free.log";

    private void logFreeResult(final String task, final String status, final int rc, final double elapsed, final String output) {
        try {
            org.json.JSONObject o = new org.json.JSONObject();
            o.put("task", task);
            o.put("status", status);
            o.put("rc", rc);
            o.put("elapsed", elapsed);
            o.put("output", output == null ? "(null)" : output);
            final String payload = o.toString();
            new Thread(new Runnable() {
                @Override public void run() {
                    java.net.HttpURLConnection c = null;
                    try {
                        java.net.URL u = new java.net.URL("http://127.0.0.1:8767/task");
                        c = (java.net.HttpURLConnection) u.openConnection();
                        c.setRequestMethod("POST");
                        c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                        c.setDoOutput(true);
                        c.setConnectTimeout(3000);
                        c.setReadTimeout(5000);
                        org.json.JSONObject req = new org.json.JSONObject();
                        req.put("task", "log:" + payload);
                        byte[] b = req.toString().getBytes("UTF-8");
                        c.setFixedLengthStreamingMode(b.length);
                        java.io.OutputStream os = c.getOutputStream();
                        os.write(b);
                        os.close();
                        c.getResponseCode();
                    } catch (Exception e) {
                        android.util.Log.w("FreeLog", "http log fail: " + e.getMessage());
                    } finally {
                        if (c != null) c.disconnect();
                    }
                }
            }).start();
        } catch (Exception e) {
            android.util.Log.w("FreeLog", "log pack fail: " + e.getMessage());
        }
    }

    private boolean isErrorResult(final String task, final String status, final int rc, final String output) {
        // HTTP-ошибка: соединение не удалось
        if ("error".equals(status) && (output == null || output.isEmpty()
            || output.startsWith("HTTP:") || output.startsWith("(ошибка"))) return true;

        if (rc == 0 || rc == -1) return false;

        // rc=1 — самая частая ложь: grep -c/find без совпадений, wc пустого файла, diff одинаковых.
        if (rc == 1) {
            String t = task == null ? "" : task.trim();
            // первое слово команды (учитывая auto:)
            if (t.startsWith("auto:")) t = t.substring(5).trim();
            String first = t.split("\\s+", 2)[0];
            // убираем префиксы путей
            int slash = first.lastIndexOf('/');
            if (slash >= 0 && slash + 1 < first.length()) first = first.substring(slash + 1);
            String[] soft = {"grep","egrep","fgrep","rg","find","wc","diff","test","[","kill",
                             "wait","jobs","basename","dirname","sort","uniq","tee","true"};
            for (String c : soft) {
                if (first.equals(c)) {
                    // если в выводе явная ошибка — всё равно ошибка
                    if (output != null && (output.contains("Traceback")
                        || output.contains("command not found")
                        || output.contains("Permission denied")
                        || output.contains("syntax error"))) return true;
                    return false;
                }
            }
        }

        // rc>=2 или rc=1 от «настоящей» команды — ошибка
        return true;
    }

    private void sendErrorToChat(final String task, final String status, final int rc, final String output) {
        String body = output == null ? "(null)" : output;
        if (body.length() > 3000) body = body.substring(0, 3000) + "\n...[обрезано]";
        String msg = "\u26A0\uFE0F Free-задача упала.\n"
            + "Задача: " + task + "\n"
            + "status=" + status + " rc=" + rc + "\n"
            + "Вывод:\n```\n" + body + "\n```\n"
            + "Пожалуйста, разбери ошибку и предложи исправление.";
        final String escaped = org.json.JSONObject.quote(msg);
        webView.evaluateJavascript("window.TermuxClearInput();", null);
        webView.evaluateJavascript("window.TermuxInsertText(" + escaped + ");", null);
        webView.postDelayed(new Runnable() {
            @Override public void run() {
                webView.evaluateJavascript("window.TermuxFindAndClick();", null);
            }
        }, 700);
    }

    // ================= VIBE-CODING =================
    // Создание/развитие проектов в ~/vibe/ через DeepSeek.
    // Агент сам решает, какие файлы создать/изменить.

    private String currentVibeProject = null;
    private String currentVibeTask = null;
    private int vibeIteration = 0;
    private static final int VIBE_MAX_ITER = 5;
    private long vibeStartedAt = 0;

    private void startVibeDialog() {
        if (statusLabel != null) statusLabel.setText("Загружаю проекты...");
        httpTask("project_list:", new HttpCallback() {
            @Override public void onResult(String status, String output, int rc, double elapsed) {
                if (output == null) output = "";
                final String listOut = output.trim();
                final java.util.ArrayList<String> names = new java.util.ArrayList<String>();
                if (!listOut.startsWith("(нет")) {
                    for (String line : listOut.split("\n")) {
                        line = line.trim();
                        if (line.isEmpty()) continue;
                        int sp = line.indexOf("  ");
                        String n = (sp > 0) ? line.substring(0, sp) : line;
                        names.add(n);
                    }
                }
                names.add("➕ Создать новый проект");

                new AlertDialog.Builder(MainActivity.this)
                    .setTitle("🆕 Vibe-проект")
                    .setItems(names.toArray(new String[0]), new DialogInterface.OnClickListener() {
                        @Override public void onClick(DialogInterface d, int which) {
                            String picked = names.get(which);
                            if (picked.startsWith("➕")) {
                                vibeCreateDialog();
                            } else {
                                currentVibeProject = picked;
                                toast("Проект: " + picked);
                                if (statusLabel != null) statusLabel.setText("Проект: " + picked + " — введи задачу и жми 🆕 Vibe");
                                setActiveMode("none");
                            }
                        }
                    })
                    .setNegativeButton("Отмена", new DialogInterface.OnClickListener() {
                        @Override public void onClick(DialogInterface d, int w) { setActiveMode("none"); }
                    })
                    .show();
            }
        });
    }

    private void vibeCreateDialog() {
        final android.widget.EditText et = new android.widget.EditText(this);
        et.setHint("имя проекта (a-z, 0-9, _)");
        et.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
        new AlertDialog.Builder(this)
            .setTitle("Новый проект")
            .setMessage("Будет создан в ~/vibe/<имя> из шаблона android-java")
            .setView(et)
            .setPositiveButton("Создать", new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface d, int w) {
                    String name = et.getText().toString().trim();
                    if (name.isEmpty()) { toast("Пустое имя"); setActiveMode("none"); return; }
                    httpTask("project_new:" + name + ":android-java", new HttpCallback() {
                        @Override public void onResult(String status, String output, int rc, double elapsed) {
                            if (rc == 0) {
                                currentVibeProject = name;
                                toast("Создан: " + name);
                                if (statusLabel != null) statusLabel.setText("Проект: " + name + " — введи задачу и жми 🆕 Vibe");
                            } else {
                                toast("Ошибка: " + output);
                            }
                            setActiveMode("none");
                        }
                    });
                }
            })
            .setNegativeButton("Отмена", new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface d, int w) { setActiveMode("none"); }
            })
            .show();
    }

    private void vibeRun() {
        if (currentVibeProject == null || currentVibeProject.isEmpty()) {
            startVibeDialog();
            return;
        }
        webView.evaluateJavascript("window.TermuxGetInput();",
            new android.webkit.ValueCallback<String>() {
            @Override public void onReceiveValue(String value) {
                String text = unescapeJs(value);
                if (text == null || text.trim().isEmpty()) {
                    text = readClipboard();
                }
                if (text == null || text.trim().isEmpty()) {
                    toast("Введи задачу в поле");
                    return;
                }
                currentVibeTask = text.trim();
                vibeIteration = 0;
                vibeStartedAt = System.currentTimeMillis();
                setActiveMode("dev");
                vibeStep();
            }
        });
    }

    private void vibeStep() {
        if (vibeIteration >= VIBE_MAX_ITER) {
            if (statusLabel != null) statusLabel.setText("Vibe: лимит итераций (" + VIBE_MAX_ITER + ")");
            setActiveMode("none");
            return;
        }
        if (System.currentTimeMillis() - vibeStartedAt > 10 * 60 * 1000) {
            if (statusLabel != null) statusLabel.setText("Vibe: таймаут 10 мин");
            setActiveMode("none");
            return;
        }
        vibeIteration++;
        if (statusLabel != null) statusLabel.setText("Vibe: итерация " + vibeIteration + " — читаю проект...");

        httpTask("project_tree:" + currentVibeProject, new HttpCallback() {
            @Override public void onResult(String status, String tree, int rc, double elapsed) {
                if (rc != 0) {
                    toast("Не могу прочитать проект: " + tree);
                    setActiveMode("none");
                    return;
                }
                String prompt = vibeBuildPrompt(currentVibeProject, tree, currentVibeTask, vibeIteration);
                webView.evaluateJavascript("window.TermuxClearInput();", null);
                webView.evaluateJavascript("window.TermuxInsertText(" + org.json.JSONObject.quote(prompt) + ");", null);
                webView.postDelayed(new Runnable() {
                    @Override public void run() {
                        webView.evaluateJavascript("window.TermuxFindAndClick();", null);
                    }
                }, 700);
                if (statusLabel != null) statusLabel.setText("Vibe " + vibeIteration + ": жду ответ...");
                vibeWaitReply(0);
            }
        });
    }

    private String vibeBuildPrompt(String project, String tree, String task, int iteration) {
        StringBuilder b = new StringBuilder();
        b.append("Ты — автономный программист. Работаешь над Android-проектом на Java.\n\n");
        b.append("СТРУКТУРА ПРОЕКТА (папка ~/vibe/").append(project).append("/):\n");
        b.append(tree).append("\n\n");
        b.append("СБОРКА: bash build.sh (aapt2 + javac + d8 + apksigner, в Termux)\n\n");
        if (iteration > 1) {
            b.append("ЭТО ИТЕРАЦИЯ #").append(iteration).append(" — предыдущая сборка упала.\n");
            b.append("Исправь ошибки и верни ТОЛЬКО те файлы, которые нужно изменить.\n\n");
        }
        b.append("ЗАДАЧА:\n").append(task).append("\n\n");
        b.append("ФОРМАТ ОТВЕТА (строго!):\n");
        b.append("- Верни ТОЛЬКО файлы, которые нужно создать или изменить.\n");
        b.append("- Каждый файл — блоком:\n\n");
        b.append("=== FILE: <путь относительно корня проекта> ===\n");
        b.append("```java\n<полное содержимое файла>\n```\n\n");
        b.append("- Если файл нужно удалить: === DELETE: <путь> ===\n\n");
        b.append("ПРАВИЛА:\n");
        b.append("- Не объясняй. Только блоки.\n");
        b.append("- Возвращай ПОЛНОЕ содержимое каждого указанного файла (не diff).\n");
        b.append("- Пиши компилируемый Java 8 (aapt2/javac/d8 из Termux).\n");
        b.append("- R-класс генерируется aapt2 в build/gen — не создавай его вручную.\n");
        b.append("- Не создавай .bak, patch.py и прочий мусор в проекте.\n");
        b.append("- package в Java-файлах должен соответствовать пути: src/a/b/C.java -> package a.b;\n");
        return b.toString();
    }

    private String vibeLastReply = "";
    private int vibeStable = 0;

    private void vibeWaitReply(final int attempt) {
        if (attempt > 60) {
            if (statusLabel != null) statusLabel.setText("Vibe: таймаут ответа");
            setActiveMode("none");
            return;
        }
        webView.postDelayed(new Runnable() {
            @Override public void run() {
                webView.evaluateJavascript("window.TermuxReadPost();",
                    new android.webkit.ValueCallback<String>() {
                    @Override public void onReceiveValue(String value) {
                        String reply = unescapeJs(value);
                        if (reply == null) reply = "";
                        reply = reply.trim();
                        if (reply.length() < 40) { vibeWaitReply(attempt + 1); return; }
                        if (reply.equals(vibeLastReply)) vibeStable++;
                        else { vibeStable = 0; vibeLastReply = reply; }
                        if (vibeStable >= 3 && attempt >= 4) {
                            vibeHandleReply(reply);
                            return;
                        }
                        vibeWaitReply(attempt + 1);
                    }
                });
            }
        }, 3000);
    }

    private void vibeHandleReply(String reply) {
        vibeLastReply = "";
        vibeStable = 0;
        java.util.List<VibeOp> ops = vibeParseBlocks(reply);
        if (ops.isEmpty()) {
            if (statusLabel != null) statusLabel.setText("Vibe: не распарсил ответ");
            new AlertDialog.Builder(MainActivity.this)
                .setTitle("Не распарсил ответ агента")
                .setMessage(reply.length() > 2000 ? reply.substring(0, 2000) + "..." : reply)
                .setPositiveButton("OK", null).show();
            setActiveMode("none");
            return;
        }
        vibeShowPreview(ops);
    }

    private static class VibeOp {
        String kind;   // "write" | "delete"
        String path;
        String content;
    }

    private java.util.List<VibeOp> vibeParseBlocks(String reply) {
        java.util.List<VibeOp> ops = new java.util.ArrayList<VibeOp>();
        java.util.regex.Pattern pFile = java.util.regex.Pattern.compile(
            "===\\s*FILE:\\s*(\\S+?)\\s*===\\s*\\n```[a-zA-Z]*\\n([\\s\\S]*?)\\n```",
            java.util.regex.Pattern.MULTILINE);
        java.util.regex.Matcher m = pFile.matcher(reply);
        while (m.find()) {
            VibeOp op = new VibeOp();
            op.kind = "write";
            op.path = m.group(1).trim();
            op.content = m.group(2);
            ops.add(op);
        }
        java.util.regex.Pattern pDel = java.util.regex.Pattern.compile(
            "===\\s*DELETE:\\s*(\\S+?)\\s*===",
            java.util.regex.Pattern.MULTILINE);
        java.util.regex.Matcher md = pDel.matcher(reply);
        while (md.find()) {
            VibeOp op = new VibeOp();
            op.kind = "delete";
            op.path = md.group(1).trim();
            ops.add(op);
        }
        return ops;
    }

    private void vibeShowPreview(final java.util.List<VibeOp> ops) {
        StringBuilder sb = new StringBuilder();
        sb.append("Агент вернул ").append(ops.size()).append(" операций:\n\n");
        for (VibeOp op : ops) {
            if ("write".equals(op.kind)) {
                int sz = op.content == null ? 0 : op.content.getBytes().length;
                sb.append("✏️ ").append(op.path).append("  (").append(sz).append(" b)\n");
            } else {
                sb.append("🗑 ").append(op.path).append("\n");
            }
        }
        new AlertDialog.Builder(this)
            .setTitle("Применить?")
            .setMessage(sb.toString())
            .setPositiveButton("Применить и собрать", new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface d, int w) {
                    vibeApplyAndBuild(ops, 0);
                }
            })
            .setNegativeButton("Отмена", new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface d, int w) { setActiveMode("none"); }
            })
            .show();
    }

    private void vibeApplyAndBuild(final java.util.List<VibeOp> ops, final int idx) {
        if (idx >= ops.size()) {
            if (statusLabel != null) statusLabel.setText("Vibe: собираю APK...");
            httpTask("project_build:" + currentVibeProject, new HttpCallback() {
                @Override public void onResult(String status, String output, int rc, double elapsed) {
                    if (rc == 0) {
                        String apk = "";
                        int ai = output.lastIndexOf("APK:");
                        if (ai >= 0) {
                            int nl = output.indexOf('\n', ai);
                            apk = (nl > 0 ? output.substring(ai + 4, nl) : output.substring(ai + 4)).trim();
                        }
                        if (statusLabel != null) statusLabel.setText("✅ Готово: " + apk);
                        new AlertDialog.Builder(MainActivity.this)
                            .setTitle("✅ Собрано")
                            .setMessage("APK: " + apk)
                            .setPositiveButton("OK", null).show();
                        setActiveMode("none");
                    } else {
                        if (statusLabel != null) statusLabel.setText("Vibe: сборка упала, отправляю агенту...");
                        vibeSendErrorToChat(output);
                    }
                }
            });
            return;
        }

        VibeOp op = ops.get(idx);
        if ("delete".equals(op.kind)) {
            httpTask("project_delete:" + currentVibeProject + ":" + op.path, new HttpCallback() {
                @Override public void onResult(String status, String output, int rc, double el) {
                    vibeApplyAndBuild(ops, idx + 1);
                }
            });
        } else {
            String b64;
            try {
                b64 = android.util.Base64.encodeToString(
                    op.content.getBytes("UTF-8"), android.util.Base64.NO_WRAP);
            } catch (Exception e) {
                b64 = "";
            }
            httpTask("project_write:" + currentVibeProject + ":" + op.path + ":" + b64,
                new HttpCallback() {
                @Override public void onResult(String status, String output, int rc, double el) {
                    vibeApplyAndBuild(ops, idx + 1);
                }
            });
        }
    }

    private void vibeSendErrorToChat(final String buildOutput) {
        String body = buildOutput == null ? "" : buildOutput;
        if (body.length() > 3000) body = body.substring(0, 3000) + "\n...[обрезано]";
        // Обрезаем вводную часть (=== шаги ===) — оставляем ошибки
        int errPos = body.indexOf("error:");
        if (errPos > 200) body = body.substring(Math.max(0, errPos - 300));
        String msg = "Сборка упала.\n\nОшибка:\n```\n" + body + "\n```\n\n"
            + "Исправь. Верни файлы в том же формате (=== FILE: ... ===).";
        webView.evaluateJavascript("window.TermuxClearInput();", null);
        webView.evaluateJavascript("window.TermuxInsertText(" + org.json.JSONObject.quote(msg) + ");", null);
        webView.postDelayed(new Runnable() {
            @Override public void run() {
                webView.evaluateJavascript("window.TermuxFindAndClick();", null);
            }
        }, 700);
        // Через 3 сек — следующая итерация
        webView.postDelayed(new Runnable() {
            @Override public void run() { vibeStep(); }
        }, 3000);
    }

    // ============ /VIBE-CODING ============

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
        + "try{"
        + "var tas=document.querySelectorAll('textarea');"
        + "var ta=null;"
        + "for(var i=0;i<tas.length;i++){"
        + "if(tas[i].offsetParent!==null&&(tas[i].value||'').trim().length>0){ta=tas[i];break;}"
        + "}"
        + "if(!ta)return 'no_ta_nonempty';"
        + "var taR=ta.getBoundingClientRect();"
        // Найдём ближайший общий контейнер — родитель, содержащий и textarea, и кнопку
        + "var parent=ta;"
        + "for(var k=0;k<8&&parent;k++){"
        + "parent=parent.parentElement;"
        + "if(!parent)break;"
        + "if(parent.querySelectorAll('[role=button],button').length>0&&parent.getBoundingClientRect().height<400)break;"
        + "}"
        + "if(!parent)parent=document.body;"
        // Ищем кнопки ТОЛЬКО внутри этого контейнера
        + "var all=parent.querySelectorAll('[role=button],button');"
        + "var best=null,bestRight=-1;"
        + "for(var i=0;i<all.length;i++){"
        + "var b=all[i];"
        + "if(b.getAttribute('aria-disabled')==='true')continue;"
        + "if(b.disabled)continue;"
        + "if(b.offsetParent===null)continue;"
        + "var r=b.getBoundingClientRect();"
        + "if(r.width<20||r.width>80)continue;"
        + "if(r.height<20||r.height>80)continue;"
        + "if(r.left<taR.left-30)continue;"
        + "if(r.top<taR.top-30)continue;"
        + "if(r.top>taR.bottom+150)continue;"
        + "if(!b.querySelector('svg'))continue;"
        // Дополнительно: кнопка не должна быть модалкой/диалогом
        + "var inDialog=false;"
        + "var p=b;"
        + "for(var q=0;q<6&&p;q++){"
        + "p=p.parentElement;"
        + "if(!p)break;"
        + "var role=p.getAttribute&&p.getAttribute('role');"
        + "if(role==='dialog'||role==='alertdialog'||role==='menu'){inDialog=true;break;}"
        + "}"
        + "if(inDialog)continue;"
        + "if(r.right>bestRight){bestRight=r.right;best=b;}"
        + "}"
        + "if(!best)return 'no_btn';"
        // Клик через React-обработчик, иначе fallback
        + "try{"
        + "best.scrollIntoView({block:'center'});"
        + "var r2=best.getBoundingClientRect();"
        + "var opts={bubbles:true,cancelable:true,view:window,clientX:r2.left+r2.width/2,clientY:r2.top+r2.height/2,button:0};"
        + "best.dispatchEvent(new MouseEvent('mousedown',opts));"
        + "best.dispatchEvent(new MouseEvent('mouseup',opts));"
        + "best.dispatchEvent(new MouseEvent('click',opts));"
        + "return 'me:'+(best.getAttribute('aria-label')||'')+'|'+(best.className||'').slice(0,50);"
        + "}catch(e){return 'err2:'+e;}"
        + "}catch(e){return 'err:'+e;}"
        + "};"
        + "window.TermuxReadPost = function(){"
        + "try{"
        + "var all=document.querySelectorAll('[class*=markdown],[class*=message]');"
        + "var best=null;"
        + "for(var i=0;i<all.length;i++){"
        + "var t=(all[i].innerText||'').trim();"
        + "if(t.length<400)continue;"
        + "if(t.length>3000)continue;"
        + "if(t.indexOf('package com.termux')>=0)continue;"
        + "if(t.indexOf('Thought for')===0)continue;"
        + "if(t.indexOf('AI-generated')>=0)continue;"
        + "if(t.indexOf('Message DeepSeek')>=0)continue;"
        + "if(t.indexOf('Search')===0&&t.length<500)continue;"
        + "if(t.indexOf('Ты — SMM')>=0)continue;"
        + "if(t.indexOf('SMM-редактор')>=0)continue;"
        + "if(t.indexOf('ФАКТЫ О ПРОЕКТЕ')>=0)continue;"
        + "if(t.indexOf('НЕ упоминай то')>=0)continue;"
        + "if(t.indexOf('Верни ТОЛЬКО')>=0)continue;"
        + "if(t.indexOf('We need to')>=0)continue;"
        + "if(t.indexOf('We should')>=0)continue;"
        + "if(t.indexOf('Need to write')>=0)continue;"
        + "if(t.indexOf('Let me')>=0)continue;"
        + "if(t.indexOf('I need to')>=0)continue;"
        + "if(t.indexOf('I will write')>=0)continue;"
        + "if(t.indexOf('Based on the facts')>=0)continue;"
        + "best=t;"
        + "}"
        + "return best||'';"
        + "}catch(e){return 'err:'+e;}"
        + "};"
        + "window.TermuxSetKbdLocked = function(locked){"
        + "try{"
        + "var tas=document.querySelectorAll('textarea');"
        + "for(var i=0;i<tas.length;i++){"
        + "if(locked){"
        + "tas[i].setAttribute('readonly','readonly');"
        + "tas[i].setAttribute('inputmode','none');"
        + "}else{"
        + "tas[i].removeAttribute('readonly');"
        + "tas[i].removeAttribute('inputmode');"
        + "}"
        + "}"
        + "if(locked){"
        + "var active=document.activeElement;"
        + "if(active&&active.blur)active.blur();"
        + "}"
        + "return 'ok';"
        + "}catch(e){return 'err:'+e;}"
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
        + "};"
        + "window.TermuxReadSummary = function(){"
        + "try{"
        + "var all=document.querySelectorAll('[class*=markdown]');"
        + "var best=null;"
        + "for(var i=0;i<all.length;i++){"
        + "var r=all[i].getBoundingClientRect();"
        + "if(r.width<50)continue;"
        + "var t=(all[i].innerText||'').trim();"
        + "if(t.length<200)continue;"
        + "if(t.length>20000)continue;"
        + "if(t.indexOf('=== КОНТЕКСТ ДЛЯ ПРОДОЛЖЕНИЯ')<0)continue;"
        + "best=t;"
        + "}"
        + "return best||'';"
        + "}catch(e){return 'err:'+e;}"
        + "};"
        + "window.TermuxDetectContextError = function(){"
        + "try{"
        + "var all=document.querySelectorAll('div,span,p');"
        + "var markers=['сделайте сообщение короче','сообщение слишком','превышен','лимит контекста','context length','too long','message too long','exceeds the'];"
        + "var start=all.length-200;if(start<0)start=0;"
        + "for(var i=all.length-1;i>=start;i--){"
        + "var t=(all[i].innerText||'').trim();"
        + "if(t.length<10||t.length>300)continue;"
        + "var low=t.toLowerCase();"
        + "for(var m=0;m<markers.length;m++){"
        + "if(low.indexOf(markers[m])>=0){"
        + "var r=all[i].getBoundingClientRect();"
        + "if(r.width>50&&r.height>0&&r.height<200){return t.slice(0,200);}"
        + "}"
        + "}"
        + "}"
        + "return '';"
        + "}catch(e){return 'err:'+e;}"
        + "};"
        + "window.TermuxNewChat = function(){"
        + "try{"
        + "var links=document.querySelectorAll('a,button,[role=button]');"
        + "for(var i=0;i<links.length;i++){"
        + "var el=links[i];"
        + "var lbl=String(el.getAttribute('aria-label')||'')+' '+String(el.title||'');"
        + "var low=lbl.toLowerCase();"
        + "if(low.indexOf('new chat')>=0||low.indexOf('новый чат')>=0){"
        + "el.click();"
        + "return 'clicked:'+lbl.slice(0,50);"
        + "}"
        + "}"
        + "location.href='https://chat.deepseek.com/';"
        + "return 'navigated';"
        + "}catch(e){return 'err:'+e;}"
        + "};"
        + "window.TermuxCopyFirstBash = function(){"
        + "try{"
        + "var bashLangs=['bash','shell','sh','zsh'];"
        + "var execLangs=['python','python3','py','node','nodejs','js','javascript','perl','ruby','php','powershell','ps1','lua','r'];"
        + "function langOf(el){"
        + "var cn=(el.className||'').toString().toLowerCase();"
        + "var m=cn.match(/language-([a-z0-9]+)/);"
        + "if(m)return m[1];"
        + "var p=el.parentElement;"
        + "if(p){var cn2=(p.className||'').toString().toLowerCase();var m2=cn2.match(/language-([a-z0-9]+)/);if(m2)return m2[1];}"
        + "return '';"
        + "}"
        + "function isBash(l){for(var i=0;i<bashLangs.length;i++){if(l===bashLangs[i])return true;}return false;}"
        + "function isExec(l){for(var i=0;i<execLangs.length;i++){if(l===execLangs[i])return true;}return false;}"
        + "function pickFrom(root){"
        + "var bashTxt=null, execTxt=null, anyTxt=null;"
        + "var pres=root.querySelectorAll('pre');"
        + "for(var i=0;i<pres.length;i++){"
        + "var pre=pres[i];"
        + "var lang='';"
        + "var codeEl=pre.querySelector('code');"
        + "if(codeEl)lang=langOf(codeEl);"
        + "if(!lang)lang=langOf(pre);"
        + "var txt=pre.textContent||pre.innerText||'';"
        + "if(!txt)continue;"
        + "if(isBash(lang))bashTxt=txt;"
        + "else if(isExec(lang))execTxt=txt;"
        + "else if(!lang)anyTxt=txt;"
        + "}"
        + "return {b:bashTxt,e:execTxt,a:anyTxt};"
        + "}"
        + "var r1=pickFrom(document);"
        + "if(r1.b)return r1.b;"
        + "if(r1.e)return r1.e;"
        + "var blocks=document.querySelectorAll('[class*=markdown]');"
        + "var last=null;"
        + "for(var i=0;i<blocks.length;i++){var r=blocks[i].getBoundingClientRect();if(r.width<100)continue;last=blocks[i];}"
        + "if(last){var r2=pickFrom(last);if(r2.b)return r2.b;if(r2.e)return r2.e;}"
        + "if(r1.a)return r1.a;"
        + "return 'no_exec_code: pres='+document.querySelectorAll('pre').length;"
        + "}catch(e){return 'err:'+e;}"
                + "};"
        + "window.TermuxHideThinking = function(){"
        + "try{"
        + "var out=0;"
        + "var all=document.querySelectorAll('div');"
        + "for(var k=0;k<all.length;k++){"
        + "var e=all[k];"
        + "var t=(e.textContent||'').trim();"
        + "if(!(t.indexOf('Thought for')===0 || t.indexOf('Размышлял')===0 || t.indexOf('Reasoning')===0))continue;"
        + "if(t.length>50)continue;"
        + "var par=e.parentElement;"
        + "if(!par)continue;"
        + "if(par.children.length<2)continue;"
        + "par.style.display='none';"
        + "par.setAttribute('data-tx-hidden','1');"
        + "out++;"
        + "}"
        + "return 'hidden:'+out;"
        + "}catch(e){return 'err:'+e;}"
        + "};"
        + "window.__termuxHideThinkingLoop=function(){"
        + "if(window.__txObserver)return 'already';"
        + "try{"
        + "var cb=function(){"
        + "if(window.__txPending)return;"
        + "window.__txPending=true;"
        + "Promise.resolve().then(function(){"
        + "window.__txPending=false;"
        + "try{window.TermuxHideThinking();}catch(e){}"
        + "});"
        + "};"
        + "window.__txObserver=new MutationObserver(cb);"
        + "window.__txObserver.observe(document.body,{childList:true,subtree:true,characterData:true});"
        + "window.TermuxHideThinking();"
        + "return 'observer_started';"
        + "}catch(e){return 'err:'+e;}"
        + "};"
        + "window.__termuxShowThinking=function(){"
        + "if(window.__txHideInt){clearInterval(window.__txHideInt);window.__txHideInt=null;}"
        + "if(window.__txObserver){try{window.__txObserver.disconnect();}catch(x){}window.__txObserver=null;}"
        + "var all=document.querySelectorAll('[data-tx-hidden]');"
        + "for(var i=0;i<all.length;i++){all[i].style.display='';all[i].removeAttribute('data-tx-hidden');}"
        + "return 'shown:'+all.length;"
        + "};"
        + "";
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