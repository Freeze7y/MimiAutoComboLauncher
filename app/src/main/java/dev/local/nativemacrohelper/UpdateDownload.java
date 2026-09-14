package dev.local.nativemacrohelper;

import android.content.Context;
import android.content.pm.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.json.*;

final class UpdateDownload {
    interface Progress { void show(String message); }
    static final String ROOT = UpdateChecker.PROJECT + "/releases/download/";
    static void fetch(String address, File file, long size, Progress progress) throws Exception {
        URL url = new URL(address); HttpURLConnection connection = null;
        try {
            for (int redirects = 0; ; redirects++) {
                String host = url.getHost();
                if (!url.getProtocol().equals("https") || !(host.equals("github.com") || host.equals("release-assets.githubusercontent.com") || host.equals("objects.githubusercontent.com")))
                    throw new IOException("非官方更新地址");
                connection = (HttpURLConnection) url.openConnection();
                connection.setInstanceFollowRedirects(false);
                connection.setConnectTimeout(10000); connection.setReadTimeout(15000);
                connection.setRequestProperty("User-Agent", "MimiAutoComboLauncher");
                int code = connection.getResponseCode();
                if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                    if (redirects >= 5) throw new IOException("重定向过多");
                    String location = connection.getHeaderField("Location");
                    if (location == null) throw new IOException("下载重定向无效");
                    url = new URL(url, location); connection.disconnect(); continue;
                }
                if (code != 200) throw new IOException("GitHub HTTP " + code);
                break;
            }
            long count = 0, shown = 0;
            try (InputStream in = connection.getInputStream(); OutputStream out = new FileOutputStream(file)) {
                byte[] buffer = new byte[32768]; int n;
                while ((n = in.read(buffer)) != -1) {
                    DeltaPatch.interrupted(); count += n;
                    if (count > size) throw new IOException("下载超过声明大小");
                    out.write(buffer, 0, n);
                    long now = System.currentTimeMillis();
                    if (now - shown > 250) { progress.show("已下载 " + count / 1024 + " / " + size / 1024 + " KB"); shown = now; }
                }
            }
        } finally { if (connection != null) connection.disconnect(); }
    }
    static void asset(JSONObject asset, String prefix, File output, Progress progress) throws Exception {
        String url = asset.getString("url"); long size = asset.getLong("size");
        if (!url.startsWith(prefix) || url.substring(prefix.length()).contains("/") || url.contains("..") || url.contains("%") || url.contains("?") || url.contains("#")) throw new IOException("更新文件地址无效");
        if (size <= 0 || size > DeltaPatch.MAX) throw new IOException("更新大小无效");
        fetch(url, output, size, progress);
        if (output.length() != size || !DeltaPatch.hex(DeltaPatch.hash(output)).equals(asset.getString("sha256"))) throw new IOException("下载校验失败");
    }
    static File prepare(Context c, String tag, File dir, Progress progress) throws Exception {
        if (!tag.matches("v[0-9]+\\.[0-9]+\\.[0-9]+")) throw new IOException("版本格式不支持");
        String prefix = ROOT + tag + "/";
        File metadata = new File(dir, "update.json");
        progress.show("读取更新信息"); fetch(prefix + "update.json", metadata, 262144, message -> {});
        byte[] data = new byte[(int)metadata.length()];
        try (DataInputStream in = new DataInputStream(new FileInputStream(metadata))) { in.readFully(data); }
        JSONObject manifest = new JSONObject(new String(data, StandardCharsets.UTF_8));
        long code = manifest.getLong("versionCode");
        PackageInfo current = c.getPackageManager().getPackageInfo(c.getPackageName(), PackageManager.GET_SIGNING_CERTIFICATES);
        if (manifest.getInt("format") != 1 || !("v" + manifest.getString("version")).equals(tag) || code <= current.getLongVersionCode()) throw new IOException("更新版本不匹配或不是新版");
        File base = new File(c.getApplicationInfo().sourceDir), apk = new File(dir, "update.apk");
        String hash = DeltaPatch.hex(DeltaPatch.hash(base)); boolean patched = false;
        JSONArray patches = manifest.getJSONArray("patches");
        for (int i = 0; i < patches.length(); i++) {
            JSONObject patch = patches.getJSONObject(i);
            if (!hash.equals(patch.getString("baseSha256"))) continue;
            try {
                progress.show("正在下载增量补丁"); File delta = new File(dir, "update.mmd");
                asset(patch, prefix, delta, progress); progress.show("补丁下载完成，正在合成安装包");
                DeltaPatch.apply(base, delta, apk); patched = true;
                MacroController.log(c, "更新：差分补丁合成完成");
            } catch (Exception e) { DeltaPatch.interrupted(); MacroController.log(c, "差分更新失败，回退完整包：" + e); }
            break;
        }
        JSONObject full = manifest.getJSONObject("apk");
        if (patched && (apk.length() != full.getLong("size") || !DeltaPatch.hex(DeltaPatch.hash(apk)).equals(full.getString("sha256")))) {
            patched = false; MacroController.log(c, "合成结果与发布清单不符，回退完整包");
        }
        if (!patched) {
            progress.show("无适用补丁或补丁失败，下载完整包");
            asset(full, prefix, apk, progress);
        }
        verify(c, apk, current, code);
        progress.show((patched ? "增量更新" : "完整更新") + "已准备好；哈希、包名、版本及签名身份检查通过。系统安装器将验证 APK 签名并请求确认。");
        return apk;
    }
    static void verify(Context c, File apk, PackageInfo current, long code) throws Exception {
        PackageInfo next = c.getPackageManager().getPackageArchiveInfo(apk.getAbsolutePath(), PackageManager.GET_SIGNING_CERTIFICATES);
        if (next == null || !c.getPackageName().equals(next.packageName) || next.getLongVersionCode() != code || code <= current.getLongVersionCode()) throw new IOException("安装包身份或版本不符");
        if (next.signingInfo == null || current.signingInfo == null || !Arrays.equals(next.signingInfo.getApkContentsSigners(), current.signingInfo.getApkContentsSigners())) throw new IOException("安装包签名身份不符");
    }
}
