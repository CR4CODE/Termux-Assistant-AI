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

    private WindowManager windowManager;
    private View overlayView;
    private TextView indicator;
    private WindowManager.LayoutParams params;

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
        params.x = dpToPx(20);
        params.y = dpToPx(200);

        try {
            windowManager.addView(overlayView, params);
        } catch (Exception e) {
            overlayView = null;
            return;
        }
        updateState(state);
    }

    private void updateState(String state) {
        if (indicator == null) return;
        int color;
        if ("busy".equals(state)) {
            color = 0xFFFFC107; // жёлтый
        } else if ("off".equals(state)) {
            color = 0xFFDC2626; // красный
        } else {
            color = 0xFF22C55E; // зелёный
        }

        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        bg.setColor(0xFF1A1A1D);              // тёмный круг
        bg.setStroke(dpToPx(2), color);       // цветная обводка
        indicator.setBackground(bg);
        indicator.setTextColor(color);
        indicator.setText("\u25CF");
    }

    private View buildOverlayView() {
        indicator = new TextView(this);
        indicator.setText("\u25CF");
        indicator.setTextSize(28);
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
                        if (Math.abs(dx) > 10 || Math.abs(dy) > 10) moved[0] = true;
                        params.x = initialX[0] + dx;
                        params.y = initialY[0] + dy;
                        try { windowManager.updateViewLayout(overlayView, params); } catch (Exception ignored) {}
                        return true;

                    case MotionEvent.ACTION_UP:
                        long dur = System.currentTimeMillis() - downTime[0];
                        if (!moved[0]) {
                            if (dur > 800) {
                                // долгий тап = скрыть
                                removeOverlay();
                                stopForeground(true);
                                stopSelf();
                            } else {
                                // короткий тап = выполнить
                                triggerRun();
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

    private void removeOverlay() {
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
