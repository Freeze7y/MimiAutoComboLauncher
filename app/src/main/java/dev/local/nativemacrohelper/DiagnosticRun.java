package dev.local.nativemacrohelper;

import android.content.Context;

/** One user action, one probe, unconditional cleanup; no retries or persistent test session. */
final class DiagnosticRun {
    static String run(Context c, String game) {
        String tested = "未选择游戏，仅完成静态检查", summary = "未选择游戏，没有执行原生初始化", cleanup;
        try {
            if (game != null && !game.isEmpty()) {
                tested = MacroController.execute(c, game, "prepare");
                summary = MacroController.prefs(c).getString("diagnosticLatest", tested);
            }
        } finally {
            cleanup = MacroController.execute(c, game, "stop");
        }
        MacroController.prefs(c).edit().putString("diagnosticLatest", summary + "\n诊断收尾：" + cleanup)
            .putString("lastResult", tested).putString("attemptGame", game == null ? "" : game).apply();
        return tested + "\n诊断收尾：" + cleanup;
    }
}
