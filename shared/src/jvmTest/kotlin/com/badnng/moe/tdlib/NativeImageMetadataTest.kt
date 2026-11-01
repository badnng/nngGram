package com.badnng.moe.tdlib

import org.drinkless.tdlib.TdApi
import java.lang.reflect.Modifier
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Properties
import java.util.zip.ZipFile
import kotlin.test.*

class NativeImageMetadataTest {
    @Test
    fun embeddedBindingProvenanceMatchesTheActualPinnedJar() {
        val metadata = NativeBundle.embeddedBindingMetadata()
        val pin = Properties().apply {
            NativeImageMetadataTest::class.java.getResourceAsStream("/tdlib-source.properties")!!.use(::load)
        }
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(bindingJar()).use { input ->
            val bytes = ByteArray(65_536)
            while (true) {
                val count = input.read(bytes)
                if (count < 0) break
                digest.update(bytes, 0, count)
            }
        }
        assertEquals(pin.getProperty("commit"), metadata.getProperty("source.commit"))
        assertEquals(NativeBundle.bindingCommit(), metadata.getProperty("java.api.commit"))
        assertEquals("absolute-path-v1", metadata.getProperty("loader.patch"))
        assertEquals(digest.digest().joinToString("") { "%02x".format(it) }, metadata.getProperty("binding.sha256"))
    }

    @Test
    fun jniMetadataCoversEveryPinnedApiTypeAndNativeAccessibleFieldWithoutLoadingJni() {
        val content = javaClass.getResourceAsStream("/META-INF/native-image/com.badnng.moe/tdlib/jni-config.json")!!
            .bufferedReader().use { it.readText() }
        val reflections = javaClass.getResourceAsStream("/META-INF/native-image/com.badnng.moe/tdlib/reflect-config.json")!!
            .bufferedReader().use { it.readText() }
        assertEquals("[]", reflections.trim(), "JNI allocations must not register all TDLib nests for reflection")
        val className = Regex("^\\s*\\{\"name\":\"([^\"]+)\"")
        val entries = content.lineSequence().mapNotNull { line ->
            className.find(line)?.groupValues?.get(1)?.let { it to line }
        }.toMap()
        val names = ZipFile(bindingJar().toFile()).use { zip ->
            zip.entries().asSequence().map { it.name }
                .filter { it.startsWith("org/drinkless/tdlib/TdApi") && it.endsWith(".class") }
                .map { it.removeSuffix(".class").replace('/', '.') }.toList()
        }
        assertTrue(names.size > 3_000, "The complete pinned API must be retained, including unseen updates")
        names.forEach { name ->
            val entry = assertNotNull(entries[name], "Missing native class metadata: $name")
            val type = Class.forName(name, false, TdApi::class.java.classLoader)
            if (TdApi.Object::class.java.isAssignableFrom(type) && !type.isInterface && !Modifier.isAbstract(type.modifiers)) {
                assertNotNull(type.getDeclaredConstructor())
                assertTrue(entry.contains("{\"name\":\"<init>\",\"parameterTypes\":[]}"),
                    "GraalVM 25.0.2 JNI AllocObject requires constructor registration for every concrete API type: $name")
            }
            type.declaredFields.filter { !Modifier.isStatic(it.modifiers) || it.name == "GIT_COMMIT_HASH" }.forEach {
                assertTrue(entry.contains("\"name\":\"${it.name}\""), "Missing native field: $name.${it.name}")
            }
        }
        val client = assertNotNull(entries["org.drinkless.tdlib.Client"])
        listOf("createNativeClient", "nativeClientSend", "nativeClientReceive", "nativeClientExecute", "nativeClientSetLogMessageHandler").forEach {
            assertTrue(client.contains("\"name\":\"$it\""))
        }
        listOf("KeyboardButton", "InlineKeyboardButton", "PageBlockTableCell").forEach {
            assertNotNull(entries["org.drinkless.tdlib.TdApi\$$it[]"])
        }
    }

    @Test
    fun unrelatedCommandLineDoesNotRunTheOfflineSmokeOrInitializeTdlib() {
        assertFalse(TdNativeImageSmoke.handle(emptyArray()))
        assertFalse(TdNativeImageSmoke.handle(arrayOf("--preview")))
        assertFailsWith<IllegalArgumentException> { TdNativeImageSmoke.handle(arrayOf("--tdlib-native-smoke", "relative.log")) }
    }

    @Test
    fun syntheticSmokeFailureContainsFixedStageAndCategoryWithoutExceptionText() {
        val progress = NativeSmokeProgress().apply {
            bundleStage = NativeBundleStage.INITIALIZE_JNI
            nativeFailure = nativeSmokeFailureKind(UnsatisfiedLinkError("private path and native error"))
        }
        val report = progress.failure(SetupFailure("private configuration", "private recovery"))
        assertTrue(report.contains("stage=LOAD_BUNDLE"))
        assertTrue(report.contains("bundleStage=INITIALIZE_JNI"))
        assertTrue(report.contains("category=NATIVE_LINK"))
        assertFalse(report.contains("private"))
        assertFalse(report.contains("UnsatisfiedLinkError"))
    }

    @Test
    fun syntheticEngineFailureExposesOnlyTypedStateAndFailureCategory() {
        val progress = NativeSmokeProgress().apply {
            stage = NativeSmokeStage.WAIT_INITIALIZED
            round = 1
            engine = com.badnng.moe.status.EnginePhase.FAILED
            engineFailure = com.badnng.moe.status.FailureCategory.NATIVE
        }
        val report = progress.failure(IllegalStateException("raw TDLib message"))
        assertTrue(report.contains("category=ASSERTION"))
        assertTrue(report.contains("engine=FAILED"))
        assertTrue(report.contains("engineFailure=NATIVE"))
        assertFalse(report.contains("raw TDLib"))
    }

    @Test
    fun nativeAllocationFailureExposesOnlyAnAllowlistedCategory() {
        val failure = IllegalArgumentException("private native detail").apply {
            stackTrace = arrayOf(StackTraceElement(
                "com.oracle.svm.core.graal.snippets.SubstrateAllocationSnippets",
                "slowPathHubOrUnsafeInstantiationError", "private-path.java", 123,
            ))
        }
        assertEquals("JNI_ALLOCATION", nativeSmokeFailureKind(failure))
        assertEquals("ARGUMENT", nativeSmokeFailureKind(IllegalArgumentException("private detail")))
        assertFalse(NativeSmokeProgress().failure(failure).contains("private"))
    }

    private fun bindingJar() = Path.of(TdApi::class.java.protectionDomain.codeSource.location.toURI())
}
