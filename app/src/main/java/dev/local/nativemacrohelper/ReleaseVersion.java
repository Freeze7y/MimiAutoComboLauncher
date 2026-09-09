package dev.local.nativemacrohelper;

final class ReleaseVersion {
    static boolean newer(String remote, String installed) {
        long[] a = parse(remote), b = parse(installed);
        for (int i = 0; i < 3; i++) if (a[i] != b[i]) return a[i] > b[i];
        return false;
    }
    private static long[] parse(String version) {
        if (version == null || !version.matches("[vV]?[0-9]{1,9}(\\.[0-9]{1,9}){0,2}"))
            throw new IllegalArgumentException("无法识别 GitHub 版本号");
        String[] parts = version.replaceFirst("^[vV]", "").split("\\.");
        long[] values = new long[3];
        for (int i = 0; i < parts.length; i++) values[i] = Long.parseLong(parts[i]);
        return values;
    }
}
