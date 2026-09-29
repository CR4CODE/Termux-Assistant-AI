package com.termux.assistant;

import android.graphics.drawable.Icon;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;
import android.util.Log;

public class QuickTileService extends TileService {

    private static final String TAG = "AI-Tile";

    @Override
    public void onStartListening() {
        super.onStartListening();
        updateTileState();
    }

    @Override
    public void onClick() {
        super.onClick();
        Log.i(TAG, "tile clicked");

        try {
            Tile tile = getQsTile();
            if (tile != null) {
                tile.setState(Tile.STATE_ACTIVE);
                if (Build.VERSION.SDK_INT >= 29) {
                    tile.setSubtitle("Выполняю...");
                }
                tile.updateTile();
            }

            // Отправляем команду сервису: выполнить последний код-блок DeepSeek
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
                        Log.e(TAG, "sock err", e);
                    }
                }
            }).start();

        } catch (Throwable t) {
            Log.e(TAG, "onClick err", t);
        }
    }

    @Override
    public void onTileAdded() {
        super.onTileAdded();
        updateTileState();
    }

    private void updateTileState() {
        try {
            Tile tile = getQsTile();
            if (tile == null) return;

            boolean up = isBridgeRunning();
            tile.setState(up ? Tile.STATE_INACTIVE : Tile.STATE_UNAVAILABLE);
            if (Build.VERSION.SDK_INT >= 29) {
                tile.setSubtitle(up ? "Готов" : "Сервис выкл");
            }
            tile.updateTile();
        } catch (Throwable ignored) {}
    }

    private boolean isBridgeRunning() {
        try {
            java.net.Socket sock = new java.net.Socket();
            sock.connect(new java.net.InetSocketAddress("127.0.0.1", 8766), 500);
            sock.close();
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
