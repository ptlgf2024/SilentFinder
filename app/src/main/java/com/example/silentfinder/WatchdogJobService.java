package com.example.silentfinder;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.util.Log;

/**
 * 持久化看门狗任务：Android 的 AlarmManager 闹钟在关机重启后会被全部清空，
 * 只有 setPersisted(true) 的 JobScheduler 任务能跨重启存活。
 * 因此这里以 15 分钟（系统允许的最小周期）周期性地确认监听服务是否存活并拉起，
 * 作为开机广播之外的最后一道兜底。
 */
public class WatchdogJobService extends JobService {

    private static final String TAG = "WatchdogJobService";
    private static final int JOB_ID = 20261030;
    private static final long PERIOD_MS = 15 * 60 * 1000L;

    @Override
    public boolean onStartJob(JobParameters params) {
        try {
            if (!SilentModeService.isRunning) {
                SilentModeService.ensureStarted(this);
                SilentModeService.scheduleWatchdog(this, SilentModeService.WATCHDOG_INTERVAL_MS);
                LogStore.append("[看门狗] 持久化任务检测到监听服务未运行，已尝试拉起");
            }
        } catch (Throwable t) {
            Log.e(TAG, "拉起监听服务失败", t);
        }
        jobFinished(params, false);
        return false;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return false;
    }

    /** 注册持久化周期任务（已存在则不重复创建） */
    public static void schedule(Context context) {
        try {
            JobScheduler js = (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            if (js == null) return;
            for (JobInfo pending : js.getAllPendingJobs()) {
                if (pending.getId() == JOB_ID) return;
            }
            JobInfo info = new JobInfo.Builder(JOB_ID,
                    new ComponentName(context, WatchdogJobService.class))
                    .setPeriodic(PERIOD_MS)
                    .setPersisted(true)
                    .setRequiresCharging(false)
                    .setRequiresDeviceIdle(false)
                    .build();
            js.schedule(info);
        } catch (Exception e) {
            Log.w(TAG, "注册持久化看门狗任务失败: " + e);
        }
    }
}
