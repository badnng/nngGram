package com.badnng.moe.tdlib

import com.badnng.moe.settings.*
import org.drinkless.tdlib.TdApi
import kotlin.test.*

class OfficialTdSettingsTest {
    @Test
    fun profileUsesTheAuthenticatedUserAndItsFullInfoWithoutSubstitutingAnActiveUsername() {
        val fixture = Fixture()
        val results = mutableListOf<SettingsResult<SettingsProfile>>()
        fixture.api.profile(results::add)
        val me = fixture.requests.single()
        assertIs<TdApi.GetMe>(me.command)
        me.respond(TdApi.User().apply {
            id = 43
            firstName = "Native first"
            lastName = "Native last"
            usernames = TdApi.Usernames().apply {
                activeUsernames = arrayOf("collectible_active")
                editableUsername = "editable_native"
            }
            phoneNumber = "15550004300"
        })
        val full = fixture.requests.last()
        assertEquals(43L, assertIs<TdApi.GetUserFullInfo>(full.command).userId)
        assertTrue(results.isEmpty())
        me.respond(TdApi.User().apply { id = 999 })
        me.fail()
        assertEquals(2, fixture.requests.size)
        full.respond(TdApi.UserFullInfo().apply {
            bio = TdApi.FormattedText("Native **bio** https://example.test", emptyArray())
        })
        full.respond(TdApi.UserFullInfo())
        full.fail()
        val option = fixture.requests.last()
        assertEquals("bio_length_max", assertIs<TdApi.GetOption>(option.command).name)
        assertEquals(3, fixture.requests.size)
        assertTrue(results.isEmpty())
        option.respond(TdApi.OptionValueInteger(70))
        option.respond(TdApi.OptionValueInteger(140))
        option.fail()
        val profile = assertNotNull(results.single().value)
        assertEquals(SettingsProfile(43, "Native first", "Native last", "editable_native",
            "Native **bio** https://example.test", "15550004300", bioLengthMax = 70), profile)
        assertFalse(profile.toString().contains("Native"))
        assertFalse(results.toString().contains("15550004300"))
    }

    @Test
    fun aFailedFullProfileNeverReportsPartialSuccessOrPublishesNativeErrorText() {
        val fixture = Fixture()
        val results = mutableListOf<SettingsResult<SettingsProfile>>()
        fixture.api.profile(results::add)
        fixture.requests.single().respond(TdApi.User().apply { id = 43; firstName = "Private name" })
        fixture.requests.last().respond(TdApi.Error(403, "Private account details"))
        val response = results.single()
        assertNull(response.value)
        assertEquals(SettingsError.FORBIDDEN, response.error)
        assertFalse(response.toString().contains("Private"))

        fixture.api.profile(results::add)
        fixture.requests.last().respond(TdApi.User().apply { id = 0 })
        assertEquals(SettingsError.REQUEST_FAILED, results.last().error)
        assertEquals(3, fixture.requests.size)
    }

    @Test
    fun eachProfileSaveSendsOnlyTheExplicitFieldAndWaitsForItsNativeAcknowledgement() {
        val fixture = Fixture()
        fixture.loadProfile()
        val edits = listOf(
            SettingsProfileEdit(ProfileField.NAME, "New first", "New last"),
            SettingsProfileEdit(ProfileField.USERNAME, "native_username"),
            SettingsProfileEdit(ProfileField.BIO, "Native bio"),
        )
        for (edit in edits) {
            val results = mutableListOf<SettingsResult<Unit>>()
            val before = fixture.requests.size
            fixture.api.saveProfile(edit, results::add)
            assertEquals(before + 1, fixture.requests.size)
            assertTrue(results.isEmpty())
            val pending = fixture.requests.last()
            when (edit.field) {
                ProfileField.NAME -> assertIs<TdApi.SetName>(pending.command).let {
                    assertEquals("New first", it.firstName)
                    assertEquals("New last", it.lastName)
                }
                ProfileField.USERNAME -> assertEquals("native_username",
                    assertIs<TdApi.SetUsername>(pending.command).username)
                ProfileField.BIO -> assertEquals("Native bio", assertIs<TdApi.SetBio>(pending.command).bio)
            }
            pending.respond(TdApi.Ok())
            pending.respond(TdApi.Error(400, "private repeated native result"))
            pending.fail()
            assertEquals(Unit, results.single().value)
            assertNull(results.single().error)
        }
        val failed = mutableListOf<SettingsResult<Unit>>()
        fixture.api.saveProfile(SettingsProfileEdit(ProfileField.USERNAME, "already_taken"), failed::add)
        fixture.requests.last().respond(TdApi.Error(400, "USERNAME_OCCUPIED private identity"))
        assertNull(failed.single().value)
        assertEquals(SettingsError.INVALID_VALUE, failed.single().error)
        assertFalse(failed.toString().contains("private identity"))
    }

    @Test
    fun invalidProfileFieldsNeverReachTdlibAndEmptyUsernameRemainsAnExplicitRemoval() {
        val fixture = Fixture()
        val results = mutableListOf<SettingsResult<Unit>>()
        listOf(
            SettingsProfileEdit(ProfileField.NAME, " "),
            SettingsProfileEdit(ProfileField.NAME, "line\nbreak"),
            SettingsProfileEdit(ProfileField.NAME, "First", "line\rbreak"),
            SettingsProfileEdit(ProfileField.USERNAME, "four"),
            SettingsProfileEdit(ProfileField.USERNAME, "1invalid"),
            SettingsProfileEdit(ProfileField.USERNAME, "bad name"),
            SettingsProfileEdit(ProfileField.BIO, "a".repeat(141)),
        ).forEach { fixture.api.saveProfile(it, results::add) }
        assertTrue(fixture.requests.isEmpty())
        assertEquals(List(7) { SettingsError.INVALID_VALUE }, results.map { it.error })
        fixture.api.saveProfile(SettingsProfileEdit(ProfileField.USERNAME, ""), results::add)
        assertEquals("", assertIs<TdApi.SetUsername>(fixture.requests.single().command).username)
        fixture.requests.single().respond(TdApi.Ok())
        assertEquals(Unit, results.last().value)
    }

    @Test
    fun biographyUsesTheNativeLimitAndUnicodeScalarCountsWhileRejectingSilentNativeNewlineChanges() {
        val fixture = Fixture()
        val results = mutableListOf<SettingsResult<Unit>>()
        fixture.api.saveProfile(SettingsProfileEdit(ProfileField.BIO, "small"), results::add)
        assertEquals(SettingsError.INVALID_VALUE, results.single().error)
        assertTrue(fixture.requests.isEmpty())
        fixture.loadProfile(70)
        val before = fixture.requests.size
        for (bio in listOf("a".repeat(71), "line\nbreak", "line\rbreak")) {
            fixture.api.saveProfile(SettingsProfileEdit(ProfileField.BIO, bio), results::add)
            assertEquals(SettingsError.INVALID_VALUE, results.last().error)
        }
        assertEquals(before, fixture.requests.size)
        val astralBio = "😀".repeat(70)
        fixture.api.saveProfile(SettingsProfileEdit(ProfileField.BIO, astralBio), results::add)
        assertEquals(astralBio, assertIs<TdApi.SetBio>(fixture.requests.last().command).bio)
        fixture.requests.last().respond(TdApi.Ok())
        assertEquals(Unit, results.last().value)
        val astralName = "😀".repeat(64)
        fixture.api.saveProfile(SettingsProfileEdit(ProfileField.NAME, astralName), results::add)
        assertEquals(astralName, assertIs<TdApi.SetName>(fixture.requests.last().command).firstName)
        fixture.requests.last().respond(TdApi.Ok())
        fixture.api.saveProfile(SettingsProfileEdit(ProfileField.NAME, "😀".repeat(65)), results::add)
        assertEquals(SettingsError.INVALID_VALUE, results.last().error)
        assertEquals(before + 2, fixture.requests.size)
    }

    @Test
    fun anUnavailableNativeBiographyLimitKeepsTheRealProfileReadableAndDisablesBiographyWrites() {
        val fixture = Fixture()
        val results = mutableListOf<SettingsResult<SettingsProfile>>()
        fixture.api.profile(results::add)
        fixture.requests.last().respond(TdApi.User().apply { id = 43; firstName = "Native first" })
        fixture.requests.last().respond(TdApi.UserFullInfo().apply { bio = TdApi.FormattedText("Native bio", emptyArray()) })
        fixture.requests.last().respond(TdApi.Error(500, "private native option failure"))
        val profile = assertNotNull(results.single().value)
        assertEquals("Native bio", profile.bio)
        assertNull(profile.bioLengthMax)
        val saves = mutableListOf<SettingsResult<Unit>>()
        fixture.api.saveProfile(SettingsProfileEdit(ProfileField.BIO, "New bio"), saves::add)
        assertEquals(SettingsError.INVALID_VALUE, saves.single().error)
        assertEquals(3, fixture.requests.size)
        assertFalse(results.toString().contains("private native"))
    }

    @Test
    fun notificationsWaitForAllNativeScopesAndRetainEveryFieldIncludingSoundSentinels() {
        val fixture = Fixture()
        val results = mutableListOf<SettingsResult<Map<NotificationScope, NotificationPreferences>>>()
        fixture.api.notifications(results::add)
        assertEquals(3, fixture.requests.size)
        val scopes = fixture.requests.map { assertIs<TdApi.GetScopeNotificationSettings>(it.command).scope.constructor }
        assertEquals(setOf(TdApi.NotificationSettingsScopePrivateChats.CONSTRUCTOR,
            TdApi.NotificationSettingsScopeGroupChats.CONSTRUCTOR,
            TdApi.NotificationSettingsScopeChannelChats.CONSTRUCTOR), scopes.toSet())
        val expected = mapOf(
            NotificationScope.PRIVATE_CHATS to NotificationPreferences(3600, 0, false, false, true, -1, false, true, false),
            NotificationScope.GROUP_CHATS to NotificationPreferences(0, -1, true, true, false, 0, true, false, true),
            NotificationScope.CHANNEL_CHATS to NotificationPreferences(Int.MAX_VALUE, 918, false, false, false, 919, false, true, true),
        )
        for ((index, pending) in fixture.requests.asReversed().withIndex()) {
            val scope = scope(assertIs<TdApi.GetScopeNotificationSettings>(pending.command).scope)
            pending.respond(nativeNotifications(expected.getValue(scope)))
            pending.respond(TdApi.Error(403, "duplicate private preference"))
            pending.fail()
            if (index < 2) assertTrue(results.isEmpty())
        }
        assertEquals(expected, results.single().value)
        assertFalse(expected.getValue(NotificationScope.PRIVATE_CHATS).soundEnabled)
        assertTrue(expected.getValue(NotificationScope.GROUP_CHATS).soundEnabled)
        assertTrue(expected.getValue(NotificationScope.GROUP_CHATS).enabled)

        for ((notificationScope, value) in expected) {
            val saved = mutableListOf<SettingsResult<Unit>>()
            fixture.api.saveNotifications(notificationScope, value, saved::add)
            val refresh = fixture.requests.last()
            assertEquals(notificationScope, scope(assertIs<TdApi.GetScopeNotificationSettings>(refresh.command).scope))
            assertTrue(saved.isEmpty())
            refresh.respond(nativeNotifications(value))
            refresh.respond(TdApi.Error(403, "duplicate preference refresh"))
            refresh.fail()
            val pending = fixture.requests.last()
            val request = assertIs<TdApi.SetScopeNotificationSettings>(pending.command)
            assertEquals(notificationScope, scope(request.scope))
            assertNativeNotifications(value, request.notificationSettings)
            assertTrue(saved.isEmpty())
            pending.respond(TdApi.Ok())
            assertEquals(Unit, saved.single().value)
        }
    }

    @Test
    fun oneFailedNotificationScopeDoesNotTurnAPartialLoadIntoSuccess() {
        val fixture = Fixture()
        val results = mutableListOf<SettingsResult<Map<NotificationScope, NotificationPreferences>>>()
        fixture.api.notifications(results::add)
        fixture.requests[0].respond(nativeNotifications(NotificationPreferences()))
        fixture.requests[1].respond(TdApi.Error(429, "private account flood wait"))
        assertTrue(results.isEmpty())
        fixture.requests[2].respond(nativeNotifications(NotificationPreferences()))
        assertNull(results.single().value)
        assertEquals(SettingsError.RATE_LIMITED, results.single().error)
        assertFalse(results.toString().contains("private account"))
    }

    @Test
    fun invalidNotificationValuesDoNotWriteAndDroppedWritesCompleteOnlyOnce() {
        val fixture = Fixture()
        val results = mutableListOf<SettingsResult<Unit>>()
        listOf(NotificationPreferences(muteFor = -1), NotificationPreferences(muteFor = -2),
            NotificationPreferences(muteFor = Int.MIN_VALUE)).forEach {
            fixture.api.saveNotifications(NotificationScope.PRIVATE_CHATS, it, results::add)
        }
        assertTrue(fixture.requests.isEmpty())
        assertEquals(List(3) { SettingsError.INVALID_VALUE }, results.map { it.error })
        fixture.loadNotifications()
        fixture.api.saveNotifications(NotificationScope.PRIVATE_CHATS, NotificationPreferences(), results::add)
        fixture.requests.last().fail()
        fixture.requests.last().respond(nativeNotifications(NotificationPreferences()))
        assertEquals(4, results.size)
        assertNull(results.last().value)
        assertEquals(SettingsError.REQUEST_FAILED, results.last().error)
    }

    @Test
    fun notificationSaveRequiresACompleteLoadAndPreservesRemoteChangesToUneditedNativeFields() {
        val fixture = Fixture()
        val results = mutableListOf<SettingsResult<Unit>>()
        fixture.api.saveNotifications(NotificationScope.PRIVATE_CHATS, NotificationPreferences(showPreview = false), results::add)
        assertEquals(SettingsError.NOT_READY, results.single().error)
        assertTrue(fixture.requests.isEmpty())
        fixture.loadNotifications()
        fixture.api.saveNotifications(NotificationScope.PRIVATE_CHATS, NotificationPreferences(showPreview = false), results::add)
        val refresh = fixture.requests.last()
        assertIs<TdApi.GetScopeNotificationSettings>(refresh.command)
        val fresh = NotificationPreferences(storySoundId = 99, muteStories = true,
            disablePinnedMessageNotifications = true, disableMentionNotifications = true)
        refresh.respond(nativeNotifications(fresh))
        val pending = fixture.requests.last()
        val write = assertIs<TdApi.SetScopeNotificationSettings>(pending.command)
        assertNativeNotifications(fresh.copy(showPreview = false), write.notificationSettings)
        assertEquals(1, results.size)
        pending.respond(TdApi.Ok())
        assertEquals(Unit, results.last().value)
    }

    @Test
    fun notificationSoundFieldsRoundTripSignedOpaqueIdsAndPreserveUneditedFreshFields() {
        val loaded = NotificationPreferences(muteFor = 3600, soundId = 918, showPreview = true,
            useDefaultMuteStories = true, muteStories = false, storySoundId = 77,
            showStoryPoster = true, disablePinnedMessageNotifications = false, disableMentionNotifications = false)
        val soundIds = listOf(Long.MIN_VALUE, -2L, 0L, -1L)

        for (storySoundEdited in listOf(false, true)) for (soundId in soundIds) {
            val fixture = Fixture()
            fixture.loadNotifications(loaded)
            val results = mutableListOf<SettingsResult<Unit>>()
            val desired = if (storySoundEdited) loaded.copy(storySoundId = soundId) else loaded.copy(soundId = soundId)
            fixture.api.saveNotifications(NotificationScope.PRIVATE_CHATS, desired, results::add)

            val refresh = fixture.requests.last()
            assertEquals(NotificationScope.PRIVATE_CHATS,
                scope(assertIs<TdApi.GetScopeNotificationSettings>(refresh.command).scope))
            val latest = loaded.copy(muteFor = 3_595,
                soundId = if (storySoundEdited) Long.MIN_VALUE else loaded.soundId,
                showPreview = false, useDefaultMuteStories = false, muteStories = true,
                storySoundId = if (storySoundEdited) loaded.storySoundId else Long.MIN_VALUE,
                showStoryPoster = false, disablePinnedMessageNotifications = true, disableMentionNotifications = true)
            refresh.respond(nativeNotifications(latest))

            val pending = fixture.requests.last()
            val write = assertIs<TdApi.SetScopeNotificationSettings>(pending.command)
            val expected = if (storySoundEdited) latest.copy(storySoundId = soundId) else latest.copy(soundId = soundId)
            assertNativeNotifications(expected, write.notificationSettings)
            assertTrue(results.isEmpty())
            pending.respond(TdApi.Ok())
            assertEquals(Unit, results.single().value)
        }
    }

    @Test
    fun notificationMuteCountdownSurvivesAnUnrelatedPreviewEditAndStillAllowsExplicitUnmuting() {
        val loaded = NotificationPreferences(muteFor = 3600, soundId = 918)
        val fresh = loaded.copy(muteFor = 3598)
        for ((desired, expectedWrite) in listOf(
            loaded.copy(showPreview = false) to fresh.copy(showPreview = false),
            loaded.copy(muteFor = 0) to fresh.copy(muteFor = 0),
        )) {
            val fixture = Fixture()
            fixture.loadNotifications(loaded)
            val results = mutableListOf<SettingsResult<Unit>>()
            fixture.api.saveNotifications(NotificationScope.PRIVATE_CHATS, desired, results::add)
            val refresh = fixture.requests.last()
            assertIs<TdApi.GetScopeNotificationSettings>(refresh.command)
            refresh.respond(nativeNotifications(fresh))
            val pending = fixture.requests.last()
            val write = assertIs<TdApi.SetScopeNotificationSettings>(pending.command)
            assertNativeNotifications(expectedWrite, write.notificationSettings)
            assertTrue(results.isEmpty())
            pending.respond(TdApi.Ok())
            assertEquals(Unit, results.single().value)
        }
    }

    @Test
    fun anEditedNotificationSoundRejectsAConflictingRemoteValueButAcceptsAnAlreadyAppliedValue() {
        val loaded = NotificationPreferences(soundId = 918)
        val desired = loaded.copy(soundId = 0)
        for (freshSound in listOf(919L, Long.MIN_VALUE, 0L)) {
            val fixture = Fixture()
            fixture.loadNotifications(loaded)
            val results = mutableListOf<SettingsResult<Unit>>()
            fixture.api.saveNotifications(NotificationScope.PRIVATE_CHATS, desired, results::add)
            val refresh = fixture.requests.last()
            val before = fixture.requests.size
            refresh.respond(nativeNotifications(loaded.copy(soundId = freshSound)))
            if (freshSound != 0L) {
                assertEquals(before, fixture.requests.size)
                assertTrue(fixture.requests.none { it.command is TdApi.SetScopeNotificationSettings })
                assertNull(results.single().value)
                assertEquals(SettingsError.NOT_READY, results.single().error)
            } else {
                val pending = fixture.requests.last()
                assertEquals(0L, assertIs<TdApi.SetScopeNotificationSettings>(pending.command).notificationSettings.soundId)
                pending.respond(TdApi.Ok())
                assertEquals(Unit, results.single().value)
            }
        }
    }

    @Test
    fun retiredAccountCannotContinueAProfileReadOrNotificationWriteThroughLateCallbacks() {
        val profileFixture = Fixture()
        val profiles = mutableListOf<SettingsResult<SettingsProfile>>()
        profileFixture.api.profile(profiles::add)
        profileFixture.api.retire()
        profileFixture.requests.single().respond(TdApi.User().apply { id = 43 })
        assertEquals(1, profileFixture.requests.size)
        assertEquals(SettingsError.REQUEST_FAILED, profiles.single().error)

        val notificationFixture = Fixture()
        notificationFixture.loadNotifications()
        val saves = mutableListOf<SettingsResult<Unit>>()
        notificationFixture.api.saveNotifications(NotificationScope.PRIVATE_CHATS, NotificationPreferences(showPreview = false), saves::add)
        val refresh = notificationFixture.requests.last()
        notificationFixture.api.retire()
        val before = notificationFixture.requests.size
        refresh.respond(nativeNotifications(NotificationPreferences()))
        assertEquals(before, notificationFixture.requests.size)
        assertTrue(notificationFixture.requests.none { it.command is TdApi.SetScopeNotificationSettings })
        assertEquals(SettingsError.REQUEST_FAILED, saves.single().error)
    }

    @Test
    fun allElevenNativePrivacyRulesRoundTripWithTheirOriginalOrderAndIds() {
        val native = allNativeRules()
        val mapped = mapPrivacyRules(TdApi.UserPrivacySettingRules(native))
        // This fixture deliberately contains every kind, including a noncanonical baseline suffix.
        assertFalse(mapped.editable)
        assertEquals(PrivacyBase.CONTACTS, mapped.base)
        assertEquals(listOf(
            PrivacyRule(PrivacyRuleKind.ALLOW_USERS, listOf(19, 7, 19)),
            PrivacyRule(PrivacyRuleKind.RESTRICT_CHAT_MEMBERS, listOf(-49, -3)),
            PrivacyRule(PrivacyRuleKind.ALLOW_BOTS),
            PrivacyRule(PrivacyRuleKind.ALLOW_PREMIUM),
            PrivacyRule(PrivacyRuleKind.ALLOW_CHAT_MEMBERS, listOf(-8, -11)),
            PrivacyRule(PrivacyRuleKind.RESTRICT_USERS, listOf(30, 20)),
            PrivacyRule(PrivacyRuleKind.RESTRICT_CONTACTS),
            PrivacyRule(PrivacyRuleKind.RESTRICT_BOTS),
            PrivacyRule(PrivacyRuleKind.ALLOW_CONTACTS),
            PrivacyRule(PrivacyRuleKind.RESTRICT_ALL),
            PrivacyRule(PrivacyRuleKind.ALLOW_ALL),
        ), mapped.rules)
        val restored = mapped.rules.map(::nativePrivacyRule)
        assertEquals(native.map { it.constructor }, restored.map { it.constructor })
        assertContentEquals(longArrayOf(19, 7, 19), assertIs<TdApi.UserPrivacySettingRuleAllowUsers>(restored[0]).userIds)
        assertContentEquals(longArrayOf(-49, -3), assertIs<TdApi.UserPrivacySettingRuleRestrictChatMembers>(restored[1]).chatIds)
        assertContentEquals(longArrayOf(-8, -11), assertIs<TdApi.UserPrivacySettingRuleAllowChatMembers>(restored[4]).chatIds)
        assertContentEquals(longArrayOf(30, 20), assertIs<TdApi.UserPrivacySettingRuleRestrictUsers>(restored[5]).userIds)
        assertEquals(mapped, mapPrivacyRules(TdApi.UserPrivacySettingRules(restored.toTypedArray())))
    }

    @Test
    fun changingOnlyThePrivacyBaseRetainsEverySpecialAllowAndDenyException() {
        val source = mapPrivacyRules(TdApi.UserPrivacySettingRules(canonicalNativeRules()))
        assertTrue(source.editable)
        val exceptions = source.rules.take(8)
        val baselines = mapOf(
            PrivacyBase.EVERYBODY to listOf(PrivacyRule(PrivacyRuleKind.ALLOW_ALL)),
            PrivacyBase.CONTACTS to listOf(PrivacyRule(PrivacyRuleKind.ALLOW_CONTACTS)),
            PrivacyBase.NOBODY to listOf(PrivacyRule(PrivacyRuleKind.RESTRICT_ALL)),
        )
        for ((base, baseline) in baselines) {
            val replacement = assertNotNull(replacePrivacyBase(source, base))
            assertEquals(base, replacement.base)
            assertEquals(exceptions + baseline, replacement.rules)
            assertEquals(source.exceptionCount, replacement.exceptionCount)
        }
        assertNull(replacePrivacyBase(source.copy(editable = false), PrivacyBase.NOBODY))
        assertNull(replacePrivacyBase(source.copy(base = null), PrivacyBase.NOBODY))
    }

    @Test
    fun nativeImplicitDenyIsEditableAndSelectingItsCurrentBasePreservesTheExactRuleSequence() {
        val sequences = listOf(
            emptyArray<TdApi.UserPrivacySettingRule>(),
            arrayOf<TdApi.UserPrivacySettingRule>(TdApi.UserPrivacySettingRuleAllowUsers(longArrayOf(7, 9))),
            arrayOf<TdApi.UserPrivacySettingRule>(TdApi.UserPrivacySettingRuleAllowUsers(longArrayOf(7, 9)),
                TdApi.UserPrivacySettingRuleRestrictAll()),
        )
        for (sequence in sequences) {
            val mapped = mapPrivacyRules(TdApi.UserPrivacySettingRules(sequence))
            assertTrue(mapped.editable)
            assertEquals(PrivacyBase.NOBODY, mapped.base)
            assertSame(mapped, replacePrivacyBase(mapped, PrivacyBase.NOBODY))
            assertEquals(sequence.map { it.constructor }, mapped.rules.map { nativePrivacyRule(it).constructor })
        }
        val contacts = mapPrivacyRules(TdApi.UserPrivacySettingRules(canonicalNativeRules()))
        assertSame(contacts, replacePrivacyBase(contacts, PrivacyBase.CONTACTS))
        val nobody = mapPrivacyRules(TdApi.UserPrivacySettingRules(sequences[1]))
        val contactsReplacement = assertNotNull(replacePrivacyBase(nobody, PrivacyBase.CONTACTS))
        assertEquals(listOf(PrivacyRule(PrivacyRuleKind.ALLOW_USERS, listOf(7, 9)),
            PrivacyRule(PrivacyRuleKind.ALLOW_CONTACTS), PrivacyRule(PrivacyRuleKind.RESTRICT_ALL)), contactsReplacement.rules)
    }

    @Test
    fun firstMatchRulesWithABaselineBeforeExceptionsRemainReadableButCannotBeReorderedForEditing() {
        val rules = TdApi.UserPrivacySettingRules(arrayOf(
            TdApi.UserPrivacySettingRuleAllowAll(), TdApi.UserPrivacySettingRuleRestrictUsers(longArrayOf(91)),
        ))
        val mapped = mapPrivacyRules(rules)
        assertEquals(PrivacyBase.EVERYBODY, mapped.base)
        assertFalse(mapped.editable)
        assertEquals(listOf(PrivacyRule(PrivacyRuleKind.ALLOW_ALL),
            PrivacyRule(PrivacyRuleKind.RESTRICT_USERS, listOf(91))), mapped.rules)
        assertNull(replacePrivacyBase(mapped, PrivacyBase.NOBODY))
        val fixture = PrivacyFixture()
        fixture.load(rules)
        val saves = mutableListOf<SettingsResult<Unit>>()
        val before = fixture.requests.size
        fixture.api.save(PrivacyKey.LAST_SEEN, PrivacyBase.NOBODY, saves::add)
        assertEquals(before, fixture.requests.size)
        assertEquals(SettingsError.NOT_READY, saves.single().error)
    }

    @Test
    fun findingByPhoneNumberRejectsNobodyWithoutANativeWrite() {
        val fixture = PrivacyFixture()
        val saves = mutableListOf<SettingsResult<Unit>>()
        fixture.api.save(PrivacyKey.FIND_BY_PHONE_NUMBER, PrivacyBase.NOBODY, saves::add)
        assertEquals(SettingsError.INVALID_VALUE, saves.single().error)
        assertTrue(fixture.requests.isEmpty())
        fixture.load()
        val before = fixture.requests.size
        fixture.api.save(PrivacyKey.FIND_BY_PHONE_NUMBER, PrivacyBase.NOBODY, saves::add)
        assertEquals(before, fixture.requests.size)
        assertEquals(listOf(SettingsError.INVALID_VALUE, SettingsError.INVALID_VALUE), saves.map { it.error })
    }

    @Test
    fun privacySaveRequiresALoadAndRefusesAChangedNativeExceptionListBeforeWriting() {
        val fixture = PrivacyFixture()
        val saves = mutableListOf<SettingsResult<Unit>>()
        fixture.api.save(PrivacyKey.LAST_SEEN, PrivacyBase.NOBODY, saves::add)
        assertEquals(SettingsError.NOT_READY, saves.single().error)
        assertTrue(fixture.requests.isEmpty())

        fixture.load()
        fixture.api.save(PrivacyKey.LAST_SEEN, PrivacyBase.NOBODY, saves::add)
        val refresh = fixture.requests.last()
        assertIs<TdApi.UserPrivacySettingShowStatus>(assertIs<TdApi.GetUserPrivacySettingRules>(refresh.command).setting)
        val before = fixture.requests.size
        refresh.respond(TdApi.UserPrivacySettingRules(arrayOf(
            TdApi.UserPrivacySettingRuleAllowUsers(longArrayOf(77)), TdApi.UserPrivacySettingRuleAllowAll(),
        )))
        assertEquals(before, fixture.requests.size)
        assertTrue(fixture.requests.none { it.command is TdApi.SetUserPrivacySettingRules })
        assertEquals(SettingsError.NOT_READY, saves.last().error)
        assertNull(saves.last().value)
    }

    @Test
    fun privacyWritesTheRefreshedExceptionsAndDoesNotConsumeItsExpectedBaseOnFailure() {
        val fixture = PrivacyFixture()
        fixture.load()
        val saves = mutableListOf<SettingsResult<Unit>>()
        fixture.api.save(PrivacyKey.LAST_SEEN, PrivacyBase.NOBODY, saves::add)
        val refresh = fixture.requests.last()
        refresh.respond(TdApi.UserPrivacySettingRules(canonicalNativeRules()))
        refresh.respond(TdApi.Error(403, "duplicate private exception list"))
        refresh.fail()
        val pending = fixture.requests.last()
        val write = assertIs<TdApi.SetUserPrivacySettingRules>(pending.command)
        assertIs<TdApi.UserPrivacySettingShowStatus>(write.setting)
        val native = write.rules.rules
        assertEquals(allNativeRules().take(8).map { it.constructor } + TdApi.UserPrivacySettingRuleRestrictAll.CONSTRUCTOR,
            native.map { it.constructor })
        assertContentEquals(longArrayOf(19, 7, 19), assertIs<TdApi.UserPrivacySettingRuleAllowUsers>(native[0]).userIds)
        assertContentEquals(longArrayOf(-49, -3), assertIs<TdApi.UserPrivacySettingRuleRestrictChatMembers>(native[1]).chatIds)
        assertTrue(saves.isEmpty())
        pending.respond(TdApi.Error(403, "private exception identities"))
        pending.respond(TdApi.Ok())
        pending.fail()
        assertEquals(SettingsError.FORBIDDEN, saves.single().error)
        assertFalse(saves.toString().contains("private exception"))

        // A failed write did not install NOBODY locally; retry still compares against the original rules.
        fixture.api.save(PrivacyKey.LAST_SEEN, PrivacyBase.NOBODY, saves::add)
        fixture.requests.last().respond(TdApi.UserPrivacySettingRules(canonicalNativeRules()))
        val retry = fixture.requests.last()
        assertIs<TdApi.SetUserPrivacySettingRules>(retry.command)
        retry.respond(TdApi.Ok())
        retry.respond(TdApi.Ok())
        assertEquals(2, saves.size)
        assertEquals(Unit, saves.last().value)
    }

    @Test
    fun oversizedNativePrivacyExceptionsRefuseEditingInsteadOfTruncatingAndWriting() {
        val tooMany = TdApi.UserPrivacySettingRules(arrayOf(
            TdApi.UserPrivacySettingRuleAllowUsers(LongArray(10_001) { it + 1L }),
            TdApi.UserPrivacySettingRuleAllowAll(),
        ))
        val mapped = mapPrivacyRules(tooMany)
        assertFalse(mapped.editable)
        assertTrue(mapped.rules.isEmpty())
        assertNull(replacePrivacyBase(mapped, PrivacyBase.NOBODY))
        val fixture = PrivacyFixture()
        fixture.load(tooMany)
        val before = fixture.requests.size
        val saves = mutableListOf<SettingsResult<Unit>>()
        fixture.api.save(PrivacyKey.LAST_SEEN, PrivacyBase.NOBODY, saves::add)
        assertEquals(before, fixture.requests.size)
        assertEquals(SettingsError.NOT_READY, saves.single().error)

        val boundary = mapPrivacyRules(TdApi.UserPrivacySettingRules(arrayOf(
            TdApi.UserPrivacySettingRuleAllowUsers(LongArray(10_000) { it + 1L }),
            TdApi.UserPrivacySettingRuleAllowAll(),
        )))
        assertTrue(boundary.editable)
        assertEquals(10_000, boundary.exceptionCount)
    }

    private data class Request(
        val command: TdApi.Function<*>, val respond: (TdApi.Object) -> Unit, val fail: () -> Unit,
    )

    private class Fixture {
        val requests = mutableListOf<Request>()
        val api = OfficialTdSettingsSession { command, respond, fail -> requests += Request(command, respond, fail) }

        fun loadProfile(bioLimit: Long = 70) {
            val results = mutableListOf<SettingsResult<SettingsProfile>>()
            api.profile(results::add)
            requests.last().respond(TdApi.User().apply { id = 43 })
            requests.last().respond(TdApi.UserFullInfo())
            assertEquals("bio_length_max", assertIs<TdApi.GetOption>(requests.last().command).name)
            requests.last().respond(TdApi.OptionValueInteger(bioLimit))
            assertEquals(bioLimit.toInt(), assertNotNull(results.single().value).bioLengthMax)
        }

        fun loadNotifications(value: NotificationPreferences = NotificationPreferences()) {
            val before = requests.size
            val results = mutableListOf<SettingsResult<Map<NotificationScope, NotificationPreferences>>>()
            api.notifications(results::add)
            requests.drop(before).forEach { it.respond(nativeNotifications(value)) }
            assertEquals(NotificationScope.entries.toSet(), assertNotNull(results.single().value).keys)
        }
    }

    private class PrivacyFixture {
        val requests = mutableListOf<Request>()
        val api = OfficialTdSettingsPrivacy { command, respond, fail -> requests += Request(command, respond, fail) }

        fun load(rules: TdApi.UserPrivacySettingRules = TdApi.UserPrivacySettingRules(canonicalNativeRules())) {
            val results = mutableListOf<SettingsResult<Pair<Map<PrivacyKey, PrivacyRules>, Map<PrivacyKey, SettingsError>>>>()
            api.load(results::add)
            val loads = requests.filter { it.command is TdApi.GetUserPrivacySettingRules }
            assertEquals(PrivacyKey.entries.size, loads.size)
            assertEquals(listOf(TdApi.UserPrivacySettingShowPhoneNumber.CONSTRUCTOR,
                TdApi.UserPrivacySettingAllowFindingByPhoneNumber.CONSTRUCTOR, TdApi.UserPrivacySettingShowStatus.CONSTRUCTOR,
                TdApi.UserPrivacySettingShowProfilePhoto.CONSTRUCTOR, TdApi.UserPrivacySettingShowLinkInForwardedMessages.CONSTRUCTOR,
                TdApi.UserPrivacySettingAllowCalls.CONSTRUCTOR, TdApi.UserPrivacySettingAllowPeerToPeerCalls.CONSTRUCTOR,
                TdApi.UserPrivacySettingAllowPrivateVoiceAndVideoNoteMessages.CONSTRUCTOR, TdApi.UserPrivacySettingShowBio.CONSTRUCTOR,
                TdApi.UserPrivacySettingShowBirthdate.CONSTRUCTOR, TdApi.UserPrivacySettingAllowChatInvites.CONSTRUCTOR),
                loads.map { assertIs<TdApi.GetUserPrivacySettingRules>(it.command).setting.constructor })
            for (pending in loads) {
                pending.respond(rules)
                pending.respond(TdApi.Error(403, "duplicate private rules"))
                pending.fail()
            }
            assertEquals(PrivacyKey.entries.toSet(), assertNotNull(results.single().value).first.keys)
            assertTrue(assertNotNull(results.single().value).second.isEmpty())
        }
    }

    private companion object {
        fun canonicalNativeRules(): Array<TdApi.UserPrivacySettingRule> = allNativeRules().take(9).toTypedArray()

        fun allNativeRules(): Array<TdApi.UserPrivacySettingRule> = arrayOf(
            TdApi.UserPrivacySettingRuleAllowUsers(longArrayOf(19, 7, 19)),
            TdApi.UserPrivacySettingRuleRestrictChatMembers(longArrayOf(-49, -3)),
            TdApi.UserPrivacySettingRuleAllowBots(),
            TdApi.UserPrivacySettingRuleAllowPremiumUsers(),
            TdApi.UserPrivacySettingRuleAllowChatMembers(longArrayOf(-8, -11)),
            TdApi.UserPrivacySettingRuleRestrictUsers(longArrayOf(30, 20)),
            TdApi.UserPrivacySettingRuleRestrictContacts(),
            TdApi.UserPrivacySettingRuleRestrictBots(),
            TdApi.UserPrivacySettingRuleAllowContacts(),
            TdApi.UserPrivacySettingRuleRestrictAll(),
            TdApi.UserPrivacySettingRuleAllowAll(),
        )

        fun scope(scope: TdApi.NotificationSettingsScope): NotificationScope = when (scope) {
            is TdApi.NotificationSettingsScopePrivateChats -> NotificationScope.PRIVATE_CHATS
            is TdApi.NotificationSettingsScopeGroupChats -> NotificationScope.GROUP_CHATS
            is TdApi.NotificationSettingsScopeChannelChats -> NotificationScope.CHANNEL_CHATS
            else -> error("Unexpected notification scope")
        }

        fun nativeNotifications(value: NotificationPreferences) = TdApi.ScopeNotificationSettings().apply {
            muteFor = value.muteFor
            soundId = value.soundId
            showPreview = value.showPreview
            useDefaultMuteStories = value.useDefaultMuteStories
            muteStories = value.muteStories
            storySoundId = value.storySoundId
            showStoryPoster = value.showStoryPoster
            disablePinnedMessageNotifications = value.disablePinnedMessageNotifications
            disableMentionNotifications = value.disableMentionNotifications
        }

        fun assertNativeNotifications(expected: NotificationPreferences, actual: TdApi.ScopeNotificationSettings) {
            assertEquals(expected.muteFor, actual.muteFor)
            assertEquals(expected.soundId, actual.soundId)
            assertEquals(expected.showPreview, actual.showPreview)
            assertEquals(expected.useDefaultMuteStories, actual.useDefaultMuteStories)
            assertEquals(expected.muteStories, actual.muteStories)
            assertEquals(expected.storySoundId, actual.storySoundId)
            assertEquals(expected.showStoryPoster, actual.showStoryPoster)
            assertEquals(expected.disablePinnedMessageNotifications, actual.disablePinnedMessageNotifications)
            assertEquals(expected.disableMentionNotifications, actual.disableMentionNotifications)
        }
    }
}
