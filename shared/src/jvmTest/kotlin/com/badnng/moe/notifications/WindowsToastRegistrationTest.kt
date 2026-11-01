package com.badnng.moe.notifications

import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WindowsToastRegistrationTest {
    @Test
    fun constructionAndStartupInspectionNeverCreateRegistrationOrManifest() {
        val fake = WindowsToastRegistrationTestBoundary()
        val registration = WindowsToastRegistration(fake)
        assertTrue(fake.events.isEmpty())
        repeat(3) { assertEquals(WindowsToastRegistrationStatus.ABSENT, registration.readState().status) }
        assertTrue(fake.events.isEmpty())
        assertNull(fake.manifest)
    }

    @Test
    fun jvmAndMissingActivationEntryPointAreUnavailableWithoutAnyMutation() {
        val fake = WindowsToastRegistrationTestBoundary()
        fake.environment = fake.environment.copy(nativeImageRuntime = false)
        val registration = WindowsToastRegistration(fake)
        assertEquals(WindowsToastRegistrationError.NATIVE_EXECUTABLE_REQUIRED, registration.enable().error)
        assertEquals(WindowsToastRegistrationStatus.UNAVAILABLE, registration.readState().status)
        fake.environment = fake.environment.copy(nativeImageRuntime = true, activationEntryPointAvailable = false)
        assertEquals(WindowsToastRegistrationError.ACTIVATION_ENTRY_POINT_UNAVAILABLE, registration.enable().error)
        assertEquals(WindowsToastRegistrationError.ACTIVATION_ENTRY_POINT_UNAVAILABLE, registration.uninstallOwned().error)
        assertTrue(fake.events.isEmpty())
    }

    @Test
    fun identitiesAreStableForTheirInstallationAndRejectUnusableOrInjectedEntrypoints() {
        val environment = WindowsToastRegistrationTestBoundary().environment
        val identity = assertNotNull(registrationIdentity(environment))
        assertEquals(identity, registrationIdentity(environment))
        assertTrue(identity.aumid.startsWith("com.badnng.nngGram.Toast."))
        assertTrue(identity.aumid.length <= 128)
        assertEquals(UUID.fromString(identity.clsid).toString(), identity.clsid)
        val moved = assertNotNull(registrationIdentity(environment.copy(currentExecutable =
            environment.currentExecutable!!.parent.resolve("Other nngGram.exe"))))
        assertNotEquals(identity.aumid, moved.aumid)
        assertNotEquals(identity.clsid, moved.clsid)
        assertEquals("\"${identity.executable}\" --toast-activate", toastActivationCommand(identity))
        for (name in listOf("java.exe", "javaw.exe", "cmd.exe", "powershell.exe", "pwsh.exe", "rundll32.exe", "nngGram.jar", "bad\".exe", "bad\n.exe")) {
            // Quote/newline paths are legal Path objects on some test OSs; model rejects them too.
            runCatching { environment.currentExecutable!!.parent.resolve(name) }.getOrNull()?.let { path ->
                assertNull(registrationIdentity(environment.copy(currentExecutable = path)), name)
            }
        }
        assertNull(registrationIdentity(environment.copy(currentExecutable = Path.of("relative.exe"))))
        assertNull(registrationIdentity(environment.copy(currentExecutable = environment.currentExecutable!!.parent.resolve("..\\nngGram.exe").normalize(), executableIsRegularFile = false)))
        assertNull(registrationIdentity(environment.copy(executableIsRegularFile = false)))
    }

    @Test
    fun installJournalsBeforeOsMutationVerifiesBothObjectsAndIsIdempotent() {
        val fake = WindowsToastRegistrationTestBoundary()
        val registration = WindowsToastRegistration(fake)
        val result = registration.enable()
        assertTrue(result.succeeded)
        assertEquals(WindowsToastRegistrationStatus.READY, result.state.status)
        assertEquals(listOf("render", "manifest:PREPARED", "create:registry", "create:shortcut", "manifest:COMMITTED"), fake.events)
        assertEquals(WindowsToastManifestStage.COMMITTED, fake.manifest!!.stage)
        assertEquals(registrySpec(fake.manifest!!), fake.registry)
        assertEquals(shortcutSnapshot(fake.manifest!!), fake.shortcut)
        val completed = fake.events.toList()
        repeat(2) { assertTrue(registration.enable().succeeded) }
        assertEquals(completed, fake.events)
        assertEquals(WindowsToastRegistrationStatus.READY, registration.readState().status)
    }

    @Test
    fun matchingObjectsWithoutOwnershipManifestAreNeverAdoptedOrRemoved() {
        val fake = WindowsToastRegistrationTestBoundary()
        val unowned = fake.newManifest()
        fake.registry = registrySpec(unowned)
        fake.shortcut = shortcutSnapshot(unowned)
        val registration = WindowsToastRegistration(fake)
        assertEquals(WindowsToastRegistrationError.FOREIGN_REGISTRATION, registration.enable().error)
        assertEquals(WindowsToastRegistrationError.FOREIGN_REGISTRATION, registration.uninstallOwned().error)
        assertEquals(registrySpec(unowned), fake.registry)
        assertEquals(shortcutSnapshot(unowned), fake.shortcut)
        assertTrue(fake.events.isEmpty())
    }

    @Test
    fun foreignShortcutOrExtraRegistryValueStopsBeforeAnyWrite() {
        for (foreignShortcut in listOf(true, false)) {
            val fake = WindowsToastRegistrationTestBoundary()
            val manifest = fake.newManifest()
            if (foreignShortcut) fake.shortcut = shortcutSnapshot(manifest).copy(spec = shortcutSpec(manifest.identity, manifest.nonce).copy(executable = "other.exe"))
            else fake.registry = registrySpec(manifest).copy(keys = mapOf("" to mapOf("OtherOwner" to WindowsToastRegistryValue(4, "AQAAAA=="))))
            val result = WindowsToastRegistration(fake).enable()
            assertEquals(WindowsToastRegistrationStatus.CONFLICT, result.state.status)
            assertTrue(fake.events.isEmpty())
        }
    }

    @Test
    fun everyInstallCommitFailureRollsBackOnlyTheOwnedCandidate() {
        for (failure in listOf("manifest:PREPARED", "create:registry", "create:shortcut", "manifest:COMMITTED")) {
            val fake = WindowsToastRegistrationTestBoundary()
            fake.failOnce = failure
            val result = WindowsToastRegistration(fake).enable()
            assertFalse(result.succeeded, failure)
            assertNull(fake.registry, failure)
            assertNull(fake.shortcut, failure)
            assertNull(fake.manifest, failure)
        }
    }

    @Test
    fun preparedCrashAfterRegistryCreationResumesWithoutInventingAnotherOwner() {
        val fake = WindowsToastRegistrationTestBoundary()
        val interrupted = fake.newManifest()
        fake.manifest = interrupted
        fake.registry = registrySpec(interrupted)
        val registration = WindowsToastRegistration(fake)
        assertEquals(WindowsToastRegistrationStatus.RECOVERY_REQUIRED, registration.readState().status)
        assertTrue(registration.enable().succeeded)
        assertEquals(interrupted.nonce, fake.manifest!!.nonce)
        assertEquals(listOf("create:shortcut", "manifest:COMMITTED"), fake.events)
    }

    @Test
    fun committedMissingObjectNeedsExplicitRemovalInsteadOfDestructiveRepairRollback() {
        val fake = WindowsToastRegistrationTestBoundary()
        val committed = fake.newManifest().copy(stage = WindowsToastManifestStage.COMMITTED)
        fake.manifest = committed
        fake.registry = registrySpec(committed)
        val result = WindowsToastRegistration(fake).enable()
        assertEquals(WindowsToastRegistrationStatus.RECOVERY_REQUIRED, result.state.status)
        assertEquals(WindowsToastRegistrationError.REGISTRATION_FAILED, result.error)
        assertEquals(committed, fake.manifest)
        assertEquals(registrySpec(committed), fake.registry)
        assertTrue(fake.events.isEmpty())
    }

    @Test
    fun replacementDuringCommitSurvivesRollbackWhileUnchangedOwnedCounterpartIsRemoved() {
        val fake = WindowsToastRegistrationTestBoundary()
        fake.beforeEvent = { event ->
            if (event == "manifest:COMMITTED") {
                fake.shortcut = fake.shortcut!!.copy(sha256 = "e".repeat(64))
                error("External replacement before commit")
            }
        }
        val result = WindowsToastRegistration(fake).enable()
        assertEquals(WindowsToastRegistrationError.ROLLBACK_INCOMPLETE, result.error)
        assertNull(fake.registry)
        assertEquals("e".repeat(64), fake.shortcut!!.sha256)
        assertEquals(WindowsToastManifestStage.PREPARED, fake.manifest!!.stage)
        assertEquals(WindowsToastRegistrationStatus.CONFLICT, WindowsToastRegistration(fake).readState().status)
    }

    @Test
    fun rollbackFailureRetainsDurableOwnershipAndCanBeExplicitlyUninstalledLater() {
        val fake = WindowsToastRegistrationTestBoundary()
        fake.failOnce = "create:shortcut"
        fake.refuseRegistryDelete = true
        val registration = WindowsToastRegistration(fake)
        assertEquals(WindowsToastRegistrationError.ROLLBACK_INCOMPLETE, registration.enable().error)
        assertNotNull(fake.manifest)
        assertNotNull(fake.registry)
        fake.refuseRegistryDelete = false
        assertTrue(registration.uninstallOwned().succeeded)
        assertEquals(WindowsToastRegistrationStatus.ABSENT, registration.readState().status)
        assertNull(fake.manifest)
    }

    @Test
    fun uninstallPersistsIntentAndDeletesExactOwnedObjectsOnly() {
        val fake = WindowsToastRegistrationTestBoundary()
        val registration = WindowsToastRegistration(fake)
        assertTrue(registration.enable().succeeded)
        fake.events.clear()
        assertTrue(registration.uninstallOwned().succeeded)
        assertEquals(listOf("manifest:REMOVING", "delete:shortcut", "delete:registry", "manifest:clear"), fake.events)
        assertNull(fake.registry)
        assertNull(fake.shortcut)
        assertNull(fake.manifest)
        assertTrue(registration.uninstallOwned().succeeded)
    }

    @Test
    fun uninstallKeepsReplacedShortcutAndDurableManifestButCleansOwnedRegistry() {
        val fake = WindowsToastRegistrationTestBoundary()
        val registration = WindowsToastRegistration(fake)
        assertTrue(registration.enable().succeeded)
        val foreign = fake.shortcut!!.copy(spec = fake.shortcut!!.spec.copy(arguments = "--different-owner"))
        fake.shortcut = foreign
        val result = registration.uninstallOwned()
        assertEquals(WindowsToastRegistrationError.FOREIGN_REGISTRATION, result.error)
        assertEquals(foreign, fake.shortcut)
        assertNull(fake.registry)
        assertEquals(WindowsToastManifestStage.REMOVING, fake.manifest!!.stage)
        // Explicit retry never overwrites that replacement, including a subsequent enable.
        assertEquals(WindowsToastRegistrationStatus.CONFLICT, registration.enable().state.status)
        assertEquals(foreign, fake.shortcut)
    }

    @Test
    fun partiallyCompletedUninstallCanResumeAndThenExplicitEnableCreatesFreshOwner() {
        val fake = WindowsToastRegistrationTestBoundary()
        val old = fake.newManifest().copy(stage = WindowsToastManifestStage.REMOVING)
        fake.manifest = old
        fake.shortcut = shortcutSnapshot(old)
        assertTrue(WindowsToastRegistration(fake).enable().succeeded)
        assertNotEquals(old.nonce, fake.manifest!!.nonce)
        assertEquals(WindowsToastManifestStage.COMMITTED, fake.manifest!!.stage)
        assertEquals("delete:shortcut", fake.events.first())
    }

    @Test
    fun malformedOrOtherInstallationManifestCannotGrantDeletionOwnership() {
        val variants: List<(WindowsToastRegistrationManifest) -> WindowsToastRegistrationManifest> = listOf(
            { it.copy(shortcutSha256 = "0".repeat(64)) },
            { it.copy(shortcutBase64 = "invalid base64!") },
            { it.copy(nonce = "untrusted") },
            { it.copy(identity = it.identity.copy(aumid = "Another.App")) },
        )
        for (variant in variants) {
            val fake = WindowsToastRegistrationTestBoundary()
            val good = fake.newManifest()
            fake.manifest = variant(good)
            fake.registry = registrySpec(good)
            fake.shortcut = shortcutSnapshot(good)
            val registration = WindowsToastRegistration(fake)
            assertEquals(WindowsToastRegistrationError.INVALID_MANIFEST, registration.enable().error)
            assertEquals(WindowsToastRegistrationError.INVALID_MANIFEST, registration.uninstallOwned().error)
            assertEquals(registrySpec(good), fake.registry)
            assertEquals(shortcutSnapshot(good), fake.shortcut)
            assertTrue(fake.events.isEmpty())
        }
    }

    @Test
    fun nativeManifestCodecUsesOnlySandboxFilesAndRoundTripsUnicodePathsAndBoundedPayload() {
        val sandbox = Files.createTempDirectory("toast-registration-contract-")
        val fake = WindowsToastRegistrationTestBoundary(sandbox)
        val native = NativeWindowsToastRegistrationBoundary(sandbox.resolve("data"), true,
            object : WindowsToastRegistrationNativeDriver() {
                override fun currentExecutable(): Path = error("No native OS API permitted by this test")
                override fun programsDirectory(): Path = error("No native OS API permitted by this test")
                override fun readRegistry(path: String): WindowsToastRegistrySnapshot? = error("No registry permitted")
            })
        val manifest = fake.newManifest()
        assertNull(native.readManifest(manifest.identity))
        assertFalse(Files.exists(sandbox.resolve("data")))
        native.exclusive {
            native.writeManifest(null, manifest)
            assertEquals(manifest, native.readManifest(manifest.identity))
            val committed = manifest.copy(stage = WindowsToastManifestStage.COMMITTED)
            native.writeManifest(manifest, committed)
            assertEquals(committed, native.readManifest(manifest.identity))
            assertFailsWith<IllegalStateException> { native.writeManifest(manifest, null) }
            native.writeManifest(committed, null)
        }
        assertNull(native.readManifest(manifest.identity))
    }

    @Test
    fun invalidNativeManifestRemainsUntouchedAndGuidHasSdkFieldEndianness() {
        val sandbox = Files.createTempDirectory("toast-manifest-invalid-")
        val fake = WindowsToastRegistrationTestBoundary(sandbox)
        val identity = fake.identity
        val file = sandbox.resolve("toast-${identity.clsid}.properties")
        val bytes = "schema=999\naumid=Other.App\n".toByteArray()
        Files.write(file, bytes)
        val native = NativeWindowsToastRegistrationBoundary(sandbox, true)
        assertFailsWith<InvalidToastManifest> { native.readManifest(identity) }
        assertContentEquals(bytes, Files.readAllBytes(file))
        guidMemory("9f4c2855-9f79-4b39-a8d0-e1d42de1d5f3").use { guid ->
            assertContentEquals(byteArrayOf(0x55, 0x28, 0x4c, 0x9f.toByte(), 0x79, 0x9f.toByte(), 0x39, 0x4b,
                0xa8.toByte(), 0xd0.toByte(), 0xe1.toByte(), 0xd4.toByte(), 0x2d, 0xe1.toByte(), 0xd5.toByte(), 0xf3.toByte()), guid.getByteArray(0, 16))
        }
    }

    @Test
    fun oversizedRenderedLinkDoesNotCreateOsObjectsOrOwnershipJournal() {
        val fake = WindowsToastRegistrationTestBoundary()
        fake.renderedSize = MAX_SHORTCUT_BYTES + 1
        assertFalse(WindowsToastRegistration(fake).enable().succeeded)
        assertNull(fake.manifest)
        assertNull(fake.registry)
        assertNull(fake.shortcut)
        assertEquals(listOf("render"), fake.events)
    }

    @Test
    fun retiredOwnerNeverStartsAFollowOnCreateOrLeavesItsCommittedCandidateBehind() {
        for (retirementPoint in listOf("render", "manifest:PREPARED", "create:registry", "create:shortcut", "manifest:COMMITTED")) {
            val fake = WindowsToastRegistrationTestBoundary()
            var current = true
            fake.beforeEvent = { if (it == retirementPoint) current = false }
            val result = WindowsToastRegistration(fake).enable { current }
            assertEquals(WindowsToastRegistrationError.RETIRED, result.error, retirementPoint)
            assertFalse(result.succeeded)
            assertNull(fake.manifest, retirementPoint)
            assertNull(fake.registry, retirementPoint)
            assertNull(fake.shortcut, retirementPoint)
            val laterCreates = fake.events.drop(fake.events.indexOf(retirementPoint) + 1).filter { it.startsWith("create:") }
            assertTrue(laterCreates.isEmpty(), "$retirementPoint: $laterCreates")
        }
        val fake = WindowsToastRegistrationTestBoundary()
        assertEquals(WindowsToastRegistrationError.RETIRED, WindowsToastRegistration(fake).enable { false }.error)
        assertTrue(fake.events.isEmpty())
    }

    @Test
    fun retiredExplicitUninstallStopsBeforeLaterDeletesAndKeepsRemovalJournalForRetry() {
        val fake = WindowsToastRegistrationTestBoundary()
        val registration = WindowsToastRegistration(fake)
        assertTrue(registration.enable().succeeded)
        var current = true
        fake.events.clear()
        fake.beforeEvent = { if (it == "delete:shortcut") current = false }
        assertEquals(WindowsToastRegistrationError.RETIRED, registration.uninstallOwned { current }.error)
        assertNull(fake.shortcut)
        assertNotNull(fake.registry)
        assertEquals(WindowsToastManifestStage.REMOVING, fake.manifest!!.stage)
        assertEquals(listOf("manifest:REMOVING", "delete:shortcut"), fake.events)
        fake.beforeEvent = null
        assertTrue(registration.uninstallOwned().succeeded)
        assertNull(fake.manifest)
        assertNull(fake.registry)
    }

    @Test
    fun provisionalRegistrationRemainsDurablyUnfinishedUntilItsExactPreferenceCommit() {
        val fake = WindowsToastRegistrationTestBoundary()
        val registration = WindowsToastRegistration(fake)
        val installed = registration.enable(awaitSelectionCommit = true)
        val token = assertNotNull(installed.state.pendingSelectionToken)
        assertTrue(installed.succeeded)
        assertEquals(WindowsToastManifestStage.SELECTION_PENDING, fake.manifest!!.stage)
        val events = fake.events.toList()
        assertEquals(WindowsToastRegistrationStatus.RECOVERY_REQUIRED, registration.readState().status)
        assertEquals(token, registration.readState().pendingSelectionToken)
        assertEquals(events, fake.events.toList(), "Startup inspection must not commit a saved preference by inference")
        assertFalse(registration.commitPendingSelection(UUID.randomUUID().toString()).succeeded)
        assertEquals(WindowsToastManifestStage.SELECTION_PENDING, fake.manifest!!.stage)
        assertTrue(registration.commitPendingSelection(token).succeeded)
        assertEquals(WindowsToastRegistrationStatus.READY, registration.readState().status)
        assertNull(registration.readState().pendingSelectionToken)
    }

    @Test
    fun cancellationIsLimitedToAnExactPendingNonceAndNeverRemovesCommittedRegistration() {
        val fake = WindowsToastRegistrationTestBoundary()
        val registration = WindowsToastRegistration(fake)
        val token = registration.enable(awaitSelectionCommit = true).state.pendingSelectionToken!!
        registration.cancelPendingSelection(UUID.randomUUID().toString())
        assertNotNull(fake.registry)
        assertTrue(registration.cancelPendingSelection(token).succeeded)
        assertNull(fake.manifest)
        val committedToken = registration.enable(awaitSelectionCommit = true).state.pendingSelectionToken!!
        assertTrue(registration.commitPendingSelection(committedToken).succeeded)
        assertTrue(registration.cancelPendingSelection(committedToken).succeeded)
        assertEquals(WindowsToastRegistrationStatus.READY, registration.readState().status)
        assertNotNull(fake.registry)
    }

    @Test
    fun preferenceCommitFailureAndRetirementKeepCancelablePendingJournal() {
        val fake = WindowsToastRegistrationTestBoundary()
        val registration = WindowsToastRegistration(fake)
        val token = registration.enable(awaitSelectionCommit = true).state.pendingSelectionToken!!
        fake.failOnce = "manifest:COMMITTED"
        assertFalse(registration.commitPendingSelection(token).succeeded)
        assertEquals(WindowsToastManifestStage.SELECTION_PENDING, fake.manifest!!.stage)
        assertEquals(WindowsToastRegistrationError.RETIRED, registration.commitPendingSelection(token) { false }.error)
        assertTrue(registration.cancelPendingSelection(token).succeeded)
        assertNull(fake.manifest)
    }

    @Test
    fun preferenceCommitRecoversAnUnknownSuccessfulAtomicWriteAndIsIdempotent() {
        val fake = WindowsToastRegistrationTestBoundary()
        val registration = WindowsToastRegistration(fake)
        val token = registration.enable(awaitSelectionCommit = true).state.pendingSelectionToken!!
        fake.failAfterManifestWrite = WindowsToastManifestStage.COMMITTED
        assertTrue(registration.commitPendingSelection(token).succeeded)
        assertEquals(WindowsToastRegistrationStatus.READY, registration.readState().status)
        val events = fake.events.toList()
        assertTrue(registration.commitPendingSelection(token).succeeded)
        assertEquals(events, fake.events.toList())
    }
}

internal class WindowsToastRegistrationTestBoundary(root: Path = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath()) : WindowsToastRegistrationBoundary {
    var environment = WindowsToastRegistrationEnvironment(true, true,
        root.resolve("原生 app with spaces").resolve("nngGram.exe"), true, root.resolve("Programs 用户"))
    val identity get() = checkNotNull(registrationIdentity(environment))
    @Volatile var manifest: WindowsToastRegistrationManifest? = null
    @Volatile var registry: WindowsToastRegistrySnapshot? = null
    @Volatile var shortcut: WindowsToastShortcutSnapshot? = null
    var failOnce: String? = null
    var failAfterManifestWrite: WindowsToastManifestStage? = null
    var refuseRegistryDelete = false
    var renderedSize: Int? = null
    var beforeEvent: ((String) -> Unit)? = null
    val events = java.util.concurrent.CopyOnWriteArrayList<String>()
    private var locked = false

    fun newManifest(): WindowsToastRegistrationManifest {
        val nonce = UUID.randomUUID().toString()
        val bytes = payload(shortcutSpec(identity, nonce))
        return WindowsToastRegistrationManifest(identity, nonce, WindowsToastManifestStage.PREPARED,
            Base64.getEncoder().encodeToString(bytes), sha256(bytes))
    }

    override fun readEnvironment() = environment
    override fun <T> exclusive(block: () -> T): T {
        check(!locked)
        locked = true
        return try { block() } finally { locked = false }
    }
    override fun readManifest(identity: WindowsToastRegistrationIdentity) = manifest
    override fun writeManifest(expected: WindowsToastRegistrationManifest?, next: WindowsToastRegistrationManifest?) {
        check(locked && manifest == expected)
        event("manifest:${next?.stage?.name ?: "clear"}")
        manifest = next
        if (next != null && failAfterManifestWrite == next.stage) { failAfterManifestWrite = null; error("Injected failure after durable write") }
    }
    override fun readRegistry(identity: WindowsToastRegistrationIdentity) = registry
    override fun createRegistry(identity: WindowsToastRegistrationIdentity, expected: WindowsToastRegistrySnapshot): Boolean {
        check(locked)
        event("create:registry")
        if (registry != null) return false
        registry = expected
        return true
    }
    override fun deleteRegistry(identity: WindowsToastRegistrationIdentity, expected: WindowsToastRegistrySnapshot): Boolean {
        check(locked)
        event("delete:registry")
        if (refuseRegistryDelete || registry != expected) return false
        registry = null
        return true
    }
    override fun readShortcut(path: Path) = shortcut
    override fun renderShortcut(spec: WindowsToastShortcutSpec): ByteArray {
        check(locked)
        event("render")
        return renderedSize?.let(::ByteArray) ?: payload(spec)
    }
    override fun createShortcut(path: Path, spec: WindowsToastShortcutSpec, bytes: ByteArray): Boolean {
        check(locked)
        event("create:shortcut")
        if (shortcut != null) return false
        assertContentEquals(payload(spec), bytes)
        shortcut = WindowsToastShortcutSnapshot(spec, sha256(bytes))
        return true
    }
    override fun deleteShortcut(path: Path, expected: WindowsToastShortcutSnapshot): Boolean {
        check(locked)
        event("delete:shortcut")
        if (shortcut != expected) return false
        shortcut = null
        return true
    }
    private fun event(name: String) {
        events += name
        beforeEvent?.invoke(name)
        if (failOnce == name) { failOnce = null; error("Injected failure") }
    }
    private fun payload(spec: WindowsToastShortcutSpec) = spec.toString().toByteArray(Charsets.UTF_8)
}
