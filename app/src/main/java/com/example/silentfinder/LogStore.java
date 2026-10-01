package com.example.silentfinder;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;

/**
 * 进程级日志存储：服务无论界面是否在前台都写入这里，
 * 界面 onResume 时全量恢复，不丢后台期间产生的日志。
 */
public final class LogStore {

    public static final int MAX_LINES = 500;
    public static final ArrayDeque<String> BUFFER = new ArrayDeque<>();

    private static final SimpleDateFormat FMT =
            new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.getDefault());

    private LogStore() {}

    /** 追加一条带时间戳的日志 */
    public static void append(String msg) {
        String line = FMT.format(new Date()) + "  " + msg;
        synchronized (BUFFER) {
            BUFFER.addLast(line);
            while (BUFFER.size() > MAX_LINES) {
                BUFFER.removeFirst();
            }
        }
    }

    /** 导出全部日志为一段文本（可能为空） */
    public static String dump() {
        synchronized (BUFFER) {
            StringBuilder sb = new StringBuilder();
            for (String l : BUFFER) sb.append(l).append('\n');
            return sb.toString();
        }
    }

    /** 清空日志 */
    public static void clear() {
        synchronized (BUFFER) {
            BUFFER.clear();
        }
    }
}
