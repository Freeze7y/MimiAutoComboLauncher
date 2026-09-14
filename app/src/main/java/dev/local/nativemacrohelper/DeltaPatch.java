package dev.local.nativemacrohelper;

import java.io.*;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.zip.GZIPInputStream;

/** Bounded copy/literal patch over the exact signed APK bytes. */
final class DeltaPatch {
    static final long MAX = 64L * 1024 * 1024;
    static byte[] hash(File file) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new FileInputStream(file)) {
            byte[] buffer = new byte[32768]; int n;
            while ((n = in.read(buffer)) != -1) { interrupted(); md.update(buffer, 0, n); }
        }
        return md.digest();
    }
    static String hex(byte[] bytes) {
        StringBuilder text = new StringBuilder();
        for (byte b : bytes) text.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
        return text.toString();
    }
    static void interrupted() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("更新已取消");
    }
    static void apply(File base, File patch, File output) throws Exception {
        boolean success = false;
        try (DataInputStream in = new DataInputStream(new GZIPInputStream(new FileInputStream(patch)));
             RandomAccessFile old = new RandomAccessFile(base, "r")) {
            if (in.readInt() != 0x4d4d4431) throw new IOException("补丁格式不支持");
            long size = in.readLong();
            if (size <= 0 || size > MAX) throw new IOException("目标大小无效");
            byte[] baseHash = new byte[32], targetHash = new byte[32];
            in.readFully(baseHash); in.readFully(targetHash);
            if (!Arrays.equals(baseHash, hash(base))) throw new IOException("旧安装包与补丁不匹配");
            byte[] buffer = new byte[32768]; long written = 0; int commands = 0;
            try (OutputStream out = new BufferedOutputStream(new FileOutputStream(output))) {
                while (true) {
                    interrupted(); int type = in.readUnsignedByte();
                    if (type == 0) break;
                    if (++commands > 1000000 || (type != 1 && type != 2)) throw new IOException("补丁指令无效");
                    long offset = type == 1 ? in.readLong() : 0;
                    int length = in.readInt();
                    if (length <= 0 || length > size - written) throw new IOException("补丁输出越界");
                    if (type == 1) {
                        if (offset < 0 || offset > old.length() || length > old.length() - offset) throw new IOException("补丁读取越界");
                        old.seek(offset);
                    }
                    int left = length;
                    while (left > 0) {
                        interrupted(); int n = Math.min(left, buffer.length);
                        if (type == 1) old.readFully(buffer, 0, n); else in.readFully(buffer, 0, n);
                        out.write(buffer, 0, n); left -= n;
                    }
                    written += length;
                }
                if (written != size || in.read() != -1) throw new IOException("补丁长度不符");
            }
            if (!Arrays.equals(targetHash, hash(output))) throw new IOException("合成校验失败");
            success = true;
        } finally { if (!success) output.delete(); }
    }
}
