import java.io.OutputStream;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.jar.JarFile;

/** Checks the actual packaged classpath without starting the application or reading account data. */
public final class VerifyNativeUberJar {
    private static final String ARGON = "org/bouncycastle/crypto/generators/Argon2BytesGenerator.class";

    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Expected original BC JAR and application uber-JAR");
        byte[] original;
        try (var jar = new JarFile(args[0], true)) {
            var entry = jar.getJarEntry(ARGON);
            if (entry == null) throw new IllegalStateException("Original Argon2 class is missing");
            try (var input = jar.getInputStream(entry)) { original = input.readAllBytes(); }
            if (entry.getCodeSigners() == null) throw new IllegalStateException("Original BC class is not signed");
        }
        try (var jar = new JarFile(args[1], true)) {
            var entries = jar.entries();
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                if (entry.isDirectory()) continue;
                if (entry.getName().matches("(?i)META-INF/(?:[^/]+\\.(?:SF|RSA|DSA|EC)|SIG-[^/]+)")) {
                    throw new IllegalStateException("An original dependency signature remains in the repackaged JAR");
                }
                try (var input = jar.getInputStream(entry)) { input.transferTo(OutputStream.nullOutputStream()); }
            }
            try (var input = jar.getInputStream(jar.getJarEntry(ARGON))) {
                if (!Arrays.equals(original, input.readAllBytes())) throw new IllegalStateException("Argon2 class changed during packaging");
            }
            if (jar.getJarEntry("org/drinkless/tdlib/TdApi.class") == null ||
                jar.getJarEntry("META-INF/native-image/com.badnng.moe/tdlib/jni-config.json") == null) {
                throw new IllegalStateException("TDLib binding or Native Image JNI metadata is missing");
            }
            if (!"true".equalsIgnoreCase(jar.getManifest().getMainAttributes().getValue("Multi-Release"))) {
                throw new IllegalStateException("Multi-release classpath semantics were lost");
            }
        }
        // RFC 9106 section 5.3, the same published vector as LocalPasscodeCryptoTest.
        try (var loader = new URLClassLoader(new java.net.URL[]{Path.of(args[1]).toUri().toURL()}, ClassLoader.getPlatformClassLoader())) {
            var parameters = Class.forName("org.bouncycastle.crypto.params.Argon2Parameters", true, loader);
            var builderType = Class.forName("org.bouncycastle.crypto.params.Argon2Parameters$Builder", true, loader);
            var builder = builderType.getConstructor(int.class).newInstance(2);
            builderType.getMethod("withVersion", int.class).invoke(builder, 0x13);
            builderType.getMethod("withMemoryAsKB", int.class).invoke(builder, 32);
            builderType.getMethod("withIterations", int.class).invoke(builder, 3);
            builderType.getMethod("withParallelism", int.class).invoke(builder, 4);
            builderType.getMethod("withSalt", byte[].class).invoke(builder, repeated(16, 2));
            builderType.getMethod("withSecret", byte[].class).invoke(builder, repeated(8, 3));
            builderType.getMethod("withAdditional", byte[].class).invoke(builder, repeated(12, 4));
            var params = builderType.getMethod("build").invoke(builder);
            var generatorType = Class.forName("org.bouncycastle.crypto.generators.Argon2BytesGenerator", true, loader);
            var generator = generatorType.getConstructor().newInstance();
            generatorType.getMethod("init", parameters).invoke(generator, params);
            var output = new byte[32];
            generatorType.getMethod("generateBytes", byte[].class, byte[].class).invoke(generator, repeated(32, 1), output);
            if (!HexFormat.of().formatHex(output).equals("0d640df58d78766c08c037a34a8b53c9d01ef0452d75b65eb52520e96b01e659")) {
                throw new IllegalStateException("Packaged Argon2 RFC vector failed");
            }
            parameters.getMethod("clear").invoke(params);
            builderType.getMethod("clear").invoke(builder);
        }
        System.out.println("Verified original signed BC class, repackaged JAR, TDLib JNI metadata and packaged Argon2 RFC vector.");
    }

    private static byte[] repeated(int count, int value) {
        var result = new byte[count];
        Arrays.fill(result, (byte) value);
        return result;
    }
}
