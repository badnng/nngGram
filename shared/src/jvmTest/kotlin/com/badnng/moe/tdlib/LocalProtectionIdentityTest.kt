package com.badnng.moe.tdlib

import com.badnng.moe.security.LocalCryptoResult
import com.badnng.moe.security.LocalDatabaseKey
import com.badnng.moe.security.LocalOpenWrap
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

class LocalProtectionIdentityTest {
    @Test
    fun legacyRecordSurvivesDeviceRenumberingButNotDirectoryReplacement() {
        val root = createTempDirectory()
        val record = root.resolve("local-protection.wrap")
        try {
            val legacy = localProtectionIdentity(root, "(dev=1000011,ino=77011864)")
            val key = LocalDatabaseKey.copyOf(ByteArray(32) { 1 })
            try {
                val encoded = assertIs<LocalCryptoResult.Success<ByteArray>>(LocalOpenWrap.encode(key, legacy, 1))
                Files.write(record, encoded.value)
            } finally {
                key.close()
            }

            val afterReboot = macLocalProtectionIdentity(root, "(dev=1000012,ino=77011864)") { "volume-a" }
            assertContentEquals(legacy, afterReboot)
            val replacement = macLocalProtectionIdentity(root, "(dev=1000012,ino=77011865)") { "volume-a" }
            assertFalse(legacy.contentEquals(replacement))
        } finally {
            Files.deleteIfExists(record)
            Files.deleteIfExists(root)
        }
    }

    @Test
    fun newBindingUsesVolumeUuidInsteadOfDeviceNumber() {
        val root = createTempDirectory()
        try {
            val initial = macLocalProtectionIdentity(root, "(dev=1000011,ino=42)") { "volume-a" }
            val afterReboot = macLocalProtectionIdentity(root, "(dev=1000012,ino=42)") { "volume-a" }
            val differentVolume = macLocalProtectionIdentity(root, "(dev=1000011,ino=42)") { "volume-b" }
            assertContentEquals(initial, afterReboot)
            assertFalse(initial.contentEquals(differentVolume))
        } finally {
            Files.deleteIfExists(root)
        }
    }
}
