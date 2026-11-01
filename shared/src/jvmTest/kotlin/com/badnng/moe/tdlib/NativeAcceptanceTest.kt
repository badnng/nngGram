package com.badnng.moe.tdlib

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.jar.Attributes
import java.util.jar.JarOutputStream
import java.util.jar.Manifest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Explicit opt-in: all children use isolated synthetic configuration and NetworkTypeNone. */
class NativeAcceptanceTest {
    @Test
    fun tenFreshJvmsReuseTheDatabaseAndExitWithoutRetainingItsLock() {
        val root = fixture("十次进程启动")
        repeat(10) { round ->
            runChild("initialize", root, "round-${round + 1}")
            DataDirectoryLease.acquire(root.resolve("用户 数据")).close()
        }
    }

    @Test
    fun oneEngineRestartsTenTimesAndIgnoresRepeatedStartRequests() {
        runChild("restart", fixture("十次引擎重启"))
    }

    @Test
    fun missingConfigurationLoadsNativeDiagnosticsWithoutCreatingAClient() {
        runChild("missing-configuration", fixture("未填写配置"))
    }

    @Test
    fun missingDllFailsBeforeCreatingAClient() {
        runChild("missing-dll", fixture("缺少原生依赖"))
    }

    @Test
    fun databaseDirectoryOccupiedByAFileFailsWithoutLeakingTheLease() {
        runChild("occupied-directory", fixture("数据库路径被文件占用"))
    }

    @Test
    fun readOnlyLockFileReportsAnActualAccessDeniedWithoutChangingAcls() {
        runChild("permission-denied", fixture("关键文件拒绝写入"))
    }

    private fun fixture(label: String): Path = Files.createTempDirectory("native-acceptance-$label ")

    private fun runChild(scenario: String, root: Path, label: String = scenario) {
        val classpath = requireNotNull(System.getProperty("nnggram.acceptance.classpath")) {
            "Run with -PnativeAcceptance so Gradle supplies the child runtime classpath."
        }
        val nativeDirectory = requireNotNull(System.getProperty("nnggram.acceptance.native"))
        val temporaryDirectory = Files.createDirectories(root.resolve("tmp"))
        val output = root.resolve("$label.stdout.log")
        val errors = root.resolve("$label.stderr.log")
        // @argfile decoding depends on the Windows launcher code page. Keep
        // Unicode arguments on ProcessBuilder's wide-character command line,
        // and shorten the classpath with ASCII URI entries in a manifest JAR.
        val classpathJar = root.resolve("$label.classpath.jar")
        val manifest = Manifest().apply {
            mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
            mainAttributes[Attributes.Name.CLASS_PATH] = classpath.split(File.pathSeparatorChar)
                .filter(String::isNotBlank)
                .joinToString(" ") { Path.of(it).toAbsolutePath().toUri().toASCIIString() }
        }
        JarOutputStream(Files.newOutputStream(classpathJar), manifest).use { }
        val arguments = listOf(
            Path.of(System.getProperty("java.home"), "bin", "java.exe").toString(),
            "-Xms32m", "-Xmx256m",
            "-Djava.io.tmpdir=$temporaryDirectory",
            "-Dnnggram.tdlib.dir=$nativeDirectory",
            "-cp", classpathJar.toString(),
            NativeAcceptanceProcess::class.java.name,
            scenario, root.toString(), nativeDirectory,
        )
        val processBuilder = ProcessBuilder(arguments)
            .directory(root.toFile()).redirectOutput(output.toFile()).redirectError(errors.toFile())
        // Do not inherit an actual account configuration, native override or injected JVM options.
        listOf("NNGGRAM_CONFIG", "NNGGRAM_TDLIB_DIR", "JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS")
            .forEach { processBuilder.environment().remove(it) }
        val process = processBuilder.start()
        process.outputStream.close()
        val exited = try {
            process.waitFor(if (scenario == "restart") 120 else 75, TimeUnit.SECONDS)
        } finally {
            if (process.isAlive) {
                process.destroyForcibly()
                process.waitFor(5, TimeUnit.SECONDS)
            }
        }
        val stderr = Files.readString(errors)
        val stdout = Files.readString(output)
        assertTrue(exited, "$label: child JVM did not exit after bounded TDLib shutdown; ${stderr.takeLast(4000)}")
        assertEquals(0, process.exitValue(), "$label: child failed; ${stderr.takeLast(4000)}")
        assertEquals("", stderr, "$label: native execution emitted unexpected private/raw diagnostics")
        assertTrue(stdout.contains("ACCEPTANCE_OK $scenario"), "$label: child did not confirm its assertions")
        println("$label: ${stdout.trim()}")
    }
}
