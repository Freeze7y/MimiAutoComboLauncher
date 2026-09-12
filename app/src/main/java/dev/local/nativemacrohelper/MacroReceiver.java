package dev.local.nativemacrohelper;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.widget.Toast;

/** Matches the original helper: notification -> receiver -> vendor service, no Activity. */
public final class MacroReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if (!"panel".equals(action) && !"stop".equals(action)) return;
        MacroController.log(context, "收到通知操作 " + action + " game=" + intent.getStringExtra("game"));
        String result = MacroController.execute(context, intent.getStringExtra("game"), action);
        if (!result.startsWith("已发送")) Toast.makeText(context, result, Toast.LENGTH_LONG).show();
    }
}
