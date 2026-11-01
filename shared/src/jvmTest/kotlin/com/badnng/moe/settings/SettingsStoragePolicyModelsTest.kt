package com.badnng.moe.settings

import kotlin.test.*

class SettingsStoragePolicyModelsTest {
    @Test fun byteConversionIsExactAndBoundedByTheNativeKiBLimit() {
        assertEquals(0L, settingsStoragePolicyKiBFromBytes(0))
        assertEquals(102400L, settingsStoragePolicyKiBFromBytes(100L * 1024 * 1024))
        assertEquals(SETTINGS_STORAGE_POLICY_MAX, settingsStoragePolicyKiBFromBytes(SETTINGS_STORAGE_POLICY_MAX * 1024))
        assertNull(settingsStoragePolicyKiBFromBytes(-1))
        assertNull(settingsStoragePolicyKiBFromBytes(1025))
        assertNull(settingsStoragePolicyKiBFromBytes((SETTINGS_STORAGE_POLICY_MAX + 1) * 1024))
        assertEquals(2_147_483_647L, SETTINGS_STORAGE_POLICY_LONG_RETENTION_SECONDS)
    }

    @Test fun rawUnsetExplicitFallbackAndUnknownHaveDifferentCompareIdentities() {
        val unset = SettingsStoragePolicyNumber(effective = 82800, source = SettingsStoragePolicySource.DEFAULT)
        val explicit = SettingsStoragePolicyNumber(82800, 82800, SettingsStoragePolicySource.EXPLICIT)
        assertTrue(unset.known); assertTrue(explicit.known)
        assertNotEquals(unset, explicit)
        assertFalse(SettingsStoragePolicyNumber().known)
        assertFalse(SettingsStoragePolicyNumber(1, 2, SettingsStoragePolicySource.EXPLICIT).known)
        assertFalse(SettingsStoragePolicySwitch(effective = true, source = SettingsStoragePolicySource.DEFAULT).known)
        assertNotEquals(SettingsStoragePolicySwitch(effective = false, source = SettingsStoragePolicySource.DEFAULT),
            SettingsStoragePolicySwitch(false, false, SettingsStoragePolicySource.EXPLICIT))
    }

    @Test fun patchesPreserveUntouchedFieldsAndZeroIsAnExplicitFiniteValue() {
        assertTrue(SettingsStoragePolicyDraft().empty)
        assertTrue(SettingsStoragePolicyDraft().valid)
        val timeOnly = SettingsStoragePolicyDraft(unusedSeconds = 0)
        assertNull(timeOnly.sizeKiB); assertNull(timeOnly.automatic)
        assertFalse(timeOnly.empty); assertTrue(timeOnly.valid)
        assertTrue(SettingsStoragePolicyDraft(SETTINGS_STORAGE_POLICY_MAX, SETTINGS_STORAGE_POLICY_MAX, false).valid)
        assertFalse(SettingsStoragePolicyDraft(sizeKiB = -1).valid)
        assertFalse(SettingsStoragePolicyDraft(unusedSeconds = SETTINGS_STORAGE_POLICY_MAX + 1).valid)
    }

    @Test fun rejectionReceiptsKeepTheirExactOwnerAndRequestWithoutInventingReadback() {
        val token = SettingsStoragePolicyToken(8, 42)
        val denied = SettingsStoragePolicyCompletion.rejected(token, 91)
        assertEquals(token, denied.token); assertEquals(91L, denied.requestId)
        assertEquals(SettingsStoragePolicyStatus.REJECTED, denied.status)
        assertEquals(SettingsError.NOT_READY, denied.error)
        assertNull(denied.actual); assertTrue(denied.steps.isEmpty())
        assertFalse(denied.refreshRequired)
        assertTrue(SettingsStoragePolicyCompletion.rejected(token, 92, SettingsError.CONFLICT).refreshRequired)
    }
}
