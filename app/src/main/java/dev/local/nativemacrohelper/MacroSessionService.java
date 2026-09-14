package dev.local.nativemacrohelper;

import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.widget.Toast;

public final class MacroSessionService extends Service {
    private String operationId = "", exitReason = "";
    @Override public IBinder onBind(Intent intent) { return null; }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String game = intent == null ? null : intent.getStringExtra("game");
        String action = intent == null ? null : intent.getStringExtra("command");
        DiagnosticTrace.begin(this, game, action, "HELPER_SERVICE", intent == null ? null : intent.getStringExtra("traceId"));
        DiagnosticTrace.queued(intent == null ? null : intent.getStringExtra("traceStart"));
        operationId = DiagnosticTrace.id(); exitReason = "";
        MacroController.prefs(this).edit().putString("helperStopReason", "").apply();
        DiagnosticTrace.step(this, "HELPER_RECEIVED");
        MacroController.log(this, "startId=" + startId + " flags=" + flags);
        String result = "";
        try {
            if (game == null || (!"launch".equals(action) && !"panel".equals(action))) {
                result = DiagnosticTrace.result("INVALID_INPUT", "空命令或无效操作");
                exitReason = result; stopSelf(startId); return START_NOT_STICKY;
            }
            DiagnosticTrace.step(this, "FOREGROUND_PROMOTE");
            if (Build.VERSION.SDK_INT >= 34) startForeground(1, MacroController.notification(this, game), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            else startForeground(1, MacroController.notification(this, game));
            MacroController.log(this, "助手前台通知建立完成");
            result = MacroController.execute(this, game, action);
            if (!result.startsWith("已发送") && !result.startsWith("请求刚刚")) {
                exitReason = result;
                MacroController.log(this, "助手退出原因：" + exitReason);
                Toast.makeText(this, result, Toast.LENGTH_LONG).show(); stopSelf(startId);
            }
        } catch (RuntimeException e) {
            DiagnosticTrace.exception(this, e);
            result = "助手前台服务失败：" + e; exitReason = result;
            Toast.makeText(this, "助手启动失败，请查看诊断", Toast.LENGTH_LONG).show(); stopSelf(startId);
        } finally { DiagnosticTrace.finish(this, result); }
        return START_NOT_STICKY;
    }
    @Override public void onDestroy() {
        stopForeground(STOP_FOREGROUND_REMOVE);
        String reason = exitReason.isEmpty() ? MacroController.prefs(this).getString("helperStopReason", "") : exitReason;
        MacroController.prefs(this).edit().putString("diagnosticLifecycle", operationId + " / " + (reason.isEmpty() ? "未知原因" : reason)).apply();
        MacroController.log(this, "[" + operationId + "] HELPER_DESTROY 原因=" + (reason.isEmpty() ? "未知（没有记录到主动停止原因）" : reason) + "；回调不停止原生宏");
        super.onDestroy();
    }
}
