package com.badnng.moe.tdlib

import com.badnng.moe.status.FailureCategory

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.test.*

class NativeBundleTest {
    @Test
    fun pinnedJavaCommitCanBeReadBeforeLoadingJni() {
        val pin = java.util.Properties().apply {
            NativeBundleTest::class.java.getResourceAsStream("/tdlib-source.properties")!!.use(::load)
        }
        assertEquals(pin.getProperty("commit"), NativeBundle.bindingCommit())
    }

    @Test
    fun missingDependencyIdentifiesTheFileBeforeLoadingNativeCode() {
        val directory = fixture()
        Files.delete(directory.resolve("dependency.dll"))
        val failure = assertFailsWith<SetupFailure> { validate(directory) }
        assertEquals(FailureCategory.NATIVE, failure.category)
        assertTrue(failure.summary.contains("dependency.dll"))
    }

    @Test
    fun x86DllIsRejectedWithAnArchitectureExplanation() {
        val directory = fixture(machine = 0x14c)
        val failure = assertFailsWith<SetupFailure> { validate(directory) }
        assertTrue(failure.summary.contains("不是 Windows x64"))
    }

    @Test
    fun mismatchedJavaJarIsRejectedBeforeJniCanAbortTheProcess() {
        val directory = fixture()
        Files.writeString(directory.resolve("tdlib.jar"), "different binding")
        val failure = assertFailsWith<SetupFailure> { validate(directory) }
        assertTrue(failure.summary.contains("校验失败：tdlib.jar"))
    }

    @Test
    fun validBundlePreservesDependencyLoadOrder() {
        val directory = fixture()
        assertEquals(listOf("dependency.dll", "tdjni.dll"), validate(directory).map { it.fileName.toString() })
    }

    @Test
    fun nativeImageRejectsAnExternalBindingDifferentFromItsCompiledBinding() {
        val directory = fixture()
        val failure = assertFailsWith<SetupFailure> {
            NativeBundleValidator("fixture-commit", "0".repeat(64)).validate(directory, directory.resolve("tdlib.jar"))
        }
        assertTrue(failure.summary.contains("绑定校验信息不匹配"))
        val hash = java.util.Properties().apply {
            Files.newBufferedReader(directory.resolve("manifest.properties")).use(::load)
        }.getProperty("sha256.tdlib.jar")
        assertEquals(2, NativeBundleValidator("fixture-commit", hash).validate(directory, directory.resolve("tdlib.jar")).size)
    }

    @Test
    fun nativeImageLocatesASiblingBundleWithoutAJarCodeSourceOrWorkingDirectory() {
        val directory = fixture()
        assertEquals(directory, NativeBundlePaths.locate(null, null, null, directory.resolve("nngGram.exe")))
        assertEquals(directory, NativeBundlePaths.locate(null, directory.toString(), null, null))
        assertEquals(directory, NativeBundlePaths.locate(directory.toString(), null, null, null))
        assertFailsWith<SetupFailure> { NativeBundlePaths.locate("relative/native", null, null, null) }
        assertFailsWith<SetupFailure> { NativeBundlePaths.locate(null, null, null, null) }
    }

    @Test
    fun malformedManifestHasANativeCategoryWithoutEchoingItsContents() {
        val directory = fixture()
        Files.writeString(directory.resolve("manifest.properties"), "private-value=\\uZZZZ")
        val failure = assertFailsWith<SetupFailure> { validate(directory) }
        assertEquals(FailureCategory.NATIVE, failure.category)
        assertFalse(failure.summary.contains("private-value"))
        assertTrue(failure.recovery.isNotBlank())
    }

    private fun validate(path: Path) = NativeBundleValidator("fixture-commit").validate(path, path.resolve("tdlib.jar"))

    private fun fixture(machine: Int = 0x8664): Path {
        val directory = Files.createTempDirectory("native-fixture-")
        val pe = ByteBuffer.allocate(128).order(ByteOrder.LITTLE_ENDIAN).apply {
            put(0, 'M'.code.toByte()); put(1, 'Z'.code.toByte())
            putInt(0x3c, 64); putInt(64, 0x4550); putShort(68, machine.toShort())
        }.array()
        Files.writeString(directory.resolve("tdlib.jar"), "fixture binding")
        Files.write(directory.resolve("dependency.dll"), pe)
        Files.write(directory.resolve("tdjni.dll"), pe)
        val properties = StringBuilder("source.commit=fixture-commit\narchitecture=x64\nbinding.loader.patch=absolute-path-v1\nload.order=dependency.dll,tdjni.dll\n")
        for (file in listOf("tdlib.jar", "dependency.dll", "tdjni.dll")) {
            val hash = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(directory.resolve(file))).joinToString("") { "%02x".format(it) }
            properties.append("sha256.$file=$hash\n")
        }
        Files.writeString(directory.resolve("manifest.properties"), properties.toString())
        return directory
    }
}
