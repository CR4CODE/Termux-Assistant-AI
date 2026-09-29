package com.termux.assistant;

import android.app.Activity;
import android.content.Intent;import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.view.View;
import android.widget.Button;import android.widget.RadioGroup;
import android.widget.TextView;

import java.io.File;

public class SettingsActivity extends Activity {

    private TextView envDetails;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // Применяем тему из prefs до super.onCreate
        android.content.SharedPreferences _t = getSharedPreferences("app_prefs", MODE_PRIVATE);
        String _th = _t.getString("theme", "system");
        if ("light".equals(_th)) setTheme(R.style.AppTheme_Light);
        else if ("dark".equals(_th)) setTheme(R.style.AppTheme_Dark);
        else setTheme(R.style.AppTheme_Dark);  // системная по умолчанию

        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        final SharedPreferences sp2 = getSharedPreferences("app_prefs", MODE_PRIVATE);
        String ct = sp2.getString("theme", "system");
        RadioGroup tg = findViewById(R.id.theme_group);        if (tg != null) {            if ("light".equals(ct)) tg.check(R.id.theme_light);            else if ("dark".equals(ct)) tg.check(R.id.theme_dark);            else tg.check(R.id.theme_system);            tg.setOnCheckedChangeListener(new RadioGroup.OnCheckedChangeListener() {                @Override public void onCheckedChanged(RadioGroup g, int id) {                    String v = id == R.id.theme_light ? "light" : (id == R.id.theme_dark ? "dark" : "system");                    sp2.edit().putString("theme", v).apply();
                    recreate();                }            });        }

        envDetails = findViewById(R.id.env_details);
        Button recheck = findViewById(R.id.btn_recheck);
        Button openSd = findViewById(R.id.btn_open_sdcard);

        recheck.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { updateEnv(); }
        });

        openSd.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                // 1. Копируем путь в буфер (гарантированно работает)
                try {
                    android.content.ClipboardManager cm = (android.content.ClipboardManager)
                        getSystemService(CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("path", "/sdcard/ai-tasker"));
                } catch (Exception ignored) {}

                // 2. Пытаемся открыть через content URI (работает на Android 7+)
                boolean opened = false;
                try {
                    Uri uri = Uri.parse("content://com.android.externalstorage.documents/root/primary");
                    Intent i = new Intent(Intent.ACTION_VIEW);
                    i.setDataAndType(uri, "vnd.android.document/root");
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(i);
                    opened = true;
                } catch (Exception ignored) {}

                // 3. Fallback — открыть файловый менеджер без указания папки
                if (!opened) {
                    try {
                        Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                        i.setType("*/*");
                        startActivity(i);
                        opened = true;
                    } catch (Exception ignored) {}
                }

                android.widget.Toast.makeText(SettingsActivity.this,
                    "Путь скопирован: /sdcard/ai-tasker", android.widget.Toast.LENGTH_LONG).show();
            }
        });

        updateEnv();
        setupA11yBlock();

        // Кнопка Инструкция
        Button btnDocs = findViewById(R.id.btn_open_docs);
        if (btnDocs != null) {
            btnDocs.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    try {
                        startActivity(new Intent(SettingsActivity.this, DocsActivity.class));
                    } catch (Exception e) {
                        android.widget.Toast.makeText(SettingsActivity.this,
                            "Ошибка: " + e.getMessage(), android.widget.Toast.LENGTH_LONG).show();
                    }
                }
            });
        }

        // Кнопка Улучшить приложение
        Button btnImprove = findViewById(R.id.btn_improve);
        if (btnImprove != null) {
            btnImprove.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { showImproveDialog(); }
            });
        }

        // Кнопка VK-сообщества
        Button btnVk = findViewById(R.id.btn_vk);
        if (btnVk != null) {
            btnVk.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    try {
                        Intent i = new Intent(Intent.ACTION_VIEW,
                            Uri.parse("https://vk.ru/termuxai"));
                        startActivity(i);
                    } catch (Exception e) {
                        android.widget.Toast.makeText(SettingsActivity.this,
                            "Не удалось открыть: " + e.getMessage(),
                            android.widget.Toast.LENGTH_LONG).show();
                    }
                }
            });
        }
    }

    private void setupA11yBlock() {
        TextView status = findViewById(R.id.aibridge_status);
        Button btnOpen = findViewById(R.id.btn_open_a11y);
        Button btnRestrict = findViewById(R.id.btn_open_restricted);

        refreshA11yStatus(status);

        if (btnOpen != null) {
            btnOpen.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    try {
                        startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
                    } catch (Exception ignored) {}
                }
            });
        }

        // Кнопка «Открыть настройки приложения» — ведёт на экран «О приложении»
        // Именно там, в ⋮, появляется пункт «Разрешить ограниченные настройки»
        Button btnAppSettings = findViewById(R.id.btn_open_a11y_settings);
        if (btnAppSettings != null) {
            btnAppSettings.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    try {
                        Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                        i.setData(Uri.parse("package:" + getPackageName()));
                        startActivity(i);
                    } catch (Exception ignored) {}
                }
            });
        }
    }

    private boolean isAccessibilityEnabled() {
        try {
            String enabled = Settings.Secure.getString(getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (enabled == null) return false;
            String pkg = getPackageName();
            return enabled.contains(pkg + "/" + pkg + ".AiBridgeService")
                || enabled.contains(pkg + "/.AiBridgeService");
        } catch (Exception e) {
            return false;
        }
    }

    private void refreshA11yStatus(TextView tv) {
        if (tv == null) return;
        StringBuilder sb = new StringBuilder();

        boolean a11y = isAccessibilityEnabled();
        sb.append(a11y ? "✓ " : "✗ ").append("Accessibility-сервис: ")
          .append(a11y ? "включён" : "выключен").append("\n");

        boolean allFiles = true;
        if (Build.VERSION.SDK_INT >= 30) {
            try { allFiles = Environment.isExternalStorageManager(); } catch (Exception e) { allFiles = false; }
        }
        sb.append(allFiles ? "✓ " : "✗ ").append("Доступ ко всем файлам: ")
          .append(allFiles ? "разрешён" : "не разрешён").append("\n");

        boolean audio = true;
        if (Build.VERSION.SDK_INT >= 23) {
            audio = checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                == android.content.pm.PackageManager.PERMISSION_GRANTED;
        }
        sb.append(audio ? "✓ " : "✗ ").append("Микрофон: ")
          .append(audio ? "разрешён" : "не разрешён");

        tv.setText(sb.toString());
    }

    @Override
    protected void onResume() {
        super.onResume();
        TextView status = findViewById(R.id.aibridge_status);
        refreshA11yStatus(status);
    }

    private void updateEnv() {
        StringBuilder sb = new StringBuilder();
        sb.append(check("/sdcard/ai-tasker", "папка /sdcard/ai-tasker"));
        sb.append(check("/sdcard/ai-tasker/inbox", "папка inbox"));
        sb.append(check("/sdcard/ai-tasker/outbox", "папка outbox"));
        sb.append(check("/sdcard/ai-tasker/done", "папка done"));
        sb.append(check("/sdcard/ai-tasker/logs", "папка logs"));
        envDetails.setText(sb.toString());
    }

    private String check(String path, String label) {
        File f = new File(path);
        if (f.exists()) return "\u2713 " + label + "\n";
        return "\u2717 " + label + " — отсутствует\n";
    }

    private void showImproveDialog() {
        final android.widget.EditText input = new android.widget.EditText(this);
        input.setHint("Что улучшить?");
        input.setMinLines(3);
        input.setPadding(24, 24, 24, 24);

        new android.app.AlertDialog.Builder(this)
            .setTitle("Улучшить приложение")
            .setMessage("Опиши, что нужно изменить. DeepSeek прочитает исходники и пересоберёт APK.\n\nНовый APK появится в /sdcard/Download/")
            .setView(input)
            .setPositiveButton("Запустить", new android.content.DialogInterface.OnClickListener() {
                @Override public void onClick(android.content.DialogInterface d, int w) {
                    String task = input.getText().toString().trim();
                    if (task.isEmpty()) return;
                    submitDevTask(task);
                }
            })
            .setNegativeButton("Отмена", null)
            .show();
    }

    private void submitDevTask(String task) {
        try {
            String id = "dev" + System.currentTimeMillis();
            java.io.File dir = new java.io.File("/sdcard/ai-tasker/inbox");
            dir.mkdirs();
            java.io.File f = new java.io.File(dir, "task-" + id + ".txt");
            java.io.FileWriter fw = new java.io.FileWriter(f);
            fw.write("dev:" + task);
            fw.close();
            android.widget.Toast.makeText(SettingsActivity.this,
                "Задача отправлена. Смотри историю.",
                android.widget.Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            android.widget.Toast.makeText(SettingsActivity.this,
                "Ошибка: " + e.getMessage(),
                android.widget.Toast.LENGTH_LONG).show();
        }
    }

}
