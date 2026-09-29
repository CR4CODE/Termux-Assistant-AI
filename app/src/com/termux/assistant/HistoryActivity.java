package com.termux.assistant;

import android.app.Activity;
import android.os.Bundle;
import java.io.FileWriter;
import android.app.AlertDialog;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ListView;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class HistoryActivity extends Activity {

    private static final String INBOX = "/sdcard/ai-tasker/inbox";

    private static final String OUTBOX = "/sdcard/ai-tasker/outbox";

    private ListView listHistory;
    private final List<TaskItem> tasks = new ArrayList<>();
    private TaskAdapter adapter;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable refreshRunnable;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_history);

        listHistory = findViewById(R.id.list_history);
        adapter = new TaskAdapter(this, tasks);
        listHistory.setAdapter(adapter);

        listHistory.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override public void onItemClick(AdapterView<?> parent, View view, int pos, long id) {
                if (pos >= 0 && pos < tasks.size()) {
                    showTaskDialog(tasks.get(pos));
                }
            }
        });

        loadTasks();
    }

    @Override
    protected void onResume() {
        super.onResume();
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
                handler.postDelayed(this, 3000);
            }
        };
        handler.postDelayed(refreshRunnable, 3000);
    }

    private void stopAutoRefresh() {
        if (refreshRunnable != null) handler.removeCallbacks(refreshRunnable);
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
            TaskItem t = MainActivity.parseTaskFile(f);
            if (t != null) fresh.add(t);
        }
        Collections.sort(fresh, new java.util.Comparator<TaskItem>() {
            @Override public int compare(TaskItem a, TaskItem b) { return Long.compare(b.started, a.started); }
        });

        tasks.clear();
        tasks.addAll(fresh);
        if (adapter != null) adapter.notifyDataSetChanged();
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

    private void toast(String msg) {
        try {
            android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show();
        } catch (Exception ignored) {}
    }
}
