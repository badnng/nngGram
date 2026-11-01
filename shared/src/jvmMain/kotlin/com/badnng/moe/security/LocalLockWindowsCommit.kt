package com.badnng.moe.security

import com.badnng.moe.media.extendedWindowsIdentityPath
import com.sun.jna.Function
import com.sun.jna.NativeLibrary
import com.sun.jna.Platform
import com.sun.jna.WString
import java.nio.file.Path

/**
 * The caller holds the root lease, owns all three names, and has already checked root/file
 * identity, reparse points, backup absence, expected revision, pending force and readback.
 * COMMITTED only reports that the OS call returned success; the caller must read back the
 * record and keep the previous wrap until its separate finalization succeeds.
 */
internal fun interface LocalLockCommitBackend {
    fun commit(pending: Path, record: Path, backup: Path, replaceExisting: Boolean): LocalCommitResult
}

/** Test boundary; implementations must not delete files or fall back to copy-and-delete. */
internal interface LocalLockWindowsCalls {
    fun publishNew(pending: Path, record: Path): Boolean
    fun replaceWithBackup(pending: Path, record: Path, backup: Path): Boolean
}

/**
 * This only makes one same-directory name transition. It does not write/flush file bytes,
 * prove persistent-media or directory durability, finalize a transaction, or recover one.
 * After any possibly issued OS call, a failure is indeterminate even if an exception hides
 * the return value. The caller must retain every remaining file and enter recovery.
 */
internal class LocalLockWindowsCommitAdapter(
    private val isWindows: () -> Boolean,
    private val calls: () -> LocalLockWindowsCalls,
) : LocalLockCommitBackend {
    override fun commit(pending: Path, record: Path, backup: Path, replaceExisting: Boolean): LocalCommitResult {
        val native = try {
            if (!isWindows() || !fixedSiblings(pending, record, backup)) return LocalCommitResult.UNAVAILABLE
            calls()
        } catch (_: Throwable) {
            // No filesystem primitive has been invoked by this adapter yet.
            return LocalCommitResult.UNAVAILABLE
        }
        return try {
            val ok = if (replaceExisting) native.replaceWithBackup(pending, record, backup)
                else native.publishNew(pending, record)
            if (ok) LocalCommitResult.COMMITTED else LocalCommitResult.INDETERMINATE
        } catch (_: Throwable) {
            // The OS call may already have changed one or more names.
            LocalCommitResult.INDETERMINATE
        }
    }

    private fun fixedSiblings(pending: Path, record: Path, backup: Path): Boolean {
        if (!pending.isAbsolute || !record.isAbsolute || !backup.isAbsolute) return false
        if (pending.normalize() != pending || record.normalize() != record || backup.normalize() != backup) return false
        val parent = pending.parent ?: return false
        return parent == record.parent && parent == backup.parent &&
            pending.fileName.toString() == "local-protection.pending" &&
            record.fileName.toString() == "local-protection.wrap" &&
            backup.fileName.toString() == "local-protection.previous"
    }
}

/** Win32 is loaded lazily; merely constructing a coordinator does not load kernel32. */
internal object LocalLockWindowsCommit : LocalLockCommitBackend {
    private val delegate = LocalLockWindowsCommitAdapter(
        isWindows = { Platform.isWindows() },
        calls = { JnaLocalLockWindowsCalls() },
    )

    override fun commit(pending: Path, record: Path, backup: Path, replaceExisting: Boolean): LocalCommitResult =
        delegate.commit(pending, record, backup, replaceExisting)
}

private class JnaLocalLockWindowsCalls : LocalLockWindowsCalls {
    private val kernel = NativeLibrary.getInstance("kernel32")

    override fun publishNew(pending: Path, record: Path): Boolean =
        kernel.getFunction("MoveFileExW", Function.ALT_CONVENTION).invokeInt(
            arrayOf(WString(extendedWindowsIdentityPath(pending)), WString(extendedWindowsIdentityPath(record)), MOVEFILE_WRITE_THROUGH),
        ) != 0

    override fun replaceWithBackup(pending: Path, record: Path, backup: Path): Boolean =
        kernel.getFunction("ReplaceFileW", Function.ALT_CONVENTION).invokeInt(
            arrayOf(WString(extendedWindowsIdentityPath(record)), WString(extendedWindowsIdentityPath(pending)),
                WString(extendedWindowsIdentityPath(backup)), 0, null, null),
        ) != 0

    private companion object {
        // No MOVEFILE_COPY_ALLOWED, MOVEFILE_REPLACE_EXISTING or delayed reboot.
        const val MOVEFILE_WRITE_THROUGH = 0x00000008
    }
}
