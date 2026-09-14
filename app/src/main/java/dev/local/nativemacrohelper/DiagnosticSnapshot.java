package dev.local.nativemacrohelper;

import android.app.*;
import android.content.*;
import android.content.pm.*;
import android.os.*;

/** Read-only snapshot. Every unavailable field remains unknown, never false by assumption. */
final class DiagnosticSnapshot {
    static String capture(Context c, String game) {
        try { return captureInternal(c, game); }
        catch (RuntimeException e) { return "快照部分不可用：" + e; }
    }
    private static String captureInternal(Context c, String game) {
        StringBuilder b = new StringBuilder("主界面状态=").append(DiagnosticTrace.activityState)
            .append(" SDK=").append(Build.VERSION.SDK_INT).append(" PID=").append(android.os.Process.myPid()).append('\n');
        try {
            ActivityManager.RunningAppProcessInfo process = new ActivityManager.RunningAppProcessInfo();
            ActivityManager.getMyMemoryState(process); b.append("助手进程 importance=").append(process.importance).append("（不等于目标游戏前台状态）\n");
            PowerManager power = c.getSystemService(PowerManager.class);
            b.append("屏幕交互=").append(power.isInteractive()).append(" 助手电池优化豁免=").append(power.isIgnoringBatteryOptimizations(c.getPackageName())).append('\n');
            b.append("设备锁定=").append(c.getSystemService(KeyguardManager.class).isDeviceLocked()).append('\n');
        } catch (RuntimeException e) { b.append("运行状态部分未知：").append(e).append('\n'); }
        try {
            NotificationManager nm = c.getSystemService(NotificationManager.class);
            NotificationChannel channel = nm.getNotificationChannel("controls");
            if (!nm.areNotificationsEnabled() || (channel != null && channel.getImportance() == NotificationManager.IMPORTANCE_NONE)) b.append("建议：打开助手通知或 controls 频道，否则通知快捷入口不可见。\n");
            b.append("助手通知允许=").append(nm.areNotificationsEnabled()).append(" controls重要性=").append(channel == null ? "未建立" : channel.getImportance()).append('\n');
        } catch (RuntimeException e) { b.append("通知状态未知：").append(e).append('\n'); }
        PackageManager pm = c.getPackageManager();
        for (String pkg : new String[]{c.getPackageName(), MacroController.provider(c), game}) {
            if (pkg == null || pkg.isEmpty()) continue;
            try {
                PackageInfo pi = pm.getPackageInfo(pkg, PackageManager.MATCH_DISABLED_COMPONENTS);
                ApplicationInfo ai = pm.getApplicationInfo(pkg, PackageManager.MATCH_DISABLED_COMPONENTS);
                b.append(pkg).append(" version=").append(pi.versionName).append('/').append(pi.getLongVersionCode())
                    .append(" uid=").append(ai.uid).append(" enabled=").append(ai.enabled)
                    .append(" appOverride=").append(pm.getApplicationEnabledSetting(pkg)).append('\n');
                if (pkg.equals(game)) b.append("目标启动入口存在=").append(pm.getLaunchIntentForPackage(pkg) != null).append('\n');
                if (!pkg.equals(MacroController.XIAOMI) && !pkg.equals(MacroController.SHARK)) continue;
                b.append(" 原生宏悬浮窗=").append(NativeOverlayPermission.describe(NativeOverlayPermission.check(c, pkg))).append('\n');
                ComponentName component = new ComponentName(pkg, pkg + ".MainService");
                ServiceInfo si = pm.getServiceInfo(component, PackageManager.MATCH_DISABLED_COMPONENTS);
                b.append(" MainService enabled=").append(si.enabled).append(" exported=").append(si.exported)
                    .append(" override=").append(pm.getComponentEnabledSetting(component)).append(" permission=").append(si.permission).append('\n');
                if (si.permission != null) b.append(" 声明权限已授予=").append(c.checkSelfPermission(si.permission) == PackageManager.PERMISSION_GRANTED).append('\n');
            } catch (PackageManager.NameNotFoundException e) { b.append(pkg).append(" 包或组件不可查询：").append(e.getMessage()).append('\n'); }
            catch (RuntimeException e) { b.append(pkg).append(" 状态未知：").append(e).append('\n'); }
        }
        b.append("选择组件=").append(MacroController.prefs(c).getString("provider", MacroController.XIAOMI))
            .append(" 上次实际组件=").append(MacroController.prefs(c).getString("lastProvider", "无"))
            .append("\n厂商关联启动/自启动 AppOps=未知；声明权限已授予不代表厂商启动管控放行。\n");
        return b.toString();
    }
}
