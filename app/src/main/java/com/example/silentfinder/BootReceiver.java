package com.example.silentfinder;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/** 开机自启动接收器：手机重启后自动拉起监听服务（无通知的后台服务） */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;
        try {
            context.startService(new Intent(context, SilentModeService.class));
        } catch (Exception e) {
            // 极个别 ROM 限制后台启动时忽略，看门狗闹钟稍后会拉起
        }
    }
}
