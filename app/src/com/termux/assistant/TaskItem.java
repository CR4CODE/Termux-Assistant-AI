package com.termux.assistant;

public class TaskItem {
    public String id;
    public String task;
    public String status;   // pending | running | success | error
    public String output;
    public long started;    // epoch ms
    public String filePath;
    public long duration;   // ms (если известно)

    public String statusLabel() {
        if (status == null) return "•";
        switch (status) {
            case "success": return "✓";
            case "error":   return "✗";
            case "running": return "⏳";
            default:        return "•";
        }
    }

    public String statusText() {
        if (status == null) return "Неизвестно";
        switch (status) {
            case "success": return "Успешно";
            case "error":   return "Ошибка";
            case "running": return "Выполняется…";
            case "pending": return "Ожидание";
            default:        return status;
        }
    }

    public int statusColor() {
        if (status == null) return 0xFF8B919A;
        switch (status) {
            case "success": return 0xFF4CAF50;
            case "error":   return 0xFFE5484D;
            case "running": return 0xFF4C8DFF;
            default:        return 0xFF8B919A;
        }
    }

    public String timeLabel() {
        try {
            java.text.SimpleDateFormat fmt = new java.text.SimpleDateFormat("HH:mm:ss");
            return fmt.format(new java.util.Date(started));
        } catch (Exception e) {
            return "";
        }
    }

    public String durationLabel() {
        if (duration <= 0) return "";
        if (duration < 1000) return duration + " мс";
        long sec = duration / 1000;
        if (sec < 60) return sec + " сек";
        long m = sec / 60;
        long s = sec % 60;
        return m + "м " + s + "с";
    }
}
