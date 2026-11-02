package com.example.silentfinder;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.media.AudioManager;
import android.os.Build;
import android.os.IBinder;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;

import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * 前台服务：常驻内存，监听系统铃声模式变化。
 * 切换到静音模式 → 发送寻车指令（cmd/search）；
 * 2 秒内连续两次切换到震动模式 → 发送车辆启动指令（cmd/start）；
 * 拨到震动模式并保持不动 3 秒 → 发送车辆停止指令（cmd/stop）；
 * 离开震动档（拨到静音或正常）即取消停止计时。
 * authorization 必须在设置界面配置，未配置时不发送请求。
 * 网络失败（DNS 解析失败、连接超时等瞬时故障）自动重试：间隔 3 秒，最多 3 次。
 * 所有事件通过广播发送到主界面显示。
 */
public class SilentModeService extends Service {

    private static final String TAG = "SilentModeService";
    private static final int NOTIFICATION_ID = 1;
    /** 可见渠道：IMPORTANCE_LOW 显示一条常驻通知（微信同款做法，显著降低被回收概率） */
    public static final String CHANNEL_ID_VISIBLE = "silent_monitor_visible";
    /**
     * 彻底隐藏渠道：IMPORTANCE_NONE。
     * 部分 ROM（本机 ColorOS 已实测）会把 MIN 渠道强行提升为 LOW 继续显示，
     * 因此「隐藏」档使用 NONE 级别的新渠道，系统不会展示其通知条目。
     */
    private static final String CHANNEL_ID_SILENT = "silent_monitor_off";
    /** 「常驻通知」开关变更后由界面触发刷新前台通知 */
    public static final String ACTION_REFRESH_NOTIFICATION =
            "com.example.silentfinder.REFRESH_NOTIFICATION";
    private static final String BASE_URL = "https://www.tailgdd.com/v1/api/app/device/cmd/";
    /** 寻车指令（切到静音档触发） */
    private static final String CMD_SEARCH = "search";
    /** 车辆启动指令（2 秒内连续两次切到震动档触发） */
    private static final String CMD_START = "start";
    /** 车辆停止指令（拨到震动档保持不动触发） */
    private static final String CMD_STOP = "stop";
    /**
     * 连续震动判定窗口：两次「切换为震动」间隔不超过该时长视为连续。
     * 三段式键拨离再拨回震动档通常 2 秒内完成，窗口设为 2 秒。
     */
    private static final long CONSECUTIVE_WINDOW_MS = 2_000L;
    /**
     * 停止指令的保持时长：拨到震动档后保持不动超过该时长触发停止。
     * 快速双拨（2 秒内）在保持计时到点前就已触发启动，不会误停。
     */
    private static final long HOLD_STOP_MS = 3_000L;

    /** 网络失败自动重试次数（含首次在内共尝试的次数） */
    private static final int MAX_ATTEMPTS = 3;

    /** 每次重试前等待的时长：瞬时网络故障（弱信号断连、DNS 超时）通常几秒内自行恢复 */
    private static final long RETRY_DELAY_MS = 3_000L;

    /** 上一次切换为震动模式的时刻（elapsedRealtime）；0 表示无记录 */
    private long lastVibrateAt = 0L;
    // 公共 SDK 中 ACTION_RINGER_MODE_CHANGED / EXTRA_RINGER_MODE 被隐藏，使用实际字符串常量
    private static final String ACTION_RINGER_MODE_CHANGED = "android.media.RINGER_MODE_CHANGED";
    private static final String EXTRA_RINGER_MODE = "android.media.EXTRA_RINGER_MODE";
    /**
     * 上一次已处理的铃声模式。
     * 广播与轮询两条通道会对同一次切换各触发一次，按模式去重可避免重复发指令。
     */
    private int lastHandledMode = -1;

    /** 轮询周期：不依赖任何系统通知（广播/Settings），直接比较系统当前铃声模式，睡眠唤醒后也能自动对齐状态 */
    private static final long POLL_INTERVAL_MS = 1_000L;

    /** 持久化的「最后一次已处理的铃声模式」：进程被杀重启后可发现并补上遗漏的那次切换 */
    private static final String PREF_KEY_LAST_MODE = "last_ringer_mode";

    private final Runnable pollRunnable = new Runnable() {
        @Override
        public void run() {
            int mode = currentRingerMode();
            if (mode >= 0) handleRingerMode(mode);
            handler.postDelayed(this, POLL_INTERVAL_MS);
        }
    };

    /** 供主界面查询服务是否在运行 */
    public static volatile boolean isRunning = false;

    /** 用户主动停止时置 true，闹钟看门狗与自愈重启据此跳过拉起 */
    public static volatile boolean userStopped = false;

    /** 「用户主动停止」是否持久化的键：进程被杀后重建仍能记住用户的停止状态 */
    private static final String PREF_KEY_STOPPED = "stopped";

    /** 是否已成功提升为前台服务 */
    private volatile boolean foreground = false;

    /**
     * 看门狗周期：进程被杀后由系统闹钟自动拉起服务。
     * 常驻通知启用后进程存活率已大幅提高，30 秒一次的闹钟（每天 2880 次唤醒）收益很低、代价明确，
     * 故放宽到 3 分钟；服务被正常销毁时另有 2 秒的即时自愈闹钟，实际恢复通常在数秒内。
     */
    public static final long WATCHDOG_INTERVAL_MS = 180_000L;

    // ── 统一的启停入口（开机广播 / 看门狗任务 / 界面共用）──

    /** 读取持久化的「用户主动停止」标记 */
    public static boolean isUserStopped(Context context) {
        return context.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(PREF_KEY_STOPPED, false);
    }

    /** 写入「用户主动停止」标记（同时更新进程内标记，重启失效前可被其他组件直接读取） */
    public static void setUserStopped(Context context, boolean stopped) {
        userStopped = stopped;
        context.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putBoolean(PREF_KEY_STOPPED, stopped).apply();
    }

    /**
     * 拉起监听服务（唯一的启动入口）。
     * Android 8+ 禁止在后台普通 startService，必须以「前台服务」方式启动；
     * 若仍被 ROM 拦截则退化为普通启动，并由已注册的看门狗闹钟 + 持久化任务继续兜底。
     */
    public static void ensureStarted(Context context) {
        if (isUserStopped(context)) {
            Log.i(TAG, "用户已主动停止监听，跳过本次拉起");
            return;
        }
        Intent intent = new Intent(context, SilentModeService.class);
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                context.startForegroundService(intent);
            } else {
                context.startService(intent);
            }
        } catch (Throwable t) {
            Log.w(TAG, "前台方式拉起失败，退化为普通后台启动: " + t);
            try {
                context.startService(intent);
            } catch (Throwable t2) {
                Log.e(TAG, "普通后台启动也被拒绝，等待看门狗兜底: " + t2);
            }
        }
        // 注册持久化周期任务：跨关机重启存活，开机广播被拦时仍能拉起
        WatchdogJobService.schedule(context);
    }

    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    /** 主线程 Handler：实现「震动档保持 N 秒 → 停止」的延时判定 */
    private final android.os.Handler handler =
            new android.os.Handler(android.os.Looper.getMainLooper());

    /** 震动档保持计时到点后发出的停止指令 */
    private final Runnable stopAction = new Runnable() {
        @Override
        public void run() {
            logToUi("[监听] 震动档保持 " + (HOLD_STOP_MS / 1000) + " 秒，触发车辆停止指令");
            sendCmd(CMD_STOP);
        }
    };

    /** 安排停止计时（先清旧计时再排新的，重复调用安全） */
    private void scheduleStopTimer() {
        handler.removeCallbacks(stopAction);
        handler.postDelayed(stopAction, HOLD_STOP_MS);
    }

    /** 取消停止计时（离开震动档时调用） */
    private void cancelStopTimer() {
        handler.removeCallbacks(stopAction);
    }

    private final OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .build();

    private final BroadcastReceiver ringerReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!ACTION_RINGER_MODE_CHANGED.equals(intent.getAction())) return;
            int extra = intent.getIntExtra(EXTRA_RINGER_MODE, -1);
            int systemMode = currentRingerMode();
            // 部分 ROM 派发的广播不带 EXTRA_RINGER_MODE（此时 extra 为 -1），直接读 AudioManager 兜底
            int ringerMode = extra >= 0 ? extra : systemMode;
            logDebug("收到铃声广播：extra=" + modeName(extra) + "，系统当前=" + modeName(systemMode)
                    + "，采用=" + modeName(ringerMode));
            handleRingerMode(ringerMode);
        }
    };

    /**
     * 铃声模式变化处理（广播与 Settings 两条通道共用）。
     * 与上一次已处理的模式相同则忽略，避免两条通道对同一次切换各发一次指令。
     */
    private void handleRingerMode(int ringerMode) {
        if (ringerMode < 0) return;
        if (ringerMode == lastHandledMode) return;
        lastHandledMode = ringerMode;
        persistLastMode(ringerMode);
        if (ringerMode == AudioManager.RINGER_MODE_SILENT) {
            // 静音档：取消停止计时（离开震动档），清零双拨计数，立即寻车
            cancelStopTimer();
            lastVibrateAt = 0L;
            logToUi("[监听] 检测到静音模式，触发寻车请求");
            sendCmd(CMD_SEARCH);
        } else if (ringerMode == AudioManager.RINGER_MODE_VIBRATE) {
            cancelStopTimer();
            long now = SystemClock.elapsedRealtime();
            if (lastVibrateAt != 0 && (now - lastVibrateAt) <= CONSECUTIVE_WINDOW_MS) {
                // 连续第二次震动：触发车辆启动指令，并清零计数
                lastVibrateAt = 0L;
                logToUi("[监听] " + (CONSECUTIVE_WINDOW_MS / 1000) + " 秒内连续第二次震动模式，触发车辆启动指令"
                        + "（保持不动 " + (HOLD_STOP_MS / 1000) + " 秒将触发停止）");
                sendCmd(CMD_START);
            } else {
                lastVibrateAt = now;
                logToUi("[监听] 切换到震动模式（" + (CONSECUTIVE_WINDOW_MS / 1000)
                        + " 秒内再次震动将触发启动；保持不动 "
                        + (HOLD_STOP_MS / 1000) + " 秒将触发停止）");
            }
            // 保持不动到时即停；期间再次拨动会先取消本计时再重新安排
            scheduleStopTimer();
        } else if (ringerMode == AudioManager.RINGER_MODE_NORMAL) {
            // 正常档：取消停止计时。注意：不清零 lastVibrateAt，
            // 否则双拨手势（震动→正常→震动）中间的正常档事件会打断连续判定
            cancelStopTimer();
            logToUi("[监听] 恢复正常模式（不触发请求）");
        }
    }

    /** 读取持久化的「最后一次已处理的铃声模式」（无记录返回 -1） */
    private int readLastMode() {
        return getSharedPreferences(MainActivity.PREFS_NAME, MODE_PRIVATE)
                .getInt(PREF_KEY_LAST_MODE, -1);
    }

    private void persistLastMode(int mode) {
        getSharedPreferences(MainActivity.PREFS_NAME, MODE_PRIVATE)
                .edit().putInt(PREF_KEY_LAST_MODE, mode).apply();
    }

    /** 读取系统当前铃声模式（读不到返回 -1） */
    private int currentRingerMode() {
        try {
            AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
            return am != null ? am.getRingerMode() : -1;
        } catch (Throwable t) {
            return -1;
        }
    }

    /** 铃声模式的可读名称，便于在日志里判断通道实际拿到的是什么值 */
    private static String modeName(int mode) {
        switch (mode) {
            case AudioManager.RINGER_MODE_SILENT:
                return "静音(" + AudioManager.RINGER_MODE_SILENT + ")";
            case AudioManager.RINGER_MODE_VIBRATE:
                return "震动(" + AudioManager.RINGER_MODE_VIBRATE + ")";
            case AudioManager.RINGER_MODE_NORMAL:
                return "正常(" + AudioManager.RINGER_MODE_NORMAL + ")";
            default:
                return "未知(" + mode + ")";
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        // 进程可能已被杀后重建：从持久化标记恢复「用户主动停止」状态
        userStopped = isUserStopped(this);
        isRunning = true;
        startForegroundQuietly();
        IntentFilter filter = new IntentFilter(ACTION_RINGER_MODE_CHANGED);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(ringerReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(ringerReceiver, filter);
        }
        // 继承上次已处理的模式（进程被杀重启后即刻比对，补上期间被漏掉的切换）
        lastHandledMode = readLastMode();
        int bootMode = currentRingerMode();
        logToUi("[服务] 监听服务已启动（pid=" + Process.myPid() + "）");
        logDebug("上次处理模式=" + modeName(lastHandledMode) + "，当前系统模式=" + modeName(bootMode)
                + "，通知样式=" + notificationStyle(this));
        if (bootMode >= 0) {
            handleRingerMode(bootMode);
        }
        // 轮询兜底：不依赖系统任何通知，每 POLL_INTERVAL_MS 比对一次真实模式
        handler.post(pollRunnable);
        if (getAuth().isEmpty()) {
            logToUi("[服务] 尚未设置 authorization，请在菜单「设置 authorization」中配置后再触发请求");
        }
        if (getImei().isEmpty()) {
            logToUi("[服务] 尚未设置车辆 IMEI，请在菜单「设置车辆 IMEI」中配置后再触发请求");
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // 界面改了「常驻通知」开关：立刻重建通知（渠道不同需要重新 build）
        if (intent != null && ACTION_REFRESH_NOTIFICATION.equals(intent.getAction())) {
            refreshForegroundNotification();
        }
        // 周期性重设看门狗闹钟：即使进程被系统杀死，闹钟也会拉起本服务
        if (!userStopped) {
            scheduleWatchdog(this, WATCHDOG_INTERVAL_MS);
        }
        // START_STICKY：服务被系统杀死后会尽量重建，保证常驻
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        isRunning = false;
        try {
            if (foreground && Build.VERSION.SDK_INT >= 26) {
                stopForeground(STOP_FOREGROUND_REMOVE);
            }
        } catch (Throwable ignored) {
        }
        foreground = false;
        handler.removeCallbacks(pollRunnable);
        unregisterReceiver(ringerReceiver);
        cancelStopTimer();
        executor.shutdown();
        if (userStopped) {
            // 用户主动停止：取消看门狗闹钟
            cancelWatchdog();
            logToUi("[服务] 监听服务已停止");
        } else {
            // 被系统杀死：2 秒后自愈重启
            scheduleWatchdog(2000);
            logToUi("[服务] 服务被销毁，已安排 2 秒后自动重启");
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    /** 读取设置中保存的 authorization（必须自行配置，无内置值） */
    private String getAuth() {
        SharedPreferences sp =
                getSharedPreferences(MainActivity.PREFS_NAME, MODE_PRIVATE);
        return sp.getString(MainActivity.PREF_KEY_AUTH, "").trim();
    }

    /** 读取设置中保存的车辆 IMEI（必须自行配置，无内置值） */
    private String getImei() {
        SharedPreferences sp =
                getSharedPreferences(MainActivity.PREFS_NAME, MODE_PRIVATE);
        return sp.getString(MainActivity.PREF_KEY_IMEI, "").trim();
    }

    /**
     * 详细日志：只写 logcat，默认不进界面日志区；
     * 需要排查时可在菜单「🐞 详细日志」里临时开启。
     */
    private void logDebug(String msg) {
        Log.d(TAG, msg);
        if (getSharedPreferences(MainActivity.PREFS_NAME, MODE_PRIVATE)
                .getBoolean(MainActivity.PREF_KEY_VERBOSE_LOG, false)) {
            logToUi("[调试] " + msg);
        }
    }

    /** 长文本截断，避免异常堆栈或超长响应体占满日志区 */
    private static String truncate(String s, int max) {
        if (s == null) return "";
        String t = s.trim();
        return t.length() <= max ? t : t.substring(0, max) + "…";
    }

    /** 把日志同时写入 logcat 和广播到主界面（时间戳由界面统一添加） */
    private void logToUi(String msg) {
        Log.i(TAG, msg);
        // 先写入进程级共享存储：界面在后台/销毁期间产生的日志也不会丢
        LogStore.append(msg);
        // 再广播给前台界面实时刷新（界面不在前台时广播无人接收，无影响）
        Intent i = new Intent(MainActivity.ACTION_LOG);
        i.setPackage(getPackageName());
        sendBroadcast(i);
    }

    /** 看门狗：安排一个系统闹钟，到时由系统直接拉起本服务（进程死了也能唤醒） */
    private void scheduleWatchdog(long delayMs) {
        scheduleWatchdog(this, delayMs);
    }

    /** 看门狗：安排一个系统闹钟，到时由系统直接拉起本服务（进程死了也能唤醒） */
    public static void scheduleWatchdog(Context context, long delayMs) {
        try {
            AlarmManager am = (AlarmManager) context.getSystemService(ALARM_SERVICE);
            if (am == null) return;
            PendingIntent pi = getRestartPendingIntent(context);
            long at = SystemClock.elapsedRealtime() + delayMs;
            if (Build.VERSION.SDK_INT >= 23) {
                am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi);
            } else {
                am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi);
            }
        } catch (Exception e) {
            Log.e(TAG, "安排看门狗闹钟失败: " + e);
        }
    }

    public static void cancelWatchdog(Context context) {
        try {
            AlarmManager am = (AlarmManager) context.getSystemService(ALARM_SERVICE);
            if (am != null) am.cancel(getRestartPendingIntent(context));
        } catch (Exception ignored) {
        }
    }

    private void cancelWatchdog() {
        cancelWatchdog(this);
    }

    /**
     * 重启用 PendingIntent：Android 8+ 后台限制同样适用于闹钟触发的普通 startService，
     * 因此 API 26+ 使用 getForegroundService 创建。
     */
    private static PendingIntent getRestartPendingIntent(Context context) {
        Intent i = new Intent(context, SilentModeService.class);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        if (Build.VERSION.SDK_INT >= 26) {
            return PendingIntent.getForegroundService(context, 0, i, flags);
        }
        return PendingIntent.getService(context, 0, i, flags);
    }

    /**
     * 读取通知样式：
     * standard（标题 + 说明）/ minimal（仅标题）/ hidden（状态栏不显示）。
     * 旧版「常驻通知」总开关关闭时视为 hidden，保证升级后行为不变。
     */
    public static String notificationStyle(Context context) {
        SharedPreferences sp =
                context.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE);
        if (!sp.getBoolean(MainActivity.PREF_KEY_SHOW_NOTI, true)) {
            return MainActivity.NOTI_STYLE_HIDDEN;
        }
        return sp.getString(MainActivity.PREF_KEY_NOTI_STYLE, MainActivity.NOTI_STYLE_STANDARD);
    }

    /**
     * 提升为前台服务：Android 8+ 只有前台服务才能可靠地在后台长期存活并从开机广播被拉起。
     * 通知分两档：默认显示一条 IMPORTANCE_LOW 常驻通知（无声、不打扰，但系统能感知服务在被使用，
     * 与微信「微信正在运行」同思路，显著降低被 ROM 回收的概率）；用户在菜单中关闭后退回静默渠道。
     * 若被个别 ROM 拒绝，则退化为普通后台服务（由看门狗继续保活）。
     */
    private void startForegroundQuietly() {
        if (Build.VERSION.SDK_INT < 26) return;
        try {
            ensureChannels();
            startForeground(NOTIFICATION_ID, buildNotification());
            foreground = true;
        } catch (Throwable t) {
            Log.w(TAG, "前台化失败，退化为普通后台服务: " + t);
        }
    }

    /** 开关变更后重建通知并重新提交前台状态（渠道不同，必须重新 build） */
    private void refreshForegroundNotification() {
        if (Build.VERSION.SDK_INT < 26) return;
        try {
            ensureChannels();
            Notification notification = buildNotification();
            startForeground(NOTIFICATION_ID, notification);
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) nm.notify(NOTIFICATION_ID, notification);
        } catch (Throwable t) {
            Log.w(TAG, "刷新前台通知失败: " + t);
        }
    }

    /** 创建两个通知渠道：可见（IMPORTANCE_LOW）与隐藏（IMPORTANCE_NONE） */
    private void ensureChannels() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) return;

        if (nm.getNotificationChannel(CHANNEL_ID_VISIBLE) == null) {
            NotificationChannel visible = new NotificationChannel(
                    CHANNEL_ID_VISIBLE, "静音寻车监听服务（常驻）", NotificationManager.IMPORTANCE_LOW);
            visible.setDescription("常驻通知：让系统知道服务正在使用，显著降低被后台回收的概率。可在应用菜单中关闭");
            visible.setShowBadge(false);
            visible.enableVibration(false);
            visible.setSound(null, null);
            nm.createNotificationChannel(visible);
        }
        if (nm.getNotificationChannel(CHANNEL_ID_SILENT) == null) {
            NotificationChannel silent = new NotificationChannel(
                    CHANNEL_ID_SILENT, "静音寻车（已隐藏）", NotificationManager.IMPORTANCE_NONE);
            silent.setDescription("隐藏档渠道：通知不展示，仅用于维持前台服务状态");
            silent.setShowBadge(false);
            silent.enableVibration(false);
            silent.setSound(null, null);
            nm.createNotificationChannel(silent);
        }
    }

    /** 按当前通知样式构建通知：标准（标题+说明）/ 极简（仅标题）/ 隐藏（不显示条目） */
    private Notification buildNotification() {
        String style = notificationStyle(this);
        boolean hidden = MainActivity.NOTI_STYLE_HIDDEN.equals(style);
        boolean minimal = MainActivity.NOTI_STYLE_MINIMAL.equals(style);
        String channelId = hidden ? CHANNEL_ID_SILENT : CHANNEL_ID_VISIBLE;

        Intent openIntent = new Intent(this, MainActivity.class);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pi = PendingIntent.getActivity(this, 0, openIntent, flags);

        int icon = getApplicationInfo().icon;
        if (icon == 0) icon = android.R.drawable.sym_def_app_icon;

        Notification.Builder builder = new Notification.Builder(this, channelId)
                .setSmallIcon(icon)
                .setContentTitle("静音寻车已就绪")
                .setContentIntent(pi)
                .setOngoing(true)
                .setShowWhen(false)
                .setCategory(Notification.CATEGORY_SERVICE);
        if (!minimal && !hidden) {
            // 标准档才显示说明文字，极简档只留一行标题
            builder.setContentText("正在监听三段式按键，点击打开应用");
        }
        if (hidden) {
            builder.setVisibility(Notification.VISIBILITY_SECRET);
        }
        return builder.build();
    }

    /** 使用 OkHttp 在后台线程执行 HTTP/2 POST 请求；未设置 authorization/IMEI 时不发送 */
    private void sendCmd(final String cmd) {
        final String url = BASE_URL + cmd;
        final String auth = getAuth();
        final String imei = getImei();
        if (auth.isEmpty()) {
            logToUi("[HTTP] 未设置 authorization，请求已跳过。请通过菜单「设置 authorization」配置");
            return;
        }
        if (imei.isEmpty()) {
            logToUi("[HTTP] 未设置车辆 IMEI，请求已跳过。请通过菜单「设置 IMEI」配置");
            return;
        }
        executor.execute(new Runnable() {
            @Override
            public void run() {
                String jsonBody = "{\"imei\":\"" + imei + "\"}";
                MediaType mediaType = MediaType.parse("application/json; charset=UTF-8");
                RequestBody body = RequestBody.create(mediaType, jsonBody);

                for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
                    try {
                        Request request = new Request.Builder()
                                .url(url)
                                .header("host", "www.tailgdd.com")
                                .header("authorization", auth)
                                .header("forward-service-ip", "localhost")
                                .header("language", "zh_CN")
                                .header("content-type", "application/json; charset=UTF-8")
                                .header("accept-encoding", "gzip")
                                .header("user-agent", "okhttp/4.2.2")
                                .post(body)
                                .build();

                        logToUi("[HTTP] 发送" + cmdLabel(cmd) + "请求"
                                + (attempt > 1 ? "（第 " + attempt + " 次尝试）" : ""));
                        logDebug("POST " + url + "，请求体: " + jsonBody);

                        Response response = httpClient.newCall(request).execute();
                        int code = response.code();
                        String resp = readBody(response);
                        if (code >= 200 && code < 300) {
                            logToUi("[HTTP] " + cmdLabel(cmd) + "请求成功，code=" + code);
                            logDebug("响应体: " + truncate(resp, 500));
                        } else {
                            logToUi("[HTTP] " + cmdLabel(cmd) + "请求返回 code=" + code
                                    + "，响应：" + truncate(resp, 120));
                        }
                        return;
                    } catch (Exception e) {
                        StringBuilder err = new StringBuilder(e.toString());
                        Throwable cause = e.getCause();
                        int depth = 0;
                        while (cause != null && depth < 5) {
                            err.append(" / ").append(cause);
                            cause = cause.getCause();
                            depth++;
                        }
                        logDebug("请求异常: " + err);
                        if (attempt < MAX_ATTEMPTS) {
                            logToUi("[HTTP] 请求失败：" + truncate(err.toString(), 100)
                                    + "，" + (RETRY_DELAY_MS / 1000) + " 秒后重试");
                            try {
                                Thread.sleep(RETRY_DELAY_MS);
                            } catch (InterruptedException ie) {
                                Thread.currentThread().interrupt();
                                logToUi("[HTTP] 重试被中断，放弃请求");
                                return;
                            }
                        } else {
                            logToUi("[HTTP] 请求失败（已重试 " + (MAX_ATTEMPTS - 1) + " 次）："
                                    + truncate(err.toString(), 100));
                        }
                    }
                }
            }
        });
    }

    /** 指令的可读名称（仅用于日志展示） */
    private static String cmdLabel(String cmd) {
        if (CMD_START.equals(cmd)) return "车辆启动";
        if (CMD_SEARCH.equals(cmd)) return "寻车";
        if (CMD_STOP.equals(cmd)) return "车辆停止";
        return cmd;
    }

    /**
     * 读取响应体：手动声明的 accept-encoding: gzip 会关闭 OkHttp 的透明解压，
     * 需根据响应的 Content-Encoding 自行解压 gzip，否则显示乱码。
     */
    private String readBody(Response response) {
        try {
            okhttp3.ResponseBody rb = response.body();
            if (rb == null) return "";
            byte[] raw = rb.bytes();
            String encoding = response.header("Content-Encoding");
            if (encoding != null && encoding.toLowerCase(java.util.Locale.ROOT).contains("gzip")) {
                java.io.ByteArrayInputStream bis = new java.io.ByteArrayInputStream(raw);
                java.util.zip.GZIPInputStream gzis = new java.util.zip.GZIPInputStream(bis);
                java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int n;
                while ((n = gzis.read(buf)) != -1) bos.write(buf, 0, n);
                gzis.close();
                return new String(bos.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
            }
            return new String(raw, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "<读取响应体失败: " + e.getMessage() + ">";
        }
    }
}
