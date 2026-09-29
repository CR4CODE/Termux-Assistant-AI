// test-pipeline-ok
package com.termux.assistant;


import android.app.Activity;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.os.Build;
import android.content.Intent;
import android.os.Bundle;import android.content.SharedPreferences;
import android.provider.Settings;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;import android.speech.RecognizerIntent;import android.speech.SpeechRecognizer;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class MainActivity extends Activity {

    private static final String ROOT_DIR = "/sdcard/ai-tasker";
    private static final String INBOX = ROOT_DIR + "/inbox";
    private static final String OUTBOX = ROOT_DIR + "/outbox";

    private EditText inputTask;
    private Button btnRun;
    private Button btnSettings;
    private ListView listTasks;
    private TextView emptyHistory;
    private TextView envStatus;

    private final List<TaskItem> tasks = new ArrayList<>();
    private String lastAppliedTheme = null;
    private TaskAdapter adapter;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable refreshRunnable;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);        SharedPreferences tp = getSharedPreferences("app_prefs", MODE_PRIVATE);        String th = tp.getString("theme", "system");        if ("light".equals(th)) setTheme(R.style.AppTheme_Light);        else if ("dark".equals(th)) setTheme(R.style.AppTheme_Dark);
        // Проверка первого запуска → Welcome wizard
        android.content.SharedPreferences _wp = getSharedPreferences("app_prefs", MODE_PRIVATE);
        if (!_wp.getBoolean("welcome_done", false)) {
            startActivity(new Intent(MainActivity.this, WelcomeActivity.class));
        }

        setContentView(R.layout.activity_main);

        View btnMicView = findViewById(R.id.btn_mic);
        if (btnMicView != null) {
            btnMicView.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    startVoiceInput();
                }
            });
        }

        inputTask = findViewById(R.id.input_task);
        btnRun = findViewById(R.id.btn_run);
        btnSettings = findViewById(R.id.btn_settings);
        Button btnOpenHistory = findViewById(R.id.btn_open_history);
        if (btnOpenHistory != null) {
            btnOpenHistory.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    startActivity(new Intent(MainActivity.this, HistoryActivity.class));
                }
            });
        }
        listTasks = null; // moved to HistoryActivity
        emptyHistory = null; // moved to HistoryActivity
        envStatus = findViewById(R.id.env_status);

        adapter = new TaskAdapter(this, tasks);
        if (listTasks != null) listTasks.setAdapter(adapter);

        btnRun.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { submitTask(); }
        });
        btnSettings.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Intent i = new Intent(MainActivity.this, SettingsActivity.class);
                startActivity(i);
            }
        });

        if (listTasks != null) listTasks.setOnItemClickListener(new android.widget.AdapterView.OnItemClickListener() {
            @Override public void onItemClick(android.widget.AdapterView<?> p, View v, int pos, long id) {
                showTaskDialog(tasks.get(pos));
            }
        });

        // Долгий тап = удалить задачу
        if (listTasks != null) listTasks.setOnItemLongClickListener(new android.widget.AdapterView.OnItemLongClickListener() {
            @Override public boolean onItemLongClick(android.widget.AdapterView<?> p, View v, final int pos, long id) {
                final TaskItem t = tasks.get(pos);
                new android.app.AlertDialog.Builder(MainActivity.this)
                    .setTitle("Удалить задачу?")
                    .setMessage(t.task)
                    .setPositiveButton("Удалить", new android.content.DialogInterface.OnClickListener() {
                        @Override public void onClick(android.content.DialogInterface d, int w) {
                            deleteTask(t);
                        }
                    })
                    .setNegativeButton("Отмена", null)
                    .show();
                return true;
            }
        });

        // Кнопка "Свободный режим"
        Button btnFree = findViewById(R.id.btn_free_mode);
        if (btnFree != null) {
            btnFree.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    toggleFreeMode();
                }
            });
        }

        ensureDirs();
        requestAllPermissions();
        initNotificationChannel();
        requestAllFilesPermissionIfNeeded();
        updateEnvStatus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Проверяем, не сменилась ли тема
        android.content.SharedPreferences _p = getSharedPreferences("app_prefs", MODE_PRIVATE);
        String _t = _p.getString("theme", "system");
        if (lastAppliedTheme != null && !lastAppliedTheme.equals(_t)) {
            lastAppliedTheme = _t;
            recreate();
            return;
        }
        lastAppliedTheme = _t;
        startAutoRefresh();
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopAutoRefresh();
    }

    private void startAutoRefresh() {
        refreshRunnable = new Runnable() {
            @Override public void run() {
                loadTasks();
                updateEnvStatus();
                handler.postDelayed(this, 3000);
            }
        };
        handler.post(refreshRunnable);
    }

    private void stopAutoRefresh() {
        if (refreshRunnable != null) handler.removeCallbacks(refreshRunnable);
    }

    private void ensureDirs() {
        try {
            new File(INBOX).mkdirs();
            new File(OUTBOX).mkdirs();
        } catch (Exception e) {
            toast("Не могу создать /sdcard/ai-tasker: " + e.getMessage());
        }
    }

    private void requestAllFilesPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < 30) return;
        if (Environment.isExternalStorageManager()) return;

        try {
            Intent i = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
            i.setData(Uri.parse("package:" + getPackageName()));
            startActivity(i);
        } catch (Exception e) {
            try {
                Intent i = new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
                startActivity(i);
            } catch (Exception e2) {
                toast("Открой разрешения вручную: Настройки → Приложения → Termux Assistant AI");
            }
        }
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    private void submitTask() {
        String text = inputTask.getText().toString().trim();
        if (TextUtils.isEmpty(text)) {
            toast("Введи задачу");
            return;
        }

        String id = "t" + System.currentTimeMillis();
        File f = new File(INBOX, "task-" + id + ".txt");

        try {
            FileWriter w = new FileWriter(f);
            w.write(text);
            w.close();
            inputTask.setText("");
            toast("Задача отправлена");
            // Немедленно перечитаем
            handler.postDelayed(new Runnable() { @Override public void run() { loadTasks(); } }, 500);
        } catch (Exception e) {
            toast("Ошибка: " + e.getMessage());
        }
    }

    private void loadTasks() {
        File dir = new File(OUTBOX);
        if (!dir.exists()) return;

        File[] files = dir.listFiles(new java.io.FilenameFilter() {
            @Override public boolean accept(File d, String n) {
                return n.startsWith("task-") && n.endsWith(".json");
            }
        });
        if (files == null) return;

        List<TaskItem> fresh = new ArrayList<>();
        for (File f : files) {
            TaskItem t = parseTaskFile(f);
            if (t != null) fresh.add(t);
        }
        Collections.sort(fresh, new java.util.Comparator<TaskItem>() {
            @Override public int compare(TaskItem a, TaskItem b) { return Long.compare(b.started, a.started); }
        });

        tasks.clear();
        tasks.addAll(fresh);
        adapter.notifyDataSetChanged();

        for (TaskItem t : tasks) notifyIfNeeded(t);

        if (emptyHistory != null) emptyHistory.setVisibility(tasks.isEmpty() ? View.VISIBLE : View.GONE);
    }

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

    private void showTaskDialog(final TaskItem t) {
        try {
            android.app.AlertDialog.Builder b = new android.app.AlertDialog.Builder(this);
            View view = getLayoutInflater().inflate(R.layout.dialog_task, null);
            b.setView(view);

            TextView statusIcon = view.findViewById(R.id.dlg_status_icon);
            TextView statusText = view.findViewById(R.id.dlg_status_text);
            TextView durationTv = view.findViewById(R.id.dlg_duration);
            TextView taskTv = view.findViewById(R.id.dlg_task);
            TextView outputTv = view.findViewById(R.id.dlg_output);

            statusIcon.setText(t.statusLabel());
            statusIcon.setTextColor(t.statusColor());
            statusText.setText(t.statusText());
            statusText.setTextColor(t.statusColor());

            String dur = t.durationLabel();
            durationTv.setText(dur.length() > 0 ? dur : "");

            taskTv.setText(t.task);
            outputTv.setText(t.output != null && t.output.length() > 0 ? t.output : "(нет вывода)");

            final android.app.AlertDialog dialog = b.create();

            view.findViewById(R.id.dlg_retry).setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    retryTask(t);
                    dialog.dismiss();
                }
            });
            view.findViewById(R.id.dlg_copy).setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    copyToClipboard(t.output != null ? t.output : "");
                    toast("Скопировано");
                }
            });
            view.findViewById(R.id.dlg_delete).setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    deleteTask(t);
                    dialog.dismiss();
                }
            });

            dialog.show();
        } catch (Exception e) {
            toast("Ошибка диалога: " + e.getMessage());
        }
    }

    private void retryTask(TaskItem t) {
        try {
            String id = "t" + System.currentTimeMillis();
            File f = new File(INBOX, "task-" + id + ".txt");
            FileWriter w = new FileWriter(f);
            w.write(t.task);
            w.close();
            toast("Задача отправлена снова");
            handler.postDelayed(new Runnable() { @Override public void run() { loadTasks(); } }, 500);
        } catch (Exception e) {
            toast("Ошибка: " + e.getMessage());
        }
    }

    private void deleteTask(TaskItem t) {
        try {
            File f = new File(t.filePath);
            if (f.exists()) f.delete();
            toast("Удалено");
            loadTasks();
        } catch (Exception e) {
            toast("Ошибка удаления: " + e.getMessage());
        }
    }

    private void copyToClipboard(String text) {
        try {
            android.content.ClipboardManager cm = (android.content.ClipboardManager)
                getSystemService(CLIPBOARD_SERVICE);
            cm.setPrimaryClip(android.content.ClipData.newPlainText("tasker", text));
        } catch (Exception ignored) {}
    }

    private static final String CHANNEL_ID = "ai-tasker";

    private void initNotificationChannel() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        NotificationChannel ch = new NotificationChannel(
            CHANNEL_ID, "Задачи Termux Assistant AI", NotificationManager.IMPORTANCE_DEFAULT);
        ch.setDescription("Уведомления о завершении задач");
        nm.createNotificationChannel(ch);
    }

    private java.util.Set<String> notifiedIds = new java.util.HashSet<>();

    private void notifyIfNeeded(TaskItem t) {
        if (t == null || t.id == null) return;
        if (!"success".equals(t.status) && !"error".equals(t.status)) return;
        if (notifiedIds.contains(t.id)) return;
        notifiedIds.add(t.id);

        try {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            int icon = "success".equals(t.status) ? android.R.drawable.stat_sys_download_done
                                                  : android.R.drawable.stat_notify_error;

            android.app.Notification.Builder b;
            if (Build.VERSION.SDK_INT >= 26) {
                b = new android.app.Notification.Builder(this, CHANNEL_ID);
            } else {
                b = new android.app.Notification.Builder(this);
            }

            String title = "success".equals(t.status) ? "✓ Задача выполнена" : "✗ Задача с ошибкой";
            String text = t.task;
            if (text != null && text.length() > 60) text = text.substring(0, 60) + "…";

            b.setSmallIcon(icon)
             .setContentTitle(title)
             .setContentText(text)
             .setAutoCancel(true);

            Intent i = new Intent(this, MainActivity.class);
            i.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
            PendingIntent pi = PendingIntent.getActivity(this, 0, i,
                PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0));
            b.setContentIntent(pi);

            nm.notify(t.id.hashCode(), b.build());
        } catch (Exception ignored) {}
    }

    private void requestAllPermissions() {
        // 1. MANAGE_EXTERNAL_STORAGE (для /sdcard/ai-tasker)
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                if (!Environment.isExternalStorageManager()) {
                    Intent i = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                    i.setData(Uri.parse("package:" + getPackageName()));
                    startActivity(i);
                }
            } catch (Exception e) {
                try {
                    startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
                } catch (Exception ignored) {}
            }
        }

        // 2. RECORD_AUDIO (для голосового ввода)
        if (Build.VERSION.SDK_INT >= 23) {
            if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{android.Manifest.permission.RECORD_AUDIO}, 1001);
            }
        }
    }

    private boolean isAccessibilityEnabled() {
        try {
            String enabled = Settings.Secure.getString(getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (enabled == null) return false;
            return enabled.contains(getPackageName() + "/" + getPackageName() + ".AiBridgeService")
                || enabled.contains(getPackageName() + "/.AiBridgeService");
        } catch (Exception e) {
            return false;
        }
    }

    private void openAccessibilitySettings() {
        try {
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        } catch (Exception ignored) {}
    }

    private void updateEnvStatus() {
        StringBuilder issues = new StringBuilder();
        if (!new File("/sdcard/ai-tasker").exists()) issues.append("нет /sdcard/ai-tasker; ");
        if (!new File(INBOX).exists()) issues.append("нет inbox; ");
        if (!new File(OUTBOX).exists()) issues.append("нет outbox; ");

        if (issues.length() == 0) {
            envStatus.setText("✓ OK");
            envStatus.setTextColor(0xFF4CAF50);
        } else {
            envStatus.setText("⚠ " + issues);
            envStatus.setTextColor(0xFFE5484D);
        }
    }

    private static final int REQ_VOICE = 1001;

    private void startVoiceInput() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Toast.makeText(this, "Recognition unavailable", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU");
        intent.putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak");
        try {
            startActivityForResult(intent, REQ_VOICE);
        } catch (Exception e) {
            Toast.makeText(this, "Error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_VOICE && resultCode == RESULT_OK && data != null) {
            java.util.ArrayList<String> results = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
            if (results != null && !results.isEmpty() && inputTask != null) {
                inputTask.setText(results.get(0));
            }
        }
    }


    private boolean freeModeEnabled = false;

    private void toggleFreeMode() {
        freeModeEnabled = !freeModeEnabled;
        Button btn = findViewById(R.id.btn_free_mode);

        try {
            // 1. Отправляем команду сервису через сокет
            new Thread(new Runnable() {
                public void run() {
                    try {
                        java.net.Socket sock = new java.net.Socket();
                        sock.connect(new java.net.InetSocketAddress("127.0.0.1", 8766), 2000);
                        java.io.OutputStream os = sock.getOutputStream();
                        String cmd = freeModeEnabled ? "BUFFER_WATCH on\n" : "BUFFER_WATCH off\n";
                        os.write(cmd.getBytes("UTF-8"));
                        os.flush();
                        sock.close();
                    } catch (Exception ignored) {}
                }
            }).start();

            if (freeModeEnabled) {
                // 2. Запускаем overlay если настройка включена
                SharedPreferences p = getSharedPreferences("app_prefs", MODE_PRIVATE);
                boolean showOverlay = p.getBoolean("show_overlay", true);
                if (showOverlay) {
                    if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this)) {
                        // Запрашиваем разрешение
                        try {
                            Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:" + getPackageName()));
                            startActivity(i);
                        } catch (Exception ignored) {}
                    } else {
                        OverlayService.start(this);
                        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(new Runnable() {
                            @Override public void run() {
                                OverlayService.setState(MainActivity.this, "on");
                            }
                        }, 500);
                    }
                }
                if (btn != null) btn.setText("🤖 Свободный режим: ВКЛ");
                Toast.makeText(this, "Свободный режим включён. Копируй код из DeepSeek.", Toast.LENGTH_LONG).show();
            } else {
                // Выключаем
                OverlayService.stop(this);
                if (btn != null) btn.setText("🤖 Свободный режим: ВЫКЛ");
                Toast.makeText(this, "Свободный режим выключен", Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            Toast.makeText(this, "Ошибка: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }
}
