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
        message = DiagnosticTrace.decorate(message);
        String old = prefs(c).getString("log", "");
        String line = new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.ROOT).format(new Date()) + " " + message;
        if (!DiagnosticTrace.active() && (message.contains("失败") || message.contains("异常")))
            prefs(c).edit().putString("diagnosticAuxiliary", DiagnosticTrace.limit(line, 1200)).apply();
        String value = line + "\n" + old;
        String timeline = prefs(c).getString("diagnosticTimeline", "") + line + "\n";
        if (timeline.length() > 16000) timeline = "[较早记录已省略]\n" + timeline.substring(timeline.length() - 15000);
        prefs(c).edit().putString("log", value.substring(0, Math.min(value.length(), 12000)))
            .putString("diagnosticTimeline", timeline).apply();
    }

    static String request(Context c, String game, String action) {
        DiagnosticTrace.begin(c, game, action, "UI_REQUEST", null);
        String result;
        try { result = requestInternal(c, game, action); }
        catch (RuntimeException e) { DiagnosticTrace.exception(c, e); result = "助手请求失败：" + e; }
        log(c, "助手请求结果 " + result);
        if (!result.startsWith("已提交")) prefs(c).edit().putString("lastResult", result).apply();
        DiagnosticTrace.finish(c, result);
        return result;
    }

    private static String requestInternal(Context c, String game, String action) {
        if ("stop".equals(action)) return execute(c, game, action);
        if (!"launch".equals(action) && !"panel".equals(action)) return DiagnosticTrace.result("INVALID_INPUT", "未知操作");
        if (game == null || !game.matches("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+")) return DiagnosticTrace.result("INVALID_INPUT", "请输入有效的应用包名");
        try {
            prefs(c).edit().putString("attemptGame", game).apply();
            log(c, "提交助手会话 action=" + action + " game=" + game);
            DiagnosticTrace.step(c, "HELPER_START");
            ComponentName helper = c.startForegroundService(new Intent(c, MacroSessionService.class).putExtra("game", game).putExtra("command", action).putExtra("traceId", DiagnosticTrace.id()).putExtra("traceStart", DiagnosticTrace.startTime()));
            if (helper == null) { log(c, "助手启动返回空值"); return DiagnosticTrace.result("HELPER_NULL", "助手未能启动，请查看系统启动限制"); }
            return DiagnosticTrace.result("QUEUED", "已提交请求，助手将调用原生宏服务");
        } catch (RuntimeException e) {
            DiagnosticTrace.exception(c, e);
            log(c, "助手服务启动失败: " + e);
            return "助手服务启动失败：" + e.getMessage();
        }
    }

    static String execute(Context c, String game, String action) {
        boolean own = !DiagnosticTrace.active();
        if (own) DiagnosticTrace.begin(c, game, action, c.getClass().getSimpleName(), null);
        prefs(c).edit().putString("attemptGame", game == null ? "" : game).apply();
        String result;
        try { result = executeInternal(c, game, action); }
        catch (RuntimeException e) { DiagnosticTrace.exception(c, e); result = "调用失败：" + e; }
        prefs(c).edit().putString("lastResult", result).apply();
        log(c, "结果 " + result);
        if (own) DiagnosticTrace.finish(c, result);
        return result;
    }

    private static String executeInternal(Context c, String game, String action) {
        if (!Arrays.asList("launch", "panel", "prepare", "stop").contains(action)) return DiagnosticTrace.result("INVALID_INPUT", "未知操作");
        if ("stop".equals(action)) return stop(c);
        if (game == null || !game.matches("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+")) return DiagnosticTrace.result("INVALID_INPUT", "请输入有效的应用包名");
        DiagnosticTrace.step(c, "RESOLVE_PROVIDER");
        String target = "panel".equals(action) && game.equals(prefs(c).getString("lastGame", ""))
                ? prefs(c).getString("lastProvider", provider(c)) : provider(c);
        DiagnosticTrace.target(target);
        if (target.isEmpty()) return DiagnosticTrace.result("NO_PROVIDER", "未检测到小米或黑鲨原生宏组件");
        String key = target + game + action;
        long now = SystemClock.elapsedRealtime();
        if ("launch".equals(action) && lastKey.equals(key) && now - lastRequest < 1500)
            return DiagnosticTrace.result("DUPLICATE", "请求刚刚发送，请稍候再试");
        Intent service = new Intent().setComponent(new ComponentName(target, target + ".MainService"));
        try {
            service.putExtra("gamePackage", game).putExtra("clickIcon", action.equals("panel"));
            if (action.equals("launch")) {
                DiagnosticTrace.step(c, "GAME_RESOLVE");
                Intent launch = c.getPackageManager().getLaunchIntentForPackage(game);
                log(c, "游戏启动入口=" + (launch != null));
                if (launch == null) return DiagnosticTrace.result("NO_LAUNCHER", "找不到该应用的启动入口，请确认包名和应用状态");
                DiagnosticTrace.step(c, "GAME_LAUNCH");
                c.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            }
            log(c, "调用 startForegroundService target=" + target + "/.MainService gamePackage=" + game + " clickIcon=" + action.equals("panel"));
            DiagnosticTrace.step(c, "NATIVE_START");
            ComponentName started = c.startForegroundService(service);
            if (started == null) return DiagnosticTrace.result("NATIVE_NULL", "原生宏启动返回空值；请查看诊断建议");
            if ("launch".equals(action)) { lastKey = key; lastRequest = now; }
            prefs(c).edit().putString("lastGame", game).putString("lastProvider", target).apply();
            log(c, target + "/.MainService gamePackage=" + game + " clickIcon=" + action.equals("panel") + " 请求已发送");
            DiagnosticTrace.result("REQUEST_SENT", "");
            DiagnosticTrace.step(c, "NOTIFICATION_REFRESH");
            try { c.getSystemService(NotificationManager.class).notify(1, notification(c, game)); }
            catch (RuntimeException e) { log(c, "通知刷新异常（原生请求已发送）: " + e); DiagnosticTrace.exception(c, e); }
            return "已发送原生宏请求，请在游戏中确认显示效果";
        } catch (RuntimeException e) {
            DiagnosticTrace.exception(c, e);
            log(c, action + " " + target + " " + game + ": " + e);
            return "调用失败：" + e.getClass().getSimpleName() + "\n" + e.getMessage();
        }
    }

    private static String stop(Context c) {
        String target = prefs(c).getString("lastProvider", provider(c));
        DiagnosticTrace.target(target);
        String error = "";
        try {
            if (!target.isEmpty()) {
                DiagnosticTrace.step(c, "NATIVE_STOP");
                boolean stopped = c.stopService(new Intent().setComponent(new ComponentName(target, target + ".MainService")));
                // false means no running service matched, not a failed stop acknowledgement.
                log(c, target + " stopService=" + stopped);
            }
        } catch (RuntimeException e) {
            DiagnosticTrace.exception(c, e);
            log(c, "原生宏停止请求异常: " + e);
            error = "原生宏停止请求失败：" + e.getClass().getSimpleName();
        }
        prefs(c).edit().putString("helperStopReason", "显式停止操作 " + DiagnosticTrace.id()).apply();
        DiagnosticTrace.step(c, "HELPER_STOP");
        try { c.stopService(new Intent(c, MacroSessionService.class)); }
        catch (RuntimeException e) { DiagnosticTrace.exception(c, e); log(c, "助手停止异常: " + e); error += " 助手停止失败"; }
        try { c.getSystemService(NotificationManager.class).cancel(1); }
        catch (RuntimeException e) { DiagnosticTrace.exception(c, e); log(c, "通知清理异常: " + e); error += " 通知清理失败"; }
        lastKey = "";
        return error.isEmpty() ? DiagnosticTrace.result("STOPPED", "已结束助手会话；原生宏已请求停止或未在运行") : error.trim();
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
