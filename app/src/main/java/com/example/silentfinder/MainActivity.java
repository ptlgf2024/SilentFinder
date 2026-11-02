package com.example.silentfinder;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
/**
 * 主界面：卡片式布局。
 * 顶部标题 + 菜单，状态卡片（铃声模式/服务/authorization 指示灯），
 * 主按钮切换服务启停，深色终端风格日志区。
 */
public class MainActivity extends Activity {

    public static final String ACTION_LOG = "com.example.silentfinder.LOG";

    public static final String PREFS_NAME = "config";
    public static final String PREF_KEY_AUTH = "auth";
    public static final String PREF_KEY_IMEI = "imei";
    public static final String PREF_KEY_FIRST_RUN = "first_run";
    /** 「常驻通知」总开关：仅用于旧版兼容（关闭视为隐藏样式） */
    public static final String PREF_KEY_SHOW_NOTI = "show_notification";
    /** 通知样式：standard（标题+说明）/ minimal（仅标题）/ hidden（不显示） */
    public static final String PREF_KEY_NOTI_STYLE = "notification_style";
    public static final String NOTI_STYLE_STANDARD = "standard";
    public static final String NOTI_STYLE_MINIMAL = "minimal";
    public static final String NOTI_STYLE_HIDDEN = "hidden";
    /** 「详细日志」开关：开启后日志区才显示调试明细（默认关闭，保持日志区清爽） */
    public static final String PREF_KEY_VERBOSE_LOG = "verbose_log";

    /** 通知权限请求码（Android 13+） */
    private static final int REQ_POST_NOTIFICATIONS = 1001;

    // 配色
    private static final int COLOR_PRIMARY = 0xFF1565C0;      // 主色：深蓝
    private static final int COLOR_BG = 0xFFEEF2F7;           // 页面背景
    private static final int COLOR_CARD = 0xFFFFFFFF;         // 卡片背景
    private static final int COLOR_OK = 0xFF2E7D32;           // 正常/运行中：绿
    private static final int COLOR_WARN = 0xFFC62828;         // 未设置/停止：红
    private static final int COLOR_LOG_BG = 0xFF1E1E2E;       // 日志背景：深色
    private static final int COLOR_LOG_TEXT = 0xFFA6E3A1;     // 日志文字：浅绿

    /** 日志统一存放在 LogStore（进程级），界面只负责渲染 */

    /** 已请求启动（服务异步启动期间保持按钮为"停止"状态，与服务同步） */
    private static volatile boolean serviceRequested = false;

    private Button toggleButton;
    private LinearLayout rowMode, rowService, rowAuth, rowImei;
    private TextView logText;
    private ScrollView logScroll;

    /** 接收服务发来的日志：服务已写入 LogStore，这里只需重新渲染 */
    private final BroadcastReceiver logReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            renderLog();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        float d = getResources().getDisplayMetrics().density;
        int pad = dp(d, 16);

        // 页面根布局
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(COLOR_BG);
        root.setPadding(pad, dp(d, 24), pad, pad);

        // ── 顶部标题栏 ──
        LinearLayout titleBar = new LinearLayout(this);
        titleBar.setOrientation(LinearLayout.HORIZONTAL);
        titleBar.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = new TextView(this);
        title.setText("🔔 静音寻车");
        title.setTextSize(24);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(COLOR_PRIMARY);
        title.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        Button menuButton = new Button(this);
        menuButton.setText("☰");
        menuButton.setTextSize(20);
        menuButton.setTextColor(COLOR_PRIMARY);
        menuButton.setBackgroundColor(Color.TRANSPARENT);
        menuButton.setPadding(dp(d, 12), 0, 0, 0);
        menuButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showMainMenu();
            }
        });

        titleBar.addView(title);
        titleBar.addView(menuButton);
        root.addView(titleBar, matchWrap());

        // ── 状态卡片 ──
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(rounded(COLOR_CARD, dp(d, 14), dp(d, 1), 0xFFDDE3EA));
        int cardPad = dp(d, 18);
        card.setPadding(cardPad, cardPad, cardPad, cardPad);
        root.addView(card, matchWrap());
        ((LinearLayout.LayoutParams) card.getLayoutParams()).topMargin = dp(d, 12);

        rowMode = statusRow(d, "铃声模式", "—");
        rowService = statusRow(d, "监听服务", "—");
        rowAuth = statusRow(d, "authorization", "—");
        rowImei = statusRow(d, "车辆 IMEI", "—");
        card.addView(rowMode);
        card.addView(rowService);
        card.addView(rowAuth);
        card.addView(rowImei);
        // 最后一行去掉底部分隔线留白
        ((LinearLayout.LayoutParams) rowImei.getLayoutParams()).bottomMargin = 0;

        // ── 主按钮：启停切换 ──
        toggleButton = new Button(this);
        toggleButton.setTextSize(17);
        toggleButton.setTypeface(Typeface.DEFAULT_BOLD);
        toggleButton.setTextColor(Color.WHITE);
        toggleButton.setPadding(0, dp(d, 12), 0, dp(d, 12));
        toggleButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleService();
            }
        });
        LinearLayout.LayoutParams btnLp = matchWrap();
        btnLp.topMargin = dp(d, 16);
        root.addView(toggleButton, btnLp);

        // ── 次要按钮：清空日志 ──
        Button clearButton = new Button(this);
        clearButton.setText("清空日志");
        clearButton.setTextSize(14);
        clearButton.setTextColor(0xFF546E7A);
        clearButton.setBackground(rounded(COLOR_CARD, dp(d, 10), dp(d, 1), 0xFFDDE3EA));
        LinearLayout.LayoutParams clearLp = matchWrap();
        clearLp.topMargin = dp(d, 10);
        root.addView(clearButton, clearLp);
        clearButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                LogStore.clear();
                appendLog("[界面] 日志已清空");
            }
        });

        // ── 日志卡片（终端风格）──
        TextView logTitle = new TextView(this);
        logTitle.setText("运行日志");
        logTitle.setTextSize(14);
        logTitle.setTypeface(Typeface.DEFAULT_BOLD);
        logTitle.setTextColor(0xFF37474F);
        LinearLayout.LayoutParams ltLp = matchWrap();
        ltLp.topMargin = dp(d, 18);
        ltLp.bottomMargin = dp(d, 6);
        root.addView(logTitle, ltLp);

        logText = new TextView(this);
        logText.setTextSize(11);
        logText.setTypeface(Typeface.MONOSPACE);
        logText.setTextColor(COLOR_LOG_TEXT);
        logText.setLineSpacing(dp(d, 2), 1f);
        logText.setPadding(dp(d, 12), dp(d, 12), dp(d, 12), dp(d, 12));

        logScroll = new ScrollView(this);
        logScroll.setBackgroundColor(COLOR_LOG_BG);
        logScroll.setBackground(rounded(COLOR_LOG_BG, dp(d, 14), 0, 0));
        LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        scrollLp.topMargin = 0;
        logScroll.addView(logText, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));
        root.addView(logScroll, scrollLp);

        setContentView(root);

        // 渲染历史日志（含后台期间服务写入的全部日志）
        if (LogStore.dump().isEmpty()) {
            appendLog("[界面] 应用已启动");
        } else {
            renderLog();
        }

        // 打开应用时自动启动监听服务（无需手动点启动）
        SilentModeService.setUserStopped(this, false);
        if (!SilentModeService.isRunning) {
            startMonitorService();
        }

        // Android 13+：常驻通知需要通知权限。未授予时服务照常运行，但通知不显示、后台更易被回收
        if (Build.VERSION.SDK_INT >= 33
                && getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getBoolean(PREF_KEY_SHOW_NOTI, true)
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},
                    REQ_POST_NOTIFICATIONS);
        }

        // 首次运行（或尚未完成电池白名单）时弹出保活设置引导
        showSetupGuideIfNeeded();
    }

    // ── 界面辅助方法 ──

    private int dp(float density, int v) {
        return (int) (v * density);
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    /** 圆角背景 drawable */
    private GradientDrawable rounded(int color, int radius, int strokeWidth, int strokeColor) {
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(color);
        gd.setCornerRadius(radius);
        if (strokeWidth > 0 && strokeColor != 0) {
            gd.setStroke(strokeWidth, strokeColor);
        }
        return gd;
    }

    /** 状态卡片中的一行：左侧标签 + 右侧值（带彩色指示点） */
    private LinearLayout statusRow(float d, String label, String value) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams lp = matchWrap();
        lp.topMargin = dp(d, 8);
        lp.bottomMargin = dp(d, 8);
        row.setLayoutParams(lp);

        TextView name = new TextView(this);
        name.setText(label);
        name.setTextSize(15);
        name.setTextColor(0xFF78909C);
        name.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView statusValue = new TextView(this);
        statusValue.setText(value);
        statusValue.setTextSize(15);
        statusValue.setTypeface(Typeface.DEFAULT_BOLD);
        statusValue.setTextColor(COLOR_OK);

        row.addView(name);
        row.addView(statusValue);
        return row;
    }

    /** 更新状态行文字与颜色 */
    private void setRow(LinearLayout row, String label, String value, int color) {
        TextView name = (TextView) row.getChildAt(0);
        TextView val = (TextView) row.getChildAt(1);
        name.setText(label);
        val.setText("● " + value);
        val.setTextColor(color);
    }

    // ── 菜单 ──

    /** 主菜单：authorization / IMEI / 通知样式 / 详细日志 / 耗电管理 / 自启动 / 关于 */
    private void showMainMenu() {
        SharedPreferences sp = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        boolean verbose = sp.getBoolean(PREF_KEY_VERBOSE_LOG, false);
        final String[] items = {
                "🔑  设置 authorization",
                "🚗  设置车辆 IMEI",
                "🔔  通知样式：" + styleLabel(SilentModeService.notificationStyle(this)),
                verbose ? "🐞  详细日志：已开启（点此关闭）" : "🐞  详细日志：已关闭（点此开启）",
                "🔋  耗电管理（完全允许后台行为）",
                "🚀  自启动设置",
                "📴  在系统设置中屏蔽通知（服务仍保活）",
                "ℹ️  关于"
        };
        new AlertDialog.Builder(this)
                .setTitle("菜单")
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        if (which == 0) {
                            showAuthSetting();
                        } else if (which == 1) {
                            showImeiSetting();
                        } else if (which == 2) {
                            chooseNotificationStyle();
                        } else if (which == 3) {
                            toggleVerboseLog();
                        } else if (which == 4) {
                            requestIgnoreBatteryOptimizations();
                        } else if (which == 5) {
                            jumpToAutoStartSettings();
                        } else if (which == 6) {
                            openNotificationChannelSettings();
                        } else {
                            showAbout();
                        }
                    }
                })
                .show();
    }

    /** 通知样式的可读名称 */
    private static String styleLabel(String style) {
        if (NOTI_STYLE_MINIMAL.equals(style)) return "极简（仅一行标题）";
        if (NOTI_STYLE_HIDDEN.equals(style)) return "隐藏（不显示，更易被回收）";
        return "标准（标题 + 说明）";
    }

    /**
     * 选择通知样式：
     * 标准 = 状态栏显示"标题 + 说明"；极简 = 只留一行标题；隐藏 = 不显示通知条目。
     * 可见的两档能让系统感知服务在使用中，存活率最高；隐藏档更清爽但更易被回收。
     */
    private void chooseNotificationStyle() {
        final String[] labels = {
                "标准：静音寻车已就绪 · 正在监听三段式按键",
                "极简：只显示「静音寻车已就绪」一行",
                "隐藏：状态栏不显示（更容易被系统回收）"
        };
        final String[] values = {NOTI_STYLE_STANDARD, NOTI_STYLE_MINIMAL, NOTI_STYLE_HIDDEN};
        String current = SilentModeService.notificationStyle(this);
        int checked = 0;
        for (int i = 0; i < values.length; i++) {
            if (values[i].equals(current)) checked = i;
        }
        new AlertDialog.Builder(this)
                .setTitle("通知样式（当前：" + styleLabel(current) + "）")
                .setSingleChoiceItems(labels, checked, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        dialog.dismiss();
                        applyNotificationStyle(values[which]);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 保存通知样式并让服务立刻重建通知 */
    private void applyNotificationStyle(String style) {
        boolean hidden = NOTI_STYLE_HIDDEN.equals(style);
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                .putBoolean(PREF_KEY_SHOW_NOTI, !hidden)
                .putString(PREF_KEY_NOTI_STYLE, style)
                .apply();

        if (!hidden && Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},
                    REQ_POST_NOTIFICATIONS);
        }
        // 渠道不同，必须让服务重新 build 通知并重新提交前台状态
        Intent i = new Intent(this, SilentModeService.class);
        i.setAction(SilentModeService.ACTION_REFRESH_NOTIFICATION);
        try {
            startService(i);
        } catch (Exception ignored) {
            // 服务未运行时忽略，下次启动按新样式生效
        }
        appendLog("[设置] 通知样式已改为" + styleLabel(style) + (hidden
                ? "：状态栏不再显示，但后台更容易被系统回收"
                : "：让系统感知服务在使用中，存活率更高"));
        Toast.makeText(this, "通知样式：" + styleLabel(style), Toast.LENGTH_SHORT).show();
    }

    /** 跳到系统通知设置，用户可自行关闭/静默该渠道（服务仍以前台身份运行） */
    private void openNotificationChannelSettings() {
        try {
            Intent i = new Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS);
            i.putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
            i.putExtra(Settings.EXTRA_CHANNEL_ID, SilentModeService.CHANNEL_ID_VISIBLE);
            startActivity(i);
        } catch (Exception e) {
            try {
                Intent i = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS);
                i.putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
                startActivity(i);
            } catch (Exception e2) {
                Toast.makeText(this, "无法打开系统通知设置", Toast.LENGTH_SHORT).show();
            }
        }
    }

    /** 切换「详细日志」：关闭时日志区只保留动作与结果，调试明细只写 logcat */
    private void toggleVerboseLog() {
        SharedPreferences sp = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        boolean next = !sp.getBoolean(PREF_KEY_VERBOSE_LOG, false);
        sp.edit().putBoolean(PREF_KEY_VERBOSE_LOG, next).apply();
        appendLog("[设置] 详细日志已" + (next ? "开启（将显示调试明细）" : "关闭（日志区只保留动作与结果）"));
        Toast.makeText(this, next ? "详细日志已开启" : "详细日志已关闭", Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_POST_NOTIFICATIONS) return;
        boolean granted = grantResults != null && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        appendLog(granted
                ? "[权限] 通知权限已授予，常驻通知将正常显示"
                : "[权限] 通知权限被拒绝：服务照常运行，但无法显示常驻通知，后台更容易被回收");
    }

    // ── 首次运行引导 ──

    /**
     * 首次运行弹引导；之后若电池优化白名单尚未允许，每次启动仍轻量提醒。
     * 已加入白名单后不再弹。
     */
    private void showSetupGuideIfNeeded() {
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        boolean ignored = pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
        SharedPreferences sp = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        boolean firstRun = sp.getBoolean(PREF_KEY_FIRST_RUN, true);

        if (!firstRun && ignored) return; // 非首次且已完成白名单：不再提醒
        if (!firstRun) {
            // 非首次但未加白名单：日志轻量提醒，不弹窗打扰
            appendLog("[提醒] 耗电管理未设为「完全允许后台行为」，后台可能被冻结，建议到菜单中设置");
            return;
        }
        sp.edit().putBoolean(PREF_KEY_FIRST_RUN, false).apply();

        final String[] items = {
                "🔋  耗电管理：选「完全允许后台行为」（最关键）",
                "🚀  自启动：允许「静音寻车」",
                "❌  跳过，稍后再说"
        };
        new AlertDialog.Builder(this)
                .setTitle("首次使用：完成两项设置\n保证后台常驻不被杀")
                .setMessage("监听服务需要长期在后台运行，请到系统设置中完成（也可稍后在 ☰ 菜单中操作）：\n\n"
                        + "① 耗电管理：设置 → 应用 → 应用管理 → 静音寻车 → 耗电管理 → 选「完全允许后台行为」\n\n"
                        + "② 自启动：设置 → 应用 → 自启动 → 允许「静音寻车」")
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        if (which == 0) {
                            requestIgnoreBatteryOptimizations();
                        } else if (which == 1) {
                            jumpToAutoStartSettings();
                        }
                    }
                })
                .setPositiveButton("完成", null)
                .setCancelable(false)
                .show();
        appendLog("[引导] 已弹出保活设置引导（耗电管理 + 自启动）");
    }

    /**
     * 跳转自启动管理页面。
     * adb 实测结论：ColorOS 自启动页真实组件为 com.oplus.battery/...StartupAppListActivity，
     * 被签名级权限 oplus.permission.OPLUS_COMPONENT_SAFE 封锁，任何第三方 App 均无法直启（adb 也被拒）。
     * 故 ColorOS 直接打开设置主页，用户按 设置→应用→自启动 手动进入。
     */
    private void jumpToAutoStartSettings() {
        // 其他厂商：组件直启（ColorOS 已封锁，此处服务于 MIUI/EMUI/OriginOS 等）
        String[][] candidates = {
                // 小米（MIUI）
                {"com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"},
                // 华为（EMUI / HarmonyOS）
                {"com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"},
                {"com.huawei.systemmanager", "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity"},
                // vivo（OriginOS / Funtouch）
                {"com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"},
                {"com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager"},
        };
        for (String[] c : candidates) {
            try {
                Intent i = new Intent();
                i.setComponent(new android.content.ComponentName(c[0], c[1]));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
                appendLog("[引导] 已直达自启动管理页，请允许「静音寻车」自启动");
                Toast.makeText(this, "在列表中找到「静音寻车」并打开开关", Toast.LENGTH_LONG).show();
                return;
            } catch (Exception ignored) {
                // 该厂商页面不存在，尝试下一个
            }
        }
        // ColorOS 等：直接打开设置主页，用户按 设置→应用→自启动 进入
        appendLog("[引导] 已打开设置，请进入 应用→自启动，为「静音寻车」打开开关");
        Toast.makeText(this, "路径：设置 → 应用 → 自启动 → 静音寻车 开启", Toast.LENGTH_LONG).show();
        try {
            Intent home = new Intent(Settings.ACTION_SETTINGS);
            home.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(home);
        } catch (Exception ignored) {
        }
    }

    /** 引导用户到耗电管理页：设置 → 应用 → 应用管理 → 静音寻车 → 耗电管理 → 完全允许后台行为 */
    private void requestIgnoreBatteryOptimizations() {
        // 优先尝试直接跳本应用详情页（ColorOS 的耗电管理入口就在应用详情页内）
        try {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + getPackageName())));
            appendLog("[设置] 已打开应用详情页，请进入「耗电管理」选「完全允许后台行为」");
            Toast.makeText(this, "进入「耗电管理」→ 选「完全允许后台行为」", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            try {
                startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
                appendLog("[设置] 已打开电池优化列表，请将本应用设为不优化");
            } catch (Exception e2) {
                Toast.makeText(this, "请到 设置→应用→应用管理→静音寻车→耗电管理 手动设置",
                        Toast.LENGTH_LONG).show();
            }
        }
    }

    /** 设置 authorization 对话框：输入后保存到 SharedPreferences，立即生效 */
    private void showAuthSetting() {
        SharedPreferences sp = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String current = sp.getString(PREF_KEY_AUTH, "");

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        float d = getResources().getDisplayMetrics().density;
        int pad = dp(d, 20);
        box.setPadding(pad, dp(d, 10), pad, 0);

        TextView hint = new TextView(this);
        hint.setText("粘贴新的 authorization 值（必填，来自官方 App 抓包，过期后需更新）：");
        hint.setTextSize(13);
        hint.setTextColor(0xFF78909C);

        final EditText input = new EditText(this);
        input.setText(current);
        input.setTextSize(12);
        input.setSingleLine(false);
        input.setMinLines(3);
        input.setMaxLines(6);

        box.addView(hint);
        box.addView(input);

        new AlertDialog.Builder(this)
                .setTitle("设置 authorization")
                .setView(box)
                .setPositiveButton("保存", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        String value = input.getText().toString().trim();
                        if (value.isEmpty()) {
                            Toast.makeText(MainActivity.this,
                                    "authorization 不能为空", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        SharedPreferences sp = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
                        sp.edit().putString(PREF_KEY_AUTH, value).apply();
                        appendLog("[设置] authorization 已更新（长度 "
                                + value.length() + "），下次请求生效");
                        Toast.makeText(MainActivity.this, "authorization 已保存",
                                Toast.LENGTH_SHORT).show();
                        refreshUi();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 设置车辆 IMEI 对话框：输入后保存到 SharedPreferences，立即生效 */
    private void showImeiSetting() {
        SharedPreferences sp = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String current = sp.getString(PREF_KEY_IMEI, "");

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        float d = getResources().getDisplayMetrics().density;
        int pad = dp(d, 20);
        box.setPadding(pad, dp(d, 10), pad, 0);

        TextView hint = new TextView(this);
        hint.setText("输入车辆控制器 IMEI（15 位数字，来自官方 App 抓包请求体）：");
        hint.setTextSize(13);
        hint.setTextColor(0xFF78909C);

        final EditText input = new EditText(this);
        input.setText(current);
        input.setTextSize(14);
        input.setSingleLine(true);
        input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);

        box.addView(hint);
        box.addView(input);

        new AlertDialog.Builder(this)
                .setTitle("设置车辆 IMEI")
                .setView(box)
                .setPositiveButton("保存", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        String value = input.getText().toString().trim();
                        if (value.isEmpty()) {
                            Toast.makeText(MainActivity.this,
                                    "IMEI 不能为空", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        SharedPreferences sp = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
                        sp.edit().putString(PREF_KEY_IMEI, value).apply();
                        appendLog("[设置] 车辆 IMEI 已更新：" + value);
                        Toast.makeText(MainActivity.this, "车辆 IMEI 已保存",
                                Toast.LENGTH_SHORT).show();
                        refreshUi();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 关于对话框（版本号从 APK 动态读取，避免硬编码不一致） */
    private void showAbout() {
        String version = "";
        try {
            version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception ignored) {
        }
        new AlertDialog.Builder(this)
                .setTitle("关于 SilentFinder")
                .setMessage("SilentFinder（静音寻车）" + version + "\n\n"
                        + "一加手机三段式按键触发台铃电动车指令：\n"
                        + "· 拨到静音档 → 寻车（鸣响闪灯）\n"
                        + "· 2 秒内双拨震动档 → 启动\n"
                        + "· 拨到震动档保持不动 3 秒 → 停止\n\n"
                        + "· 前台服务常驻，看门狗闹钟保活，开机自启\n"
                        + "· 正常档不触发，拨离震动档即取消停止\n"
                        + "· authorization 过期后可在菜单中更新\n\n"
                        + "仅供个人自有设备使用。")
                .setPositiveButton("确定", null)
                .show();
    }

    // ── 生命周期 ──

    @Override
    protected void onResume() {
        super.onResume();
        // 注册日志接收器（应用内私有广播）
        IntentFilter filter = new IntentFilter(ACTION_LOG);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(logReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(logReceiver, filter);
        }
        // 从后台回来：把后台期间产生的日志全部渲染出来
        renderLog();
        refreshUi();
    }

    @Override
    protected void onPause() {
        super.onPause();
        try {
            unregisterReceiver(logReceiver);
        } catch (IllegalArgumentException ignored) {
            // 接收器未注册时忽略
        }
    }

    // ── 服务启停 ──

    /** 单按钮切换：运行中则停止，已停止则启动 */
    private void toggleService() {
        if (SilentModeService.isRunning || serviceRequested) {
            serviceRequested = false;
            SilentModeService.setUserStopped(this, true);
            stopService(new Intent(this, SilentModeService.class));
            appendLog("[界面] 已请求停止监听服务");
            Toast.makeText(this, "监听服务已停止", Toast.LENGTH_SHORT).show();
            scheduleRefresh();
        } else {
            SilentModeService.setUserStopped(this, false);
            startMonitorService();
        }
    }

    /** 启动监听服务 */
    private void startMonitorService() {
        serviceRequested = true;
        refreshUi();
        // 走统一入口：前台方式启动 + 注册持久化看门狗任务
        SilentModeService.ensureStarted(this);
        appendLog("[界面] 已请求启动监听服务");
        Toast.makeText(this, "监听服务已启动", Toast.LENGTH_SHORT).show();
        scheduleRefresh();
    }

    /** 服务启动是异步的：分几个时间点重复刷新，确保按钮与服务状态最终一致 */
    private void scheduleRefresh() {
        long[] delays = {300, 1000, 2500, 5000};
        for (final long delay : delays) {
            logScroll.postDelayed(new Runnable() {
                @Override
                public void run() {
                    refreshUi();
                }
            }, delay);
        }
    }

    private void refreshUi() {
        // 合并状态：服务异步启动期间 serviceRequested=true，按钮立即变为"停止"
        boolean running = SilentModeService.isRunning || serviceRequested;
        SharedPreferences sp = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        boolean hasAuth = !sp.getString(PREF_KEY_AUTH, "").trim().isEmpty();
        String imei = sp.getString(PREF_KEY_IMEI, "").trim();
        setRow(rowMode, "铃声模式", currentRingerModeName(), 0xFF37474F);
        setRow(rowService, "监听服务", running ? "运行中" : "已停止",
                running ? COLOR_OK : COLOR_WARN);
        setRow(rowAuth, "authorization", hasAuth ? "已设置" : "未设置（菜单中配置）",
                hasAuth ? COLOR_OK : COLOR_WARN);
        setRow(rowImei, "车辆 IMEI",
                imei.isEmpty() ? "未设置（菜单中配置）" : imei,
                imei.isEmpty() ? COLOR_WARN : COLOR_OK);
        toggleButton.setText(running ? "■  停止监听服务" : "▶  启动监听服务");
        toggleButton.setBackground(rounded(running ? COLOR_WARN : COLOR_PRIMARY,
                dp(getResources().getDisplayMetrics().density, 12), 0, 0));
    }

    private String currentRingerModeName() {
        AudioManager audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        switch (audioManager.getRingerMode()) {
            case AudioManager.RINGER_MODE_SILENT: return "静音";
            case AudioManager.RINGER_MODE_VIBRATE: return "震动";
            case AudioManager.RINGER_MODE_NORMAL: return "正常";
            default: return "未知";
        }
    }

    // ── 日志 ──

    /** 界面侧产生日志（服务侧日志由服务直接写入 LogStore） */
    private void appendLog(String msg) {
        LogStore.append(msg);
        renderLog();
    }

    /** 从 LogStore 全量渲染日志并滚到底部 */
    private void renderLog() {
        String text = LogStore.dump();
        if (!text.isEmpty()) {
            logText.setText(text);
            scrollToBottom();
        }
    }

    private void scrollToBottom() {
        logScroll.post(new Runnable() {
            @Override
            public void run() {
                logScroll.fullScroll(View.FOCUS_DOWN);
            }
        });
    }
}
