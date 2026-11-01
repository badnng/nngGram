package com.badnng.moe.tdlib

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** A private temporary root and injected admission predicate; no live account directories. */
class LocalLockQrDirectoryAdmissionTest {
    @Test
    fun revocationAfterFirstProbeCreationDeletesOnlyThatProbeAndNeverStartsTheSecondDirectory() {
        val root = Files.createTempDirectory("nnggram-qr-directory-test-")
        val database = Files.createDirectories(root.resolve("database"))
        val marker = database.resolve("preserved.synthetic")
        val foreign = database.resolve(".write-check-foreign.tmp")
        Files.writeString(marker, "preserve")
        Files.writeString(foreign, "other owner")
        val owner = DataRootLease.acquire(root.toAbsolutePath().normalize())
        var calls = 0
        var ownedProbe: Path? = null
        try {
            assertFailsWith<SetupFailure> {
                owner.prepareDirectories {
                    calls++
                    if (calls == 4) { // After database create + probe create, before that probe write.
                        val probes = Files.newDirectoryStream(database, ".write-check-*.tmp").use { it.toList() }
                        ownedProbe = probes.single { it != foreign }
                        false // Synthetic local-lock revocation.
                    } else true
                }
            }
            assertNotNull(ownedProbe)
            assertFalse(Files.exists(ownedProbe!!), "Only this operation's temporary probe is cleaned.")
            assertEquals("other owner", Files.readString(foreign))
            assertEquals("preserve", Files.readString(marker))
            assertFalse(Files.exists(root.resolve("files")), "The second directory cannot start after revocation.")
            assertFailsWith<SetupFailure> { DataDirectoryLease.acquire(root).close() }
        } finally {
            owner.close()
        }
    }

    @Test
    fun acquiredLeaseIsReleasedOnRevocationAndDefaultPreparationRemainsUsable() {
        val root = Files.createTempDirectory("nnggram-qr-directory-retry-")
        val database = Files.createDirectories(root.resolve("database"))
        val marker = database.resolve("preserved.synthetic")
        Files.writeString(marker, "preserve")
        assertFailsWith<SetupFailure> {
            DataDirectoryLease.acquire(root) { !Files.isDirectory(root.resolve("files")) }
        }
        assertEquals("preserve", Files.readString(marker))
        assertTrue(Files.isDirectory(root.resolve("files")))
        val probes = Files.newDirectoryStream(root.resolve("files"), ".write-check-*.tmp").use { it.toList() }
        assertTrue(probes.isEmpty(), "Revocation is checked before the second probe is created.")
        DataDirectoryLease.acquire(root).close() // Default path can acquire the released root normally.
    }

    @Test
    fun revocationAfterAcquiringRootLockReleasesOnlyItsOwnHandle() {
        val parent = Files.createTempDirectory("nnggram-qr-root-revoke-")
        val sentinel = parent.resolve("preserved.synthetic")
        Files.writeString(sentinel, "preserve")
        val root = parent.resolve("attempt").toAbsolutePath().normalize()
        var checks = 0
        assertFailsWith<SetupFailure> {
            DataRootLease.acquire(root) { ++checks != 6 } // Root lock acquired; identity checks have not begun.
        }
        assertEquals("preserve", Files.readString(sentinel))
        assertTrue(Files.exists(root.resolve("client.lock")))
        assertFalse(Files.exists(root.resolve("database")))
        assertFalse(Files.exists(root.resolve("files")))
        DataRootLease.acquire(root).close() // A later owner can acquire the released handle.
    }
}
