package android.util;

/** JVM 测试用的桩, 行为对齐 Android 的宽松解码。 */
public final class Base64 {
    public static final int DEFAULT = 0;

    public static byte[] decode(String s, int flags) {
        return java.util.Base64.getMimeDecoder().decode(s);
    }
}
