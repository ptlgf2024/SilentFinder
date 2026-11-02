package com.example.silentfinder;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * 开机自启动接收器：手机重启后自动拉起监听服务。
 *
 * 覆盖多种「开机/重启」广播（原生开机、部分 ROM 快速开机、用户解锁），
 * 以及应用升级（MY_PACKAGE_REPLACED，防止覆盖安装后彻底失活）和用户解锁屏幕
 * （USER_PRESENT，服务被杀后多一次自愈机会）。重复触发无副作用。
 *
 * 注意：如果 App 安装后从未被打开过（处于 stopped 状态），系统不会投递任何广播，
 * 需先在桌面打开一次 App 并完成「自启动」开关设置。
 */
public class BootReceiver extends BroadcastReceiver {

    private static final String TAG = "BootReceiver";

    /** 部分 MTK/HTC 系 ROM 的「快速开机」广播 */
    private static final String ACTION_QUICKBOOT_POWERON = "android.intent.action.QUICKBOOT_POWERON";
    private static final String ACTION_QUICKBOOT_POWERON_HTC = "com.htc.intent.action.QUICKBOOT_POWERON";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent != null ? intent.getAction() : null;
        if (!isSupportedAction(action)) return;

        // 开机/覆盖升级视为新一轮使用：清除上次「用户主动停止」的标记，保证能自动启动
        if (isPowerOnAction(action)) {
            SilentModeService.setUserStopped(context, false);
        }

        final PendingResult result = goAsync();
        try {
            SilentModeService.ensureStarted(context);
            // 双重兜底：即使本次被 ROM 拦截，看门狗闹钟与持久化任务稍后会再次尝试拉起
            SilentModeService.scheduleWatchdog(context, SilentModeService.WATCHDOG_INTERVAL_MS);
            // USER_PRESENT 每次解锁都会触发：服务已在运行时不再写日志，避免日志区被刷屏
            if (!SilentModeService.isRunning) {
                LogStore.append("[自启] 收到 " + action + "，已请求拉起监听服务");
            }
        } catch (Throwable t) {
            Log.e(TAG, "处理 " + action + " 失败", t);
        } finally {
            if (result != null) result.finish();
        }
    }

    private boolean isSupportedAction(String action) {
        if (action == null) return false;
        return Intent.ACTION_BOOT_COMPLETED.equals(action)
                || ACTION_QUICKBOOT_POWERON.equals(action)
                || ACTION_QUICKBOOT_POWERON_HTC.equals(action)
                || Intent.ACTION_USER_UNLOCKED.equals(action)
                || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)
                || Intent.ACTION_USER_PRESENT.equals(action);
    }

    /** 开机类广播（区别于解锁屏幕）：会重置「用户主动停止」标记 */
    private boolean isPowerOnAction(String action) {
        return Intent.ACTION_BOOT_COMPLETED.equals(action)
                || ACTION_QUICKBOOT_POWERON.equals(action)
                || ACTION_QUICKBOOT_POWERON_HTC.equals(action)
                || Intent.ACTION_USER_UNLOCKED.equals(action)
                || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action);
    }
}
