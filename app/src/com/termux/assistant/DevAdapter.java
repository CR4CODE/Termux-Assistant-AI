package com.termux.assistant;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.TextView;

import java.util.List;

public class DevAdapter extends BaseAdapter {
    private final Context ctx;
    private final List<String> data;

    public DevAdapter(Context ctx, List<String> data) {
        this.ctx = ctx;
        this.data = data;
    }

    @Override public int getCount() { return data.size(); }
    @Override public String getItem(int i) { return data.get(i); }
    @Override public long getItemId(int i) { return i; }

    @Override
    public View getView(int pos, View convertView, ViewGroup parent) {
        View v = convertView;
        if (v == null) {
            v = LayoutInflater.from(ctx).inflate(R.layout.item_dev, parent, false);
        }
        String line = data.get(pos);
        TextView text = v.findViewById(R.id.dev_line);
        if (text != null) {
            if (line == null) line = "";
            text.setText(line);
            // Пустые строки не показываем как «пробел»
            text.setVisibility(line.trim().isEmpty() ? View.GONE : View.VISIBLE);
        }
        return v;
    }
}
