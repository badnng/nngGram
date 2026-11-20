package com.badnng.moe.tdlib

import com.badnng.moe.security.LocalEnvelopeFormat
import com.badnng.moe.security.LocalOpenWrap
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.NativeLong
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardOpenOption.READ
import java.security.MessageDigest
import java.util.HexFormat

internal fun localProtectionIdentity(root: Path, fileIdentity: String): ByteArray {
    val path = root.toString().toByteArray(Charsets.UTF_8)
    val file = fileIdentity.toByteArray(Charsets.UTF_8)
    val digest = MessageDigest.getInstance("SHA-256")
    digest.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(path.size).array())
    digest.update(path)
    digest.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(file.size).array())
    return digest.digest(file)
}

private val unixFileKey = Regex("""\(dev=([0-9a-f]+),ino=(-?[0-9]+)\)""")
private const val MAC_DEVICE_UNIT_COUNT = 4096

internal fun macLocalProtectionIdentity(
    root: Path,
    fileIdentity: String,
    volumeUuid: (Path) -> String? = ::macVolumeUuid,
): ByteArray {
    val currentLegacy = localProtectionIdentity(root, fileIdentity)
    val (deviceHex, inode) = unixFileKey.matchEntire(fileIdentity)?.destructured ?: return currentLegacy
    val device = deviceHex.toLongOrNull(16) ?: return currentLegacy
    val uuid = volumeUuid(root) ?: return currentLegacy
    val stable = localProtectionIdentity(root, "mac-volume-v1:$uuid:$inode")
    val stored = storedRootBinding(root) ?: return stable
    if (MessageDigest.isEqual(stable, stored) || MessageDigest.isEqual(currentLegacy, stored)) return stored

    // Earlier macOS builds hashed UnixFileKey, whose device unit can be assigned
    // differently on each boot. Reconstruct that old hash using the same path,
    // inode and device family while keeping the record's own validation in place.
    val firstUnit = device and (MAC_DEVICE_UNIT_COUNT - 1).toLong().inv()
    for (unit in 0 until MAC_DEVICE_UNIT_COUNT) {
        val oldDevice = firstUnit + unit
        if (oldDevice == device) continue
        val candidate = localProtectionIdentity(root, "(dev=${oldDevice.toString(16)},ino=$inode)")
        if (MessageDigest.isEqual(candidate, stored)) return candidate
    }
    return stable
}

// Read the binding field only. The record store still validates the format,
// checksum or password authentication, and pending/backup state.
private fun storedRootBinding(root: Path): ByteArray? = try {
    FileChannel.open(root.resolve("local-protection.wrap"), READ, NOFOLLOW_LINKS).use { channel ->
        val offset = when (channel.size()) {
            LocalOpenWrap.RECORD_BYTES.toLong() -> 20L
            LocalEnvelopeFormat.RECORD_BYTES.toLong() -> 32L
            else -> return null
        }
        val binding = ByteBuffer.allocate(LocalEnvelopeFormat.ROOT_BYTES)
        channel.position(offset)
        while (binding.hasRemaining()) if (channel.read(binding) <= 0) return null
        binding.array()
    }
} catch (_: NoSuchFileException) {
    null
} catch (_: IOException) {
    null
}

private val macGetAttrList by lazy { NativeLibrary.getProcess().getFunction("getattrlist") }

private fun macVolumeUuid(path: Path): String? = runCatching {
    val volumeUuidAndInfo = 0x80040000.toInt() // ATTR_VOL_INFO | ATTR_VOL_UUID
    Memory(24).use { request ->
        request.clear()
        request.setShort(0, 5) // ATTR_BIT_MAP_COUNT
        request.setInt(8, volumeUuidAndInfo)
        Memory(20).use { result ->
            val status = macGetAttrList.invokeInt(arrayOf(
                Native.toByteArray(path.toString(), Charsets.UTF_8), request, result, NativeLong(result.size()), NativeLong(1),
            ))
            if (status != 0 || result.getInt(0).toLong() != result.size()) return null
            val uuid = result.getByteArray(4, 16)
            if (uuid.all { it == 0.toByte() }) return null
            HexFormat.of().formatHex(uuid)
        }
    }
}.getOrNull()
