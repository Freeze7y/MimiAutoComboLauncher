package dev.local.nativemacrohelper;

import android.app.*;
import android.content.*;
import android.content.pm.*;
import android.os.SystemClock;
import java.text.SimpleDateFormat;
import java.util.*;

final class MacroController {
    static final String XIAOMI = "com.xiaomi.macro", SHARK = "com.blackshark.macro";
    private static long lastRequest;
    private static String lastKey = "";
    static SharedPreferences prefs(Context c) { return c.getSharedPreferences("state", 0); }

    static boolean installed(Context c, String pkg) {
        try { c.getPackageManager().getApplicationInfo(pkg, 0); return true; }
        catch (PackageManager.NameNotFoundException e) { return false; }
    }

    static String provider(Context c) {
        String saved = prefs(c).getString("provider", XIAOMI);
        if (installed(c, saved)) return saved;
        if (installed(c, XIAOMI)) return XIAOMI;
        if (installed(c, SHARK)) return SHARK;
        return "";
    }

    static String label(Context c, String pkg) {
        try { return c.getPackageManager().getApplicationInfo(pkg, 0).loadLabel(c.getPackageManager()).toString(); }
        catch (PackageManager.NameNotFoundException e) { return pkg; }
    }

    static synchronized void log(Context c, String message) {
        String old = prefs(c).getString("log", "");
        String line = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.ROOT).format(new Date()) + " " + message;
        String value = line + "\n" + old;
        prefs(c).edit().putString("log", value.substring(0, Math.min(value.length(), 12000))).apply();
    }

    static String request(Context c, String game, String action) {
        String result = requestInternal(c, game, action);
        log(c, "助手请求结果 action=" + action + " game=" + game + " " + result);
        if (!result.startsWith("已提交")) prefs(c).edit().putString("lastResult", result).apply();
        return result;
    }

    private static String requestInternal(Context c, String game, String action) {
        if ("stop".equals(action)) return execute(c, game, action);
        if (!"launch".equals(action) && !"panel".equals(action)) return "未知操作";
        if (game == null || !game.matches("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+")) return "请输入有效的应用包名";
        try {
            prefs(c).edit().putString("attemptGame", game).apply();
            log(c, "提交助手会话 action=" + action + " game=" + game);
            ComponentName helper = c.startForegroundService(new Intent(c, MacroSessionService.class).putExtra("game", game).putExtra("command", action));
            if (helper == null) { log(c, "助手启动返回空值"); return "助手未能启动，请查看系统启动限制"; }
            return "已提交请求，助手将调用原生宏服务";
        } catch (RuntimeException e) {
            log(c, "助手服务启动失败: " + e);
            return "助手服务启动失败：" + e.getMessage();
        }
    }

    static String execute(Context c, String game, String action) {
        String id = Long.toString(SystemClock.elapsedRealtime());
        prefs(c).edit().putString("attemptGame", game == null ? "" : game).apply();
        log(c, "[" + id + "] 开始 action=" + action + " game=" + game + " caller=" + c.getClass().getSimpleName());
        String result = executeInternal(c, game, action);
        prefs(c).edit().putString("lastResult", result).apply();
        log(c, "[" + id + "] 结果 " + result);
        return result;
    }

    private static String executeInternal(Context c, String game, String action) {
        if (!Arrays.asList("launch", "panel", "prepare", "stop").contains(action)) return "未知操作";
        if ("stop".equals(action)) return stop(c);
        if (game == null || !game.matches("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+")) return "请输入有效的应用包名";
        String target = "panel".equals(action) && game.equals(prefs(c).getString("lastGame", ""))
                ? prefs(c).getString("lastProvider", provider(c)) : provider(c);
        if (target.isEmpty()) return "未检测到小米或黑鲨原生宏组件";
        String key = target + game + action;
        long now = SystemClock.elapsedRealtime();
        if ("launch".equals(action) && lastKey.equals(key) && now - lastRequest < 1500)
            return "请求刚刚发送，请稍候再试";
        Intent service = new Intent().setComponent(new ComponentName(target, target + ".MainService"));
        try {
            service.putExtra("gamePackage", game).putExtra("clickIcon", action.equals("panel"));
            if (action.equals("launch")) {
                Intent launch = c.getPackageManager().getLaunchIntentForPackage(game);
                log(c, "游戏启动入口=" + (launch != null));
                if (launch == null) return "找不到该应用的启动入口，请确认包名和应用状态";
                c.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            }
            log(c, "调用 startForegroundService target=" + target + "/.MainService gamePackage=" + game + " clickIcon=" + action.equals("panel"));
            ComponentName started = c.startForegroundService(service);
            if (started == null) return "原生宏启动返回空值；组件可能不可用或被系统启动管控拦截，请查看诊断建议";
            if ("launch".equals(action)) { lastKey = key; lastRequest = now; }
            prefs(c).edit().putString("lastGame", game).putString("lastProvider", target).apply();
            log(c, target + "/.MainService gamePackage=" + game + " clickIcon=" + action.equals("panel") + " 请求已发送");
            try { c.getSystemService(NotificationManager.class).notify(1, notification(c, game)); }
            catch (RuntimeException e) { log(c, "更新通知失败: " + e); }
            return "已发送原生宏请求，请在游戏中确认显示效果";
        } catch (RuntimeException e) {
            log(c, action + " " + target + " " + game + ": " + e);
            return "调用失败：" + e.getClass().getSimpleName() + "\n" + e.getMessage();
        }
    }

    private static String stop(Context c) {
        String target = prefs(c).getString("lastProvider", provider(c));
        String error = "";
        try {
            if (!target.isEmpty()) {
                boolean stopped = c.stopService(new Intent().setComponent(new ComponentName(target, target + ".MainService")));
                // false means no running service matched, not a failed stop acknowledgement.
                log(c, target + " stopService=" + stopped);
            }
        } catch (RuntimeException e) {
            log(c, "原生宏停止请求异常: " + e);
            error = "原生宏停止请求失败：" + e.getClass().getSimpleName();
        }
        try { c.stopService(new Intent(c, MacroSessionService.class)); }
        catch (RuntimeException e) { log(c, "助手停止异常: " + e); error += " 助手停止失败"; }
        try { c.getSystemService(NotificationManager.class).cancel(1); }
        catch (RuntimeException e) { log(c, "通知清理异常: " + e); error += " 通知清理失败"; }
        lastKey = "";
        return error.isEmpty() ? "已结束助手会话；原生宏已请求停止或未在运行" : error.trim();
    }

    private static PendingIntent pending(Context c, String game, String action, int id) {
        Intent i = new Intent(c, MacroReceiver.class).setAction(action).putExtra("game", game);
        return PendingIntent.getBroadcast(c, id, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    static Notification notification(Context c, String game) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel("controls", "原生宏快捷操作", NotificationManager.IMPORTANCE_LOW));
        return new Notification.Builder(c, "controls")
                .setLargeIcon(android.graphics.drawable.Icon.createWithResource(c, R.drawable.brand_image))
                .setSmallIcon(R.drawable.ic_notification).setContentTitle(label(c, game) + " · 宏快捷操作")
                .setContentText("点击打开原生面板 · 长按可打开停止按钮")
                .setContentIntent(pending(c, game, "panel", 0)).setOnlyAlertOnce(true).setOngoing(true)
                .addAction(new Notification.Action.Builder(null, "打开面板", pending(c, game, "panel", 0)).build())
                .addAction(new Notification.Action.Builder(null, "停止宏", pending(c, game, "stop", 1)).build())
                .build();
    }
}
