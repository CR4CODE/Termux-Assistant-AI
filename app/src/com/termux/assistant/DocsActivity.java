package com.termux.assistant;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.TextView;

public class DocsActivity extends Activity {

    private static final String[][] DOCS = {
        {"📖", "README", "Обзор системы и архитектура", "readme"},
        {"⚙️", "Установка", "Пошаговая инструкция", "install"},
        {"🎯", "Как пользоваться", "Ежедневные сценарии", "user_guide"},
        {"🛠", "Разработка", "Как улучшать приложение", "dev_guide"},
        {"💻", "Команды Termux", "Все скрипты и утилиты", "termux_commands"},
        {"🗺", "Roadmap", "Что сделано и что в планах", "roadmap"}
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        android.content.SharedPreferences _p = getSharedPreferences("app_prefs", MODE_PRIVATE);
        String _th = _p.getString("theme", "system");
        if ("light".equals(_th)) setTheme(R.style.AppTheme_Light);
        else if ("dark".equals(_th)) setTheme(R.style.AppTheme_Dark);
        else setTheme(R.style.AppTheme_Dark);

        setContentView(R.layout.activity_docs);

        Button btnClose = findViewById(R.id.btn_close_docs);
        if (btnClose != null) {
            btnClose.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { finish(); }
            });
        }

        ListView list = findViewById(R.id.list_docs);
        list.setAdapter(new DocsAdapter());
        list.setOnItemClickListener(new android.widget.AdapterView.OnItemClickListener() {
            @Override public void onItemClick(android.widget.AdapterView<?> p, View v, int pos, long id) {
                Intent i = new Intent(DocsActivity.this, DocViewActivity.class);
                i.putExtra("res", DOCS[pos][3]);
                i.putExtra("title", DOCS[pos][1]);
                startActivity(i);
            }
        });
    }

    private class DocsAdapter extends BaseAdapter {
        @Override public int getCount() { return DOCS.length; }
        @Override public Object getItem(int i) { return DOCS[i]; }
        @Override public long getItemId(int i) { return i; }

        @Override
        public View getView(int pos, View cv, ViewGroup parent) {
            View v = cv;
            if (v == null) {
                v = getLayoutInflater().inflate(R.layout.item_doc, parent, false);
            }
            ((TextView) v.findViewById(R.id.doc_icon)).setText(DOCS[pos][0]);
            ((TextView) v.findViewById(R.id.doc_title)).setText(DOCS[pos][1]);
            ((TextView) v.findViewById(R.id.doc_subtitle)).setText(DOCS[pos][2]);
            return v;
        }
    }
}
