package dev.local.nativemacrohelper;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.*;
import android.graphics.Insets;
import android.graphics.drawable.Icon;
import android.os.*;
import android.provider.Settings;
import android.text.*;
import android.view.*;
import android.widget.*;
import java.util.*;
import java.util.concurrent.*;

public final class MainActivity extends Activity {
    private final List<String> all = new ArrayList<>(), shown = new ArrayList<>();
    private final Map<String, String> labels = new HashMap<>();
    private final Map<String, android.graphics.Bitmap> appIcons = new HashMap<>();
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ExecutorService updateWorker = Executors.newSingleThreadExecutor();
    private boolean checkingUpdate, resumed;
    private ArrayAdapter<String> adapter;
    private EditText search;
    private TextView status, empty;
    private CheckBox onlyFavorites;
    private String pendingGame, pendingAction;
    private View listHeader;
    private <T extends View> T ui(int id) {
        T view = findViewById(id);
        return view != null ? view : listHeader.findViewById(id);
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_main);
        listHeader = getLayoutInflater().inflate(R.layout.list_header, null, false);
        ListView applicationList = findViewById(R.id.list);
        applicationList.addHeaderView(listHeader, null, false);
        View root = ui(R.id.root);
        int padding = dp(20);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                Insets i = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
                v.setPadding(padding + i.left, padding + i.top, padding + i.right, padding + i.bottom);
                boolean compact = insets.isVisible(WindowInsets.Type.ime()) || getResources().getConfiguration().screenHeightDp < 480;
                ui(R.id.hero).setVisibility(compact ? View.GONE : View.VISIBLE);
            }
            return insets;
        });
        if (Build.VERSION.SDK_INT >= 30) getWindow().setDecorFitsSystemWindows(false);
        root.requestApplyInsets();
        status = ui(R.id.status); empty = ui(R.id.empty);
        search = ui(R.id.search); onlyFavorites = ui(R.id.favorites);
        adapter = new ArrayAdapter<String>(this, R.layout.app_row, android.R.id.text1, shown) {
            @Override public View getView(int position, View view, android.view.ViewGroup parent) {
                View row = super.getView(position, view, parent);
                String pkg = getItem(position);
                ((TextView) row.findViewById(android.R.id.text1)).setText((favorites().contains(pkg) ? "★ " : "") + labels.get(pkg));
                ((TextView) row.findViewById(android.R.id.text2)).setText(pkg);
                ImageView icon = row.findViewById(R.id.app_icon);
                android.graphics.Bitmap bitmap = appIcons.get(pkg);
                if (bitmap != null) icon.setImageBitmap(bitmap);
                else icon.setImageDrawable(getPackageManager().getDefaultActivityIcon());
                return row;
            }
        };
        ListView list = ui(R.id.list); list.setAdapter(adapter);
        list.setOnItemClickListener((p, v, position, id) -> {
            getSystemService(android.view.inputmethod.InputMethodManager.class).hideSoftInputFromWindow(search.getWindowToken(), 0);
            int index = position - list.getHeaderViewsCount();
            if (index >= 0 && index < shown.size()) actions(shown.get(index));
        });
        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            public void onTextChanged(CharSequence s, int start, int before, int count) { filter(); }
            public void afterTextChanged(Editable s) {}
        });
        onlyFavorites.setOnCheckedChangeListener((v, checked) -> filter());
        ui(R.id.manual).setOnClickListener(v -> manual());
        ui(R.id.recent).setOnClickListener(v -> {
            String last = MacroController.prefs(this).getString("lastGame", "");
            if (last.isEmpty()) toast("尚未启动过应用"); else actions(last);
        });
        ui(R.id.diagnostics).setOnClickListener(v -> diagnostics());
        ui(R.id.about).setOnClickListener(v -> about());
        if (state != null) {
            pendingGame = state.getString("pendingGame");
            pendingAction = state.getString("pendingAction");
        }
        worker.execute(() -> {
            Map<String, String> loaded = new HashMap<>();
            Map<String, android.graphics.Bitmap> icons = new HashMap<>();
            Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
            try {
            for (ResolveInfo r : getPackageManager().queryIntentActivities(i, 0)) {
                if (Thread.currentThread().isInterrupted()) break;
                String pkg = r.activityInfo.packageName;
                if (!pkg.equals(getPackageName()) && !loaded.containsKey(pkg)) {
                    loaded.put(pkg, r.loadLabel(getPackageManager()).toString());
                    android.graphics.drawable.Drawable icon = r.activityInfo.applicationInfo.loadIcon(getPackageManager());
                    int size = Math.min(dp(44), 144);
                    android.graphics.Bitmap bitmap = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888);
                    icon.setBounds(0, 0, size, size); icon.draw(new android.graphics.Canvas(bitmap));
                    icons.put(pkg, bitmap);
                }
            }
            } catch (RuntimeException e) { MacroController.log(this, "读取应用列表失败: " + e); }
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                labels.putAll(loaded); appIcons.putAll(icons); all.addAll(loaded.keySet()); filter();
            });
        });
        worker.execute(() -> {
            try {
                android.graphics.drawable.Drawable image = android.graphics.ImageDecoder.decodeDrawable(
                    android.graphics.ImageDecoder.createSource(getResources(), R.raw.hero_animation));
                runOnUiThread(() -> {
                    if (!isDestroyed()) ((GifBanner) ui(R.id.banner)).setImageDrawable(image);
                });
            } catch (java.io.IOException | RuntimeException e) { MacroController.log(this, "GIF 解码失败: " + e); }
        });
        if (state == null && android.animation.ValueAnimator.areAnimatorsEnabled()) {
            View hero = ui(R.id.hero);
            hero.setAlpha(0f); hero.setTranslationY(dp(8));
            hero.animate().alpha(1f).translationY(0f).setDuration(240).setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
        }
    }

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        DiagnosticTrace.activityState = "RESUMED（生命周期状态）";
        updateStatus();
        if (MacroController.prefs(this).getBoolean("autoUpdate", false)
            && System.currentTimeMillis() - MacroController.prefs(this).getLong("updateAttempt", 0) >= 21600000L) checkUpdate(false);
    }
    private void updateStatus() {
        if (status == null) return;
        String provider = MacroController.provider(this);
        status.setText(provider.isEmpty() ? "未找到原生宏组件，请查看诊断" : "已检测到" + (provider.equals(MacroController.XIAOMI) ? "小米" : "黑鲨") + "原生宏 · 选择应用开始\n实际支持情况以游戏内显示为准");
    }
    @Override protected void onPause() { DiagnosticTrace.activityState = "PAUSED（生命周期状态）"; resumed = false; super.onPause(); }
    @Override protected void onDestroy() { worker.shutdownNow(); updateWorker.shutdownNow(); super.onDestroy(); }

    private String installedVersion() {
        try { return getPackageManager().getPackageInfo(getPackageName(), 0).versionName; }
        catch (PackageManager.NameNotFoundException e) { return "0.0.0"; }
    }

    private void about() {
        LinearLayout content = new LinearLayout(this); content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(24), dp(12), dp(24), dp(12));
        TextView version = new TextView(this); version.setText(getString(R.string.app_name) + "\n版本 " + installedVersion()); version.setTextSize(17); content.addView(version);
        Switch automatic = new Switch(this); automatic.setText("自动检查 GitHub 更新"); automatic.setMinHeight(dp(56));
        automatic.setChecked(MacroController.prefs(this).getBoolean("autoUpdate", false)); content.addView(automatic);
        TextView detail = new TextView(this); detail.setText("默认关闭。开启后仅在打开 App 时检查，最多每 6 小时一次。只检查正式 Release，不自动下载或安装。"); detail.setTextSize(13); content.addView(detail);
        automatic.setOnCheckedChangeListener((button, enabled) -> {
            MacroController.prefs(this).edit().putBoolean("autoUpdate", enabled).apply();
        });
        new AlertDialog.Builder(this).setTitle("关于").setView(content)
            .setPositiveButton("检查更新", (d, n) -> checkUpdate(true))
            .setNeutralButton("GitHub 项目", (d, n) -> openProject(UpdateChecker.PROJECT))
            .setNegativeButton("关闭", null).show();
    }

    private void openProject(String url) {
        try { startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))); }
        catch (RuntimeException e) { toast("无法打开浏览器：" + e.getMessage()); }
    }

    private void checkUpdate(boolean manual) {
        if (checkingUpdate) { if (manual) toast("正在检查，请稍候"); return; }
        checkingUpdate = true;
        MacroController.prefs(this).edit().putLong("updateAttempt", System.currentTimeMillis()).apply();
        if (manual) toast("正在检查 GitHub 更新…");
        String installed = installedVersion();
        updateWorker.execute(() -> {
            String tag = null, error = null; boolean newer = false;
            try { tag = UpdateChecker.latestTag(); newer = ReleaseVersion.newer(tag, installed); }
            catch (Exception e) { error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(); }
            final String found = tag, failure = error; final boolean available = newer;
            runOnUiThread(() -> {
                checkingUpdate = false;
                if (isDestroyed() || isFinishing() || !resumed) return;
                if (failure != null) { if (manual) toast("检查失败：" + failure); return; }
                if (!manual) {
                    if (available && MacroController.prefs(this).getBoolean("autoUpdate", false))
                        status.setText("GitHub 有新版本 " + found + "，请从右上角「关于」检查并下载");
                    return;
                }
                if (!available) { toast("当前已是最新版本（GitHub " + found + "）"); return; }
                new AlertDialog.Builder(this).setTitle("发现新版本 " + found)
                    .setMessage("当前版本 " + installed + "。在应用内下载增量补丁？无适用补丁时下载完整包，安装仍需系统确认。")
                    .setNegativeButton("稍后", null).setPositiveButton("应用内更新", (d, n) -> startActivity(new Intent(this, UpdateActivity.class).putExtra("tag", found))).show();
            });
        });
    }

    private Set<String> favorites() { return new HashSet<>(MacroController.prefs(this).getStringSet("favorites", Collections.emptySet())); }
    private void filter() {
        String q = search.getText().toString().trim().toLowerCase(Locale.ROOT);
        Set<String> fav = favorites(); shown.clear();
        for (String pkg : all) if ((!onlyFavorites.isChecked() || fav.contains(pkg)) && (pkg.toLowerCase(Locale.ROOT).contains(q) || labels.get(pkg).toLowerCase(Locale.ROOT).contains(q))) shown.add(pkg);
        shown.sort(Comparator.<String>comparingInt(p -> fav.contains(p) ? 0 : p.equals("com.tencent.tmgp.sgame") ? 1 : 2).thenComparing(p -> labels.get(p)));
        empty.setText("没有匹配的可启动应用，可尝试输入包名");
        empty.setVisibility(shown.isEmpty() ? View.VISIBLE : View.GONE); adapter.notifyDataSetChanged();
        ((TextView) ui(R.id.count)).setText("应用  ·  " + shown.size());
    }

    private void actions(String game) {
        boolean favorite = favorites().contains(game);
        String[] choices = {"启动游戏并开启原生宏", "打开原生宏面板", "停止原生宏（结束当前会话）", favorite ? "取消收藏" : "收藏", "创建桌面启动快捷方式"};
        showActions(MacroController.label(this, game), game, choices, n -> {
            if (n == 0 || n == 1) {
                runCommand(game, n == 0 ? "launch" : "panel");
            } else if (n == 2) {
                new AlertDialog.Builder(this).setMessage("停止会关闭原生宏当前会话，请先保存需要的录制。")
                    .setNegativeButton("取消", null).setPositiveButton("停止", (dialog, which) -> toast(MacroController.execute(this, game, "stop"))).show();
            } else if (n == 3) {
                Set<String> fav = favorites(); if (!fav.add(game)) fav.remove(game);
                MacroController.prefs(this).edit().putStringSet("favorites", fav).apply(); filter();
            } else shortcut(game);
        });
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private void showActions(String title, String subtitle, String[] choices, java.util.function.IntConsumer select) {
        Dialog dialog = new Dialog(this);
        LinearLayout card = new LinearLayout(this); card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(20), dp(20), dp(20), dp(24)); card.setBackgroundResource(R.drawable.card);
        TextView heading = new TextView(this); heading.setText(title); heading.setTextSize(22); heading.setTypeface(null, 1); card.addView(heading);
        TextView sub = new TextView(this); sub.setText(subtitle); sub.setTextSize(12); sub.setTextColor(getColor(R.color.muted)); sub.setPadding(0, dp(6), 0, dp(18)); card.addView(sub);
        for (int n = 0; n < choices.length; n++) {
            final int index = n;
            TextView button = new TextView(this); button.setText(choices[n]); button.setTextSize(15);
            button.setGravity(Gravity.CENTER_VERTICAL); button.setPadding(dp(16), dp(12), dp(16), dp(12)); button.setMinHeight(dp(52));
            button.setBackgroundResource(n == 0 ? R.drawable.soft : R.drawable.tap_surface);
            button.setTextColor(getColor(n == 0 ? R.color.accent : R.color.ink));
            button.setFocusable(true); button.setOnClickListener(v -> { dialog.dismiss(); select.accept(index); });
            card.addView(button, new LinearLayout.LayoutParams(-1, -2));
        }
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(false); scroll.addView(card);
        dialog.setContentView(scroll); Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawableResource(android.R.color.transparent); window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            window.setDimAmount(0.3f); window.setGravity(Gravity.BOTTOM);
            window.setWindowAnimations(android.animation.ValueAnimator.areAnimatorsEnabled() ? R.style.SheetMotion : 0);
            window.setNavigationBarColor(getColor(R.color.surface));
        }
        dialog.show();
        if (window != null) window.setLayout(Math.min(getResources().getDisplayMetrics().widthPixels - dp(24), dp(520)), WindowManager.LayoutParams.WRAP_CONTENT);
    }

    private boolean notificationsReady() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        NotificationChannel channel = nm.getNotificationChannel("controls");
        return nm.areNotificationsEnabled() && (channel == null || channel.getImportance() != NotificationManager.IMPORTANCE_NONE);
    }

    private void runCommand(String game, String action) {
        pendingGame = game; pendingAction = action;
        if (!missingRuntimePermissions().isEmpty()) {
            requestPermissions(missingRuntimePermissions().toArray(new String[0]), 1);
        } else if (!notificationsReady()) {
            notificationSettingsPrompt();
        } else {
            submitPending();
        }
    }

    private void submitPending() {
        if (pendingGame == null) return;
        String game = pendingGame, action = pendingAction;
        pendingGame = null; pendingAction = null;
        String result = MacroController.request(this, game, action);
        status.setText(result); toast(result);
    }

    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(code, permissions, grants);
        if (code != 1) return;
        if (!missingRuntimePermissions().isEmpty()) {
            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) notificationSettingsPrompt();
            else permissionOverview();
        } else if (notificationsReady()) {
            if (pendingGame != null) submitPending(); else toast("所需运行时权限已就绪");
        } else notificationSettingsPrompt();
    }

    private List<String> missingRuntimePermissions() {
        List<String> missing = new ArrayList<>();
        try {
            String[] permissions = getPackageManager().getPackageInfo(getPackageName(), PackageManager.GET_PERMISSIONS).requestedPermissions;
            if (permissions != null) for (String permission : permissions) {
                try {
                    PermissionInfo info = getPackageManager().getPermissionInfo(permission, 0);
                    if ((info.protectionLevel & PermissionInfo.PROTECTION_MASK_BASE) == PermissionInfo.PROTECTION_DANGEROUS
                        && checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) missing.add(permission);
                } catch (PackageManager.NameNotFoundException ignored) { /* Other vendor not installed. */ }
            }
        } catch (PackageManager.NameNotFoundException e) { MacroController.log(this, "权限检查失败: " + e); }
        return missing;
    }

    private void permissionOverview() {
        String provider = MacroController.provider(this);
        boolean macroGranted = !provider.isEmpty() && checkSelfPermission(provider + ".permission") == PackageManager.PERMISSION_GRANTED;
        String message = "通知及快捷操作：" + (notificationsReady() ? "已开启" : "需要开启")
            + "\n原生宏调用权限：" + (provider.isEmpty() ? "未找到组件" : macroGranted ? "已授予" : "未授予，请查看诊断")
            + "\n\n前台服务权限由系统按声明授予。当前不需要无障碍、使用情况访问或助手悬浮窗权限。"
            + "\n\n小米的自启动、后台限制和桌面快捷方式属于系统特殊设置，不能用普通权限弹窗代为开启。";
        new AlertDialog.Builder(this).setTitle("权限检查").setMessage(message)
            .setPositiveButton("检查并申请", (d, n) -> {
                List<String> missing = missingRuntimePermissions();
                if (!missing.isEmpty()) requestPermissions(missing.toArray(new String[0]), 1);
                else if (!notificationsReady()) notificationSettingsPrompt();
                else toast("所需运行时权限已就绪，无需重复授权");
            })
            .setNeutralButton("应用权限设置", (d, n) -> startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                android.net.Uri.parse("package:" + getPackageName()))))
            .setNegativeButton("关闭", null).show();
    }

    private void notificationSettingsPrompt() {
        new AlertDialog.Builder(this).setTitle("需要通知权限")
            .setMessage("游戏内打开面板和停止按钮需要通知权限。助手可以带你进入本应用的通知设置，授权后自动继续刚才的操作。")
            .setNegativeButton("取消", (d, n) -> { pendingGame = null; pendingAction = null; })
            .setPositiveButton("前往授权", (d, n) -> {
                NotificationChannel channel = getSystemService(NotificationManager.class).getNotificationChannel("controls");
                boolean channelOnly = getSystemService(NotificationManager.class).areNotificationsEnabled() && channel != null;
                Intent settings = new Intent(channelOnly ? Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS : Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName()).putExtra(Settings.EXTRA_CHANNEL_ID, "controls");
                startActivity(settings);
            })
            .setOnCancelListener(d -> { pendingGame = null; pendingAction = null; }).show();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putString("pendingGame", pendingGame); state.putString("pendingAction", pendingAction);
        super.onSaveInstanceState(state);
    }

    private void manual() {
        EditText input = new EditText(this); input.setSingleLine(true); input.setHint("com.tencent.tmgp.sgame");
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        new AlertDialog.Builder(this).setTitle("输入游戏包名").setView(input).setNegativeButton("取消", null)
            .setPositiveButton("选择", (d, n) -> {
                String pkg = input.getText().toString().trim();
                if (pkg.matches("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+")) actions(pkg); else toast("包名格式不正确");
            }).show();
    }

    private void shortcut(String game) {
        ShortcutManager manager = getSystemService(ShortcutManager.class);
        if (!manager.isRequestPinShortcutSupported()) { toast("当前桌面不支持固定快捷方式"); return; }
        String token = MacroController.prefs(this).getString("shortcutToken", "");
        if (token.isEmpty()) {
            token = UUID.randomUUID().toString();
            MacroController.prefs(this).edit().putString("shortcutToken", token).apply();
        }
        Intent i = new Intent(this, MainActivity.class).setAction(Intent.ACTION_VIEW).putExtra("shortcutGame", game).putExtra("shortcutToken", token);
        try {
            android.graphics.drawable.Drawable drawable = getPackageManager().getApplicationIcon(game);
            int size = Math.min(dp(72), 288);
            android.graphics.Bitmap bitmap = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888);
            android.graphics.Canvas canvas = new android.graphics.Canvas(bitmap);
            drawable.setBounds(0, 0, size, size); drawable.draw(canvas);
            Icon icon = drawable instanceof android.graphics.drawable.AdaptiveIconDrawable
                ? Icon.createWithAdaptiveBitmap(bitmap) : Icon.createWithBitmap(bitmap);
            ShortcutInfo info = new ShortcutInfo.Builder(this, game).setShortLabel(MacroController.label(this, game) + " · 宏")
                .setIcon(icon).setIntent(i).build();
            manager.updateShortcuts(Collections.singletonList(info));
            toast(manager.requestPinShortcut(info, null) ? "已请求桌面添加快捷方式" : "桌面未接受请求");
        } catch (PackageManager.NameNotFoundException | RuntimeException e) {
            MacroController.log(this, "shortcut: " + e); toast("快捷方式创建失败，请确认目标应用仍已安装");
        }
    }

    @Override public void onWindowFocusChanged(boolean focus) {
        super.onWindowFocusChanged(focus);
        if (focus) handleFocusedCommands();
    }
    private void handleFocusedCommands() {
        if (isFinishing() || isDestroyed() || !hasWindowFocus()) return;
        if (pendingGame != null && notificationsReady() && missingRuntimePermissions().isEmpty()) submitPending();
        if (getIntent().hasExtra("shortcutGame")) {
            String game = getIntent().getStringExtra("shortcutGame");
            String token = getIntent().getStringExtra("shortcutToken");
            getIntent().removeExtra("shortcutGame");
            String expected = MacroController.prefs(this).getString("shortcutToken", "");
            if (!expected.isEmpty() && expected.equals(token)) {
                runCommand(game, "launch");
            } else {
                actions(game == null ? "" : game);
            }
        } else if (!MacroController.prefs(this).getBoolean("permissionIntro103", false)) {
            MacroController.prefs(this).edit().putBoolean("permissionIntro103", true).apply();
            List<String> missing = missingRuntimePermissions();
            if (!missing.isEmpty()) requestPermissions(missing.toArray(new String[0]), 1);
            else permissionOverview();
        }
    }
    @Override protected void onNewIntent(Intent i) {
        super.onNewIntent(i); setIntent(i);
        // singleTop can receive a shortcut while already focused, without a new focus callback.
        if (hasWindowFocus()) getWindow().getDecorView().post(this::handleFocusedCommands);
    }

    private String report() {
        StringBuilder b = new StringBuilder(getString(R.string.app_name)).append(' ').append(installedVersion()).append("\nAndroid ").append(Build.VERSION.RELEASE).append(" / API ").append(Build.VERSION.SDK_INT)
            .append("\n设备：").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append("\n系统构建：").append(Build.DISPLAY).append("\n");
        b.append("\n").append(DiagnosticTrace.report(this));
        return b.toString();
    }

    private void diagnosticTests() {
        String game = MacroController.prefs(this).getString("attemptGame", "");
        if (game.isEmpty()) game = MacroController.prefs(this).getString("lastGame", "");
        DiagnosticRun.run(this, game);
        String report = report();
        TextView text = new TextView(this); text.setText(report); text.setPadding(dp(20),dp(12),dp(20),dp(12)); text.setTextIsSelectable(true);
        ScrollView scroll = new ScrollView(this); scroll.addView(text);
        new AlertDialog.Builder(this).setTitle("一键诊断结果").setView(scroll)
            .setPositiveButton("权限与设置", (d,n) -> permissionOverview())
            .setNeutralButton("复制完整报告", (d,n) -> {
                getSystemService(android.content.ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("宏诊断", report())); toast("已复制");
            }).setNegativeButton("关闭", null).show();
    }

    private void diagnostics() {
        String[] options = {"查看 / 复制诊断日志", "选择宏组件", "通知设置", "清空日志", "权限检查与申请", "一键诊断（结束后停止宏）", "补充面板显示结果"};
        new AlertDialog.Builder(this).setTitle("诊断与设置").setItems(options, (d, n) -> {
            if (n == 0) {
                String report = report(); TextView text = new TextView(this); text.setText(report); text.setTextIsSelectable(true); text.setPadding(24, 16, 24, 16);
                ScrollView scroll = new ScrollView(this); scroll.addView(text);
                new AlertDialog.Builder(this).setTitle("本地诊断").setView(scroll).setNegativeButton("关闭", null)
                    .setPositiveButton("复制", (dialog, which) -> { getSystemService(android.content.ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("宏诊断", report)); toast("已复制"); }).show();
            } else if (n == 1) {
                String[] providers = {MacroController.XIAOMI, MacroController.SHARK};
                new AlertDialog.Builder(this).setTitle("选择已安装的宏组件").setItems(providers, (dialog, which) -> {
                    if (!MacroController.installed(this, providers[which])) { toast("该组件未安装"); return; }
                    MacroController.prefs(this).edit().putString("provider", providers[which]).apply(); updateStatus();
                }).show();
            } else if (n == 2) {
                startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName()));
            } else if (n == 3) { DiagnosticTrace.clear(this); toast("日志已清空"); }
            else if (n == 4) permissionOverview();
            else if (n == 5) diagnosticTests();
            else if (n == 6) {
                String operation = MacroController.prefs(this).getString("displayOperation", "");
                if (operation.isEmpty()) { toast("尚无启动游戏或打开面板请求"); return; }
                String[] results = {"第一次就出现", "第二次才出现", "始终未出现", "没有观察"};
                new AlertDialog.Builder(this).setTitle("最近启动或面板请求的实际效果").setMessage(operation)
                    .setPositiveButton("选择结果", (a,b) -> new AlertDialog.Builder(this).setItems(results, (v,i) -> {
                        MacroController.prefs(this).edit().putString("displayObservation", operation + " / 用户报告：" + results[i]).apply(); toast("已加入报告");
                    }).show()).setNegativeButton("取消", null).show();
            }

        }).show();
    }
    private void toast(String message) { Toast.makeText(this, message, Toast.LENGTH_LONG).show(); }
}
