package com.termux.assistant;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.ListView;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class DevActivity extends Activity {

    private static final String OUTBOX = "/sdcard/ai-tasker/outbox";
    private static final String GIT_LOG = "/sdcard/ai-tasker/logs/git-log.txt";
    private static final String PATCHES_LOG = "/sdcard/ai-tasker/logs/patches.txt";

    private ListView listView;
    private TextView countLabel;
    private Button tabGit, tabAiDev, tabPatches;

    private int currentTab = 0; // 0=Git, 1=AI-Dev, 2=Patches

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        SharedPreferences _t = getSharedPreferences("app_prefs", MODE_PRIVATE);
        String _th = _t.getString("theme", "system");
        if ("light".equals(_th)) setTheme(R.style.AppTheme_Light);
        else setTheme(R.style.AppTheme_Dark);

        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_dev);

        listView = findViewById(R.id.list_dev);
        countLabel = findViewById(R.id.dev_count);
        tabGit = findViewById(R.id.tab_git);
        tabAiDev = findViewById(R.id.tab_aidev);
        tabPatches = findViewById(R.id.tab_patches);

        Button close = findViewById(R.id.btn_close_dev);
        if (close != null) {
            close.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { finish(); }
            });
        }

        if (tabGit != null) tabGit.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { setTab(0); }
        });
        if (tabAiDev != null) tabAiDev.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { setTab(1); }
        });
        if (tabPatches != null) tabPatches.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { setTab(2); }
        });

        setTab(1);
    }

    private void setTab(int tab) {
        currentTab = tab;
        if (tabGit != null) {
            tabGit.setBackgroundResource(tab == 0 ? R.drawable.btn_primary : R.drawable.btn_secondary);
        }
        if (tabAiDev != null) {
            tabAiDev.setBackgroundResource(tab == 1 ? R.drawable.btn_primary : R.drawable.btn_secondary);
        }
        if (tabPatches != null) {
            tabPatches.setBackgroundResource(tab == 2 ? R.drawable.btn_primary : R.drawable.btn_secondary);
        }
        reload();
    }

    private void reload() {
        List<String> items = new ArrayList<>();
        String header = "";

        if (currentTab == 0) {
            header = "Git — история коммитов";
            items = readLines(GIT_LOG);
            if (items.isEmpty()) {
                items = Arrays.asList(
                    "(Нет данных)",
                    "",
                    "Чтобы получить git-лог:",
                    "запусти в Termux",
                    "bash ~/sync-dev-info.sh");
            }
        } else if (currentTab == 1) {
            header = "AI-Dev — журнал задач";
            items = loadTileTasks();
        } else {
            header = "Патчи — история правок";
            items = readLines(PATCHES_LOG);
            if (items.isEmpty()) {
                items = Arrays.asList(
                    "(Нет данных)",
                    "",
                    "Чтобы получить список патчей:",
                    "запусти в Termux",
                    "bash ~/sync-dev-info.sh");
            }
        }

        if (countLabel != null) {
            countLabel.setText(header + " · " + items.size());
        }

        DevAdapter adapter = new DevAdapter(this, items);
        if (listView != null) listView.setAdapter(adapter);
    }

    private List<String> readLines(String path) {
        List<String> result = new ArrayList<>();
        try {
            File f = new File(path);
            if (!f.exists()) return result;
            BufferedReader r = new BufferedReader(new FileReader(f));
            String line;
            while ((line = r.readLine()) != null) {
                result.add(line);
            }
            r.close();
        } catch (Exception ignored) {}
        return result;
    }

    private List<String> loadTileTasks() {
        List<String> result = new ArrayList<>();
        try {
            File dir = new File(OUTBOX);
            if (!dir.exists()) return result;
            File[] files = dir.listFiles(new java.io.FilenameFilter() {
                @Override public boolean accept(File d, String n) {
                    return n.startsWith("task-tile") && n.endsWith(".json");
                }
            });
            if (files == null) return result;

            Arrays.sort(files, new java.util.Comparator<File>() {
                @Override public int compare(File a, File b) {
                    return b.getName().compareTo(a.getName());
                }
            });

            int limit = Math.min(files.length, 100);
            for (int i = 0; i < limit; i++) {
                TaskItem t = MainActivity.parseTaskFile(files[i]);
                if (t != null) {
                    String status = t.statusLabel() + " " + t.statusText();
                    String taskText = t.task != null ? t.task : "";
                    if (taskText.length() > 120) taskText = taskText.substring(0, 120) + "…";
                    String item = status + "  " + t.timeLabel() + "\n" + taskText;
                    result.add(item);
                }
            }
        } catch (Exception ignored) {}
        return result;
    }
}
