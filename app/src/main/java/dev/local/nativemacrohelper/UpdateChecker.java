package dev.local.nativemacrohelper;

import java.net.HttpURLConnection;
import java.net.URL;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

final class UpdateChecker {
    static final String PROJECT = "https://github.com/Freeze7y/MimiAutoComboLauncher";
    static String latestTag() throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(
            "https://api.github.com/repos/Freeze7y/MimiAutoComboLauncher/releases/latest").openConnection();
        connection.setConnectTimeout(8000); connection.setReadTimeout(8000);
        connection.setRequestProperty("Accept", "application/vnd.github+json");
        connection.setRequestProperty("User-Agent", "MimiAutoComboLauncher");
        try {
            int code = connection.getResponseCode();
            if (code == 403 || code == 429) throw new IOException("GitHub 请求受限，请稍后重试");
            if (code != 200) throw new IOException("GitHub 返回 HTTP " + code);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (InputStream input = connection.getInputStream()) {
                byte[] buffer = new byte[4096]; int count;
                while ((count = input.read(buffer)) != -1) {
                    if (bytes.size() + count > 262144) throw new IOException("更新信息过大");
                    bytes.write(buffer, 0, count);
                }
            }
            JSONObject release = new JSONObject(new String(bytes.toByteArray(), StandardCharsets.UTF_8));
            if (release.getBoolean("draft") || release.getBoolean("prerelease")) throw new IOException("未取得正式发布版本");
            return release.getString("tag_name");
        } finally { connection.disconnect(); }
    }
}
