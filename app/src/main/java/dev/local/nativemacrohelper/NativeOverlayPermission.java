package dev.local.nativemacrohelper;

import android.app.AppOpsManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;

/** Checks the vendor package, never the assistant's own overlay permission. */
final class NativeOverlayPermission {
    enum State { ALLOWED, DENIED, UNKNOWN, MISSING }

    static State check(Context c, String pkg) {
        if (pkg == null || pkg.isEmpty()) return State.MISSING;
        try {
            ApplicationInfo app = c.getPackageManager().getApplicationInfo(pkg, 0);
            AppOpsManager ops = c.getSystemService(AppOpsManager.class);
            if (ops == null) return State.UNKNOWN;
            // Cross-UID queries may be forbidden. Do not turn query failures into denial.
            int mode = ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW, app.uid, pkg);
            return classify(mode);
        } catch (PackageManager.NameNotFoundException e) {
            return State.MISSING;
        } catch (RuntimeException e) {
            return State.UNKNOWN;
        }
    }

    static State classify(int mode) {
        if (mode == AppOpsManager.MODE_ALLOWED) return State.ALLOWED;
        if (mode == AppOpsManager.MODE_IGNORED) return State.DENIED;
        // ERRORED can also mean this caller cannot inspect the other UID.
        return State.UNKNOWN;
    }

    static String describe(State state) {
        switch (state) {
            case ALLOWED: return "已允许（标准悬浮窗 AppOps）";
            case DENIED: return "未允许，请前往系统授权";
            case MISSING: return "未找到组件";
            default: return "未知，系统未提供可确认的状态，请到设置核对";
        }
    }
}
