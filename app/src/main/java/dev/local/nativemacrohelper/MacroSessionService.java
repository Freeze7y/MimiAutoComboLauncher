package dev.local.nativemacrohelper;

import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.widget.Toast;

/** User-controlled foreground session, without automatic stopping or relaunch loops. */
public final class MacroSessionService extends Service {
    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String game = intent == null ? null : intent.getStringExtra("game");
        String action = intent == null ? null : intent.getStringExtra("command");
        MacroController.log(this, "助手收到命令 startId=" + startId + " action=" + action + " game=" + game);
        if (game == null || (!"launch".equals(action) && !"panel".equals(action))) {
            MacroController.log(this, "助手退出原因：空命令或无效操作");
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(1, MacroController.notification(this, game), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else {
                startForeground(1, MacroController.notification(this, game));
            }
            MacroController.log(this, "助手前台通知建立完成");
            String result = MacroController.execute(this, game, action);
            if (!result.startsWith("已发送") && !result.startsWith("请求刚刚")) {
                MacroController.log(this, "助手退出原因：" + result);
                Toast.makeText(this, result, Toast.LENGTH_LONG).show();
                stopSelf(startId);
            }
        } catch (RuntimeException e) {
            MacroController.log(this, "助手前台服务失败: " + e);
            Toast.makeText(this, "助手启动失败，请查看诊断：" + e.getMessage(), Toast.LENGTH_LONG).show();
            stopSelf(startId);
        }
        return START_NOT_STICKY;
    }

    @Override public void onDestroy() {
        // Never stop Xiaomi's service here: system reclamation must not close the macro.
        stopForeground(STOP_FOREGROUND_REMOVE);
        MacroController.log(this, "助手销毁回调；此回调不停止原生宏，退出原因参见此前记录（无记录则未知）");
        super.onDestroy();
    }
}
