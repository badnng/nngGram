package com.badnng.moe.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AutoMediaPolicyTest {
    @Test fun actualNativePeerKindsSelectThreeIndependentSources() {
        assertEquals(AutoMediaSource.USER, ChatItem(1, "", privateUserId = 10).autoMediaSource())
        assertEquals(AutoMediaSource.GROUP, ChatItem(-1, "", basicGroupId = 10).autoMediaSource())
        assertEquals(AutoMediaSource.GROUP, ChatItem(-2, "", supergroupId = 10).autoMediaSource())
        assertEquals(AutoMediaSource.CHANNEL, ChatItem(-3, "", supergroupId = 10, isChannel = true).autoMediaSource())
        assertNull(ChatItem(9, "").autoMediaSource())
    }

    @Test fun defaultsAndUnknownZeroUseOfficialPredicateWithoutExpectedSize() {
        val policy = AutoMediaPolicy()
        AutoMediaSource.entries.forEach { source ->
            assertTrue(policy.allows(source, 7, AutoMediaType.PHOTO, 0))
            assertTrue(policy.allows(source, 7, AutoMediaType.PHOTO, 8 * MIB))
            assertFalse(policy.allows(source, 7, AutoMediaType.PHOTO, 8 * MIB + 1))
            assertFalse(policy.allows(source, 7, AutoMediaType.PHOTO, -1))
            assertTrue(policy.allows(source, 7, AutoMediaType.VIDEO, 50 * MIB))
            assertFalse(policy.allows(source, 7, AutoMediaType.VIDEO, 50 * MIB + 1))
            assertEquals(source != AutoMediaSource.CHANNEL, policy.allows(source, 7, AutoMediaType.FILE, 0))
            assertFalse(policy.allows(source, 7, AutoMediaType.VOICE, 1))
            assertFalse(policy.allows(source, 7, AutoMediaType.MUSIC, 1))
        }
    }

    @Test fun disableAndRestoreOnlyTheRequestedSource() {
        val original = AutoMediaPolicy()
        val disabled = original.sourceEnabled(AutoMediaSource.GROUP, false)
        assertFalse(disabled.rules(AutoMediaSource.GROUP).enabled)
        assertTrue(disabled.rules(AutoMediaSource.GROUP).limits.values.all { it == 0L })
        assertEquals(original.rules(AutoMediaSource.USER), disabled.rules(AutoMediaSource.USER))
        assertEquals(original, disabled.sourceEnabled(AutoMediaSource.GROUP, true))
    }

    @Test fun peerOverridesRemainFiniteAndCannotAuthorizeHiddenStreamTypes() {
        val disabled = AutoMediaPolicy().sourceEnabled(AutoMediaSource.USER, false)
        val allowed = disabled.copy(peers = mapOf(55L to AutoMediaOverride.ALLOW))
        assertTrue(allowed.allows(AutoMediaSource.USER, 55, AutoMediaType.FILE, 8 * MIB))
        assertFalse(allowed.allows(AutoMediaSource.USER, 55, AutoMediaType.FILE, 8 * MIB + 1))
        assertFalse(allowed.allows(AutoMediaSource.USER, 55, AutoMediaType.MUSIC, 1))
        val denied = allowed.copy(peers = mapOf(55L to AutoMediaOverride.DENY))
        assertFalse(denied.allows(AutoMediaSource.USER, 55, AutoMediaType.PHOTO, 0))
        assertFalse(AutoMediaPolicy(peers = mapOf(55L to AutoMediaOverride.ALLOW))
            .allows(AutoMediaSource.CHANNEL, 55, AutoMediaType.FILE, 0))
    }

    @Test fun officialHundredPositionScaleKeepsLongUpperBoundary() {
        val values = (0..99).map(::autoMediaSizeByIndex)
        assertEquals(100, values.distinct().size)
        assertTrue(values.zipWithNext().all { (a, b) -> a < b })
        listOf(0 to 1, 9 to 10, 29 to 50, 39 to 100, 59 to 300, 69 to 500,
            79 to 1000, 89 to 2000, 99 to 4000).forEach { (index, mib) ->
            assertEquals(mib * MIB, values[index])
        }
        assertTrue(values.last() > Int.MAX_VALUE)
        val high = AutoMediaPolicy().withSource(AutoMediaSource.USER,
            AutoMediaSourceRules.defaults(AutoMediaSource.USER).withLimit(AutoMediaType.FILE, MAX_AUTO_MEDIA_BYTES))
        assertTrue(high.valid())
        assertTrue(high.allows(AutoMediaSource.USER, 55, AutoMediaType.FILE, MAX_AUTO_MEDIA_BYTES))
        assertFalse(high.allows(AutoMediaSource.USER, 55, AutoMediaType.FILE, MAX_AUTO_MEDIA_BYTES + 1))
    }

    @Test fun malformedModelsCannotPersistAndDifferentReaderLeasesDoNotCompareEqual() {
        assertFalse(AutoMediaPolicy(sources = emptyMap()).valid())
        assertFalse(AutoMediaPolicy(peers = mapOf(0L to AutoMediaOverride.ALLOW)).valid())
        val bad = AutoMediaSourceRules.defaults(AutoMediaSource.USER).withLimit(AutoMediaType.FILE, -1)
        assertFalse(AutoMediaPolicy().withSource(AutoMediaSource.USER, bad).valid())
        val owner = MediaDownloadOwner(ChatToken(1, 2, 3), 4, MediaDownloadPurpose.PROBE, 5)
        assertFalse(owner == owner.copy(leaseId = 6))
    }
}
