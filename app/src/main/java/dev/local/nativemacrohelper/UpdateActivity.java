package dev.local.nativemacrohelper;

import android.app.*;
import android.content.*;
import android.content.pm.*;
import android.os.*;
import android.provider.Settings;
import android.widget.*;
import java.io.*;
import java.util.UUID;
import java.util.concurrent.*;

/** Explicit user initiated update; downloads do not run after leaving this screen. */
public final class UpdateActivity extends Activity {
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private TextView status;
    private Button install;
    private File directory, apk;
    private boolean installing;
    private Future<?> job;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout layout = new LinearLayout(this); layout.setOrientation(LinearLayout.VERTICAL);
        int pad = (int)(24 * getResources().getDisplayMetrics().density); layout.setPadding(pad,pad,pad,pad);
        TextView title = new TextView(this); title.setText("米米 · 应用内更新"); title.setTextSize(23); layout.addView(title);
        status = new TextView(this); status.setPadding(0,pad,0,pad); status.setText("准备更新…"); layout.addView(status);
        install = new Button(this); install.setText("确认安装并重新打开米米"); install.setEnabled(false); layout.addView(install);
        install.setOnClickListener(v -> install());
        Button close = new Button(this); close.setText("关闭"); close.setOnClickListener(v -> finish()); layout.addView(close);
        setContentView(layout);
        if (getIntent().hasExtra(PackageInstaller.EXTRA_STATUS)) { handleStatus(getIntent()); return; }
        String tag = getIntent().getStringExtra("tag");
        if (tag == null) { status.setText("请从关于 → 检查更新进入"); return; }
        directory = new File(getCacheDir(), "update-" + UUID.randomUUID());
        if (!directory.mkdir()) { status.setText("无法创建更新目录，请检查存储空间"); return; }
        job = worker.submit(() -> {
            try {
                File ready = UpdateDownload.prepare(this, tag, directory, this::progress);
                runOnUiThread(() -> { if (!isFinishing() && !isDestroyed()) { apk = ready; install.setEnabled(true); } });
            } catch (Exception e) {
                MacroController.log(this, "更新准备失败：" + e);
                progress("更新失败：" + e.getMessage() + "\n可返回‘关于’重试或从 GitHub 下载完整包。");
            } finally { if (isFinishing() || isDestroyed()) cleanup(); }
        });
    }
    private void progress(String message) {
        runOnUiThread(() -> { if (!isFinishing() && !isDestroyed()) status.setText(message); });
    }
    private void install() {
        if (apk == null || installing) return;
        if (!getPackageManager().canRequestPackageInstalls()) {
            status.setText("请允许米米安装应用。返回后再次点击安装；不会自动取得授权。");
            try { startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, android.net.Uri.parse("package:" + getPackageName()))); }
            catch (RuntimeException e) { status.setText("无法打开安装授权设置：" + e.getMessage()); }
            return;
        }
        installing = true; install.setEnabled(false); status.setText("正在提交系统安装器，请确认安装。安装期间米米会退出；系统若阻止自动打开，请点击安装完成页的‘打开’。");
        job = worker.submit(() -> {
            PackageInstaller installer = getPackageManager().getPackageInstaller(); int id = -1;
            try {
                PackageInfo current = getPackageManager().getPackageInfo(getPackageName(), PackageManager.GET_SIGNING_CERTIFICATES);
                PackageInfo next = getPackageManager().getPackageArchiveInfo(apk.getAbsolutePath(), 0);
                if (next == null) throw new IOException("无法读取安装包");
                UpdateDownload.verify(this, apk, current, next.getLongVersionCode());
                PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
                params.setAppPackageName(getPackageName()); params.setSize(apk.length());
                if (Build.VERSION.SDK_INT >= 31) params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED);
                id = installer.createSession(params);
                try (PackageInstaller.Session session = installer.openSession(id)) {
                    try (InputStream in = new FileInputStream(apk); OutputStream out = session.openWrite("base.apk", 0, apk.length())) {
                        byte[] buffer = new byte[32768]; int n;
                        while ((n = in.read(buffer)) != -1) { DeltaPatch.interrupted(); out.write(buffer, 0, n); }
                        session.fsync(out);
                    }
                    DeltaPatch.interrupted();
                    String token = UUID.randomUUID().toString();
                    if (!MacroController.prefs(this).edit().putInt("installSession", id).putString("installToken", token)
                        .putLong("reopenVersion", next.getLongVersionCode()).commit()) throw new IOException("无法保存安装状态");
                    Intent callback = new Intent(this, UpdateActivity.class).putExtra("installToken", token).setAction("install-result-" + id);
                    ActivityOptions options = ActivityOptions.makeBasic();
                    if (Build.VERSION.SDK_INT >= 35) options.setPendingIntentCreatorBackgroundActivityStartMode(
                        Build.VERSION.SDK_INT >= 36 ? ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_IF_VISIBLE : ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED);
                    PendingIntent pending = PendingIntent.getActivity(this, id, callback, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE, options.toBundle());
                    session.commit(pending.getIntentSender());
                    MacroController.log(this, "更新安装已提交 session=" + id);
                }
            } catch (Exception e) {
                if (id >= 0) { try { installer.abandonSession(id); } catch (RuntimeException ignored) {} }
                MacroController.prefs(this).edit().remove("reopenVersion").remove("installToken").apply();
                MacroController.log(this, "更新安装提交失败：" + e);
                progress("安装提交失败：" + e.getMessage());
                runOnUiThread(() -> { installing = false; if (!isDestroyed()) install.setEnabled(apk != null); });
            } finally { if (isFinishing() || isDestroyed()) cleanup(); }
        });
    }
    @Override protected void onNewIntent(Intent intent) { super.onNewIntent(intent); setIntent(intent); handleStatus(intent); }
    private void handleStatus(Intent intent) {
        String expected = MacroController.prefs(this).getString("installToken", "");
        if (expected.isEmpty() || !expected.equals(intent.getStringExtra("installToken")) || intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1) != MacroController.prefs(this).getInt("installSession", -2)) {
            status.setText("安装回执无效或已过期，请返回主界面检查版本。"); return;
        }
        int result = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        MacroController.log(this, "安装回执 status=" + result + " " + intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE));
        if (result == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirmation = intent.getParcelableExtra(Intent.EXTRA_INTENT);
            try {
                if (confirmation == null) throw new IllegalStateException("缺少系统确认界面");
                startActivity(confirmation); status.setText("等待系统安装确认；若取消，请返回关于重新检查更新。");
            } catch (RuntimeException e) { status.setText("无法打开安装确认：" + e.getMessage()); }
        } else {
            MacroController.prefs(this).edit().remove("installToken").apply();
            if (result == PackageInstaller.STATUS_SUCCESS) {
                MacroController.prefs(this).edit().remove("reopenVersion").apply();
                startActivity(new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK)); finish();
            } else {
                MacroController.prefs(this).edit().remove("reopenVersion").apply();
                status.setText("安装未完成：" + intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) + "\n原版本仍可使用，请返回关于重试。");
            }
        }
    }
    private void cleanup() {
        if (directory != null) { File[] files = directory.listFiles(); if (files != null) for (File file : files) file.delete(); directory.delete(); }
    }
    @Override protected void onDestroy() {
        if (job != null) job.cancel(true);
        worker.execute(this::cleanup);
        worker.shutdown();
        super.onDestroy();
    }
}
