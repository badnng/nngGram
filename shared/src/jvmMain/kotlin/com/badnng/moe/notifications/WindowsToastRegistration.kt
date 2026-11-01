package com.badnng.moe.notifications

import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Base64
import java.util.Locale
import java.util.UUID

internal const val WINDOWS_TOAST_ACTIVATION_ARGUMENT = "--toast-activate"

/** Installation identity, never an account, message, command-line argument or notification title. */
internal data class WindowsToastRegistrationIdentity(
    val aumid: String,
    val clsid: String,
    val executable: Path,
    val shortcut: Path,
)

internal enum class WindowsToastRegistrationStatus { UNAVAILABLE, ABSENT, READY, CONFLICT, RECOVERY_REQUIRED }

internal enum class WindowsToastRegistrationError {
    NATIVE_EXECUTABLE_REQUIRED, ACTIVATION_ENTRY_POINT_UNAVAILABLE, INVALID_EXECUTABLE,
    FOREIGN_REGISTRATION, INVALID_MANIFEST, IO_FAILURE, REGISTRATION_FAILED, ROLLBACK_INCOMPLETE, RETIRED,
}

internal data class WindowsToastRegistrationState(
    val status: WindowsToastRegistrationStatus,
    val identity: WindowsToastRegistrationIdentity? = null,
    val error: WindowsToastRegistrationError? = null,
    val pendingSelectionToken: String? = null,
)

internal data class WindowsToastRegistrationResult(
    val state: WindowsToastRegistrationState,
    val error: WindowsToastRegistrationError? = state.error,
) {
    val identity: WindowsToastRegistrationIdentity? get() = state.identity
    val succeeded: Boolean get() = error == null
}

internal data class WindowsToastRegistrationEnvironment(
    val nativeImageRuntime: Boolean,
    val activationEntryPointAvailable: Boolean,
    val currentExecutable: Path?,
    val executableIsRegularFile: Boolean,
    val programsDirectory: Path?,
)

internal data class WindowsToastShortcutSpec(
    val executable: String,
    val arguments: String,
    val workingDirectory: String,
    val description: String,
    val aumid: String,
    val clsid: String,
)

internal data class WindowsToastShortcutSnapshot(val spec: WindowsToastShortcutSpec, val sha256: String)

/** Preserve all native value types/bytes, so an unknown value is a conflict rather than ignored. */
internal data class WindowsToastRegistryValue(val type: Int, val base64: String) {
    companion object {
        fun string(value: String) = WindowsToastRegistryValue(
            1, Base64.getEncoder().encodeToString((value + '\u0000').toByteArray(StandardCharsets.UTF_16LE)),
        )
    }
}

internal data class WindowsToastRegistrySnapshot(
    /** Relative subkey path -> complete value name/value map. Empty path is the CLSID root. */
    val keys: Map<String, Map<String, WindowsToastRegistryValue>>,
)

internal enum class WindowsToastManifestStage { PREPARED, SELECTION_PENDING, COMMITTED, REMOVING }

internal data class WindowsToastRegistrationManifest(
    val identity: WindowsToastRegistrationIdentity,
    val nonce: String,
    val stage: WindowsToastManifestStage,
    /** Bounded exact link bytes let recovery compare ownership without trusting a matching title. */
    val shortcutBase64: String,
    val shortcutSha256: String,
)

/**
 * No method is called on construction. readEnvironment/readManifest/read* must not create anything.
 * Mutations execute under one cross-process lock. Creates are exclusive, never replace existing data;
 * on a partial create failure the boundary removes only its unchanged newly-created subset.
 * Deletes re-read and compare the complete object, and return false if anything changed.
 */
internal interface WindowsToastRegistrationBoundary {
    fun readEnvironment(): WindowsToastRegistrationEnvironment
    fun <T> exclusive(block: () -> T): T
    fun readManifest(identity: WindowsToastRegistrationIdentity): WindowsToastRegistrationManifest?
    fun writeManifest(expected: WindowsToastRegistrationManifest?, next: WindowsToastRegistrationManifest?)
    fun readRegistry(identity: WindowsToastRegistrationIdentity): WindowsToastRegistrySnapshot?
    fun createRegistry(identity: WindowsToastRegistrationIdentity, expected: WindowsToastRegistrySnapshot): Boolean
    fun deleteRegistry(identity: WindowsToastRegistrationIdentity, expected: WindowsToastRegistrySnapshot): Boolean
    fun readShortcut(path: Path): WindowsToastShortcutSnapshot?
    fun renderShortcut(spec: WindowsToastShortcutSpec): ByteArray
    fun createShortcut(path: Path, spec: WindowsToastShortcutSpec, bytes: ByteArray): Boolean
    fun deleteShortcut(path: Path, expected: WindowsToastShortcutSnapshot): Boolean
}

/**
 * Persistent registration only. Runtime COM class factories/toast history are owned by the backend.
 * Calling enable is an explicit user action; startup calls readState only. Disabling a backend is
 * separate from uninstallOwned, which requires the dedicated remove-registration user action.
 */
internal class WindowsToastRegistration(private val boundary: WindowsToastRegistrationBoundary) {
    @Synchronized
    fun readState(): WindowsToastRegistrationState {
        return try {
            val identity = availableIdentity() ?: return unavailableState()
            inspect(identity, boundary.readManifest(identity))
        } catch (_: InvalidToastManifest) {
            WindowsToastRegistrationState(WindowsToastRegistrationStatus.CONFLICT, error = WindowsToastRegistrationError.INVALID_MANIFEST)
        } catch (_: Exception) {
            WindowsToastRegistrationState(WindowsToastRegistrationStatus.UNAVAILABLE, error = WindowsToastRegistrationError.IO_FAILURE)
        }
    }

    @Synchronized
    fun enable(awaitSelectionCommit: Boolean = false, stillCurrent: () -> Boolean = { true }): WindowsToastRegistrationResult {
        val identity = try { availableIdentity() } catch (_: Exception) { null }
            ?: return WindowsToastRegistrationResult(unavailableState())
        return try {
            checkpoint(stillCurrent)
            boundary.exclusive { checkpoint(stillCurrent); installLocked(identity, awaitSelectionCommit, stillCurrent) }
        } catch (_: RetiredToastRegistration) {
            WindowsToastRegistrationResult(readState(), WindowsToastRegistrationError.RETIRED)
        } catch (_: InvalidToastManifest) {
            failed(identity, WindowsToastRegistrationError.INVALID_MANIFEST, WindowsToastRegistrationStatus.CONFLICT)
        } catch (_: Exception) {
            failed(identity, WindowsToastRegistrationError.IO_FAILURE, WindowsToastRegistrationStatus.RECOVERY_REQUIRED)
        }
    }

    /** Complete only the exact provisional registration after the preference save was accepted. */
    @Synchronized
    fun commitPendingSelection(token: String, stillCurrent: () -> Boolean = { true }): WindowsToastRegistrationResult {
        val identity = try { availableIdentity() } catch (_: Exception) { null }
            ?: return WindowsToastRegistrationResult(unavailableState())
        return try {
            boundary.exclusive {
                checkpoint(stillCurrent)
                val manifest = boundary.readManifest(identity) ?: throw InvalidToastManifest()
                validateManifest(identity, manifest)
                check(manifest.nonce == token)
                check(boundary.readRegistry(identity) == registrySpec(manifest) && boundary.readShortcut(identity.shortcut) == shortcutSnapshot(manifest))
                if (manifest.stage == WindowsToastManifestStage.COMMITTED)
                    return@exclusive WindowsToastRegistrationResult(WindowsToastRegistrationState(WindowsToastRegistrationStatus.READY, identity))
                check(manifest.stage == WindowsToastManifestStage.SELECTION_PENDING)
                checkpoint(stillCurrent)
                boundary.writeManifest(manifest, manifest.copy(stage = WindowsToastManifestStage.COMMITTED))
                WindowsToastRegistrationResult(WindowsToastRegistrationState(WindowsToastRegistrationStatus.READY, identity))
            }
        } catch (_: RetiredToastRegistration) {
            WindowsToastRegistrationResult(readState(), WindowsToastRegistrationError.RETIRED)
        } catch (_: Exception) {
            // Atomic move may have committed before an I/O failure reached the caller. Recover that
            // exact owned outcome by readback rather than rolling the saved preference back falsely.
            val completed = runCatching {
                val actual = boundary.readManifest(identity)
                actual != null && actual.nonce == token && actual.stage == WindowsToastManifestStage.COMMITTED &&
                    inspect(identity, actual).status == WindowsToastRegistrationStatus.READY
            }.getOrDefault(false)
            if (completed) WindowsToastRegistrationResult(WindowsToastRegistrationState(WindowsToastRegistrationStatus.READY, identity))
            else WindowsToastRegistrationResult(readState(), WindowsToastRegistrationError.REGISTRATION_FAILED)
        }
    }

    /** Cleanup authority is the durable pending nonce, never the account or latest operation. */
    @Synchronized
    fun cancelPendingSelection(token: String): WindowsToastRegistrationResult {
        val identity = try { availableIdentity() } catch (_: Exception) { null }
            ?: return WindowsToastRegistrationResult(unavailableState())
        return try {
            boundary.exclusive {
                val manifest = boundary.readManifest(identity)
                if (manifest == null) return@exclusive WindowsToastRegistrationResult(inspect(identity, null))
                validateManifest(identity, manifest)
                if (manifest.nonce != token || manifest.stage != WindowsToastManifestStage.SELECTION_PENDING)
                    return@exclusive WindowsToastRegistrationResult(inspect(identity, manifest))
                if (!removeMatching(manifest)) return@exclusive failed(identity, WindowsToastRegistrationError.ROLLBACK_INCOMPLETE, WindowsToastRegistrationStatus.RECOVERY_REQUIRED)
                boundary.writeManifest(manifest, null)
                WindowsToastRegistrationResult(WindowsToastRegistrationState(WindowsToastRegistrationStatus.ABSENT, identity))
            }
        } catch (_: Exception) {
            WindowsToastRegistrationResult(readState(), WindowsToastRegistrationError.ROLLBACK_INCOMPLETE)
        }
    }

    @Synchronized
    fun uninstallOwned(stillCurrent: () -> Boolean = { true }): WindowsToastRegistrationResult {
        val identity = try { availableIdentity() } catch (_: Exception) { null }
            ?: return WindowsToastRegistrationResult(unavailableState())
        return try {
            checkpoint(stillCurrent)
            boundary.exclusive {
                checkpoint(stillCurrent)
                val manifest = boundary.readManifest(identity)
                if (manifest == null) {
                    val state = inspect(identity, null)
                    return@exclusive WindowsToastRegistrationResult(state, state.error)
                }
                validateManifest(identity, manifest)
                // Persist the user's removal intent before deleting either independently owned item.
                val removing = manifest.copy(stage = WindowsToastManifestStage.REMOVING)
                checkpoint(stillCurrent)
                boundary.writeManifest(manifest, removing)
                if (removeMatching(removing, stillCurrent)) {
                    checkpoint(stillCurrent)
                    boundary.writeManifest(removing, null)
                    WindowsToastRegistrationResult(WindowsToastRegistrationState(WindowsToastRegistrationStatus.ABSENT, identity))
                } else {
                    failed(identity, WindowsToastRegistrationError.FOREIGN_REGISTRATION, WindowsToastRegistrationStatus.CONFLICT)
                }
            }
        } catch (_: RetiredToastRegistration) {
            WindowsToastRegistrationResult(readState(), WindowsToastRegistrationError.RETIRED)
        } catch (_: InvalidToastManifest) {
            failed(identity, WindowsToastRegistrationError.INVALID_MANIFEST, WindowsToastRegistrationStatus.CONFLICT)
        } catch (_: Exception) {
            failed(identity, WindowsToastRegistrationError.ROLLBACK_INCOMPLETE, WindowsToastRegistrationStatus.RECOVERY_REQUIRED)
        }
    }

    private fun installLocked(identity: WindowsToastRegistrationIdentity, awaitSelectionCommit: Boolean, stillCurrent: () -> Boolean): WindowsToastRegistrationResult {
        var manifest = boundary.readManifest(identity)
        val initial = inspect(identity, manifest)
        checkpoint(stillCurrent)
        if (initial.status == WindowsToastRegistrationStatus.READY) return WindowsToastRegistrationResult(initial)
        if (initial.status == WindowsToastRegistrationStatus.CONFLICT) return WindowsToastRegistrationResult(initial)
        if (manifest?.stage == WindowsToastManifestStage.COMMITTED) {
            // An externally damaged committed installation needs explicit removal/re-registration.
            // Do not destroy an earlier successful install while rolling back a repair attempt.
            return failed(identity, WindowsToastRegistrationError.REGISTRATION_FAILED, WindowsToastRegistrationStatus.RECOVERY_REQUIRED)
        }
        if (manifest?.stage == WindowsToastManifestStage.REMOVING) {
            // A failed uninstall is not implicitly undone by enabling. Finish that owned removal first.
            if (!removeMatching(manifest, stillCurrent)) return failed(identity, WindowsToastRegistrationError.FOREIGN_REGISTRATION, WindowsToastRegistrationStatus.CONFLICT)
            checkpoint(stillCurrent)
            boundary.writeManifest(manifest, null)
            manifest = null
        }
        if (manifest == null) {
            val nonce = UUID.randomUUID().toString()
            val spec = shortcutSpec(identity, nonce)
            checkpoint(stillCurrent)
            val bytes = boundary.renderShortcut(spec)
            require(bytes.size in 1..MAX_SHORTCUT_BYTES)
            manifest = WindowsToastRegistrationManifest(
                identity, nonce, WindowsToastManifestStage.PREPARED,
                Base64.getEncoder().encodeToString(bytes), sha256(bytes),
            )
            // No registry/Programs mutation occurs before this durable ownership journal exists.
            checkpoint(stillCurrent)
            boundary.writeManifest(null, manifest)
        }
        validateManifest(identity, manifest)
        val prepared = manifest
        try {
            checkpoint(stillCurrent)
            val registry = registrySpec(prepared)
            val currentRegistry = boundary.readRegistry(identity)
            checkpoint(stillCurrent)
            check(currentRegistry == registry || currentRegistry == null && boundary.createRegistry(identity, registry))
            checkpoint(stillCurrent)
            check(boundary.readRegistry(identity) == registry)
            val shortcut = shortcutSnapshot(prepared)
            val currentShortcut = boundary.readShortcut(identity.shortcut)
            checkpoint(stillCurrent)
            check(currentShortcut == shortcut || currentShortcut == null && boundary.createShortcut(
                identity.shortcut, shortcut.spec, Base64.getDecoder().decode(prepared.shortcutBase64),
            ))
            checkpoint(stillCurrent)
            check(boundary.readShortcut(identity.shortcut) == shortcut)
            // Commit is last; a failed commit is rolled back using the still-durable prepared journal.
            val committed = prepared.copy(stage = if (awaitSelectionCommit) WindowsToastManifestStage.SELECTION_PENDING else WindowsToastManifestStage.COMMITTED)
            checkpoint(stillCurrent)
            boundary.writeManifest(prepared, committed)
            // An executed synchronous commit cannot be cancelled, but retirement before returning
            // still rolls this owned candidate back instead of allowing backend initialization.
            checkpoint(stillCurrent)
            return WindowsToastRegistrationResult(WindowsToastRegistrationState(WindowsToastRegistrationStatus.READY, identity,
                pendingSelectionToken = committed.nonce.takeIf { awaitSelectionCommit }))
        } catch (failure: Exception) {
            val removed = runCatching { removeMatching(prepared) }.getOrDefault(false)
            // A commit can have succeeded before retirement/failure was observed. Compare the
            // exact candidate's prepared OR committed journal before removing that owned manifest.
            val cleared = removed && runCatching {
                val actual = boundary.readManifest(identity)
                check(actual == prepared || actual == prepared.copy(stage = WindowsToastManifestStage.COMMITTED) ||
                    actual == prepared.copy(stage = WindowsToastManifestStage.SELECTION_PENDING))
                boundary.writeManifest(actual, null)
            }.isSuccess
            return failed(identity,
                if (!cleared) WindowsToastRegistrationError.ROLLBACK_INCOMPLETE else if (failure is RetiredToastRegistration)
                    WindowsToastRegistrationError.RETIRED else WindowsToastRegistrationError.REGISTRATION_FAILED,
                if (cleared) WindowsToastRegistrationStatus.ABSENT else WindowsToastRegistrationStatus.RECOVERY_REQUIRED,
            )
        }
    }

    private fun inspect(identity: WindowsToastRegistrationIdentity, manifest: WindowsToastRegistrationManifest?): WindowsToastRegistrationState {
        if (manifest != null) validateManifest(identity, manifest)
        val registry = boundary.readRegistry(identity)
        val shortcut = boundary.readShortcut(identity.shortcut)
        if (manifest == null) {
            return if (registry == null && shortcut == null) WindowsToastRegistrationState(WindowsToastRegistrationStatus.ABSENT, identity)
            else WindowsToastRegistrationState(WindowsToastRegistrationStatus.CONFLICT, identity, WindowsToastRegistrationError.FOREIGN_REGISTRATION)
        }
        if (registry != null && registry != registrySpec(manifest) || shortcut != null && shortcut != shortcutSnapshot(manifest)) {
            return WindowsToastRegistrationState(WindowsToastRegistrationStatus.CONFLICT, identity, WindowsToastRegistrationError.FOREIGN_REGISTRATION)
        }
        return WindowsToastRegistrationState(
            if (registry != null && shortcut != null && manifest.stage == WindowsToastManifestStage.COMMITTED)
                WindowsToastRegistrationStatus.READY else WindowsToastRegistrationStatus.RECOVERY_REQUIRED,
            identity, pendingSelectionToken = manifest.nonce.takeIf { manifest.stage == WindowsToastManifestStage.SELECTION_PENDING },
        )
    }

    /** Independently clean unchanged owned objects even if a different object was replaced externally. */
    private fun removeMatching(manifest: WindowsToastRegistrationManifest, stillCurrent: () -> Boolean = { true }): Boolean {
        val identity = manifest.identity
        var complete = true
        val shortcut = boundary.readShortcut(identity.shortcut)
        if (shortcut != null) {
            val expected = shortcutSnapshot(manifest)
            checkpoint(stillCurrent)
            complete = shortcut == expected && boundary.deleteShortcut(identity.shortcut, expected)
        }
        val registry = boundary.readRegistry(identity)
        if (registry != null) {
            val expected = registrySpec(manifest)
            checkpoint(stillCurrent)
            val removed = registry == expected && boundary.deleteRegistry(identity, expected)
            complete = complete && removed
        }
        return complete
    }

    private fun availableIdentity(): WindowsToastRegistrationIdentity? = registrationIdentity(boundary.readEnvironment())

    private fun unavailableState(): WindowsToastRegistrationState {
        val environment = runCatching { boundary.readEnvironment() }.getOrNull()
        val error = when {
            environment == null -> WindowsToastRegistrationError.IO_FAILURE
            !environment.nativeImageRuntime -> WindowsToastRegistrationError.NATIVE_EXECUTABLE_REQUIRED
            !environment.activationEntryPointAvailable -> WindowsToastRegistrationError.ACTIVATION_ENTRY_POINT_UNAVAILABLE
            else -> WindowsToastRegistrationError.INVALID_EXECUTABLE
        }
        return WindowsToastRegistrationState(WindowsToastRegistrationStatus.UNAVAILABLE, error = error)
    }

    companion object {
        fun platform(manifestDirectory: Path, activationEntryPointAvailable: Boolean): WindowsToastRegistration =
            WindowsToastRegistration(NativeWindowsToastRegistrationBoundary(manifestDirectory, activationEntryPointAvailable))
    }
}

internal const val MAX_SHORTCUT_BYTES = 256 * 1024

internal fun registrationIdentity(environment: WindowsToastRegistrationEnvironment): WindowsToastRegistrationIdentity? {
    if (!environment.nativeImageRuntime || !environment.activationEntryPointAvailable || !environment.executableIsRegularFile) return null
    val executable = environment.currentExecutable ?: return null
    val programs = environment.programsDirectory ?: return null
    if (!executable.isAbsolute || !programs.isAbsolute || executable.normalize() != executable || programs.normalize() != programs) return null
    val text = executable.toString()
    if (text.length !in 1..32_000 || text.any { it == '"' || it == '\u0000' || it == '\r' || it == '\n' } ||
        !text.endsWith(".exe", ignoreCase = true) ||
        executable.fileName.toString().lowercase(Locale.ROOT) in setOf("java.exe", "javaw.exe", "cmd.exe", "powershell.exe", "pwsh.exe", "rundll32.exe")) return null
    val pathIdentity = text.replace('/', '\\').lowercase(Locale.ROOT)
    val hash = sha256(pathIdentity.toByteArray(StandardCharsets.UTF_8)).take(24)
    val clsid = UUID.nameUUIDFromBytes(("com.badnng.nngGram.toast.v1:" + pathIdentity).toByteArray(StandardCharsets.UTF_8)).toString()
    return WindowsToastRegistrationIdentity(
        "com.badnng.nngGram.Toast.$hash", clsid, executable,
        programs.resolve("nngGram Notifications ($hash).lnk"),
    )
}

internal fun toastActivationCommand(identity: WindowsToastRegistrationIdentity): String =
    "\"${identity.executable}\" $WINDOWS_TOAST_ACTIVATION_ARGUMENT"

internal fun shortcutSpec(identity: WindowsToastRegistrationIdentity, nonce: String) = WindowsToastShortcutSpec(
    identity.executable.toString(), WINDOWS_TOAST_ACTIVATION_ARGUMENT, identity.executable.parent.toString(),
    "nngGram notifications v1 $nonce", identity.aumid, identity.clsid,
)

internal fun shortcutSnapshot(manifest: WindowsToastRegistrationManifest) =
    WindowsToastShortcutSnapshot(shortcutSpec(manifest.identity, manifest.nonce), manifest.shortcutSha256)

internal fun registrySpec(manifest: WindowsToastRegistrationManifest) = WindowsToastRegistrySnapshot(mapOf(
    "" to mapOf(
        "" to WindowsToastRegistryValue.string("nngGram toast activation"),
        "nngGram.ToastRegistrationOwner" to WindowsToastRegistryValue.string(manifest.nonce),
    ),
    "LocalServer32" to mapOf("" to WindowsToastRegistryValue.string(toastActivationCommand(manifest.identity))),
))

internal fun validateManifest(identity: WindowsToastRegistrationIdentity, manifest: WindowsToastRegistrationManifest) {
    if (manifest.identity != identity || runCatching { UUID.fromString(manifest.nonce).toString() }.getOrNull() != manifest.nonce ||
        !manifest.shortcutSha256.matches(Regex("[0-9a-f]{64}")) || manifest.shortcutBase64.length > MAX_SHORTCUT_BYTES * 4 / 3 + 4) throw InvalidToastManifest()
    val bytes = runCatching { Base64.getDecoder().decode(manifest.shortcutBase64) }.getOrElse { throw InvalidToastManifest() }
    if (bytes.size !in 1..MAX_SHORTCUT_BYTES || sha256(bytes) != manifest.shortcutSha256) throw InvalidToastManifest()
}

internal class InvalidToastManifest : IllegalStateException("Invalid local toast registration manifest")
private class RetiredToastRegistration : IllegalStateException("Toast registration owner retired")
private fun checkpoint(stillCurrent: () -> Boolean) { if (!stillCurrent()) throw RetiredToastRegistration() }

internal fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

private fun failed(identity: WindowsToastRegistrationIdentity, error: WindowsToastRegistrationError, status: WindowsToastRegistrationStatus) =
    WindowsToastRegistrationResult(WindowsToastRegistrationState(status, identity, error), error)
