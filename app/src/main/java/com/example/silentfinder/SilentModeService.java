package com.example.silentfinder;

import android.app.AlarmManager;
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
 * 所有事件通过广播发送到主界面显示。
 */
public class SilentModeService extends Service {

    private static final String TAG = "SilentModeService";
    private static final int NOTIFICATION_ID = 1;
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

    /** 上一次切换为震动模式的时刻（elapsedRealtime）；0 表示无记录 */
    private long lastVibrateAt = 0L;
    // 公共 SDK 中 ACTION_RINGER_MODE_CHANGED / EXTRA_RINGER_MODE 被隐藏，使用实际字符串常量
    private static final String ACTION_RINGER_MODE_CHANGED = "android.media.RINGER_MODE_CHANGED";
    private static final String EXTRA_RINGER_MODE = "android.media.EXTRA_RINGER_MODE";

    /** 供主界面查询服务是否在运行 */
    public static volatile boolean isRunning = false;

    /** 用户主动停止时置 true，闹钟看门狗与自愈重启据此跳过拉起 */
    public static volatile boolean userStopped = false;

    /** 看门狗周期：进程被杀后由系统闹钟自动拉起服务（无通知的后台服务更易被杀，周期缩短） */
    private static final long WATCHDOG_INTERVAL_MS = 30_000L;

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
            int ringerMode = intent.getIntExtra(EXTRA_RINGER_MODE, -1);
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
    };

    @Override
    public void onCreate() {
        super.onCreate();
        isRunning = true;
        IntentFilter filter = new IntentFilter(ACTION_RINGER_MODE_CHANGED);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(ringerReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(ringerReceiver, filter);
        }
        logToUi("[服务] 监听服务已启动，正在监听铃声模式变化");
        if (getAuth().isEmpty()) {
            logToUi("[服务] 尚未设置 authorization，请在菜单「设置 authorization」中配置后再触发请求");
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // 周期性重设看门狗闹钟：即使进程被系统杀死，闹钟也会拉起本服务
        if (!userStopped) {
            scheduleWatchdog(WATCHDOG_INTERVAL_MS);
        }
        // START_STICKY：服务被系统杀死后会尽量重建，保证常驻
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        isRunning = false;
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

    /** 把日志同时写入 logcat 和广播到主界面（时间戳由界面统一添加） */
    private void logToUi(String msg) {
        Log.i(TAG, msg);
        // 先写入进程级共享存储：界面在后台/销毁期间产生的日志也不会丢
        LogStore.append(msg);
        // 再广播给前台界面实时刷新（界面不在前台时广播无人接收，无影响）
        Intent i = new Intent(MainActivity.ACTION_LOG);
        i.setPackage(getPackageName());
        i.putExtra(MainActivity.EXTRA_LOG_MSG, msg);
        sendBroadcast(i);
    }

    /** 看门狗：安排一个系统闹钟，到时由系统直接拉起本服务（进程死了也能唤醒） */
    private void scheduleWatchdog(long delayMs) {
        try {
            AlarmManager am = (AlarmManager) getSystemService(ALARM_SERVICE);
            if (am == null) return;
            PendingIntent pi = getRestartPendingIntent();
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

    private void cancelWatchdog() {
        try {
            AlarmManager am = (AlarmManager) getSystemService(ALARM_SERVICE);
            if (am != null) am.cancel(getRestartPendingIntent());
        } catch (Exception ignored) {
        }
    }

    private PendingIntent getRestartPendingIntent() {
        Intent i = new Intent(this, SilentModeService.class);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getService(this, 0, i, flags);
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
                try {
                    String jsonBody = "{\"imei\":\"" + imei + "\"}";
                    MediaType mediaType = MediaType.parse("application/json; charset=UTF-8");
                    RequestBody body = RequestBody.create(mediaType, jsonBody);

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

                    logToUi("[HTTP] 发送 POST " + url + "（" + cmdLabel(cmd) + "）"
                            + "\n[HTTP] 请求体: " + jsonBody);

                    Response response = httpClient.newCall(request).execute();
                    int code = response.code();
                    String resp = readBody(response);
                    logToUi("[HTTP] 响应 code=" + code + "\n[HTTP] 响应体: " + resp);
                } catch (Exception e) {
                    StringBuilder err = new StringBuilder(e.toString());
                    Throwable cause = e.getCause();
                    int depth = 0;
                    while (cause != null && depth < 5) {
                        err.append("\n  由 ").append(cause.toString()).append(" 引起");
                        cause = cause.getCause();
                        depth++;
                    }
                    logToUi("[HTTP] 请求失败: " + err);
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
