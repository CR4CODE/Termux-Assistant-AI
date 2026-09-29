package com.termux.assistant;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.util.List;

public class TaskAdapter extends BaseAdapter {
    private final Context ctx;
    private final List<TaskItem> data;

    public TaskAdapter(Context ctx, List<TaskItem> data) {
        this.ctx = ctx;
        this.data = data;
    }

    @Override public int getCount() { return data.size(); }
    @Override public TaskItem getItem(int i) { return data.get(i); }
    @Override public long getItemId(int i) { return i; }

    @Override
    public View getView(int pos, View convertView, ViewGroup parent) {
        View v = convertView;
        if (v == null) {
            v = LayoutInflater.from(ctx).inflate(R.layout.item_task, parent, false);
        }
        TaskItem t = data.get(pos);

        TextView status = v.findViewById(R.id.item_status);
        TextView task = v.findViewById(R.id.item_task);
        TextView time = v.findViewById(R.id.item_time);
        TextView output = v.findViewById(R.id.item_output);
        TextView duration = v.findViewById(R.id.item_duration);
        ProgressBar progress = v.findViewById(R.id.item_progress);

        status.setText(t.statusLabel());
        status.setTextColor(t.statusColor());
        task.setText(t.task);
        time.setText(t.timeLabel());

        if (duration != null) {
            String d = t.durationLabel();
            if (d.length() > 0) {
                duration.setText(d);
                duration.setVisibility(View.VISIBLE);
            } else {
                duration.setVisibility(View.GONE);
            }
        }

        if (progress != null) {
            progress.setVisibility("running".equals(t.status) ? View.VISIBLE : View.GONE);
        }

        if (t.output != null && t.output.length() > 0) {
            String preview = t.output.trim();
            if (preview.length() > 200) preview = preview.substring(0, 200) + "…";
            output.setText(preview);
            output.setVisibility(View.VISIBLE);
        } else {
            output.setVisibility(View.GONE);
        }

        return v;
    }
}
