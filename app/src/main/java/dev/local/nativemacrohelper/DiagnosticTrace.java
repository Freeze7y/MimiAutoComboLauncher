package dev.local.nativemacrohelper;

import android.content.Context;
import android.os.SystemClock;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.*;

/** Per-operation evidence, with IDs carried across the helper Intent boundary. */
final class DiagnosticTrace {
    private static final ThreadLocal<DiagnosticTrace> CURRENT = new ThreadLocal<>();
    static volatile String activityState = "未知（本进程尚无主界面生命周期记录）";
    final String id, action, game, source;
    final long started = SystemClock.elapsedRealtime();
    final String time = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS Z", Locale.ROOT).format(new Date());
    String step = "ENTRY", failureStep = "", code = "UNKNOWN", stack = "", before, target = "未知";
    String path = "ENTRY@0ms";
    long previous = started;
    private DiagnosticTrace(String id, String game, String action, String source) {
        this.id = id; this.game = game; this.action = action; this.source = source;
    }
    static boolean active() { return CURRENT.get() != null; }
    static String startTime() { return active() ? Long.toString(CURRENT.get().started) : ""; }
    static void queued(String start) {
        if (!active() || start == null) return;
        try { long delay = CURRENT.get().started - Long.parseLong(start); CURRENT.get().path = "UI→服务等待=" + (delay < 0 ? "未知" : delay + "ms") + " → ENTRY@0ms"; }
        catch (NumberFormatException ignored) {}
    }
    static String id() { return active() ? CURRENT.get().id : ""; }
    static void begin(Context c, String game, String action, String source, String inherited) {
        DiagnosticTrace t = new DiagnosticTrace(inherited == null || inherited.isEmpty() ? UUID.randomUUID().toString() : inherited, game, action, source);
        CURRENT.set(t);
        t.before = DiagnosticSnapshot.capture(c, game);
        MacroController.log(c, "开始 action=" + action + " game=" + game + " caller=" + source);
    }
    static String decorate(String text) {
        DiagnosticTrace t = CURRENT.get();
        if (t == null) return "[事件] " + text;
        long now = SystemClock.elapsedRealtime(), delta = now - t.previous; t.previous = now;
        return "[" + t.id + "][" + t.step + "][+" + (now-t.started) + "ms Δ" + delta + "ms] " + text;
    }
    static void step(Context c, String step) {
        if (active()) { CURRENT.get().step = step; CURRENT.get().path += " → " + step + "@" + (SystemClock.elapsedRealtime()-CURRENT.get().started) + "ms"; }
        MacroController.log(c, "步骤 " + step);
    }
    static void target(String target) { if (active()) CURRENT.get().target = target; }
    static String result(String code, String text) {
        if (active()) CURRENT.get().code = code;
        return text;
    }
    static void exception(Context c, Throwable e) {
        DiagnosticTrace t = CURRENT.get();
        StringWriter writer = new StringWriter(); e.printStackTrace(new PrintWriter(writer));
        String detail = writer.toString();
        if (t != null) {
            if (t.failureStep.isEmpty()) t.failureStep = t.step;
            t.code = e.getClass().getSimpleName().contains("ForegroundServiceStartNotAllowed") ? "BACKGROUND_START_DENIED"
                    : e instanceof SecurityException ? "SECURITY_EXCEPTION" : "CALL_EXCEPTION";
            t.stack += detail + "\n";
        }
        MacroController.log(c, "异常：\n" + compactStack(detail));
    }
    static boolean failure(String code) {
        return !Arrays.asList("QUEUED", "REQUEST_SENT", "STOPPED", "DUPLICATE", "UNKNOWN").contains(code);
    }
    static String explain(String code) {
        switch (code) {
            case "NATIVE_NULL": return "已确认：原生服务启动调用返回 null。\n尚未确认：组件不可用还是厂商启动管控拦截；不是‘已证明未安装’。\n建议：检查同一时间系统的‘启动自动连招’拒绝记录，连同本报告反馈。";
            case "HELPER_NULL": return "已确认：助手前台服务启动返回 null。\n建议：检查助手的系统启动管控记录；不能仅凭返回值指定某项权限。";
            case "NO_PROVIDER": return "已确认：助手未查询到可选原生宏包。\n建议：查看系统是否提供原生自动连招组件；不建议盲目安装其他机型组件。";
            case "NO_LAUNCHER": return "已确认：目标应用没有可查询的启动入口。\n建议：检查包名、应用是否启用及是否处于另一用户/分身空间。";
            case "SECURITY_EXCEPTION": return "已确认：当前步骤抛出 SecurityException。\n建议：根据下面失败步骤、异常中的权限名及当时权限快照处理；不代表所有权限都缺失。";
            case "BACKGROUND_START_DENIED": return "已确认：系统抛出前台服务后台启动限制异常。\n建议：从助手可见界面重试并检查系统启动管控；声明权限通过不等于启动获准。";
            case "INVALID_INPUT": return "已确认：包名或操作参数无效。\n建议：从应用列表重新选择目标。";
            case "CALL_EXCEPTION": return "已确认：调用步骤抛出异常，完整堆栈见下方。\n建议：按异常类型定位，不默认归因于杀后台。";
            case "REQUEST_SENT": return "已确认：服务调用返回非空组件。\n尚未确认：原生面板是否显示、宏是否可用；请补充实际显示结果。";
            case "QUEUED": return "已确认：助手服务请求已提交。\n尚未确认：服务是否收到命令；在事件时间线中查找相同操作编号的 HELPER_RECEIVED。";
            case "STOPPED": return "已确认：停止流程已执行且未捕获异常；false 表示未匹配运行服务，不是失败。\n尚未确认：原生组件界面是否已消失。";
            case "DUPLICATE": return "已确认：重复启动游戏请求被短时间去重；打开面板操作不会被此规则拦截。";
            default: return "暂无足够证据确定原因；请保留下面的步骤和结果。";
        }
    }
    static void finish(Context c, String result) {
        DiagnosticTrace t = CURRENT.get(); if (t == null) return;
        try {
            String after = DiagnosticSnapshot.capture(c, t.game);
            String summary = "时间：" + t.time + "\n操作编号：" + t.id + "\n来源：" + t.source + "\n动作：" + t.action + " 游戏：" + t.game
                + "\n组件：" + t.target + "\n链路：" + t.path
                + "\n分类：" + t.code + " 步骤：" + (t.failureStep.isEmpty() ? t.step : t.failureStep)
                + " 阶段耗时：" + (SystemClock.elapsedRealtime()-t.started) + "ms\n结果：" + result + "\n" + explain(t.code);
            MacroController.prefs(c).edit().putString("diagnosticLatest", summary).apply();
            if (failure(t.code)) MacroController.prefs(c).edit().putString("diagnosticFailure", limit(summary + "\n失败时状态：\n" + after + (t.before.equals(after) ? "" : "\n调用前状态（发生变化）：\n" + t.before) + "\n异常：\n" + compactStack(t.stack), 6500)).apply();
            if ("REQUEST_SENT".equals(t.code) && ("launch".equals(t.action) || "panel".equals(t.action)))
                MacroController.prefs(c).edit().putString("displayOperation", t.id + " / " + t.game + " / " + t.action + " / " + t.time).apply();
            MacroController.log(c, "结束分类=" + t.code + " 结果=" + result);
        } finally { CURRENT.remove(); }
    }
    static String limit(String value, int max) { return value.length() <= max ? value : value.substring(0,max) + "\n[此段超限，已截断]"; }
    private static String compactStack(String stack) {
        StringBuilder b = new StringBuilder(); int frames = 0;
        for (String line : stack.split("\n")) {
            if (!line.trim().startsWith("at ") || line.contains("dev.local.nativemacrohelper") || frames++ < 4) b.append(line).append('\n');
        }
        return limit(b.toString(), 2400);
    }
    static String report(Context c) {
        return "【最近操作】\n" + MacroController.prefs(c).getString("diagnosticLatest", "新版尚无操作记录")
            + "\n\n【最近失败，可能已恢复】\n" + MacroController.prefs(c).getString("diagnosticFailure", "暂无")
            + "\n服务退出：" + MacroController.prefs(c).getString("diagnosticLifecycle", "尚无退出记录")
            + "\n\n【用户观察】\n" + MacroController.prefs(c).getString("displayObservation", "未提供，面板是否显示未知")
            + "\n\n【当前状态，不代表失败时状态】\n" + DiagnosticSnapshot.capture(c, MacroController.prefs(c).getString("attemptGame", ""))
            + "\n\n其他最近异常（可能已恢复）：" + MacroController.prefs(c).getString("diagnosticAuxiliary", "无")
            + "\n\n边界：无系统私有管控日志；调用成功不证明面板显示，未知退出不等于系统杀后台。";
    }
    static void clear(Context c) {
        MacroController.prefs(c).edit().putString("log", "").putString("diagnosticTimeline", "").putString("diagnosticLatest", "已清空")
            .putString("diagnosticAuxiliary", "无").putString("diagnosticFailure", "已清空").putString("diagnosticLifecycle", "已清空").putString("displayObservation", "未提供").putString("displayOperation", "").putString("lastResult", "已清空").apply();
    }
}
