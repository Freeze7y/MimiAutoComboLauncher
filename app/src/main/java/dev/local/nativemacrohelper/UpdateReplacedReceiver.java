package dev.local.nativemacrohelper;

import android.app.*;
import android.content.*;

/** Best effort self-reopen, with a user-clickable fallback on restricted systems. */
public final class UpdateReplacedReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent intent) {
        if (!Intent.ACTION_MY_PACKAGE_REPLACED.equals(intent.getAction())) return;
        long expected = MacroController.prefs(c).getLong("reopenVersion", -1);
        try {
            if (expected != c.getPackageManager().getPackageInfo(c.getPackageName(), 0).getLongVersionCode()) return;
            MacroController.prefs(c).edit().remove("reopenVersion").apply();
            Intent open = new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            NotificationManager nm = c.getSystemService(NotificationManager.class);
            nm.createNotificationChannel(new NotificationChannel("updates", "应用更新", NotificationManager.IMPORTANCE_DEFAULT));
            if (nm.areNotificationsEnabled()) nm.notify(2, new Notification.Builder(c,"updates").setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("米米更新完成").setContentText("点击打开米米自动连招启动器").setAutoCancel(true)
                .setContentIntent(PendingIntent.getActivity(c, 200, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE)).build());
            try { c.startActivity(open); MacroController.log(c, "更新完成，已尝试重新打开米米；显示结果由系统决定"); }
            catch (RuntimeException e) { MacroController.log(c, "系统限制更新后打开，请点击通知或桌面图标：" + e); }
        } catch (Exception e) { MacroController.log(c, "更新后处理失败：" + e); }
    }
}
