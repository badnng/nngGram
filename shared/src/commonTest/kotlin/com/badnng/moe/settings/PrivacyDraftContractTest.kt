package com.badnng.moe.settings

import kotlin.test.*

/** Synthetic settings rules only; no account or native engine is created. */
class PrivacyDraftContractTest {
    @Test
    fun splitExceptionKindsCannotMoveAnExistingBotAcrossAnInterleavedDenyRule() {
        val source = rules(PrivacyRule(PrivacyRuleKind.ALLOW_USERS, listOf(11)),
            PrivacyRule(PrivacyRuleKind.RESTRICT_BOTS), PrivacyRule(PrivacyRuleKind.ALLOW_USERS, listOf(12)))
        // Even a hand-built source claiming editable cannot authorize flattening the two segments.
        assertTrue(source.editable)
        val draft = assertNotNull(PrivacyDraft.from(source))
        assertNull(draft.replacement(PrivacyKey.LAST_SEEN))
        val changed = draft.withSelection(PrivacyExceptionTarget.ALLOW,
            draft.selected(PrivacyExceptionTarget.ALLOW) + PrivacyPeerId(13, false))
        assertNull(changed.replacement(PrivacyKey.LAST_SEEN))
        assertEquals(listOf(PrivacyRule(PrivacyRuleKind.ALLOW_USERS, listOf(11)),
            PrivacyRule(PrivacyRuleKind.RESTRICT_BOTS), PrivacyRule(PrivacyRuleKind.ALLOW_USERS, listOf(12))),
            changed.source.rules.take(3))
    }

    @Test
    fun childSelectionChangesItsParentDraftAndRemovesOnlyTheOppositeMatchingIdentityType() {
        val source = rules(
            PrivacyRule(PrivacyRuleKind.ALLOW_USERS, listOf(1, 2)),
            PrivacyRule(PrivacyRuleKind.ALLOW_CHAT_MEMBERS, listOf(5, -6)),
            PrivacyRule(PrivacyRuleKind.RESTRICT_USERS, listOf(5, 7)),
            PrivacyRule(PrivacyRuleKind.RESTRICT_CHAT_MEMBERS, listOf(2, -8)),
        )
        val original = assertNotNull(PrivacyDraft.from(source))
        val updated = original.withSelection(PrivacyExceptionTarget.ALLOW,
            listOf(PrivacyPeerId(5, false), PrivacyPeerId(2, true), PrivacyPeerId(5, false)))
        assertEquals(listOf(5L), updated.allowUsers)
        assertEquals(listOf(2L), updated.allowChats)
        assertEquals(listOf(7L), updated.denyUsers)
        assertEquals(listOf(-8L), updated.denyChats)
        assertEquals(listOf(1L, 2L), original.allowUsers)
        assertEquals(listOf(5L, -6L), original.allowChats)
        assertEquals(listOf(5L, 7L), original.denyUsers)
        assertEquals(0L, original.revision)
        assertEquals(1L, updated.revision)
        assertSame(source, updated.source)
        assertEquals(listOf(PrivacyPeerId(5, false), PrivacyPeerId(2, true)), updated.selected(PrivacyExceptionTarget.ALLOW))

        val denied = updated.withSelection(PrivacyExceptionTarget.DENY, listOf(PrivacyPeerId(5, true), PrivacyPeerId(5, false)))
        assertTrue(denied.allowUsers.isEmpty())
        assertEquals(listOf(2L), denied.allowChats) // User 5 and chat 5 remain different identities.
        assertEquals(listOf(5L), denied.denyUsers)
        assertEquals(listOf(5L), denied.denyChats)
        assertEquals(2L, denied.revision)
    }

    @Test
    fun changingTheBaseRetainsTheEntireHiddenAllowAndDenyLists() {
        val source = rules(
            PrivacyRule(PrivacyRuleKind.ALLOW_USERS, listOf(11)),
            PrivacyRule(PrivacyRuleKind.ALLOW_CHAT_MEMBERS, listOf(-12)),
            PrivacyRule(PrivacyRuleKind.RESTRICT_USERS, listOf(21)),
            PrivacyRule(PrivacyRuleKind.RESTRICT_CHAT_MEMBERS, listOf(-22)),
        )
        val draft = assertNotNull(PrivacyDraft.from(source))
        for ((base, baseline) in listOf(
            PrivacyBase.EVERYBODY to listOf(PrivacyRule(PrivacyRuleKind.ALLOW_ALL)),
            PrivacyBase.NOBODY to listOf(PrivacyRule(PrivacyRuleKind.RESTRICT_ALL)),
        )) {
            val changed = assertNotNull(draft.copy(base = base).replacement(PrivacyKey.LAST_SEEN))
            assertEquals(source.rules.take(4) + baseline, changed.rules)
            assertEquals(base, changed.base)
        }
        assertSame(source, draft.replacement(PrivacyKey.LAST_SEEN))
    }

    @Test
    fun anUntouchedNativeSequenceIncludingDuplicateIdsAndImplicitDenyRoundTripsVerbatim() {
        for (source in listOf(
            PrivacyRules(PrivacyBase.NOBODY, emptyList()),
            PrivacyRules(PrivacyBase.NOBODY, listOf(PrivacyRule(PrivacyRuleKind.ALLOW_USERS, listOf(11, 4, 11)))),
            rules(PrivacyRule(PrivacyRuleKind.ALLOW_USERS, listOf(11, 4, 11)),
                PrivacyRule(PrivacyRuleKind.RESTRICT_CONTACTS), PrivacyRule(PrivacyRuleKind.RESTRICT_BOTS)),
        )) {
            val draft = assertNotNull(PrivacyDraft.from(source))
            assertSame(source, draft.replacement(PrivacyKey.LAST_SEEN))
        }
    }

    @Test
    fun editingOneListPreservesEveryUntouchedRuleAndItsRelativeFirstMatchPosition() {
        val source = rules(
            PrivacyRule(PrivacyRuleKind.ALLOW_USERS, listOf(19, 7, 19)),
            PrivacyRule(PrivacyRuleKind.RESTRICT_CHAT_MEMBERS, listOf(-49, -3)),
            PrivacyRule(PrivacyRuleKind.ALLOW_BOTS),
            PrivacyRule(PrivacyRuleKind.ALLOW_PREMIUM),
            PrivacyRule(PrivacyRuleKind.ALLOW_CHAT_MEMBERS, listOf(-8, -11)),
            PrivacyRule(PrivacyRuleKind.RESTRICT_USERS, listOf(30, 20)),
            PrivacyRule(PrivacyRuleKind.RESTRICT_CONTACTS),
            PrivacyRule(PrivacyRuleKind.RESTRICT_BOTS),
        )
        val draft = assertNotNull(PrivacyDraft.from(source)).withSelection(PrivacyExceptionTarget.DENY,
            listOf(PrivacyPeerId(31, false), PrivacyPeerId(-49, true), PrivacyPeerId(-3, true)))
        val changed = assertNotNull(draft.replacement(PrivacyKey.CHAT_INVITES))
        assertEquals(source.rules.mapIndexed { index, rule ->
            if (index == 5) rule.copy(ids = listOf(31)) else rule
        }, changed.rules)
        assertEquals(listOf(19L, 7L, 19L), changed.rules.first().ids)
        assertEquals(source, draft.source)
    }

    @Test
    fun clearingAnExceptionListRemovesOnlyItsExplicitRule() {
        val source = rules(
            PrivacyRule(PrivacyRuleKind.ALLOW_BOTS),
            PrivacyRule(PrivacyRuleKind.ALLOW_USERS, listOf(5)),
            PrivacyRule(PrivacyRuleKind.RESTRICT_BOTS),
            PrivacyRule(PrivacyRuleKind.RESTRICT_USERS, listOf(7)),
        )
        val draft = assertNotNull(PrivacyDraft.from(source)).withSelection(PrivacyExceptionTarget.ALLOW, emptyList())
        assertEquals(source.rules.filterNot { it.kind == PrivacyRuleKind.ALLOW_USERS },
            assertNotNull(draft.replacement(PrivacyKey.LAST_SEEN)).rules)
    }

    @Test
    fun peerLimitsApplyAcrossBothTargetsWithExactUserAndGroupBoundaries() {
        val draft = assertNotNull(PrivacyDraft.from(rules()))
        val boundary = draft.copy(allowUsers = (1L..500L).toList(), denyUsers = (501L..1000L).toList(),
            allowChats = (-10L..-1L).toList(), denyChats = (-20L..-11L).toList())
        assertNotNull(boundary.replacement(PrivacyKey.LAST_SEEN))
        assertNull(boundary.copy(denyUsers = boundary.denyUsers + 1001L).replacement(PrivacyKey.LAST_SEEN))
        assertNull(boundary.copy(denyChats = boundary.denyChats + -21L).replacement(PrivacyKey.LAST_SEEN))
        assertEquals(1000, boundary.allowUsers.size + boundary.denyUsers.size)
        assertEquals(20, boundary.allowChats.size + boundary.denyChats.size)
    }

    @Test
    fun malformedOrIntersectingIdsAreRejectedWithoutRepairingAHandBuiltDraft() {
        val draft = assertNotNull(PrivacyDraft.from(rules()))
        for (invalid in listOf(
            draft.copy(allowUsers = listOf(0)), draft.copy(denyUsers = listOf(-1)),
            draft.copy(allowChats = listOf(0)), draft.copy(denyChats = listOf(0)),
            draft.copy(allowUsers = listOf(7, 7)), draft.copy(denyChats = listOf(-9, -9)),
            draft.copy(allowUsers = listOf(7), denyUsers = listOf(7)),
            draft.copy(allowChats = listOf(-9), denyChats = listOf(-9)),
        )) assertNull(invalid.replacement(PrivacyKey.LAST_SEEN))
        assertNotNull(draft.copy(allowUsers = listOf(7), denyChats = listOf(7)).replacement(PrivacyKey.LAST_SEEN))
    }

    @Test
    fun premiumCanOnlyBeAddedToTheNativeChatInviteKeyAndDenySelectionDoesNotChangeIt() {
        val draft = assertNotNull(PrivacyDraft.from(rules())).withSelection(PrivacyExceptionTarget.ALLOW,
            emptyList(), premium = true)
        assertNotNull(draft.replacement(PrivacyKey.CHAT_INVITES))
        for (key in PrivacyKey.entries - PrivacyKey.CHAT_INVITES) assertNull(draft.replacement(key))
        val denied = draft.withSelection(PrivacyExceptionTarget.DENY, listOf(PrivacyPeerId(9, false)), premium = false)
        assertTrue(denied.allowPremium)
        val removed = denied.withSelection(PrivacyExceptionTarget.ALLOW, emptyList(), premium = false)
        assertFalse(removed.allowPremium)
        assertTrue(assertNotNull(removed.replacement(PrivacyKey.CHAT_INVITES)).rules.none { it.kind == PrivacyRuleKind.ALLOW_PREMIUM })
    }

    @Test
    fun findingByPhoneNumberNeverAcceptsNobodyOrExceptions() {
        val draft = assertNotNull(PrivacyDraft.from(rules()))
        assertNotNull(draft.replacement(PrivacyKey.FIND_BY_PHONE_NUMBER))
        assertNull(draft.copy(base = PrivacyBase.NOBODY).replacement(PrivacyKey.FIND_BY_PHONE_NUMBER))
        assertNull(draft.copy(allowUsers = listOf(7)).replacement(PrivacyKey.FIND_BY_PHONE_NUMBER))
        assertNull(draft.copy(denyChats = listOf(-7)).replacement(PrivacyKey.FIND_BY_PHONE_NUMBER))
        assertNull(assertNotNull(PrivacyDraft.from(rules(PrivacyRule(PrivacyRuleKind.ALLOW_BOTS))))
            .replacement(PrivacyKey.FIND_BY_PHONE_NUMBER))
    }

    @Test
    fun unreadableAndNoncanonicalFirstMatchSourcesNeverBecomeWritableDrafts() {
        assertNull(PrivacyDraft.from(PrivacyRules(null, emptyList(), editable = false)))
        val source = PrivacyRules(PrivacyBase.EVERYBODY,
            listOf(PrivacyRule(PrivacyRuleKind.ALLOW_ALL), PrivacyRule(PrivacyRuleKind.RESTRICT_USERS, listOf(9))),
            editable = false)
        val draft = assertNotNull(PrivacyDraft.from(source))
        assertNull(draft.replacement(PrivacyKey.LAST_SEEN))
        assertNull(draft.withSelection(PrivacyExceptionTarget.DENY, emptyList()).replacement(PrivacyKey.LAST_SEEN))
        assertEquals(source.rules, draft.source.rules)
    }

    @Test
    fun exceptionValuesDoNotExposePrivateIdentitiesOrQueriesInDiagnostics() {
        val identity = PrivacyPeerId(829401235L, false)
        val source = rules(PrivacyRule(PrivacyRuleKind.ALLOW_USERS, listOf(identity.id)))
        val values = listOf(identity, SettingsPrivacyPeer(identity, "Private contact title"),
            PrivacyPeerQuery(PrivacyExceptionTarget.ALLOW, 8, 9, PrivacyPeerSource.SERVER,
                query = "private search text", selected = listOf(identity)),
            SettingsPrivacyPeers(listOf(SettingsPrivacyPeer(identity, "Private contact title")), false),
            assertNotNull(PrivacyDraft.from(source)))
        for (value in values) {
            assertFalse(value.toString().contains("829401235"))
            assertFalse(value.toString().contains("Private contact title"))
            assertFalse(value.toString().contains("private search text"))
        }
    }

    private fun rules(vararg prefix: PrivacyRule) = PrivacyRules(PrivacyBase.CONTACTS,
        prefix.toList() + PrivacyRule(PrivacyRuleKind.ALLOW_CONTACTS) + PrivacyRule(PrivacyRuleKind.RESTRICT_ALL))
}
