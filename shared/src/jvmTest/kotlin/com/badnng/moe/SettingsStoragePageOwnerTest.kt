package com.badnng.moe

import com.badnng.moe.settings.SettingsStorageHandle
import kotlin.test.*

class SettingsStoragePageOwnerTest {
    @Test fun navigationSynchronouslyInvalidatesTheConcreteVisitAndOldRevision() {
        val workspace = SettingsWorkspaceState()
        workspace.destination = SettingsDestination.ADVANCED
        var closes = 0
        val visit = SettingsStorageVisit()
        visit.install(SettingsStorageHandle { closes++ })
        workspace.storageOwner.install(visit)
        val capturedRevision = workspace.storageOwner.revision
        workspace.destination = SettingsDestination.CHAT
        assertFalse(visit.active())
        assertFalse(workspace.storageOwner.owns(visit))
        assertNotEquals(capturedRevision, workspace.storageOwner.revision)
        assertEquals(1, closes)
        workspace.storageOwner.remove(visit)
        assertEquals(1, closes)
    }

    @Test fun reentrantCloseCannotRestoreAnInvalidatedReplacement() {
        val owner = SettingsStoragePageOwner()
        val first = SettingsStorageVisit()
        first.install(SettingsStorageHandle { owner.invalidate() })
        owner.install(first)
        val replacement = SettingsStorageVisit()
        owner.install(replacement)
        assertNull(owner.current)
        assertFalse(replacement.active())
        assertFalse(owner.owns(replacement))
    }
}
