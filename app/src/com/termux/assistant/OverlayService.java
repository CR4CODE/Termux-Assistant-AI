package com.termux.assistant;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.IBinder;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

public class OverlayService extends Service {

    public static final String ACTION_START = "com.termux.assistant.OVERLAY_START";
    public static final String ACTION_STOP = "com.termux.assistant.OVERLAY_STOP";
    public static final String ACTION_SET_STATE = "com.termux.assistant.OVERLAY_STATE";
    public static final String EXTRA_STATE = "state";

    private static final String CHANNEL_ID = "overlay-status";
    private static final int NOTIF_ID = 9001;

    private static final String PAUSE_FLAG = "/sdcard/ai-tasker/.pause";
    private static final String STOP_FLAG = "/sdcard/ai-tasker/.stop";

    private static boolean paused = false;
    private static boolean stopped = false;

    private WindowManager windowManager;
    private View overlayView;
    private TextView indicator;
    private WindowManager.LayoutParams params;
    private TextView trashView;
    private WindowManager.LayoutParams trashParams;

    @Override
    public void onCreate() {
        super.onCreate();
        windowManager = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_STICKY;

        String action = intent.getAction();
        if (ACTION_STOP.equals(action)) {
            removeOverlay();
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }

        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this)) {
            stopSelf();
            return START_NOT_STICKY;
        }

        ensureForeground();

        if (ACTION_SET_STATE.equals(action)) {
            String state = intent.getStringExtra(EXTRA_STATE);
            showOverlay(state != null ? state : "off");
        } else {
            showOverlay("on");
        }

        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onDestroy() {
        super.onDestroy();
        removeOverlay();
    }

    private void ensureForeground() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID, "Overlay indicator", NotificationManager.IMPORTANCE_MIN);
            ch.setDescription("Плавающая кнопка ассистента");
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);

            Notification n = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.presence_online)
                .setContentTitle("Ассистент активен")
                .setContentText("Плавающая кнопка")
                .setOngoing(true)
                .build();
            startForeground(NOTIF_ID, n);
        }
    }

    private void showOverlay(String state) {
        if (overlayView != null) {
            updateState(state);
            return;
        }

        overlayView = buildOverlayView();

        params = new WindowManager.LayoutParams(
            dpToPx(56),
            dpToPx(56),
            Build.VERSION.SDK_INT >= 26
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = dpToPx(16);
        params.y = dpToPx(400);

        try {
            windowManager.addView(overlayView, params);
        } catch (Exception e) {
            overlayView = null;
            return;
        }
        updateState(state);
    }

    private void updateState(final String state) {
        if (indicator == null) return;
        indicator.post(new Runnable() {
            @Override public void run() {
                applyState(state);
            }
        });
    }

    private void applyState(String state) {
        if (indicator == null) return;
        int color;
        String symbol;

        if ("off".equals(state) || stopped) {
            color = 0xFFDC2626;    // красный — стоп
            symbol = "\u25A0";     // ■
        } else if ("busy".equals(state) || paused) {
            color = 0xFFFFC107;    // жёлтый — пауза
            symbol = "\u23F8";     // ⏸
        } else {
            color = 0xFF22C55E;    // зелёный — работает
            symbol = "\u25B6";     // ▶
        }

        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        bg.setColor(0xFF1A1A1D);
        bg.setStroke(dpToPx(2), color);
        indicator.setBackground(bg);
        indicator.setTextColor(color);
        indicator.setText(symbol);
    }

    private View buildOverlayView() {
        indicator = new TextView(this);
        indicator.setText("\u25B6");
        indicator.setTextSize(22);
        indicator.setTextColor(0xFF22C55E);
        indicator.setGravity(Gravity.CENTER);
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        bg.setColor(0xFF1A1A1D);
        bg.setStroke(dpToPx(2), 0xFF22C55E);
        indicator.setBackground(bg);

        final int[] initialX = {0};
        final int[] initialY = {0};
        final float[] initialTouchX = {0};
        final float[] initialTouchY = {0};
        final long[] downTime = {0};
        final boolean[] moved = {false};

        indicator.setOnTouchListener(new View.OnTouchListener() {
            @Override public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        initialX[0] = params.x;
                        initialY[0] = params.y;
                        initialTouchX[0] = event.getRawX();
                        initialTouchY[0] = event.getRawY();
                        downTime[0] = System.currentTimeMillis();
                        moved[0] = false;
                        return true;

                    case MotionEvent.ACTION_MOVE:
                        int dx = (int)(event.getRawX() - initialTouchX[0]);
                        int dy = (int)(event.getRawY() - initialTouchY[0]);
                        if (Math.abs(dx) > 10 || Math.abs(dy) > 10) {
                            moved[0] = true;
                            showTrash();
                        }
                        params.x = initialX[0] + dx;
                        params.y = initialY[0] + dy;
                        try { windowManager.updateViewLayout(overlayView, params); } catch (Exception ignored) {}
                        return true;

                    case MotionEvent.ACTION_UP:
                        long dur = System.currentTimeMillis() - downTime[0];
                        if (moved[0]) {
                            hideTrash();
                            // Попал в крестик?
                            int cx = (int) event.getRawX();
                            int cy = (int) event.getRawY();
                            int sw = getResources().getDisplayMetrics().widthPixels;
                            int sh = getResources().getDisplayMetrics().heightPixels;
                            int targetY = sh - dpToPx(80);
                            int targetX = sw / 2;
                            int dist = (int)Math.hypot(cx - targetX, cy - targetY);
                            if (dist < dpToPx(80)) {
                                // Отключаем overlay И free mode
                                getSharedPreferences("app_prefs", MODE_PRIVATE)
                                    .edit()
                                    .putBoolean("overlay_enabled", false)
                                    .putBoolean("free_mode_enabled", false)
                                    .apply();
                                // Отправляем демону команду выключить буфер
                                new Thread(new Runnable() {
                                    public void run() {
                                        try {
                                            java.net.Socket sock = new java.net.Socket();
                                            sock.connect(new java.net.InetSocketAddress("127.0.0.1", 8766), 2000);
                                            java.io.OutputStream os = sock.getOutputStream();
                                            os.write("BUFFER_WATCH off\n".getBytes("UTF-8"));
                                            os.flush();
                                            sock.close();
                                        } catch (Exception ignored) {}
                                    }
                                }).start();
                                removeOverlay();
                                stopForeground(true);
                                stopSelf();
                                return true;
                            }
                        }
                        if (!moved[0]) {
                            boolean freeMode = getSharedPreferences("app_prefs", MODE_PRIVATE)
                                .getBoolean("free_mode_enabled", false);

                            if (dur > 800) {
                                // долгий тап
                                if (freeMode) {
                                    // старая логика — скрыть
                                    removeOverlay();
                                    stopForeground(true);
                                    stopSelf();
                                } else {
                                    // тумблер СТОП
                                    setStopped(!isStopped());
                                    if (isStopped()) {
                                        setPaused(false);
                                    }
                                    updateState(isStopped() ? "off" : "on");
                                }
                            } else {
                                // короткий тап
                                if (freeMode) {
                                    triggerRun();
                                } else {
                                    // если был стоп — сбрасываем и продолжаем
                                    if (isStopped()) {
                                        setStopped(false);
                                        setPaused(false);
                                        updateState("on");
                                    } else {
                                        // тумблер ПАУЗА
                                        setPaused(!isPaused());
                                        updateState(isPaused() ? "busy" : "on");
                                    }
                                }
                            }
                        }
                        return true;
                }
                return false;
            }
        });

        return indicator;
    }

    private void triggerRun() {
        // Меняем цвет на жёлтый сразу
        updateState("busy");

        new Thread(new Runnable() {
            public void run() {
                try {
                    java.net.Socket sock = new java.net.Socket();
                    sock.connect(new java.net.InetSocketAddress("127.0.0.1", 8766), 2000);
                    java.io.OutputStream os = sock.getOutputStream();
                    os.write("RUN_FROM_DUMP\n".getBytes("UTF-8"));
                    os.flush();
                    sock.close();
                } catch (Exception e) {
                    // Если не смогли — красный
                    updateState("off");
                }
            }
        }).start();
    }

    private void showTrash() {
        if (trashView != null) return;
        try {
            trashView = new TextView(this);
            trashView.setText("\u2715");
            trashView.setTextSize(28);
            trashView.setTextColor(0xFFFFFFFF);
            trashView.setGravity(Gravity.CENTER);
            android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
            bg.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            bg.setColor(0xCCDC2626);
            trashView.setBackground(bg);

            trashParams = new WindowManager.LayoutParams(
                dpToPx(64), dpToPx(64),
                Build.VERSION.SDK_INT >= 26
                    ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    : WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                    | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
            trashParams.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
            trashParams.y = dpToPx(48);
            windowManager.addView(trashView, trashParams);
        } catch (Exception ignored) {}
    }

    private void hideTrash() {
        if (trashView != null && windowManager != null) {
            try { windowManager.removeView(trashView); } catch (Exception ignored) {}
            trashView = null;
        }
    }

    private void removeOverlay() {
        hideTrash();
        if (overlayView != null && windowManager != null) {
            try { windowManager.removeView(overlayView); } catch (Exception ignored) {}
            overlayView = null;
            indicator = null;
        }
    }

    private int dpToPx(int dp) {
        return (int)(dp * getResources().getDisplayMetrics().density);
    }

    // ---- статические хелперы ----

    public static boolean isPaused() { return paused; }
    public static boolean isStopped() { return stopped; }

    public static void setPaused(boolean value) {
        paused = value;
        try {
            java.io.File f = new java.io.File(PAUSE_FLAG);
            if (value) {
                f.getParentFile().mkdirs();
                f.createNewFile();
            } else {
                if (f.exists()) f.delete();
            }
        } catch (Exception ignored) {}
    }

    public static void setStopped(boolean value) {
        stopped = value;
        try {
            java.io.File f = new java.io.File(STOP_FLAG);
            if (value) {
                f.getParentFile().mkdirs();
                f.createNewFile();
            } else {
                if (f.exists()) f.delete();
            }
        } catch (Exception ignored) {}
    }

    public static void start(Context ctx) {
        Intent i = new Intent(ctx, OverlayService.class);
        i.setAction(ACTION_START);
        if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i);
        else ctx.startService(i);
    }

    public static void stop(Context ctx) {
        Intent i = new Intent(ctx, OverlayService.class);
        i.setAction(ACTION_STOP);
        ctx.startService(i);
    }

    public static void setState(Context ctx, String state) {
        Intent i = new Intent(ctx, OverlayService.class);
        i.setAction(ACTION_SET_STATE);
        i.putExtra(EXTRA_STATE, state);
        ctx.startService(i);
    }
}
