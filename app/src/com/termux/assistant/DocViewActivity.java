package com.termux.assistant;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;

public class DocViewActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        android.content.SharedPreferences _p = getSharedPreferences("app_prefs", MODE_PRIVATE);
        String _th = _p.getString("theme", "system");
        if ("light".equals(_th)) setTheme(R.style.AppTheme_Light);
        else if ("dark".equals(_th)) setTheme(R.style.AppTheme_Dark);
        else setTheme(R.style.AppTheme_Dark);

        setContentView(R.layout.activity_doc_view);

        String resName = getIntent().getStringExtra("res");
        String title = getIntent().getStringExtra("title");

        TextView titleTv = findViewById(R.id.doc_view_title);
        TextView textTv = findViewById(R.id.doc_view_text);
        Button btnBack = findViewById(R.id.btn_back_doc);

        if (titleTv != null) titleTv.setText(title != null ? title : "Документ");
        if (btnBack != null) {
            btnBack.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { finish(); }
            });
        }

        if (textTv != null && resName != null) {
            textTv.setText(loadText(resName));
        }
    }

    private String loadText(String name) {
        try {
            int id = getResources().getIdentifier(name, "raw", getPackageName());
            if (id == 0) return "(документ не найден: " + name + ")";

            InputStream is = getResources().openRawResource(id);
            BufferedReader r = new BufferedReader(new InputStreamReader(is, "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) {
                sb.append(line).append('\n');
            }
            r.close();
            return sb.toString();
        } catch (Exception e) {
            return "(ошибка загрузки: " + e.getMessage() + ")";
        }
    }
}
