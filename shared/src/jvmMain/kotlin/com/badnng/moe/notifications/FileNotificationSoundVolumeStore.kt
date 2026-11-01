package com.badnng.moe.notifications

import com.badnng.moe.settings.NotificationScope
import com.badnng.moe.settings.NotificationSoundVolumeReading
import com.badnng.moe.settings.NotificationSoundVolumeTarget
import com.badnng.moe.settings.SettingsError
import com.badnng.moe.settings.SettingsResult
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import java.security.MessageDigest

/** Synchronous and actor-owned. Local numeric preferences only; never account content or diagnostics. */
internal class FileNotificationSoundVolumeStore(private val path: Path) : NotificationSoundVolumeStore {
    private data class Key(val accountUserId: Long, val target: NotificationSoundVolumeTarget)
    private data class Stamp(val size: Long, val modified: FileTime, val created: FileTime, val fileKey: Any?)
    private data class Signature(val stamp: Stamp?, val digest: List<Byte>)
    private data class Snapshot(val values: Map<Key, Int>, val signature: Signature)
    private data class Lease(val reading: NotificationSoundVolumeReading, val signature: Signature)

    // Reads of unset targets must also be bounded. Eviction only makes an old editor retry its read.
    private val leases = LinkedHashMap<Long, Lease>()
    private var nextRevision = 1L

    override fun read(
        accountUserId: Long,
        target: NotificationSoundVolumeTarget,
    ): SettingsResult<NotificationSoundVolumeReading> {
        if (!validIdentity(accountUserId, target)) return failure(SettingsError.INVALID_VALUE)
        return try {
            issue(Key(accountUserId, target), load())
        } catch (_: Exception) {
            failure(SettingsError.REQUEST_FAILED)
        }
    }

    override fun save(
        expected: NotificationSoundVolumeReading,
        overridePercent: Int?,
    ): SettingsResult<NotificationSoundVolumeReading> {
        if (!validIdentity(expected.accountUserId, expected.target) || expected.revision <= 0 ||
            !validOverride(expected.overridePercent) || !validOverride(overridePercent)
        ) return failure(SettingsError.INVALID_VALUE)
        val lease = leases[expected.revision]
        if (lease == null || lease.reading != expected) return failure(SettingsError.CONFLICT)
        var temporary: Path? = null
        return try {
            val current = load()
            val key = Key(expected.accountUserId, expected.target)
            if (current.signature != lease.signature || current.values[key] != expected.overridePercent) {
                leases.remove(expected.revision)
                return failure(SettingsError.CONFLICT)
            }
            if (nextRevision == Long.MAX_VALUE) return failure(SettingsError.REQUEST_FAILED)
            if (expected.overridePercent == overridePercent) {
                leases.remove(expected.revision)
                return issue(key, current)
            }
            val updated = current.values.toMutableMap().apply {
                if (overridePercent == null) remove(key) else put(key, overridePercent)
            }
            if (updated.size > MAX_TARGETS) return failure(SettingsError.INVALID_VALUE)
            val bytes = encode(updated)
            if (bytes.size > MAX_BYTES) return failure(SettingsError.INVALID_VALUE)
            val destination = path.toAbsolutePath().normalize()
            Files.createDirectories(destination.parent)
            temporary = Files.createTempFile(destination.parent, "notification-volumes-", ".tmp")
            Files.write(temporary, bytes)
            // Catch changes made while preparing the replacement as well as changes since read().
            if (load().signature != current.signature) {
                leases.remove(expected.revision)
                return failure(SettingsError.CONFLICT)
            }
            // No non-atomic fallback: an unsupported filesystem reports a visible persistence error.
            Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            leases.clear()
            val persisted = load()
            if (persisted.values != updated) return failure(SettingsError.CONFLICT)
            issue(key, persisted)
        } catch (_: Exception) {
            failure(SettingsError.REQUEST_FAILED)
        } finally {
            // Never sweep a directory or delete someone else's stale/foreign temporary file.
            temporary?.let { try { Files.deleteIfExists(it) } catch (_: Exception) { } }
        }
    }

    override fun resolve(
        accountUserId: Long,
        chatId: Long,
        scope: NotificationScope,
        globalVolume: Int,
    ): SettingsResult<Int> {
        if (accountUserId <= 0 || chatId == 0L || globalVolume !in 0..100) {
            return failure(SettingsError.INVALID_VALUE)
        }
        return try {
            val values = load().values
            SettingsResult(value = values[Key(accountUserId, NotificationSoundVolumeTarget.Chat(chatId))]
                ?: values[Key(accountUserId, NotificationSoundVolumeTarget.Scope(scope))]
                ?: globalVolume)
        } catch (_: Exception) {
            failure(SettingsError.REQUEST_FAILED)
        }
    }

    private fun issue(key: Key, snapshot: Snapshot): SettingsResult<NotificationSoundVolumeReading> {
        if (nextRevision == Long.MAX_VALUE) return failure(SettingsError.REQUEST_FAILED)
        val reading = NotificationSoundVolumeReading(
            key.accountUserId, key.target, snapshot.values[key], nextRevision++,
        )
        if (leases.size >= MAX_READINGS) leases.remove(leases.keys.first())
        leases[reading.revision] = Lease(reading, snapshot.signature)
        return SettingsResult(value = reading)
    }

    private fun load(): Snapshot {
        val before = try {
            Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        } catch (_: NoSuchFileException) {
            return Snapshot(emptyMap(), Signature(null, emptyList()))
        }
        require(before.isRegularFile && before.size() <= MAX_BYTES)
        val bytes = Files.newInputStream(path).use { it.readNBytes(MAX_BYTES + 1) }
        require(bytes.size <= MAX_BYTES)
        val after = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        require(after.isRegularFile && stamp(before) == stamp(after) && after.size() == bytes.size.toLong())
        val text = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString()
        val lines = text.split('\n')
        require(lines.firstOrNull() == HEADER)
        val values = LinkedHashMap<Key, Int>()
        lines.drop(1).forEachIndexed { index, line ->
            if (line.isEmpty() && index == lines.size - 2) return@forEachIndexed
            require(values.size < MAX_TARGETS)
            val fields = line.split('\t')
            require(fields.size == 4)
            val account = fields[0].toLongOrNull()
            require(account != null && account > 0 && account.toString() == fields[0])
            val target = when (fields[1]) {
                "scope" -> NotificationSoundVolumeTarget.Scope(NotificationScope.valueOf(fields[2]))
                "chat" -> {
                    val chat = fields[2].toLongOrNull()
                    require(chat != null && chat != 0L && chat.toString() == fields[2])
                    NotificationSoundVolumeTarget.Chat(chat)
                }
                else -> error("Invalid target kind")
            }
            val volume = fields[3].toIntOrNull()
            require(volume != null && volume in 1..100 && volume.toString() == fields[3])
            require(values.put(Key(account, target), volume) == null)
        }
        return Snapshot(values, Signature(stamp(after), MessageDigest.getInstance("SHA-256").digest(bytes).toList()))
    }

    private fun encode(values: Map<Key, Int>): ByteArray = buildString {
        append(HEADER).append('\n')
        values.entries.sortedWith(compareBy<Map.Entry<Key, Int>> { it.key.accountUserId }
            .thenBy { targetText(it.key.target) }).forEach { (key, volume) ->
            append(key.accountUserId).append('\t').append(targetText(key.target)).append('\t').append(volume).append('\n')
        }
    }.toByteArray(Charsets.UTF_8)

    private fun targetText(target: NotificationSoundVolumeTarget): String = when (target) {
        is NotificationSoundVolumeTarget.Scope -> "scope\t${target.scope.name}"
        is NotificationSoundVolumeTarget.Chat -> "chat\t${target.chatId}"
    }

    private fun stamp(attributes: BasicFileAttributes) = Stamp(
        attributes.size(), attributes.lastModifiedTime(), attributes.creationTime(), attributes.fileKey(),
    )

    private fun validIdentity(accountUserId: Long, target: NotificationSoundVolumeTarget) =
        accountUserId > 0 && (target !is NotificationSoundVolumeTarget.Chat || target.chatId != 0L)

    private fun validOverride(value: Int?) = value == null || value in 1..100

    private fun <T> failure(error: SettingsError) = SettingsResult<T>(error = error)

    private companion object {
        const val HEADER = "notification-sound-volumes=1"
        const val MAX_BYTES = 1_048_576
        const val MAX_TARGETS = 32_768
        const val MAX_READINGS = 32_768
    }
}
