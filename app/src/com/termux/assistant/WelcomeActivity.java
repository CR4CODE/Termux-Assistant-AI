package com.termux.assistant;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

public class WelcomeActivity extends Activity {

    private TextView statusTermux;
    private TextView statusApi;
    private TextView statusDeepseek;
    private TextView statusPermissions;
    private TextView statusService;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        SharedPreferences _p = getSharedPreferences("app_prefs", MODE_PRIVATE);
        String _th = _p.getString("theme", "system");
        if ("light".equals(_th)) setTheme(R.style.AppTheme_Light);
        else if ("dark".equals(_th)) setTheme(R.style.AppTheme_Dark);
        else setTheme(R.style.AppTheme_Dark);

        setContentView(R.layout.activity_welcome);

        statusTermux = findViewById(R.id.step_termux_status);
        statusApi = findViewById(R.id.step_api_status);
        statusDeepseek = findViewById(R.id.step_deepseek_status);
        statusPermissions = findViewById(R.id.step_permissions_status);
        statusService = findViewById(R.id.step_service_status);

        setupButtons();
        refreshAll();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshAll();
    }

    private void setupButtons() {
        Button btnTermux = findViewById(R.id.btn_termux);
        Button btnApi = findViewById(R.id.btn_api);
        Button btnDeepseek = findViewById(R.id.btn_deepseek);
        Button btnPermissions = findViewById(R.id.btn_permissions);
        Button btnService = findViewById(R.id.btn_service);
        Button btnFinish = findViewById(R.id.btn_finish);
        Button btnSkip = findViewById(R.id.btn_skip);

        if (btnTermux != null) btnTermux.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                openFdroid("com.termux");
            }
        });

        if (btnApi != null) btnApi.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                openFdroid("com.termux.api");
            }
        });

        if (btnDeepseek != null) btnDeepseek.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                openPlayMarket("com.deepseek.chat");
            }
        });

        if (btnPermissions != null) btnPermissions.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                requestPermissions();
            }
        });

        if (btnService != null) btnService.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                try {
                    startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
                } catch (Exception ignored) {}
            }
        });

        if (btnFinish != null) btnFinish.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finishWizard(); }
        });

        if (btnSkip != null) btnSkip.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finishWizard(); }
        });
    }

    private void refreshAll() {
        updateStatus(statusTermux, isInstalled("com.termux"), "✗ Termux", "✓ Termux");
        updateStatus(statusApi, isInstalled("com.termux.api"), "✗ Termux:API", "✓ Termux:API");

        boolean ds = isInstalled("com.deepseek.chat");
        updateStatus(statusDeepseek, ds, "✗ DeepSeek", "✓ DeepSeek");

        boolean perms = hasAllFilesAccess() && hasAudioAccess();
        updateStatus(statusPermissions, perms, "✗ Разрешения", "✓ Разрешения");

        updateStatus(statusService, isAccessibilityEnabled(), "✗ AI Bridge Service", "✓ AI Bridge Service");
    }

    private void updateStatus(TextView tv, boolean ok, String failText, String okText) {
        if (tv == null) return;
        tv.setText(ok ? okText : failText);
        tv.setTextColor(ok ? 0xFF4CAF50 : 0xFFE5484D);
    }

    private boolean isInstalled(String pkg) {
        try {
            getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private boolean hasAllFilesAccess() {
        if (Build.VERSION.SDK_INT < 30) return true;
        try {
            return Environment.isExternalStorageManager();
        } catch (Exception e) {
            return false;
        }
    }

    private boolean hasAudioAccess() {
        if (Build.VERSION.SDK_INT < 23) return true;
        return checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED;
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

    private void requestPermissions() {
        if (Build.VERSION.SDK_INT >= 30 && !hasAllFilesAccess()) {
            try {
                Intent i = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                i.setData(Uri.parse("package:" + getPackageName()));
                startActivity(i);
                return;
            } catch (Exception ignored) {}
        }
        if (Build.VERSION.SDK_INT >= 23 && !hasAudioAccess()) {
            requestPermissions(new String[]{android.Manifest.permission.RECORD_AUDIO}, 1001);
        }
    }

    private void openFdroid(String pkg) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse("https://f-droid.org/packages/" + pkg + "/"));
            startActivity(i);
        } catch (Exception ignored) {}
    }

    private void openPlayMarket(String pkg) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + pkg));
            startActivity(i);
        } catch (Exception e) {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://play.google.com/store/apps/details?id=" + pkg)));
            } catch (Exception ignored) {}
        }
    }

    private void finishWizard() {
        SharedPreferences p = getSharedPreferences("app_prefs", MODE_PRIVATE);
        p.edit().putBoolean("welcome_done", true).apply();
        Intent i = new Intent(this, MainActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(i);
        finish();
    }
}
