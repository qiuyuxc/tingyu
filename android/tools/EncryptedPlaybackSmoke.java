import android.media.MediaDataSource;
import android.net.Uri;
import java.io.ByteArrayOutputStream;
import java.io.RandomAccessFile;
import java.io.FileInputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Collections;
import org.json.JSONObject;

/** Runs the built APK's encrypted reader, seek adapter and FLAC recognition without audio output.
 * Compile against android.jar, then use D8 and app_process with the helper and APK on CLASSPATH.
 * Arguments: container, password file, expected plaintext SHA-256.
 * Reflection lets the harness compile without copying the application's runtime dependencies.
 */
public final class EncryptedPlaybackSmoke {
  private static final String APP = "com.qiuyuxc.tingyu.";
  private static Class<?> type(String name) throws Exception { return Class.forName(name); }
  private static Object proxy(Class<?> api, java.lang.reflect.InvocationHandler handler) {
    return Proxy.newProxyInstance(api.getClassLoader(), new Class<?>[]{api}, handler);
  }
  private static String hex(byte[] bytes) {
    StringBuilder value = new StringBuilder();
    for (byte b : bytes) value.append(String.format("%02x", b & 255));
    return value.toString();
  }
  private static void check(boolean value, String message) {
    if (!value) throw new AssertionError(message);
  }

  public static void main(String[] args) throws Exception {
    System.out.println("PROGRESS started");
    if (args.length != 3) throw new IllegalArgumentException("Expected container, password file and plaintext hash");
    String password;
    try (FileInputStream input = new FileInputStream(args[1])) {
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      byte[] buffer = new byte[4096];
      for (int count; (count = input.read(buffer)) >= 0;) bytes.write(buffer, 0, count);
      password = new String(bytes.toByteArray(), "UTF-8");
    }
    RandomAccessFile file = new RandomAccessFile(args[0], "r");
    Class<?> sourceApi = type(APP + "Ncmx$Source");
    Object source = proxy(sourceApi, (p, m, a) -> {
      if (m.getName().equals("length")) return file.length();
      if (m.getName().equals("read")) {
        byte[] bytes = new byte[(Integer)a[1]];
        file.seek((Long)a[0]); file.readFully(bytes); return bytes;
      }
      throw new UnsupportedOperationException(m.getName());
    });
    Class<?> keysApi = type(APP + "Ncmx$Keys");
    Object keys = proxy(keysApi, (p, m, a) ->
        m.getName().equals("forKeyId") ? Collections.singletonList(password) : Collections.emptyList());
    Class<?> callback = type("kotlin.jvm.functions.Function0");
    Object close = proxy(callback, (p, m, a) -> {
      file.close(); return type("kotlin.Unit").getField("INSTANCE").get(null);
    });
    Class<?> readerClass = type(APP + "EncryptedReader");
    Object readerFactory = readerClass.getField("Companion").get(null);
    Object reader = readerFactory.getClass().getMethod("open", sourceApi, keysApi, callback)
        .invoke(readerFactory, source, keys, close);
    System.out.println("PROGRESS reader-opened");
    Class<?> audioClass = type(APP + "MusicDataSource");
    Object audioFactory = audioClass.getField("Companion").get(null);
    MediaDataSource audio = (MediaDataSource)audioFactory.getClass().getMethod("encrypted", readerClass)
        .invoke(audioFactory, reader);
    try {
      Object borrowed = type(APP + "BorrowedMediaDataSource").getConstructor(MediaDataSource.class)
          .newInstance(audio);
      Class<?> dataApi = type("androidx.media3.datasource.DataSource");
      Class<?> specClass = type("androidx.media3.datasource.DataSpec");
      Method open = dataApi.getMethod("open", specClass);
      Method read = dataApi.getMethod("read", byte[].class, int.class, int.class);
      Method endRead = dataApi.getMethod("close");
      open.invoke(borrowed, spec(0));
      MessageDigest plaintextHash = MessageDigest.getInstance("SHA-256");
      byte[] buffer = new byte[65536], tail = new byte[13];
      long total = 0;
      for (int count; (count = (Integer)read.invoke(borrowed, buffer, 0, buffer.length)) >= 0;) {
        if (total == 0) check(buffer[0] == 'f' && buffer[1] == 'L' && buffer[2] == 'a' && buffer[3] == 'C', "FLAC signature missing");
        plaintextHash.update(buffer, 0, count);
        if (count >= tail.length) System.arraycopy(buffer, count - tail.length, tail, 0, tail.length);
        total += count;
      }
      String plaintext = hex(plaintextHash.digest());
      check(total == audio.getSize(), "Audio length changed");
      check(plaintext.equals(args[2]), "Plaintext hash changed: " + plaintext);
      System.out.println("PROGRESS plaintext-verified bytes=" + total);
      endRead.invoke(borrowed);
      open.invoke(borrowed, spec(total - tail.length));
      byte[] sought = new byte[20];
      check((Integer)read.invoke(borrowed, sought, 0, sought.length) == tail.length, "Short tail read changed");
      check(Arrays.equals(tail, Arrays.copyOf(sought, tail.length)), "Seek returned wrong bytes");
      endRead.invoke(borrowed);
      check(audio.getSize() == total, "A seek closed the underlying source");
      System.out.println("PROGRESS seek-reopened");

      open.invoke(borrowed, spec(0));
      Class<?> dataReaderApi = type("androidx.media3.common.DataReader");
      Object dataReader = proxy(dataReaderApi, (p, m, a) -> read.invoke(borrowed, a));
      Class<?> inputClass = type("androidx.media3.extractor.DefaultExtractorInput");
      Object input = inputClass.getConstructor(dataReaderApi, long.class, long.class).newInstance(dataReader, 0L, total);
      Class<?> extractorApi = type("androidx.media3.extractor.Extractor");
      Class<?> inputApi = type("androidx.media3.extractor.ExtractorInput");
      Object factory = type(APP + "FlacExtractorsFactory").getConstructor().newInstance();
      Object[] extractors = (Object[])factory.getClass().getMethod("createExtractors").invoke(factory);
      Object extractor = null;
      for (Object candidate : extractors) {
        Object underlying = extractorApi.getMethod("getUnderlyingImplementation").invoke(candidate);
        if (underlying.getClass().getName().endsWith(".FlacExtractor")) { extractor = candidate; break; }
      }
      check(extractor != null, "FLAC extractor missing");
      check((Boolean)extractorApi.getMethod("sniff", inputApi).invoke(extractor, input), "Encrypted audio was not recognized");
      extractorApi.getMethod("release").invoke(extractor);
      endRead.invoke(borrowed);
      JSONObject result = new JSONObject();
      result.put("plaintext_bytes", total);
      result.put("plaintext_sha256", plaintext);
      result.put("seek_reopen_passed", true);
      result.put("flac_recognized", true);
      result.put("passed", true);
      System.out.println(result.toString());
    } finally { audio.close(); }
    System.exit(0);
  }

  private static Object spec(long position) throws Exception {
    Class<?> builderClass = type("androidx.media3.datasource.DataSpec$Builder");
    Object builder = builderClass.getConstructor().newInstance();
    builderClass.getMethod("setUri", Uri.class).invoke(builder, Uri.parse("tingyu://smoke/audio"));
    builderClass.getMethod("setPosition", long.class).invoke(builder, position);
    return builderClass.getMethod("build").invoke(builder);
  }
}
