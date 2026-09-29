package com.termux.assistant;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.accessibilityservice.GestureDescription;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.util.Base64;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.security.MessageDigest;
import org.json.JSONObject;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

public class AiBridgeService extends AccessibilityService {
    private static final String TAG = "AIBridge";
    private static final String HOST = "127.0.0.1";
    private static final int PORT_EVENTS = 8765;
    private static final int PORT_CMDS   = 8766;
    private static final String VERSION = "2.3";
    private static final SimpleDateFormat SDF = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);
    private static final int MAX_DUMP_LINES = 4000;
    private static final int MAX_LINE_LEN  = 4000;

    private long lastSendMs = 0;
    private String lastPkg = "";

    // === Buffer Watch (свободный режим) ===
    private volatile boolean bufferWatchEnabled = false;
    private Thread bufferWatchThread = null;
    private String lastBufferHash = "";
    private long lastCopyClickMs = 0;

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        try {
            AccessibilityServiceInfo info = getServiceInfo();
            if (info != null) {
                info.eventTypes = AccessibilityEvent.TYPES_ALL_MASK;
                info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
                info.flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
                    | AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
                    | AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS;
                info.notificationTimeout = 150;
                setServiceInfo(info);
            }
        } catch (Throwable t) { Log.e(TAG, "config", t); }
        startCommandServer();
        sendLine("HELLO service-connected v" + VERSION + " at " + SDF.format(new Date()));
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;
        try {
            CharSequence pkg = event.getPackageName();
            String p = pkg == null ? "NULL_PKG" : pkg.toString();
            int type = event.getEventType();

            // === ДИАГНОСТИКА: ловим ВСЕ type=64 (TYPE_NOTIFICATION_STATE_CHANGED) ===
            // Тост «Скопировано» приходит именно как type=64, но от системного пакета
            if (bufferWatchEnabled && type == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED) {
                try {
                    StringBuilder _all = new StringBuilder();
                    java.util.List<CharSequence> _tl = event.getText();
                    if (_tl != null) {
                        for (CharSequence _c : _tl) {
                            if (_c != null) _all.append("[").append(_c.toString()).append("]");
                        }
                    }
                    String _cls = event.getClassName() == null ? "?" : event.getClassName().toString();
                    sendLine("DEBUG_TOAST pkg=" + p + " cls=" + _cls + " texts=" + _all.toString());
                } catch (Throwable _t) {
                    sendLine("DEBUG_TOAST err " + _t);
                }
            }

            // === DEBUG_ALL: все события от DeepSeek при активном watch ===
            if (bufferWatchEnabled && ("com.deepseek.chat".equals(p) || pkg == null)) {
                try {
                    AccessibilityNodeInfo _s = event.getSource();
                    String _cls = _s == null ? "null" : String.valueOf(_s.getClassName());
                    String _txt = _s == null ? "" : (_s.getText() == null ? "" : _s.getText().toString());
                    String _desc = _s == null ? "" : (_s.getContentDescription() == null ? "" : _s.getContentDescription().toString());
                    if (_txt.length() > 50) _txt = _txt.substring(0, 50) + "...";
                    if (_desc.length() > 50) _desc = _desc.substring(0, 50) + "...";
                    // event.getText() — список текстов которые изменились
                    StringBuilder _evtTexts = new StringBuilder();
                    try {
                        java.util.List<CharSequence> _tl = event.getText();
                        if (_tl != null && !_tl.isEmpty()) {
                            for (CharSequence _cs : _tl) {
                                if (_cs != null) {
                                    String _tcs = _cs.toString();
                                    if (_tcs.length() > 80) _tcs = _tcs.substring(0, 80) + "...";
                                    _evtTexts.append("[").append(_tcs).append("]");
                                }
                            }
                        }
                    } catch (Throwable ignored) {}

                    sendLine("DEBUG_ALL type=" + type + " pkg=" + p + " cls=" + _cls + " text=\"" + _txt + "\" desc=\"" + _desc + "\" evtTexts=" + _evtTexts.toString());
                } catch (Throwable _t) {
                    sendLine("DEBUG_ALL err " + _t);
                }
            }

            if (pkg == null) return;

            // === ТОСТ "Скопировано" от DeepSeek — триггер выполнения ===
            if (bufferWatchEnabled && "com.deepseek.chat".equals(p)
                    && type == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED) {
                long now = System.currentTimeMillis();
                if (now - lastCopyClickMs > 3000) {
                    lastCopyClickMs = now;
                    sendLine("TOAST_TRIGGER detected");
                    handleCopyClick();
                }
            }

            // === DEBUG: все клики в DeepSeek ===
            if ("com.deepseek.chat".equals(p) && type == AccessibilityEvent.TYPE_VIEW_CLICKED) {
                try {
                    AccessibilityNodeInfo _src = event.getSource();
                    String _cls = _src == null ? "null" : String.valueOf(_src.getClassName());
                    String _txt = _src == null ? "" : (_src.getText() == null ? "" : _src.getText().toString());
                    String _desc = _src == null ? "" : (_src.getContentDescription() == null ? "" : _src.getContentDescription().toString());
                    sendLine("DEBUG_CLICK cls=" + _cls + " text=\"" + _txt + "\" desc=\"" + _desc + "\" watch=" + bufferWatchEnabled);
                } catch (Throwable _t) {
                    sendLine("DEBUG_CLICK err " + _t);
                }
            }

            // === Перехват клика на "Копировать" в DeepSeek ===
            if (bufferWatchEnabled && type == AccessibilityEvent.TYPE_VIEW_CLICKED
                    && "com.deepseek.chat".equals(p)) {
                try {
                    AccessibilityNodeInfo src = event.getSource();
                    if (src != null) {
                        CharSequence t = src.getText();
                        CharSequence d = src.getContentDescription();
                        String ts = t == null ? "" : t.toString().trim();
                        String ds = d == null ? "" : d.toString().trim();
                        if ("Копировать".equals(ts) || "Копировать".equals(ds)) {
                            long now = System.currentTimeMillis();
                            if (now - lastCopyClickMs > 3000) {
                                lastCopyClickMs = now;
                                sendLine("COPY_CLICK detected");
                                handleCopyClick();
                            }
                        }
                    }
                } catch (Throwable ignored) {}
            }

            long now = System.currentTimeMillis();
            if (p.equals(lastPkg) && (now - lastSendMs) < 200) return;
            lastSendMs = now; lastPkg = p;
            sendLine("EVT " + type + " " + p);
        } catch (Throwable t) { Log.e(TAG, "event", t); }
    }

    @Override
    public void onInterrupt() { sendLine("INTERRUPT"); }

    private void startCommandServer() {
        Thread t = new Thread(new Runnable() {
            public void run() {
                ServerSocket ss = null;
                try {
                    ss = new ServerSocket();
                    ss.setReuseAddress(true);
                    ss.bind(new InetSocketAddress(HOST, PORT_CMDS));
                    Log.i(TAG, "cmd server listening on " + PORT_CMDS);
                    sendLine("CMD_SERVER_UP on " + PORT_CMDS);
                    while (true) {
                        Socket cli = ss.accept();
                        try {
                            BufferedReader r = new BufferedReader(
                                new InputStreamReader(cli.getInputStream(), "UTF-8"));
                            String line;
                            while ((line = r.readLine()) != null) {
                                String cmd = line.trim();
                                if (cmd.length() == 0) continue;
                                try { handleCommand(cmd); }
                                catch (Throwable e) { sendLine("ERR handle: " + e); }
                            }
                        } catch (Throwable inner) {
                            Log.w(TAG, "cmd conn err: " + inner);
                        } finally {
                            try { cli.close(); } catch (Throwable ignored) {}
                        }
                    }
                } catch (Throwable t) {
                    Log.e(TAG, "cmd server died", t);
                    sendLine("CMD_SERVER_DIED " + t);
                }
            }
        }, "cmdsrv");
        t.setDaemon(true);
        t.start();
    }

    private void handleCommand(String cmd) {
        Log.i(TAG, "cmd: " + cmd);
        try {
            if (cmd.equals("DUMP")) { dumpAndSend(); return; }
            if (cmd.equals("PING")) { sendLine("PONG " + SDF.format(new Date())); return; }
            if (cmd.equals("WHOAMI")) {
                sendLine("WHOAMI pkg=" + getPackageName() + " sdk=" + Build.VERSION.SDK_INT + " v=" + VERSION);
                return;
            }
            if (cmd.equals("GETCLIP")) { getClipAndSend(); return; }
            if (cmd.equals("FIND_FOCUS")) { findFocusAndSend(); return; }
            if (cmd.equals("PROBE_FIELD")) { probeFieldAndSend(); return; }
            if (cmd.equals("TRY_HARD")) { tryHard(); return; }
            if (cmd.equals("BACK")) {
                boolean ok = performGlobalAction(GLOBAL_ACTION_BACK);
                sendLine("BACK ok=" + ok); return;
            }
            if (cmd.equals("HOME")) {
                boolean ok = performGlobalAction(GLOBAL_ACTION_HOME);
                sendLine("HOME ok=" + ok); return;
            }
            if (cmd.equals("PASTE")) { pasteIntoFocused(); return; }

            if (cmd.startsWith("FOCUS_AND_TYPE ")) {
                focusAndType(base64Decode(cmd.substring(16).trim()));
                return;
            }
            if (cmd.startsWith("OPEN_URL ")) {
                openUrl(base64Decode(cmd.substring(9).trim()));
                return;
            }
            if (cmd.startsWith("OPEN_APP ")) {
                openApp(cmd.substring(9).trim());
                return;
            }
            if (cmd.startsWith("SETTEXT ")) {
                setTextOnEditText(base64Decode(cmd.substring(8).trim()));
                return;
            }
            if (cmd.startsWith("CLICK ")) {
                String[] p = cmd.substring(6).trim().split("\\s+");
                if (p.length >= 2) tap(Integer.parseInt(p[0]), Integer.parseInt(p[1]), 40);
                else sendLine("ERR CLICK args");
                return;
            }
            if (cmd.startsWith("LONGCLICK ")) {
                String[] p = cmd.substring(10).trim().split("\\s+");
                if (p.length >= 2) tap(Integer.parseInt(p[0]), Integer.parseInt(p[1]), 800);
                else sendLine("ERR LONGCLICK args");
                return;
            }
            if (cmd.startsWith("ACT_CLICK ")) { actClick(cmd.substring(10)); return; }
            if (cmd.startsWith("FIND ")) { findAndSend(cmd.substring(5)); return; }
            if (cmd.startsWith("TAP_TEXT ")) { tapByText(cmd.substring(9)); return; }
            if (cmd.startsWith("SETCLIP ")) {
                String decoded = base64Decode(cmd.substring(8).trim());
                setClip(decoded);
                sendLine("SETCLIP ok len=" + decoded.length());
                return;
            }
            if (cmd.equals("BUFFER_WATCH on")) {
                startBufferWatch();
                sendLine("BUFFER_WATCH on ok");
                return;
            }
            if (cmd.equals("BUFFER_WATCH off")) {
                stopBufferWatch();
                sendLine("BUFFER_WATCH off ok");
                return;
            }
            if (cmd.equals("BUFFER_WATCH status")) {
                sendLine("BUFFER_WATCH " + (bufferWatchEnabled ? "on" : "off"));
                return;
            }
            if (cmd.equals("RUN_FROM_DUMP")) {
                runFromDump();
                sendLine("RUN_FROM_DUMP started");
                return;
            }
            sendLine("UNKNOWN_CMD " + cmd);
        } catch (Throwable t) { sendLine("ERR: " + t); }
    }

    // ================= PROBE_FIELD =================
    private void probeFieldAndSend() {
        List<String> out = new ArrayList<String>();
        out.add("PROBE_FIELD_BEGIN");
        try {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null) { out.add("root=null"); sendBlock(out); return; }
            probeWalk(root, 0, out, 1900);
        } catch (Throwable t) { out.add("ERR " + t); }
        out.add("PROBE_FIELD_END");
        sendBlock(out);
    }

    private void probeWalk(AccessibilityNodeInfo node, int depth, List<String> out, int minY) {
        if (node == null || out.size() > 300 || depth > 40) return;
        try {
            Rect r = new Rect();
            node.getBoundsInScreen(r);
            if (r.top >= minY || r.bottom >= minY) {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < depth && i < 30; i++) sb.append(" ");
                String cls = String.valueOf(node.getClassName());
                int dot = cls.lastIndexOf('.');
                if (dot >= 0 && dot + 1 < cls.length()) cls = cls.substring(dot + 1);
                sb.append(cls);
                sb.append(" [").append(r.left).append(',').append(r.top).append(',')
                  .append(r.right).append(',').append(r.bottom).append(']');
                sb.append(" CLK=").append(node.isClickable());
                sb.append(" FOC=").append(node.isFocusable());
                sb.append(" ISF=").append(node.isFocused());
                sb.append(" EDT=").append(node.isEditable());
                sb.append(" EN=").append(node.isEnabled());
                sb.append(" LCLK=").append(node.isLongClickable());
                sb.append(" SCR=").append(node.isScrollable());
                // actions
                List<AccessibilityNodeInfo.AccessibilityAction> acts = node.getActionList();
                sb.append(" actions=");
                if (acts != null) {
                    boolean first = true;
                    for (AccessibilityNodeInfo.AccessibilityAction a : acts) {
                        if (!first) sb.append(",");
                        first = false;
                        int id = a.getId();
                        String lbl = "";
                        try { CharSequence l = a.getLabel(); if (l != null) lbl = ":" + l; } catch (Throwable ignored) {}
                        sb.append(id).append(lbl);
                    }
                }
                CharSequence t = node.getText();
                if (t != null && t.length() > 0) sb.append(" text=\"").append(trunc(escape(t.toString()))).append('"');
                CharSequence d = node.getContentDescription();
                if (d != null && d.length() > 0) sb.append(" desc=\"").append(trunc(escape(d.toString()))).append('"');
                out.add(sb.toString());
            }
            int n = node.getChildCount();
            for (int i = 0; i < n; i++) probeWalk(node.getChild(i), depth + 1, out, minY);
        } catch (Throwable ignored) {}
    }

    // ================= TRY_HARD =================
    private void tryHard() {
        List<String> out = new ArrayList<String>();
        out.add("TRY_HARD_BEGIN");
        try {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null) { out.add("root=null"); sendBlock(out); return; }
            AccessibilityNodeInfo field = findByTextRegex(root,
                "(Введите сообщение|Напишите или удерживайте)");
            if (field == null) { out.add("field not found"); sendBlock(out); return; }
            Rect r = new Rect();
            field.getBoundsInScreen(r);
            int cx = (r.left + r.right) / 2;
            int cy = (r.top + r.bottom) / 2;
            out.add("field=" + field.getClassName() + " [" + r.left + "," + r.top + "," + r.right + "," + r.bottom + "]");

            // 1. ACTION_SET_TEXT прямо на узел-заглушку
            out.add("--- 1. ACTION_SET_TEXT на TextView-заглушку");
            Bundle a = new Bundle();
            a.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "PROBE");
            boolean ok = field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, a);
            out.add("   ok=" + ok);
            Thread.sleep(500);
            if (checkEditTextNow(out)) { out.add("OK после 1"); out.add("TRY_HARD_END"); sendBlock(out); return; }

            // 2. Найти родителя EditText для target
            AccessibilityNodeInfo parent = field.getParent(); // ViewFactoryHolder
            if (parent != null) {
                out.add("--- 2. ACTION_SET_TEXT на ViewFactoryHolder");
                Bundle b = new Bundle();
                b.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "PROBE");
                ok = parent.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, b);
                out.add("   ok=" + ok);
                Thread.sleep(500);
                if (checkEditTextNow(out)) { out.add("OK после 2"); out.add("TRY_HARD_END"); sendBlock(out); return; }
            }

            // 3. ACTION_FOCUS + SET_TEXT на родителя
            if (parent != null) {
                out.add("--- 3. ACTION_FOCUS+SET_TEXT на VFH");
                parent.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
                Bundle c = new Bundle();
                c.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "PROBE");
                ok = parent.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, c);
                out.add("   ok=" + ok);
                Thread.sleep(500);
                if (checkEditTextNow(out)) { out.add("OK после 3"); out.add("TRY_HARD_END"); sendBlock(out); return; }
            }

            // 4. Обычный tap по центру с временем 40..800
            int[] durs = {40, 80, 150, 300, 500, 800};
            for (int d : durs) {
                out.add("--- tap " + d + "ms @" + cx + "," + cy);
                gestureTap(cx, cy, d);
                Thread.sleep(600);
                if (checkEditTextNow(out)) { out.add("OK после tap " + d + "ms"); out.add("TRY_HARD_END"); sendBlock(out); return; }
            }

            // 5. Двойной тап
            out.add("--- двойной tap 60/100/60");
            gestureTap(cx, cy, 60);
            Thread.sleep(100);
            gestureTap(cx, cy, 60);
            Thread.sleep(700);
            if (checkEditTextNow(out)) { out.add("OK после двойного"); out.add("TRY_HARD_END"); sendBlock(out); return; }

            // 6. tap со смещением
            out.add("--- tap с микро-сдвигом");
            try {
                Path p = new Path();
                p.moveTo(cx - 2, cy - 2);
                p.lineTo(cx + 2, cy + 2);
                GestureDescription.StrokeDescription stroke =
                    new GestureDescription.StrokeDescription(p, 0, 80);
                GestureDescription gd = new GestureDescription.Builder().addStroke(stroke).build();
                dispatchGesture(gd, null, null);
            } catch (Throwable t) { out.add("err: " + t); }
            Thread.sleep(700);
            if (checkEditTextNow(out)) { out.add("OK после сдвига"); out.add("TRY_HARD_END"); sendBlock(out); return; }

            // 7. tap по верху поля (может, центр занят placeholder'ом)
            int yTop = r.top + 20;
            out.add("--- tap у верха @" + cx + "," + yTop);
            gestureTap(cx, yTop, 100);
            Thread.sleep(700);
            if (checkEditTextNow(out)) { out.add("OK после tap-в-верх"); out.add("TRY_HARD_END"); sendBlock(out); return; }

            // 8. tap по нижнему краю
            int yBot = r.bottom - 20;
            out.add("--- tap у низа @" + cx + "," + yBot);
            gestureTap(cx, yBot, 100);
            Thread.sleep(700);
            if (checkEditTextNow(out)) { out.add("OK после tap-в-низ"); out.add("TRY_HARD_END"); sendBlock(out); return; }

            // 9. tap в другую точку x (левая треть)
            int xL = r.left + (r.right - r.left) / 4;
            out.add("--- tap левая треть @" + xL + "," + cy);
            gestureTap(xL, cy, 120);
            Thread.sleep(700);
            if (checkEditTextNow(out)) { out.add("OK после левая-треть"); out.add("TRY_HARD_END"); sendBlock(out); return; }

            // 10. ACTION_ACCESSIBILITY_FOCUS на field
            out.add("--- ACTION_ACCESSIBILITY_FOCUS на field");
            field.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS);
            Thread.sleep(400);
            if (checkEditTextNow(out)) { out.add("OK после AAF"); out.add("TRY_HARD_END"); sendBlock(out); return; }

            // 11. ACTION_CLICK на field с expect фокуса
            out.add("--- ACTION_CLICK на field");
            field.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            Thread.sleep(400);
            if (checkEditTextNow(out)) { out.add("OK после AC field"); out.add("TRY_HARD_END"); sendBlock(out); return; }

            // 12. ACTION_CLICK на frame parent
            if (parent != null) {
                out.add("--- ACTION_CLICK на VFH");
                parent.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                Thread.sleep(400);
                if (checkEditTextNow(out)) { out.add("OK после AC VFH"); out.add("TRY_HARD_END"); sendBlock(out); return; }
            }

            // 13. Двойной tap с большой паузой между
            out.add("--- два tap с паузой 300ms");
            gestureTap(cx, cy, 80);
            Thread.sleep(300);
            gestureTap(cx, cy, 80);
            Thread.sleep(700);
            if (checkEditTextNow(out)) { out.add("OK после 2 с паузой"); out.add("TRY_HARD_END"); sendBlock(out); return; }

            // 14. Три тапа
            out.add("--- три tap 60мс");
            for (int i = 0; i < 3; i++) { gestureTap(cx, cy, 60); Thread.sleep(100); }
            Thread.sleep(800);
            if (checkEditTextNow(out)) { out.add("OK после 3 tap"); out.add("TRY_HARD_END"); sendBlock(out); return; }

            out.add("ВСЁ ПРОВАЛЕНО");
        } catch (Throwable t) { out.add("ERR " + t); }
        out.add("TRY_HARD_END");
        sendBlock(out);
    }

    private boolean checkEditTextNow(List<String> out) {
        try {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null) { out.add("  root=null"); return false; }
            AccessibilityNodeInfo edit = findFirstEditText(root);
            if (edit != null) {
                Rect er = new Rect();
                edit.getBoundsInScreen(er);
                out.add("  >>> EditText [" + er.left + "," + er.top + "," + er.right + "," + er.bottom + "]");
                return true;
            }
            out.add("  нет EditText");
            return false;
        } catch (Throwable t) { out.add("  check err"); return false; }
    }

    private AccessibilityNodeInfo findByTextRegex(AccessibilityNodeInfo node, String regex) {
        if (node == null) return null;
        try {
            Pattern p = Pattern.compile(regex);
            CharSequence t = node.getText();
            CharSequence d = node.getContentDescription();
            if (t != null && p.matcher(t.toString()).find()) return node;
            if (d != null && p.matcher(d.toString()).find()) return node;
            int n = node.getChildCount();
            for (int i = 0; i < n; i++) {
                AccessibilityNodeInfo r = findByTextRegex(node.getChild(i), regex);
                if (r != null) return r;
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private void gestureTap(int x, int y, int durMs) {
        try {
            Path p = new Path();
            p.moveTo(x, y);
            GestureDescription.StrokeDescription stroke =
                new GestureDescription.StrokeDescription(p, 0, durMs);
            GestureDescription gd = new GestureDescription.Builder().addStroke(stroke).build();
            dispatchGesture(gd, null, null);
        } catch (Throwable t) { sendLine("gestureTap err " + t); }
    }

    private void focusAndType(String text) {
        try {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null) { sendLine("FOCUS_AND_TYPE root=null"); return; }
            AccessibilityNodeInfo edit = findFirstEditText(root);
            if (edit == null) { sendLine("FOCUS_AND_TYPE no_edittext"); return; }
            edit.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
            Bundle args = new Bundle();
            args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
            boolean ok = edit.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
            sendLine("FOCUS_AND_TYPE SET_TEXT ok=" + ok + " len=" + text.length());
        } catch (Throwable t) { sendLine("FOCUS_AND_TYPE_ERR " + t); }
    }

    private void openUrl(String url) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
            sendLine("OPEN_URL ok " + url);
        } catch (Throwable t) {
            sendLine("OPEN_URL_ERR " + t);
        }
    }

    private void openApp(String pkg) {
        try {
            PackageManager pm = getPackageManager();
            Intent launch = pm.getLaunchIntentForPackage(pkg);
            if (launch == null) { sendLine("OPEN_APP no_launch_intent " + pkg); return; }
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            startActivity(launch);
            sendLine("OPEN_APP ok " + pkg);
        } catch (Throwable t) { sendLine("OPEN_APP_ERR " + t); }
    }

    private void tap(int x, int y, int durationMs) {
        try {
            Path p = new Path();
            p.moveTo(x, y);
            GestureDescription.StrokeDescription stroke =
                new GestureDescription.StrokeDescription(p, 0, durationMs);
            GestureDescription gd = new GestureDescription.Builder().addStroke(stroke).build();
            boolean ok = dispatchGesture(gd, null, null);
            sendLine("CLICK ok=" + ok + " at " + x + "," + y + " dur=" + durationMs);
        } catch (Throwable t) { sendLine("CLICK_ERR " + t); }
    }

    private AccessibilityNodeInfo findFirstEditText(AccessibilityNodeInfo node) {
        if (node == null) return null;
        try {
            String cls = String.valueOf(node.getClassName());
            if (cls != null && cls.contains("EditText")) return node;
            int n = node.getChildCount();
            for (int i = 0; i < n; i++) {
                AccessibilityNodeInfo r = findFirstEditText(node.getChild(i));
                if (r != null) return r;
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private void setTextOnEditText(String text) {
        try {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null) { sendLine("SETTEXT root=null"); return; }
            AccessibilityNodeInfo edit = findFirstEditText(root);
            if (edit == null) { sendLine("SETTEXT no_edittext"); return; }
            Bundle args = new Bundle();
            args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
            boolean ok = edit.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
            sendLine("SETTEXT ok=" + ok + " len=" + text.length());
        } catch (Throwable t) { sendLine("SETTEXT_ERR " + t); }
    }

    private void findFocusAndSend() {
        try {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null) { sendLine("FOCUS root=null"); return; }
            AccessibilityNodeInfo focus = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
            if (focus == null) { sendLine("FOCUS none"); return; }
            Rect r = new Rect();
            focus.getBoundsInScreen(r);
            sendLine("FOCUS cls=" + focus.getClassName() + " [" + r.left + "," + r.top + "," + r.right + "," + r.bottom + "]");
        } catch (Throwable t) { sendLine("FOCUS_ERR " + t); }
    }

    private void pasteIntoFocused() {
        try {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null) { sendLine("PASTE root=null"); return; }
            AccessibilityNodeInfo focus = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
            if (focus == null || !String.valueOf(focus.getClassName()).contains("EditText")) {
                AccessibilityNodeInfo edit = findFirstEditText(root);
                if (edit != null) focus = edit;
            }
            if (focus == null) { sendLine("PASTE no_edittext"); return; }
            boolean ok = focus.performAction(AccessibilityNodeInfo.ACTION_PASTE);
            sendLine("PASTE ok=" + ok);
        } catch (Throwable t) { sendLine("PASTE_ERR " + t); }
    }

    private void findAndSend(String regex) {
        List<String> out = new ArrayList<String>();
        out.add("FIND_BEGIN re=\"" + regex + "\"");
        try {
            Pattern pat = Pattern.compile(regex);
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null) { out.add("root=null"); }
            else {
                List<NodeHit> hits = new ArrayList<NodeHit>();
                collect(root, pat, hits, 0);
                out.add("FIND_HITS " + hits.size());
                int i = 0;
                for (NodeHit h : hits) {
                    if (i++ >= 30) break;
                    out.add("  #" + h.cls + " [" + h.rect.left + "," + h.rect.top + ","
                        + h.rect.right + "," + h.rect.bottom + "] clk=" + h.clickable
                        + " text=\"" + trunc(escape(h.text)) + "\"");
                }
            }
        } catch (Throwable t) { out.add("ERR " + t); }
        out.add("FIND_END");
        sendBlock(out);
    }

    private static class NodeHit { String cls; Rect rect; boolean clickable; String text; String desc; }

    private void collect(AccessibilityNodeInfo node, Pattern pat, List<NodeHit> out, int depth) {
        if (node == null || out.size() > 200 || depth > 40) return;
        try {
            CharSequence t = node.getText();
            CharSequence d = node.getContentDescription();
            String ts = t == null ? "" : t.toString();
            String ds = d == null ? "" : d.toString();
            boolean mt = !ts.isEmpty() && pat.matcher(ts).find();
            boolean md = !ds.isEmpty() && pat.matcher(ds).find();
            if (mt || md) {
                NodeHit h = new NodeHit();
                String cls = String.valueOf(node.getClassName());
                int dot = cls.lastIndexOf('.');
                h.cls = dot >= 0 ? cls.substring(dot + 1) : cls;
                h.rect = new Rect();
                node.getBoundsInScreen(h.rect);
                h.clickable = node.isClickable();
                h.text = ts;
                h.desc = ds;
                out.add(h);
            }
            int n = node.getChildCount();
            for (int i = 0; i < n; i++) collect(node.getChild(i), pat, out, depth + 1);
        } catch (Throwable ignored) {}
    }

    private void actClick(String regex) {
        try {
            Pattern pat = Pattern.compile(regex);
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null) { sendLine("ACT_CLICK root=null"); return; }
            // Найдём узел по text/desc
            AccessibilityNodeInfo hit = findByTextRegex(root, regex);
            if (hit == null) { sendLine("ACT_CLICK no_match"); return; }
            Rect hr = new Rect();
            hit.getBoundsInScreen(hr);
            sendLine("ACT_CLICK найден " + hit.getClassName() + " [" + hr.left + "," + hr.top + "," + hr.right + "," + hr.bottom + "]");

            // 1. Прямой ACTION_CLICK на узле
            boolean ok = hit.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            sendLine("ACT_CLICK S1 (на узле) ok=" + ok);
            if (ok) return;

            // 2. Ищем кликабельного родителя (до 5 уровней вверх)
            AccessibilityNodeInfo par = hit;
            for (int i = 1; i <= 5; i++) {
                par = par.getParent();
                if (par == null) break;
                if (par.isClickable()) {
                    Rect pr = new Rect();
                    par.getBoundsInScreen(pr);
                    boolean pok = par.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                    sendLine("ACT_CLICK S2." + i + " на родителе " + par.getClassName()
                        + " [" + pr.left + "," + pr.top + "," + pr.right + "," + pr.bottom + "] ok=" + pok);
                    if (pok) return;
                }
            }

            // 3. Кликаем по координатам кликабельного родителя (жест)
            for (AccessibilityNodeInfo p2 = hit; p2 != null; p2 = p2.getParent()) {
                if (p2.isClickable()) {
                    Rect pr = new Rect();
                    p2.getBoundsInScreen(pr);
                    int cx = (pr.left + pr.right) / 2;
                    int cy = (pr.top + pr.bottom) / 2;
                    sendLine("ACT_CLICK S3 (жест в родителя) " + cx + "," + cy);
                    gestureTap(cx, cy, 120);
                    return;
                }
            }

            // 4. Крайний случай — жест по самому узлу
            int cx = (hr.left + hr.right) / 2;
            int cy = (hr.top + hr.bottom) / 2;
            sendLine("ACT_CLICK S4 (жест в узел) " + cx + "," + cy);
            gestureTap(cx, cy, 120);
        } catch (Throwable t) { sendLine("ACT_CLICK_ERR " + t); }
    }

    private void tapByText(String regex) {
        try {
            Pattern pat = Pattern.compile(regex);
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null) { sendLine("TAP_TEXT root=null"); return; }
            List<NodeHit> hits = new ArrayList<NodeHit>();
            collect(root, pat, hits, 0);
            if (hits.isEmpty()) { sendLine("TAP_TEXT no_match"); return; }
            NodeHit h = hits.get(hits.size() - 1);
            int cx = (h.rect.left + h.rect.right) / 2;
            int cy = (h.rect.top + h.rect.bottom) / 2;
            sendLine("TAP_TEXT tap " + cx + "," + cy);
            tap(cx, cy, 40);
        } catch (Throwable t) { sendLine("TAP_TEXT_ERR " + t); }
    }

    private void getClipAndSend() {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm == null) { sendLine("CLIP null"); return; }
            ClipData clip = cm.getPrimaryClip();
            if (clip == null || clip.getItemCount() == 0) { sendLine("CLIP_EMPTY"); return; }
            CharSequence cs = clip.getItemAt(0).coerceToText(this);
            String s = cs == null ? "" : cs.toString();
            String b64 = Base64.encodeToString(s.getBytes("UTF-8"), Base64.NO_WRAP);
            sendLine("CLIP_B64 " + b64);
        } catch (Throwable t) { sendLine("CLIP_ERR " + t); }
    }

    private void setClip(String s) {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm == null) return;
            cm.setPrimaryClip(ClipData.newPlainText("aibridge", s));
        } catch (Throwable t) { sendLine("SETCLIP_ERR " + t); }
    }

    private void dumpAndSend() {
        List<String> lines = new ArrayList<String>();
        lines.add("DUMP_BEGIN " + SDF.format(new Date()));
        try {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null) { lines.add("DUMP_EMPTY root=null"); }
            else {
                CharSequence pkg = root.getPackageName();
                lines.add("DUMP_ROOT pkg=" + (pkg == null ? "?" : pkg.toString()));
                walk(root, 0, lines);
            }
        } catch (Throwable t) { lines.add("DUMP_ERR " + t); }
        lines.add("DUMP_END lines=" + lines.size());
        sendBlock(lines);
    }

    private void walk(AccessibilityNodeInfo node, int depth, List<String> out) {
        if (node == null || out.size() >= MAX_DUMP_LINES) return;
        try {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < depth && i < 40; i++) sb.append("  ");
            String cls = String.valueOf(node.getClassName());
            int dot = cls.lastIndexOf('.');
            if (dot >= 0 && dot + 1 < cls.length()) cls = cls.substring(dot + 1);
            sb.append(cls);
            Rect r = new Rect();
            node.getBoundsInScreen(r);
            sb.append(" [").append(r.left).append(',').append(r.top).append(',')
              .append(r.right).append(',').append(r.bottom).append(']');
            if (node.isClickable()) sb.append(" CLK");
            CharSequence txt = node.getText();
            if (txt != null && txt.length() > 0) sb.append(" text=\"").append(trunc(escape(txt.toString()))).append('"');
            CharSequence desc = node.getContentDescription();
            if (desc != null && desc.length() > 0) sb.append(" desc=\"").append(trunc(escape(desc.toString()))).append('"');
            out.add(sb.toString());
            int n = node.getChildCount();
            for (int i = 0; i < n; i++) walk(node.getChild(i), depth + 1, out);
        } catch (Throwable t) { out.add("ERR walk: " + t); }
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }
    private static String trunc(String s) {
        if (s.length() <= MAX_LINE_LEN) return s;
        return s.substring(0, MAX_LINE_LEN) + "...[cut]";
    }
    private static String base64Decode(String s) {
        try { return new String(Base64.decode(s, Base64.DEFAULT), "UTF-8"); }
        catch (Throwable t) { return ""; }
    }

    private void sendLine(final String line) {
        new Thread(new Runnable() { public void run() { doSend(new String[]{line}); } }).start();
    }
    private void sendBlock(final List<String> lines) {
        new Thread(new Runnable() {
            public void run() { doSend(lines.toArray(new String[0])); }
        }).start();
    }
    private void doSend(String[] lines) {
        Socket s = null;
        try {
            s = new Socket();
            s.connect(new InetSocketAddress(HOST, PORT_EVENTS), 1500);
            OutputStream os = s.getOutputStream();
            for (String l : lines) os.write((l + "\n").getBytes("UTF-8"));
            os.flush();
        } catch (Throwable t) { Log.w(TAG, "send failed: " + t); }
        finally { try { if (s != null) s.close(); } catch (Throwable ignored) {} }
    }

    // ===== EXECUTE FROM BUFFER =====

    private static final String[] BLOCKED_PATTERNS = {
        "rm -rf /", "rm -rf ~", "mkfs.", "dd of=/dev", "shutdown", "reboot",
        ":(){ :|:& };:", "> /dev/sd", "sudo rm", "su -c rm",
    };

    private boolean isBlocked(String code) {
        String c = code.toLowerCase();
        for (String p : BLOCKED_PATTERNS) {
            if (c.contains(p.toLowerCase())) return true;
        }
        return false;
    }

    private void executeFromBuffer(final String code) {
        new Thread(new Runnable() {
            public void run() {
                try {
                    // 1. Blacklist
                    if (isBlocked(code)) {
                        sendLine("BUFFER_EXEC blocked");
                        notifyOverlay("off");
                        return;
                    }

                    // 2. Индикатор = busy (жёлтый)
                    notifyOverlay("busy");

                    // 3. Пишем в inbox
                    String taskId = "buf" + System.currentTimeMillis();
                    File inboxDir = new File("/sdcard/ai-tasker/inbox");
                    inboxDir.mkdirs();
                    File taskFile = new File(inboxDir, "task-" + taskId + ".txt");
                    FileWriter fw = new FileWriter(taskFile);
                    fw.write("auto:" + code);
                    fw.close();
                    sendLine("BUFFER_EXEC written task-" + taskId);

                    // 4. Ждём результат (до 90 сек)
                    File outFile = new File("/sdcard/ai-tasker/outbox", "task-" + taskId + ".json");
                    long t0 = System.currentTimeMillis();
                    String result = null;
                    String status = null;
                    while (System.currentTimeMillis() - t0 < 90000) {
                        if (!bufferWatchEnabled) return;
                        Thread.sleep(700);
                        if (outFile.exists()) {
                            try {
                                BufferedReader br = new BufferedReader(new FileReader(outFile));
                                StringBuilder sb = new StringBuilder();
                                String line;
                                while ((line = br.readLine()) != null) sb.append(line);
                                br.close();
                                JSONObject o = new JSONObject(sb.toString());
                                String st = o.optString("status", "");
                                if ("success".equals(st) || "failed".equals(st) || "error".equals(st)) {
                                    result = o.optString("output", "");
                                    status = st;
                                    break;
                                }
                            } catch (Exception ignored) {}
                        }
                    }

                    if (result == null) {
                        sendLine("BUFFER_EXEC timeout");
                        notifyOverlay("on");
                        return;
                    }

                    // 5. Обрезаем результат если слишком длинный
                    if (result.length() > 6000) {
                        result = result.substring(0, 6000) + "\n...[обрезано]";
                    }

                    // 6. Открываем DeepSeek и вставляем результат
                    openApp("com.deepseek.chat");
                    Thread.sleep(1500);

                    String reply = "Результат (" + status + "):\n```\n" + result + "\n```";
                    // Тап в поле, чтобы фокус был на EditText
                    tap(537, 2055, 40);
                    Thread.sleep(800);

                    setTextOnEditText(reply);
                    sendLine("BUFFER_EXEC result inserted");

                    notifyOverlay("on");
                } catch (Throwable t) {
                    sendLine("BUFFER_EXEC_ERR " + t);
                    notifyOverlay("on");
                }
            }
        }, "buffer-exec").start();
    }

    private void notifyOverlay(String state) {
        try {
            Intent i = new Intent(this, OverlayService.class);
            i.setAction(OverlayService.ACTION_SET_STATE);
            i.putExtra(OverlayService.EXTRA_STATE, state);
            startService(i);
        } catch (Throwable ignored) {}
    }

    // ===== RUN FROM DUMP (QuickTile) =====

    private void runFromDump() {
        new Thread(new Runnable() {
            public void run() {
                try {
                    notifyOverlay("busy");

                    // 1. Открыть DeepSeek, дать ему отрисоваться
                    sendLine("OPEN_APP com.deepseek.chat");
                    Thread.sleep(2500);

                    // 2. Извлечь последний код-блок прямо из дерева (без файла!)
                    String code = extractLastCodeBlockFromTree();

                    if (code == null || code.trim().isEmpty()) {
                        sendLine("TILE_TASK no_code");
                        // Всё равно отправим результат с ошибкой
                        String taskId = "tile" + System.currentTimeMillis();
                        File outDir = new File("/sdcard/ai-tasker/outbox");
                        outDir.mkdirs();
                        File outFile = new File(outDir, "task-" + taskId + ".json");
                        FileWriter fw = new FileWriter(outFile);
                        fw.write("{\"id\":\"" + taskId + "\",\"status\":\"failed\",\"output\":\"(код-блок не найден в дереве DeepSeek)\",\"exit_code\":1}");
                        fw.close();

                        // Вставить результат
                        openApp("com.deepseek.chat");
                        Thread.sleep(1500);
                        tap(537, 2055, 40);
                        Thread.sleep(700);
                        setTextOnEditText("Результат (failed):\n```\n(код-блок не найден в дереве DeepSeek)\n```");
                        notifyOverlay("on");
                        return;
                    }

                    sendLine("TILE_TASK code_len=" + code.length() + " first_line=\"" + code.split("\\n")[0] + "\"");

                    // 3. Отправить код как задачу auto: (демон выполнит напрямую)
                    String taskId = "tile" + System.currentTimeMillis();
                    File inboxDir = new File("/sdcard/ai-tasker/inbox");
                    inboxDir.mkdirs();
                    File taskFile = new File(inboxDir, "task-" + taskId + ".txt");
                    FileWriter fw = new FileWriter(taskFile);
                    fw.write("auto:" + code);
                    fw.close();
                    sendLine("TILE_TASK written task-" + taskId);

                    // 4. Ждём результат в outbox
                    File outFile = new File("/sdcard/ai-tasker/outbox", "task-" + taskId + ".json");
                    long t0 = System.currentTimeMillis();
                    String result = null;
                    String status = null;
                    while (System.currentTimeMillis() - t0 < 60000) {
                        Thread.sleep(500);
                        if (outFile.exists()) {
                            try {
                                BufferedReader br = new BufferedReader(new FileReader(outFile));
                                StringBuilder sb = new StringBuilder();
                                String line;
                                while ((line = br.readLine()) != null) sb.append(line);
                                br.close();
                                JSONObject o = new JSONObject(sb.toString());
                                String st = o.optString("status", "");
                                if ("success".equals(st) || "failed".equals(st) || "error".equals(st)) {
                                    result = o.optString("output", "");
                                    status = st;
                                    break;
                                }
                            } catch (Exception ignored) {}
                        }
                    }

                    if (result == null) {
                        sendLine("TILE_TASK timeout");
                        notifyOverlay("off");
                        return;
                    }

                    if (result.length() > 6000) {
                        result = result.substring(0, 6000) + "\n...[обрезано]";
                    }

                    // 5. Открыть DeepSeek, вставить результат
                    openApp("com.deepseek.chat");
                    Thread.sleep(1500);
                    tap(537, 2055, 40);
                    Thread.sleep(700);

                    String reply = "Результат (" + status + "):\n```\n" + result + "\n```";
                    setTextOnEditText(reply);
                    sendLine("TILE_TASK result inserted");

                    notifyOverlay("on");
                } catch (Throwable t) {
                    sendLine("TILE_TASK_ERR " + t);
                    notifyOverlay("off");
                }
            }
        }, "tile-handler").start();
    }

    // ===== COPY CLICK HANDLER =====

    private void handleCopyClick() {
        new Thread(new Runnable() {
            public void run() {
                try {
                    notifyOverlay("busy");

                    // 1. Снять свежий DUMP — чтобы дерево было актуальным
                    sendLine("DUMP");
                    Thread.sleep(1200);

                    // 2. Написать задачу в inbox: copy_exec:
                    String taskId = "copy" + System.currentTimeMillis();
                    File inboxDir = new File("/sdcard/ai-tasker/inbox");
                    inboxDir.mkdirs();
                    File taskFile = new File(inboxDir, "task-" + taskId + ".txt");
                    FileWriter fw = new FileWriter(taskFile);
                    fw.write("copy_exec:");
                    fw.close();
                    sendLine("COPY_TASK written task-" + taskId);

                    // 3. Ждать outbox до 30 сек
                    File outFile = new File("/sdcard/ai-tasker/outbox", "task-" + taskId + ".json");
                    long t0 = System.currentTimeMillis();
                    String result = null;
                    String status = null;
                    while (System.currentTimeMillis() - t0 < 30000) {
                        if (!bufferWatchEnabled) return;
                        Thread.sleep(500);
                        if (outFile.exists()) {
                            try {
                                BufferedReader br = new BufferedReader(new FileReader(outFile));
                                StringBuilder sb = new StringBuilder();
                                String line;
                                while ((line = br.readLine()) != null) sb.append(line);
                                br.close();
                                JSONObject o = new JSONObject(sb.toString());
                                String st = o.optString("status", "");
                                if ("success".equals(st) || "failed".equals(st) || "error".equals(st)) {
                                    result = o.optString("output", "");
                                    status = st;
                                    break;
                                }
                            } catch (Exception ignored) {}
                        }
                    }

                    if (result == null) {
                        sendLine("COPY_TASK timeout");
                        notifyOverlay("on");
                        return;
                    }

                    if (result.length() > 6000) {
                        result = result.substring(0, 6000) + "\n...[обрезано]";
                    }

                    // 4. Открыть DeepSeek и вставить результат
                    openApp("com.deepseek.chat");
                    Thread.sleep(1500);
                    tap(537, 2055, 40);
                    Thread.sleep(700);

                    String reply = "Результат (" + status + "):\n```\n" + result + "\n```";
                    setTextOnEditText(reply);
                    sendLine("COPY_TASK result inserted");

                    notifyOverlay("on");
                } catch (Throwable t) {
                    sendLine("COPY_TASK_ERR " + t);
                    notifyOverlay("on");
                }
            }
        }, "copy-handler").start();
    }

    // ===== BUFFER WATCH (свободный режим) =====

    private void startBufferWatch() {
        if (bufferWatchEnabled) return;
        bufferWatchEnabled = true;
        bufferWatchThread = new Thread(new Runnable() {
            public void run() { bufferLoop(); }
        }, "buffer-watch");
        bufferWatchThread.setDaemon(true);
        bufferWatchThread.start();
        Log.i(TAG, "buffer watch started");
    }

    private void stopBufferWatch() {
        bufferWatchEnabled = false;
        if (bufferWatchThread != null) {
            try { bufferWatchThread.interrupt(); } catch (Throwable ignored) {}
            bufferWatchThread = null;
        }
        Log.i(TAG, "buffer watch stopped");
    }

    private void bufferLoop() {
        String initial = readClipboard();
        lastBufferHash = sha256(initial);
        sendLine("BUFFER_WATCH started; init_clip_len=" + (initial == null ? "NULL" : initial.length()));

        try { Thread.sleep(1000); } catch (Exception e) { return; }

        int heartbeat = 0;
        while (bufferWatchEnabled && !Thread.currentThread().isInterrupted()) {
            try { Thread.sleep(1500); } catch (InterruptedException e) { break; }
            if (!bufferWatchEnabled) break;

            String txt = readClipboard();
            heartbeat++;

            // Heartbeat каждые 10 итераций (15 сек)
            if (heartbeat % 10 == 0) {
                sendLine("BUFFER_WATCH heartbeat #" + heartbeat + " clip=" + (txt == null ? "NULL" : "len=" + txt.length()));
            }

            if (txt == null) continue;
            if (txt.length() < 5) continue;

            String h = sha256(txt);
            if (h.equals(lastBufferHash)) continue;
            lastBufferHash = h;

            sendLine("BUFFER_WATCH changed hash=" + h + " len=" + txt.length());

            if (!looksLikeCode(txt)) {
                sendLine("BUFFER_WATCH not_code");
                continue;
            }

            sendLine("BUFFER_WATCH exec! code=\"" + txt.substring(0, Math.min(60, txt.length())) + "...\"");
            executeFromBuffer(txt);
        }
        sendLine("BUFFER_WATCH loop ended");
    }

    private String readClipboard() {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm == null || !cm.hasPrimaryClip()) return null;
            ClipData clip = cm.getPrimaryClip();
            if (clip == null || clip.getItemCount() == 0) return null;
            CharSequence cs = clip.getItemAt(0).coerceToText(this);
            return cs == null ? null : cs.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    private static String sha256(String s) {
        if (s == null) return "";
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(s.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString().substring(0, 16);
        } catch (Exception e) {
            return "";
        }
    }

    private boolean looksLikeCode(String txt) {
        if (txt == null) return false;
        if (txt.length() < 5) return false;
        if (txt.length() > 8000) return false;

        String t = txt.trim();
        if (t.startsWith("http://") || t.startsWith("https://")) return false;

        String[] lines = t.split("\n");
        if (lines.length < 1) return false;

        String[] firstWords = {"ls","cat","cd","echo","grep","python","python3","bash","sh","mkdir",
            "touch","rm","cp","mv","pip","apt","pkg","curl","wget","git","find","head","tail",
            "df","du","ps","top","kill","chmod","sudo","which","whoami","date","uptime","free"};
        String first = t.split("\\s+")[0].toLowerCase();
        for (String w : firstWords) if (first.equals(w)) return true;

        if (t.contains(" && ") || t.contains(" || ") || t.contains("$(")) return true;
        if (t.contains(" | ") || t.contains(" > ") || t.contains(" >> ")) return true;

        return false;
    }


    // ===== EXTRACT CODE FROM ACTIVE TREE =====

    private String extractLastCodeBlockFromTree() {
        try {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null) return null;
            java.util.List<AccessibilityNodeInfo> copyButtons = new java.util.ArrayList<AccessibilityNodeInfo>();
            collectCopyButtons(root, copyButtons, 0);
            if (copyButtons.isEmpty()) return null;
            String lastCode = null;
            for (AccessibilityNodeInfo btn : copyButtons) {
                String code = findCodeForButton(btn);
                if (code != null && code.length() > 0) {
                    lastCode = code;
                }
            }
            return lastCode;
        } catch (Throwable t) {
            return null;
        }
    }

    private void collectCopyButtons(AccessibilityNodeInfo node, java.util.List<AccessibilityNodeInfo> out, int depth) {
        if (node == null || depth > 50) return;
        try {
            CharSequence t = node.getText();
            if (t != null && "Копировать".equals(t.toString().trim())) {
                out.add(node);
            }
            int n = node.getChildCount();
            for (int i = 0; i < n; i++) {
                collectCopyButtons(node.getChild(i), out, depth + 1);
            }
        } catch (Throwable ignored) {}
    }

    private String findCodeForButton(AccessibilityNodeInfo btn) {
        try {
            AccessibilityNodeInfo p1 = btn.getParent();
            if (p1 == null) return null;
            AccessibilityNodeInfo p2 = p1.getParent();
            if (p2 == null) return null;
            AccessibilityNodeInfo container = p2.getParent();
            if (container == null) return null;

            int n = container.getChildCount();
            for (int i = 0; i < n; i++) {
                AccessibilityNodeInfo child = container.getChild(i);
                if (child == null) continue;
                CharSequence t = child.getText();
                if (t == null || t.length() == 0) continue;
                String txt = t.toString();
                if ("Копировать".equals(txt.trim())) continue;
                if (txt.length() < 3) continue;
                if (txt.contains("\n") || txt.contains("$") || txt.contains("|")
                    || txt.contains(">") || txt.contains("(") || txt.contains(" ")) {
                    return txt;
                }
            }
            for (int i = 0; i < n; i++) {
                AccessibilityNodeInfo child = container.getChild(i);
                if (child == null) continue;
                CharSequence t = child.getText();
                if (t != null && t.length() >= 3 && !"Копировать".equals(t.toString().trim())) {
                    return t.toString();
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

}
