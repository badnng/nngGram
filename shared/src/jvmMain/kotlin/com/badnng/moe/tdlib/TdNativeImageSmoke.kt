package com.badnng.moe.tdlib

import com.badnng.moe.security.LocalOpenWrap
import com.badnng.moe.settings.LocalLockIssue
import com.badnng.moe.settings.LocalLockPhase
import com.badnng.moe.status.AuthorizationPhase
import com.badnng.moe.status.ConnectionPhase
import com.badnng.moe.status.EnginePhase
import com.badnng.moe.status.FailureCategory
import com.badnng.moe.status.ProxyStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.io.IOException
import java.security.MessageDigest

/** CLI acceptance path, invoked before UI and ConfigurationStore.default(). Never opens an account. */
object TdNativeImageSmoke {
    fun handle(arguments: Array<String>): Boolean {
        if (arguments.firstOrNull() != "--tdlib-native-smoke") return false
        require(arguments.size in 1..2) { "TDLIB_NATIVE_SMOKE_INVALID_ARGUMENTS" }
        val report = arguments.getOrNull(1)?.let(Path::of)
        require(report == null || (report.isAbsolute && !Files.exists(report) && Files.isDirectory(report.parent))) {
            "TDLIB_NATIVE_SMOKE_INVALID_REPORT"
        }
        val progress = NativeSmokeProgress()
        try {
            val summary = runBlocking { verifyOffline(progress) }
            progress.stage = NativeSmokeStage.WRITE_REPORT
            report?.let { Files.writeString(it, summary, StandardOpenOption.CREATE_NEW) }
            println(summary)
        } catch (error: Throwable) {
            // Even a native setup failure must not echo paths, response text or configuration values.
            val summary = progress.failure(error)
            report?.let { runCatching { Files.writeString(it, summary, StandardOpenOption.CREATE_NEW) } }
            throw IllegalStateException(summary)
        }
        return true
    }

    private suspend fun verifyOffline(progress: NativeSmokeProgress): String {
        val native = NativeBundle.loadForSmoke(
            onStage = { progress.bundleStage = it },
            onLinkageFailure = { progress.nativeFailure = nativeSmokeFailureKind(it) },
        )
        progress.stage = NativeSmokeStage.VERIFY_BINDING
        check(native.commit == NativeBundle.bindingCommit())
        verifySynchronousJni(progress)
        progress.stage = NativeSmokeStage.CREATE_SYNTHETIC_DIRECTORY
        val root = Files.createTempDirectory("nngGram-NativeImage-离线-")
        val config = root.resolve("synthetic.properties")
        val data = root.resolve("中文 数据")
        Files.writeString(config, "api_id=1\napi_hash=${"0".repeat(32)}\nuse_test_dc=true\n")
        progress.stage = NativeSmokeStage.CREATE_ENGINE
        val engine = TdEngine(ConfigurationStore(config, data), OfficialTdTransport(offline = true))
        var openRecordDigest: ByteArray? = null
        try {
            repeat(2) { round ->
                progress.round = round + 1
                progress.stage = NativeSmokeStage.START_ENGINE
                check(engine.start())
                if (round == 0) {
                    progress.stage = NativeSmokeStage.WAIT_LOCAL_SETUP
                    val setup = withTimeout(40_000) {
                        engine.state.first { it.localLock.phase == LocalLockPhase.NEW_ROOT_SETUP ||
                            it.localLock.phase == LocalLockPhase.RECOVERY || it.status.engine == EnginePhase.FAILED }
                    }
                    val token = setup.localLock
                    check(token.phase == LocalLockPhase.NEW_ROOT_SETUP && !token.busy &&
                        token.issue == LocalLockIssue.NONE && token.canCreate && !setup.clientActive)
                    val current = engine.localLockState.value
                    check(current.phase == token.phase && current.epoch == token.epoch &&
                        current.operationRevision == token.operationRevision && !current.busy)
                    progress.stage = NativeSmokeStage.SKIP_LOCAL_SETUP
                    // Only this smoke's own fresh root: real OPEN commit/readback precedes native startup.
                    engine.localLockActions.skipSetup()
                }
                progress.stage = NativeSmokeStage.WAIT_INITIALIZED
                val initialized = withTimeout(40_000) {
                    engine.state.first {
                        (it.status.engine == EnginePhase.INITIALIZED &&
                            it.status.authorization == AuthorizationPhase.WAITING_LOGIN && it.proxy == ProxyStatus.DIRECT) ||
                            it.status.engine == EnginePhase.FAILED
                    }
                }
                progress.engine = initialized.status.engine
                progress.authorization = initialized.status.authorization
                progress.connection = initialized.status.connection
                progress.engineFailure = initialized.status.failure?.category
                check(initialized.status.engine == EnginePhase.INITIALIZED && initialized.clientActive)
                val local = withTimeout(40_000) {
                    engine.localLockState.first { it.phase == LocalLockPhase.UNPROTECTED || it.phase == LocalLockPhase.RECOVERY }
                }
                check(local.phase == LocalLockPhase.UNPROTECTED && !local.enabled && local.issue == LocalLockIssue.NONE)
                progress.stage = NativeSmokeStage.WAIT_OFFLINE
                withTimeout(10_000) {
                    engine.state.first { it.status.connection == ConnectionPhase.WAITING_NETWORK }
                }
                progress.stage = NativeSmokeStage.VERIFY_DATABASE
                check(Files.list(data.resolve("database")).use { it.findAny().isPresent })
                progress.stage = NativeSmokeStage.VERIFY_LOCK
                val locked = runCatching { DataDirectoryLease.acquire(data).use { } }.exceptionOrNull()
                check(locked is SetupFailure)
                progress.stage = NativeSmokeStage.STOP_ENGINE
                check(engine.stop(15_000))
                progress.stage = NativeSmokeStage.VERIFY_CLOSED
                check(engine.state.value.status.engine == EnginePhase.CLOSED && !engine.state.value.clientActive)
                progress.stage = NativeSmokeStage.REACQUIRE_LOCK
                DataDirectoryLease.acquire(data).use {
                    progress.stage = NativeSmokeStage.VERIFY_OPEN_RECORD
                    val record = Files.newInputStream(data.resolve("local-protection.wrap")).use { input ->
                        input.readNBytes(LocalOpenWrap.RECORD_BYTES + 1)
                    }
                    try {
                        check(record.size == LocalOpenWrap.RECORD_BYTES)
                        val digest = MessageDigest.getInstance("SHA-256").digest(record)
                        if (openRecordDigest == null) openRecordDigest = digest else try {
                            check(MessageDigest.isEqual(openRecordDigest, digest))
                        } finally { digest.fill(0) }
                    } finally { record.fill(0) }
                }
            }
        } finally {
            openRecordDigest?.fill(0)
            // Preserve the lease if native Closed was not confirmed; never force an early release.
            withContext(NonCancellable) {
                while (!engine.close(15_000)) {
                    System.err.println("TDLIB_NATIVE_SMOKE_WAITING_FOR_CLOSED")
                }
            }
        }
        progress.stage = NativeSmokeStage.VERIFY_TERMINATED
        check(engine.state.value.terminated && !engine.start())
        return "TDLIB_NATIVE_SMOKE_OK; runtime=${if (isNativeImageRuntime()) "native-image" else "jvm"}; " +
            "commit=${native.commit}; rounds=2; offline=true; unicode=true; jniRoundTrip=true"
    }

    private fun verifySynchronousJni(progress: NativeSmokeProgress) {
        progress.stage = NativeSmokeStage.PARSE_ENTITIES
        val parsed = Client.execute(TdApi.ParseTextEntities("<b>合成 synthetic</b>", TdApi.TextParseModeHTML()))
        progress.stage = NativeSmokeStage.VERIFY_ENTITIES
        check(parsed is TdApi.FormattedText && parsed.text == "合成 synthetic")
        check(parsed.entities.single().type is TdApi.TextEntityTypeBold)
        // Covers C++ AllocObject, fields, virtual getConstructor, native toString and nested object arrays.
        progress.stage = NativeSmokeStage.NATIVE_TO_STRING
        check(parsed.toString().contains("synthetic"))
        progress.stage = NativeSmokeStage.PARSE_JSON
        val json = Client.execute(TdApi.GetJsonValue("{\"synthetic\":[true,42,1.5,\"中文\",null]}"))
        progress.stage = NativeSmokeStage.VERIFY_JSON
        check(json is TdApi.JsonValueObject && json.members.size == 1)
        val array = json.members.single().value as TdApi.JsonValueArray
        check(array.values.size == 5)
        progress.stage = NativeSmokeStage.ENCODE_JSON
        val encoded = Client.execute(TdApi.GetJsonString(json))
        progress.stage = NativeSmokeStage.VERIFY_ENCODED_JSON
        check(encoded is TdApi.Text && encoded.text.contains("synthetic"))
    }
}

internal enum class NativeSmokeStage {
    LOAD_BUNDLE, VERIFY_BINDING, PARSE_ENTITIES, VERIFY_ENTITIES, NATIVE_TO_STRING,
    PARSE_JSON, VERIFY_JSON, ENCODE_JSON, VERIFY_ENCODED_JSON, CREATE_SYNTHETIC_DIRECTORY,
    CREATE_ENGINE, START_ENGINE, WAIT_LOCAL_SETUP, SKIP_LOCAL_SETUP, WAIT_INITIALIZED, WAIT_OFFLINE, VERIFY_DATABASE, VERIFY_LOCK,
    STOP_ENGINE, VERIFY_CLOSED, REACQUIRE_LOCK, VERIFY_OPEN_RECORD, VERIFY_TERMINATED, WRITE_REPORT,
}

internal class NativeSmokeProgress {
    var stage = NativeSmokeStage.LOAD_BUNDLE
    var bundleStage: NativeBundleStage? = null
    var round = 0
    var nativeFailure: String? = null
    var engine: EnginePhase? = null
    var authorization: AuthorizationPhase? = null
    var connection: ConnectionPhase? = null
    var engineFailure: FailureCategory? = null

    fun failure(error: Throwable): String =
        "TDLIB_NATIVE_SMOKE_FAILED; stage=${stage.name}; bundleStage=${bundleStage?.name ?: "NONE"}; " +
            "category=${nativeFailure ?: nativeSmokeFailureKind(error)}; round=$round; " +
            "engine=${engine?.name ?: "NONE"}; authorization=${authorization?.name ?: "NONE"}; " +
            "connection=${connection?.name ?: "NONE"}; engineFailure=${engineFailure?.name ?: "NONE"}"
}

/** Whitelisted classifications only: no native messages, paths, cause strings or class names are emitted. */
internal fun nativeSmokeFailureKind(error: Throwable): String = when (error.javaClass.simpleName) {
    "MissingJNIRegistrationError" -> "MISSING_JNI_REGISTRATION"
    "MissingReflectionRegistrationError" -> "MISSING_REFLECTION_REGISTRATION"
    "MissingResourceRegistrationError" -> "MISSING_RESOURCE_REGISTRATION"
    "MissingSerializationRegistrationError" -> "MISSING_SERIALIZATION_REGISTRATION"
    else -> when (error) {
        is SetupFailure -> "SETUP_${error.category.name}"
        is UnsatisfiedLinkError -> "NATIVE_LINK"
        is ExceptionInInitializerError -> "CLASS_INITIALIZATION"
        is NoClassDefFoundError -> "CLASS_DEFINITION"
        is LinkageError -> "LINKAGE"
        is IOException -> "IO"
        is SecurityException -> "ACCESS"
        is kotlinx.coroutines.TimeoutCancellationException -> "TIMEOUT"
        is ClassCastException -> "RESPONSE_TYPE"
        is IllegalArgumentException -> if (error.stackTrace.any {
            it.className == "com.oracle.svm.core.graal.snippets.SubstrateAllocationSnippets" &&
                it.methodName == "slowPathHubOrUnsafeInstantiationError"
        }) "JNI_ALLOCATION" else "ARGUMENT"
        is IllegalStateException -> "ASSERTION"
        else -> "INTERNAL"
    }
}
