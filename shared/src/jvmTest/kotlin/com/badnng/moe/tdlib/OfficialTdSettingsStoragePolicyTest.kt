package com.badnng.moe.tdlib

import com.badnng.moe.settings.*
import com.badnng.moe.security.LocalLockWitness
import org.drinkless.tdlib.TdApi
import kotlin.test.*

/** Real option provider and synthetic TDLib callbacks only; no client, files, GC or OS access. */
class OfficialTdSettingsStoragePolicyTest {
    @Test
    fun constructionAndFiveEmptyReadsNeverWriteScanOrOptimizeAndKeepNativeDefaultsDistinct() {
        val f = Fixture()
        assertTrue(f.calls.isEmpty())
        val results = f.load()
        f.readBatch()
        val read = results.single()
        assertTrue(read.complete)
        assertEquals(defaultValues(), read.values)
        assertEquals(104_857_600L, read.values.sizeBytes)
        assertEquals(listOf(SIZE, TIME, AUTOMATIC, COUNT, IMMUNITY), f.gets.map { it.name })
        assertTrue(f.writes.isEmpty())
        assertOnlyOptions(f)
    }

    @Test
    fun explicitZeroMaximumAndBooleanValuesRetainTheirNativeUnitsWithoutApplyingFallbacks() {
        for (value in listOf(0L, SETTINGS_STORAGE_POLICY_MAX)) {
            for (automatic in listOf(false, true)) {
                val f = Fixture(explicitNative(value, automatic))
                val results = f.load()
                f.readBatch()
                val values = results.single().values
                assertTrue(results.single().complete)
                assertEquals(explicitNumber(value), values.sizeKiB)
                assertEquals(explicitNumber(value), values.unusedSeconds)
                assertEquals(explicitNumber(value), values.fileCount)
                assertEquals(explicitNumber(value), values.immunitySeconds)
                assertEquals(explicitSwitch(automatic), values.automatic)
                assertEquals(value * 1024, values.sizeBytes)
                assertTrue(f.writes.isEmpty())
            }
        }
    }

    @Test
    fun wrongTypesNegativeAndOverflowNumbersRemainUnknownWhileOtherFieldsStayActual() {
        val invalidNumbers = listOf<TdApi.Object>(TdApi.OptionValueBoolean(false), TdApi.OptionValueString("102400"),
            TdApi.OptionValueInteger(-1), TdApi.OptionValueInteger(SETTINGS_STORAGE_POLICY_MAX + 1), TdApi.Ok())
        for (field in listOf(SettingsStoragePolicyField.SIZE, SettingsStoragePolicyField.UNUSED_TIME,
                SettingsStoragePolicyField.FILE_COUNT, SettingsStoragePolicyField.IMMUNITY)) {
            for (invalid in invalidNumbers) {
                val f = Fixture()
                val results = f.load()
                f.readBatch(mapOf(option(field) to invalid))
                val read = results.single()
                assertFalse(read.complete)
                assertEquals(mapOf(field to SettingsError.REQUEST_FAILED), read.errors)
                assertEquals(SettingsStoragePolicyNumber(), number(read.values, field))
                assertTrue(read.values.automatic.known)
                assertEquals(5, f.gets.size)
                assertTrue(f.writes.isEmpty())
            }
        }
        for (invalid in listOf(TdApi.OptionValueInteger(0), TdApi.OptionValueString("false"), TdApi.Ok())) {
            val f = Fixture()
            val results = f.load()
            f.readBatch(mapOf(AUTOMATIC to invalid))
            assertEquals(mapOf(SettingsStoragePolicyField.AUTOMATIC to SettingsError.REQUEST_FAILED), results.single().errors)
            assertEquals(SettingsStoragePolicySwitch(), results.single().values.automatic)
            assertEquals(defaultNumber(102400), results.single().values.sizeKiB)
        }
    }

    @Test
    fun nativeReadFailuresAreTypedPerFieldAndNeverInventAnEmptyDefault() {
        val f = Fixture()
        val results = f.load()
        f.readBatch(mapOf(SIZE to TdApi.Error(403, "private native detail"),
            AUTOMATIC to TdApi.Error(429, "private retry detail")))
        val read = results.single()
        assertEquals(mapOf(SettingsStoragePolicyField.SIZE to SettingsError.FORBIDDEN,
            SettingsStoragePolicyField.AUTOMATIC to SettingsError.RATE_LIMITED), read.errors)
        assertFalse(read.values.sizeKiB.known)
        assertFalse(read.values.automatic.known)
        assertEquals(defaultNumber(40000), read.values.fileCount)
        assertFalse(results.toString().contains("private"))
        assertTrue(f.writes.isEmpty())
    }

    @Test
    fun absentDirtyFieldsAndSameEffectiveValuesPreserveEveryNativeUnsetWithoutWriting() {
        for (draft in listOf(SettingsStoragePolicyDraft(), SettingsStoragePolicyDraft(
                sizeKiB = 102400, unusedSeconds = 82800, automatic = false))) {
            val f = Fixture()
            val results = f.save(draft = draft)
            f.readBatch()
            val outcome = results.single()
            assertEquals(SettingsStoragePolicyStatus.UNCHANGED, outcome.status)
            assertEquals(defaultValues(), outcome.actual?.values)
            assertTrue(outcome.steps.isEmpty())
            assertFalse(outcome.refreshRequired)
            assertTrue(f.writes.isEmpty())
            assertTrue(f.raw.values.all { it is TdApi.OptionValueEmpty })
        }
    }

    @Test
    fun freshCasDistinguishesEmptyFromExplicitFallbackAndFalseInBothDirections() {
        for (key in listOf(SIZE, TIME, AUTOMATIC, COUNT, IMMUNITY)) {
            for (expectedEmpty in listOf(true, false)) {
                val nativeExplicit = when (key) {
                    AUTOMATIC -> TdApi.OptionValueBoolean(false)
                    else -> TdApi.OptionValueInteger(defaultFor(key))
                }
                val f = Fixture()
                val expected = if (expectedEmpty) defaultValues() else withExplicitDefault(defaultValues(), key)
                if (expectedEmpty) f.raw[key] = nativeExplicit
                val results = f.save(expected, SettingsStoragePolicyDraft(sizeKiB = 204800))
                f.readBatch()
                assertEquals(SettingsStoragePolicyStatus.CONFLICT, results.single().status)
                assertEquals(SettingsError.CONFLICT, results.single().error)
                assertTrue(results.single().refreshRequired)
                assertNotEquals(expected, results.single().actual?.values)
                assertTrue(f.writes.isEmpty())
            }
        }
    }

    @Test
    fun invalidDraftOrUnknownExpectedStateIsRejectedBeforeAnyNativeRequest() {
        for (draft in listOf(SettingsStoragePolicyDraft(sizeKiB = -1),
                SettingsStoragePolicyDraft(sizeKiB = SETTINGS_STORAGE_POLICY_MAX + 1),
                SettingsStoragePolicyDraft(unusedSeconds = -1),
                SettingsStoragePolicyDraft(unusedSeconds = SETTINGS_STORAGE_POLICY_MAX + 1))) {
            val f = Fixture()
            assertEquals(SettingsError.INVALID_VALUE, f.save(draft = draft).single().error)
            assertTrue(f.calls.isEmpty())
        }
        val unknown = Fixture()
        assertEquals(SettingsError.NOT_READY, unknown.save(SettingsStoragePolicyValues(),
            SettingsStoragePolicyDraft(sizeKiB = 204800)).single().error)
        assertTrue(unknown.calls.isEmpty())
    }

    @Test
    fun oneUnreadableFreshCasKeyPreventsEveryWriteWithoutDiscardingTheOtherActualFields() {
        val f = Fixture()
        val results = f.save(draft = SettingsStoragePolicyDraft(sizeKiB = 204800, automatic = true))
        f.readBatch(mapOf(COUNT to TdApi.Error(403, "private fresh failure")))
        val outcome = results.single()
        assertEquals(SettingsStoragePolicyStatus.CONFLICT, outcome.status)
        assertEquals(SettingsError.FORBIDDEN, outcome.error)
        assertEquals(mapOf(SettingsStoragePolicyField.FILE_COUNT to SettingsError.FORBIDDEN), outcome.actual?.errors)
        assertEquals(defaultNumber(102400), outcome.actual?.values?.sizeKiB)
        assertFalse(assertNotNull(outcome.actual).values.fileCount.known)
        assertTrue(outcome.refreshRequired)
        assertTrue(outcome.steps.isEmpty())
        assertTrue(f.writes.isEmpty())
    }

    @Test
    fun changingAnEnabledPolicyDisablesConfirmsParametersThenEnablesAfterEveryFreshFiveKeyRead() {
        val f = enabledFixture()
        val progress = mutableListOf<List<SettingsStoragePolicyStepResult>>()
        val results = f.save(enabledValues(), changedDraft(), progress::add)
        f.readBatch()
        val expectedSteps = listOf(SettingsStoragePolicyStep.DISABLE_AUTOMATIC, SettingsStoragePolicyStep.SIZE,
            SettingsStoragePolicyStep.UNUSED_TIME, SettingsStoragePolicyStep.ENABLE_AUTOMATIC)
        val expectedWrites = listOf(AUTOMATIC to false, SIZE to 204800L, TIME to 86400L, AUTOMATIC to true)
        expectedWrites.forEachIndexed { index, (key, value) ->
            val set = assertIs<TdApi.SetOption>(f.pending.single().command)
            assertEquals(key, set.name)
            assertNativeValue(value, set.value)
            f.acceptWrite()
            assertTrue(results.isEmpty())
            for (readIndex in 0 until 4) {
                f.replyGet()
                assertIs<TdApi.GetOption>(f.pending.single().command,
                    "Step ${expectedSteps[index]} must confirm all five options before another write.")
                assertEquals(index + 1, f.writes.size)
            }
            f.replyGet()
            assertEquals(index + 1, progress.last().size)
            assertTrue(progress.last().last().accepted)
            assertTrue(progress.last().last().confirmed)
        }
        val outcome = results.single()
        assertEquals(SettingsStoragePolicyStatus.CONFIRMED, outcome.status)
        assertEquals(expectedSteps, outcome.steps.map { it.step })
        assertTrue(outcome.steps.all { it.accepted && it.confirmed && it.error == null })
        assertEquals(204800L, outcome.actual?.values?.sizeKiB?.raw)
        assertEquals(86400L, outcome.actual?.values?.unusedSeconds?.raw)
        assertEquals(true, outcome.actual?.values?.automatic?.raw)
        assertEquals(25, f.gets.size)
        assertEquals(8, progress.size)
        assertFalse(outcome.refreshRequired)
        assertOnlyOptions(f)
    }

    @Test
    fun enablingAnUnsetPolicyWritesOnlyRequestedValuesAndKeepsCountAndImmunityUntouched() {
        val f = Fixture().apply {
            raw[COUNT] = TdApi.OptionValueInteger(123456)
            raw[IMMUNITY] = TdApi.OptionValueInteger(SETTINGS_STORAGE_POLICY_MAX)
        }
        val expected = defaultValues().copy(fileCount = explicitNumber(123456),
            immunitySeconds = explicitNumber(SETTINGS_STORAGE_POLICY_MAX))
        val results = f.save(expected, SettingsStoragePolicyDraft(sizeKiB = 0,
            unusedSeconds = SETTINGS_STORAGE_POLICY_MAX, automatic = true))
        f.readBatch()
        repeat(3) { f.acceptWrite(); f.readBatch() }
        val outcome = results.single()
        assertEquals(SettingsStoragePolicyStatus.CONFIRMED, outcome.status)
        assertEquals(listOf(SIZE, TIME, AUTOMATIC), f.writes.map { it.name })
        assertEquals(explicitNumber(0), outcome.actual?.values?.sizeKiB)
        assertEquals(explicitNumber(SETTINGS_STORAGE_POLICY_MAX), outcome.actual?.values?.unusedSeconds)
        assertEquals(expected.fileCount, outcome.actual?.values?.fileCount)
        assertEquals(expected.immunitySeconds, outcome.actual?.values?.immunitySeconds)
        assertTrue(f.writes.none { it.name == COUNT || it.name == IMMUNITY })
    }

    @Test
    fun disablingAnEnabledPolicyNeverRestoresItWhileSavingTheRequestedParameters() {
        val f = enabledFixture()
        val results = f.save(enabledValues(), changedDraft().copy(automatic = false))
        f.readBatch()
        repeat(3) { f.acceptWrite(); f.readBatch() }
        assertEquals(SettingsStoragePolicyStatus.CONFIRMED, results.single().status)
        assertEquals(listOf(AUTOMATIC, SIZE, TIME), f.writes.map { it.name })
        assertEquals(false, results.single().actual?.values?.automatic?.raw)
        assertTrue(f.writes.none { it.name == AUTOMATIC && (it.value as? TdApi.OptionValueBoolean)?.value == true })
    }

    @Test
    fun aFailureAtAnyWriteStopsThePlanKeepsConfirmedStepsAndReadsActualStateWithoutRollback() {
        for (failureIndex in 0..3) {
            val f = enabledFixture()
            val results = f.save(enabledValues(), changedDraft())
            f.readBatch()
            repeat(failureIndex) { f.acceptWrite(); f.readBatch() }
            val failed = f.take()
            assertIs<TdApi.SetOption>(failed.command)
            failed.reply(TdApi.Error(403, "private failure"))
            f.readBatch()
            val outcome = results.single()
            assertEquals(SettingsStoragePolicyStatus.INCOMPLETE, outcome.status)
            assertEquals(SettingsError.FORBIDDEN, outcome.error)
            assertTrue(outcome.refreshRequired)
            assertTrue(assertNotNull(outcome.actual).complete)
            assertEquals(failureIndex + 1, outcome.steps.size)
            assertTrue(outcome.steps.take(failureIndex).all { it.accepted && it.confirmed })
            assertFalse(outcome.steps.last().accepted)
            assertEquals(SettingsError.FORBIDDEN, outcome.steps.last().error)
            assertEquals(failureIndex + 1, f.writes.size)
            assertEquals(failureIndex == 0, outcome.actual?.values?.automatic?.effective)
            assertTrue(f.pending.isEmpty())
            assertFalse(results.toString().contains("private failure"))
            assertOnlyOptions(f)
        }
    }

    @Test
    fun anIndeterminateWriteKeepsItsPhysicalSlotAndDoesNotReadOrResumeBeforeTheTrueTerminal() {
        val f = Fixture()
        val results = f.save(draft = SettingsStoragePolicyDraft(sizeKiB = 204800, automatic = true))
        f.readBatch()
        val set = f.take()
        val command = assertIs<TdApi.SetOption>(set.command)
        f.raw[command.name] = command.value
        set.fail()
        val outcome = results.single()
        assertEquals(SettingsStoragePolicyStatus.INCOMPLETE, outcome.status)
        assertNull(outcome.actual)
        assertFalse(outcome.steps.single().accepted)
        assertEquals(SettingsStoragePolicyReceipt.INDETERMINATE, outcome.steps.single().receipt)
        assertTrue(outcome.physicalPending)
        assertTrue(outcome.refreshRequired)
        assertEquals(listOf(SIZE), f.writes.map { it.name })
        val before = f.calls.size
        assertEquals(SettingsStoragePolicyStatus.REJECTED,
            f.save(draft = SettingsStoragePolicyDraft(sizeKiB = 307200)).single().status)
        assertEquals(before, f.calls.size)
        set.reply(TdApi.Ok())
        assertEquals(1, results.size)
        assertEquals(before, f.calls.size, "The stopped plan cannot resume after its true receipt.")
        val recheck = f.load(); f.readBatch()
        assertEquals(204800L, recheck.single().values.sizeKiB.raw)
    }

    @Test
    fun mismatchedReadbackAtEveryStepPreservesAcceptanceButNeverConfirmsOrContinues() {
        for (mismatchIndex in 0..3) {
            val f = enabledFixture()
            val results = f.save(enabledValues(), changedDraft())
            f.readBatch()
            repeat(mismatchIndex) { f.acceptWrite(); f.readBatch() }
            val currentSet = assertIs<TdApi.SetOption>(f.pending.single().command)
            f.acceptWrite()
            f.raw[currentSet.name] = when (val value = currentSet.value) {
                is TdApi.OptionValueBoolean -> TdApi.OptionValueBoolean(!value.value)
                is TdApi.OptionValueInteger -> TdApi.OptionValueInteger(value.value + 1)
                else -> error("Unexpected policy write")
            }
            f.readBatch()
            val outcome = results.single()
            assertEquals(SettingsStoragePolicyStatus.INCOMPLETE, outcome.status)
            assertEquals(SettingsError.CONFLICT, outcome.error)
            assertTrue(outcome.steps.last().accepted)
            assertFalse(outcome.steps.last().confirmed)
            assertTrue(outcome.steps.take(mismatchIndex).all { it.confirmed })
            assertTrue(assertNotNull(outcome.actual).complete)
            assertTrue(outcome.refreshRequired)
            assertEquals(mismatchIndex + 1, f.writes.size)
            assertTrue(f.pending.isEmpty())
        }
    }

    @Test
    fun failedReadbackAtEveryStepKeepsTheNativeAcceptanceAndBlocksEveryLaterWrite() {
        for (failureIndex in 0..3) {
            val f = enabledFixture()
            val results = f.save(enabledValues(), changedDraft())
            f.readBatch()
            repeat(failureIndex) { f.acceptWrite(); f.readBatch() }
            val currentSet = assertIs<TdApi.SetOption>(f.pending.single().command)
            f.acceptWrite()
            f.readBatch(mapOf(currentSet.name to TdApi.Error(500, "private proof failure")))
            val outcome = results.single()
            assertEquals(SettingsStoragePolicyStatus.INCOMPLETE, outcome.status)
            assertEquals(SettingsError.REQUEST_FAILED, outcome.error)
            assertTrue(outcome.steps.last().accepted)
            assertFalse(outcome.steps.last().confirmed)
            assertFalse(assertNotNull(outcome.actual).complete)
            assertTrue(outcome.refreshRequired)
            assertEquals(failureIndex + 1, f.writes.size)
            assertTrue(f.pending.isEmpty())
        }
    }

    @Test
    fun anExternalOptionUpdateDuringReadInvalidatesAllFiveKeysWithoutStartingAnyWrite() {
        val f = Fixture()
        var changes = 0
        f.api.observe { changes++ }
        val results = f.save(draft = SettingsStoragePolicyDraft(sizeKiB = 204800))
        f.replyGet()
        f.external(COUNT, TdApi.OptionValueInteger(5))
        f.readBatch(from = 1)
        val outcome = results.single()
        assertEquals(SettingsStoragePolicyStatus.CONFLICT, outcome.status)
        assertEquals(SettingsStoragePolicyField.entries.associateWith { SettingsError.CONFLICT }, outcome.actual?.errors)
        assertEquals(explicitNumber(5), outcome.actual?.values?.fileCount)
        assertEquals(1, changes)
        assertTrue(f.writes.isEmpty())
    }

    @Test
    fun anExternalUpdateAfterOneConfirmedStepCannotAuthorizeTheNextWriteOrEnable() {
        val f = enabledFixture()
        var injected = false
        val results = f.save(enabledValues(), changedDraft(), progress = { progress ->
            if (!injected && progress.lastOrNull()?.confirmed == true) {
                injected = true
                f.external(COUNT, TdApi.OptionValueInteger(600))
            }
        })
        f.readBatch()
        f.acceptWrite()
        f.readBatch()
        assertTrue(injected)
        // The changed update revision forces a separate actual-state read instead of the SIZE write.
        f.readBatch()
        val outcome = results.single()
        assertEquals(SettingsStoragePolicyStatus.INCOMPLETE, outcome.status)
        assertEquals(SettingsError.CONFLICT, outcome.error)
        assertTrue(outcome.steps.single().confirmed)
        assertEquals(explicitNumber(600), outcome.actual?.values?.fileCount)
        assertEquals(false, outcome.actual?.values?.automatic?.effective)
        assertEquals(listOf(AUTOMATIC), f.writes.map { it.name })
    }

    @Test
    fun anExternalChangeWhileSetIsPendingPreservesItsAcceptedEffectWithoutConfirmingTheOldTuple() {
        val f = enabledFixture()
        val results = f.save(enabledValues(), changedDraft())
        f.readBatch()
        f.external(IMMUNITY, TdApi.OptionValueInteger(9000))
        f.acceptWrite()
        f.readBatch()
        val outcome = results.single()
        assertEquals(SettingsStoragePolicyStatus.INCOMPLETE, outcome.status)
        assertEquals(SettingsError.CONFLICT, outcome.error)
        assertTrue(outcome.steps.single().accepted)
        assertFalse(outcome.steps.single().confirmed)
        assertEquals(explicitNumber(9000), outcome.actual?.values?.immunitySeconds)
        assertEquals(false, outcome.actual?.values?.automatic?.raw)
        assertEquals(listOf(AUTOMATIC), f.writes.map { it.name })
        assertTrue(outcome.refreshRequired)
        assertTrue(f.pending.isEmpty())
    }

    @Test
    fun unrelatedUpdatesDoNotInvalidatePolicyReadsAndRetirementDetachesItsObserver() {
        val f = Fixture()
        var changes = 0
        f.api.observe { changes++ }
        val results = f.load()
        f.api.accept(TdApi.Ok())
        f.api.accept(TdApi.UpdateOption("unrelated_option", TdApi.OptionValueInteger(1)))
        f.readBatch()
        assertTrue(results.single().complete)
        assertEquals(0, changes)
        f.api.retire()
        f.api.accept(TdApi.UpdateOption(SIZE, TdApi.OptionValueInteger(1)))
        assertEquals(0, changes)
        val before = f.calls.size
        val after = f.load().single()
        assertEquals(SettingsStoragePolicyField.entries.associateWith { SettingsError.NOT_READY }, after.errors)
        assertEquals(before, f.calls.size)
    }

    @Test
    fun closingTheOwnerKeepsTheFlightUntilItsOriginalCallbackAndANewOwnerMustReadItsOwnBaseline() {
        val f = Fixture()
        var oldOwner = true
        val old = f.save(draft = SettingsStoragePolicyDraft(sizeKiB = 204800), active = { oldOwner })
        f.readBatch()
        val pendingOldSet = f.take()
        oldOwner = false
        val denied = f.save(draft = SettingsStoragePolicyDraft(sizeKiB = 307200))
        assertEquals(SettingsStoragePolicyStatus.REJECTED, denied.single().status)
        assertEquals(6, f.calls.size)
        val originalCommand = assertIs<TdApi.SetOption>(pendingOldSet.command)
        f.raw[originalCommand.name] = originalCommand.value
        pendingOldSet.reply(TdApi.Ok())
        assertEquals(SettingsStoragePolicyStatus.INCOMPLETE, old.single().status)
        assertTrue(old.single().steps.single().accepted)
        assertFalse(old.single().steps.single().confirmed)
        assertNull(old.single().actual)
        assertTrue(old.single().refreshRequired)
        assertTrue(f.pending.isEmpty())
        val freshExpected = defaultValues().copy(sizeKiB = explicitNumber(204800))
        val next = f.save(freshExpected, SettingsStoragePolicyDraft(sizeKiB = 307200))
        assertIs<TdApi.GetOption>(f.pending.single().command)
        assertTrue(next.isEmpty())
        f.readBatch()
        val newSet = f.pending.single()
        pendingOldSet.reply(TdApi.Ok()); pendingOldSet.fail()
        assertEquals(1, old.size)
        assertTrue(next.isEmpty())
        assertSame(newSet, f.pending.single())
        f.acceptWrite(); f.readBatch()
        assertEquals(SettingsStoragePolicyStatus.CONFIRMED, next.single().status)
        assertEquals(307200L, next.single().actual?.values?.sizeKiB?.raw)
    }

    @Test
    fun losingReadyDuringTheInitialReadOrAnAcceptedWriteCannotSendAnyFollowOnRequest() {
        val read = Fixture()
        val readResults = read.save(draft = SettingsStoragePolicyDraft(sizeKiB = 204800, automatic = true))
        read.ready = false
        read.replyGet()
        assertEquals(1, read.calls.size)
        assertEquals(SettingsStoragePolicyStatus.REJECTED, readResults.single().status)
        assertTrue(read.writes.isEmpty())
        val write = Fixture()
        val writeResults = write.save(draft = SettingsStoragePolicyDraft(sizeKiB = 204800, automatic = true))
        write.readBatch()
        write.ready = false
        write.acceptWrite()
        assertEquals(6, write.calls.size)
        assertEquals(SettingsStoragePolicyStatus.INCOMPLETE, writeResults.single().status)
        assertTrue(writeResults.single().steps.single().accepted)
        assertTrue(writeResults.single().refreshRequired)
        assertEquals(listOf(SIZE), write.writes.map { it.name })
        assertTrue(write.pending.isEmpty())
    }

    @Test
    fun duplicateResultsCannotRepeatReadsWritesProgressOrTerminalCompletion() {
        val f = Fixture()
        val progress = mutableListOf<List<SettingsStoragePolicyStepResult>>()
        val results = f.save(draft = SettingsStoragePolicyDraft(sizeKiB = 204800), progress = progress::add)
        val first = f.replyGet()
        first.reply(TdApi.OptionValueEmpty()); first.fail()
        assertEquals(2, f.calls.size)
        f.readBatch(from = 1)
        val write = f.acceptWrite()
        write.reply(TdApi.Ok()); write.fail()
        assertEquals(7, f.calls.size)
        f.readBatch()
        write.reply(TdApi.Ok()); first.fail()
        assertEquals(1, results.size)
        assertEquals(SettingsStoragePolicyStatus.CONFIRMED, results.single().status)
        assertEquals(2, progress.size)
        assertEquals(11, f.calls.size)
    }

    @Test
    fun lockingAtEveryWriteKeepsTheTrueAcceptanceAndRecordsEveryRemainingStepWithoutAnotherRpc() {
        for (index in 0..3) {
            val f = enabledFixture()
            val results = f.save(enabledValues(), changedDraft())
            f.readBatch()
            repeat(index) { f.acceptWrite(); f.readBatch() }
            val call = f.take()
            val command = assertIs<TdApi.SetOption>(call.command)
            val before = f.calls.size
            f.ready = false
            f.raw[command.name] = command.value
            call.reply(TdApi.Ok())
            val outcome = results.single()
            assertEquals(SettingsStoragePolicyStatus.INCOMPLETE, outcome.status)
            assertEquals(index + 1, outcome.steps.size)
            assertTrue(outcome.steps.take(index).all { it.confirmed })
            assertEquals(SettingsStoragePolicyReceipt.ACCEPTED, outcome.steps.last().receipt)
            assertFalse(outcome.steps.last().confirmed)
            assertEquals(allSteps().drop(index + 1), outcome.remainingSteps.map { it.step })
            assertTrue(outcome.remainingSteps.all { it.receipt == SettingsStoragePolicyReceipt.NOT_EXECUTED })
            assertFalse(outcome.physicalPending)
            assertNull(outcome.actual)
            assertEquals(before, f.calls.size)
            f.ready = true
            call.reply(TdApi.Error(403, "late private duplicate")); call.fail()
            assertEquals(1, results.size)
            assertEquals(before, f.calls.size, "Unlock is never authority to resume the old write plan.")
        }
    }

    @Test
    fun lockingAtEveryWritePreservesRealRejectionInsteadOfReplacingItWithNotReady() {
        for (index in 0..3) {
            val f = enabledFixture()
            val results = f.save(enabledValues(), changedDraft())
            f.readBatch()
            repeat(index) { f.acceptWrite(); f.readBatch() }
            val call = f.take()
            val before = f.calls.size
            f.ready = false
            call.reply(TdApi.Error(403, "private original denial"))
            val outcome = results.single()
            assertEquals(SettingsError.FORBIDDEN, outcome.error)
            assertEquals(SettingsStoragePolicyReceipt.REJECTED, outcome.steps.last().receipt)
            assertFalse(outcome.steps.last().accepted)
            assertEquals(3 - index, outcome.remainingSteps.size)
            assertEquals(before, f.calls.size)
            assertFalse(outcome.toString().contains("private"))
        }
    }

    @Test
    fun indeterminateAtEveryWriteRetainsTheSlotAndLateTerminalOnlySettlesTheOriginalOperation() {
        for (index in 0..3) for (accepted in listOf(false, true)) {
            val f = enabledFixture()
            val results = mutableListOf<SettingsStoragePolicySaveOutcome>()
            val settled = mutableListOf<SettingsStoragePolicySaveOutcome>()
            f.api.saveOwned(enabledValues(), changedDraft(), { f.ready }, {}, results::add, settled::add)
            f.readBatch()
            repeat(index) { f.acceptWrite(); f.readBatch() }
            val call = f.take()
            val command = assertIs<TdApi.SetOption>(call.command)
            val before = f.calls.size
            f.ready = false
            call.fail()
            assertTrue(results.single().physicalPending)
            assertEquals(SettingsStoragePolicyReceipt.INDETERMINATE, results.single().steps.last().receipt)
            assertEquals(3 - index, results.single().remainingSteps.size)
            f.ready = true
            assertEquals(SettingsStoragePolicyStatus.REJECTED,
                f.save(enabledValues(), changedDraft()).single().status)
            assertEquals(before, f.calls.size)
            f.ready = false // A second lock cannot relabel the real receipt or authorize another RPC.
            if (accepted) f.raw[command.name] = command.value
            call.reply(if (accepted) TdApi.Ok() else TdApi.Error(403, "private native denial"))
            val terminal = settled.single()
            assertFalse(terminal.physicalPending)
            assertEquals(if (accepted) SettingsStoragePolicyReceipt.ACCEPTED else SettingsStoragePolicyReceipt.REJECTED,
                terminal.steps.last().receipt)
            assertNull(terminal.actual)
            assertEquals(before, f.calls.size)
            call.reply(TdApi.Ok()); call.fail()
            assertEquals(1, results.size)
            assertEquals(1, settled.size)
            assertEquals(before, f.calls.size)
        }
    }

    @Test
    fun definiteGateRejectionIsNotDispatchedAndDoesNotOccupyThePhysicalSlot() {
        val f = enabledFixture()
        val results = f.save(enabledValues(), changedDraft())
        f.rejectNextWrite = true
        f.readBatch()
        val outcome = results.single()
        assertEquals(SettingsStoragePolicyStatus.REJECTED, outcome.status)
        assertEquals(SettingsStoragePolicyReceipt.NOT_DISPATCHED, outcome.steps.single().receipt)
        assertEquals(allSteps().drop(1), outcome.remainingSteps.map { it.step })
        assertFalse(outcome.physicalPending)
        assertTrue(f.writes.isEmpty())
        val next = f.save(enabledValues(), changedDraft())
        assertTrue(next.isEmpty())
        assertIs<TdApi.GetOption>(f.pending.single().command)
    }

    @Test
    fun lockDuringEveryReadbackStopsBeforeAnyFurtherReadOrWrite() {
        for (index in 0..3) {
            val f = enabledFixture()
            val results = f.save(enabledValues(), changedDraft())
            f.readBatch()
            repeat(index) { f.acceptWrite(); f.readBatch() }
            f.acceptWrite()
            val before = f.calls.size
            f.ready = false
            f.replyGet()
            val outcome = results.single()
            assertEquals(SettingsStoragePolicyReceipt.ACCEPTED, outcome.steps.last().receipt)
            assertFalse(outcome.steps.last().confirmed)
            assertEquals(before, f.calls.size)
            assertTrue(f.pending.isEmpty())
            assertEquals(3 - index, outcome.remainingSteps.size)
        }
    }

    @Test
    fun synchronousCallbacksMarkOnlyTheExactFinalWriteAndLockStopsAllFollowOnReads() {
        var active = true
        val calls = mutableListOf<TdApi.Function<*>>()
        val outcomes = mutableListOf<SettingsStoragePolicySaveOutcome>()
        val api = OfficialTdSettingsStoragePolicy { command, reply, _ ->
            calls += command
            assertEquals(command is TdApi.SetOption, SettingsSpecialMutationContext.marks(command))
            if (command is TdApi.SetOption) { active = false; reply(TdApi.Ok()) }
            else reply(TdApi.OptionValueEmpty())
        }
        api.save(defaultValues(), SettingsStoragePolicyDraft(sizeKiB = 204800, automatic = true),
            { active }, {}, outcomes::add)
        assertEquals(6, calls.size)
        assertEquals(SettingsStoragePolicyReceipt.ACCEPTED, outcomes.single().steps.single().receipt)
        assertEquals(listOf(SettingsStoragePolicyStep.ENABLE_AUTOMATIC), outcomes.single().remainingSteps.map { it.step })
    }

    private class Call(val command: TdApi.Function<*>, val reply: (TdApi.Object) -> Unit, val fail: () -> Unit)

    private class Fixture(initial: Map<String, TdApi.OptionValue> = emptyMap()) {
        val raw = linkedMapOf<String, TdApi.OptionValue>().apply {
            KEYS.forEach { put(it, TdApi.OptionValueEmpty()) }
            putAll(initial)
        }
        val calls = mutableListOf<Call>()
        val pending = ArrayDeque<Call>()
        var ready = true
        var rejectNextWrite = false
        // The blocked gate supplies exact-request non-dispatch evidence; only
        // entering the raw sender records a physical request in this fixture.
        private val deniedWriteSend = TdUserInteractionGate().apply {
            bindLocalWitness { LocalLockWitness(1, true) }
        }.guard { command, reply, fail -> Call(command, reply, fail).also { calls += it; pending.addLast(it) } }
        val api = OfficialTdSettingsStoragePolicy { command, reply, fail ->
            if (command is TdApi.SetOption && rejectNextWrite) {
                rejectNextWrite = false
                deniedWriteSend(command, reply, fail)
            } else Call(command, reply, fail).also { calls += it; pending.addLast(it) }
        }
        val gets get() = calls.mapNotNull { it.command as? TdApi.GetOption }
        val writes get() = calls.mapNotNull { it.command as? TdApi.SetOption }
        fun take(): Call = pending.removeFirst()
        fun load(): MutableList<SettingsStoragePolicyRead> = mutableListOf<SettingsStoragePolicyRead>().also {
            api.load({ ready }, it::add)
        }
        fun save(expected: SettingsStoragePolicyValues = defaultValues(), draft: SettingsStoragePolicyDraft,
            progress: (List<SettingsStoragePolicyStepResult>) -> Unit = {}, active: () -> Boolean = { ready },
        ): MutableList<SettingsStoragePolicySaveOutcome> = mutableListOf<SettingsStoragePolicySaveOutcome>().also {
            api.save(expected, draft, active, progress, it::add)
        }
        fun replyGet(overrides: Map<String, TdApi.Object> = emptyMap()): Call {
            val call = take()
            val command = assertIs<TdApi.GetOption>(call.command)
            call.reply(overrides[command.name] ?: raw.getValue(command.name))
            return call
        }
        fun readBatch(overrides: Map<String, TdApi.Object> = emptyMap(), from: Int = 0) {
            KEYS.drop(from).forEach { key ->
                assertEquals(key, assertIs<TdApi.GetOption>(pending.first().command).name)
                replyGet(overrides)
            }
        }
        fun acceptWrite(): Call {
            val call = take()
            val command = assertIs<TdApi.SetOption>(call.command)
            raw[command.name] = command.value
            // TDLib publishes its own option update before completing SetOption.
            api.accept(TdApi.UpdateOption(command.name, command.value))
            call.reply(TdApi.Ok())
            return call
        }
        fun external(key: String, value: TdApi.OptionValue) {
            raw[key] = value
            api.accept(TdApi.UpdateOption(key, value))
        }
    }

    private companion object {
        const val SIZE = "storage_max_files_size"
        const val TIME = "storage_max_time_from_last_access"
        const val AUTOMATIC = "use_storage_optimizer"
        const val COUNT = "storage_max_file_count"
        const val IMMUNITY = "storage_immunity_delay"
        val KEYS = listOf(SIZE, TIME, AUTOMATIC, COUNT, IMMUNITY)
        fun defaultNumber(value: Long) = SettingsStoragePolicyNumber(effective = value, source = SettingsStoragePolicySource.DEFAULT)
        fun explicitNumber(value: Long) = SettingsStoragePolicyNumber(value, value, SettingsStoragePolicySource.EXPLICIT)
        fun explicitSwitch(value: Boolean) = SettingsStoragePolicySwitch(value, value, SettingsStoragePolicySource.EXPLICIT)
        fun defaultValues() = SettingsStoragePolicyValues(defaultNumber(102400), defaultNumber(82800),
            SettingsStoragePolicySwitch(effective = false, source = SettingsStoragePolicySource.DEFAULT),
            defaultNumber(40000), defaultNumber(3600))
        fun enabledValues() = defaultValues().copy(automatic = explicitSwitch(true))
        fun enabledFixture() = Fixture(mapOf(AUTOMATIC to TdApi.OptionValueBoolean(true)))
        fun changedDraft() = SettingsStoragePolicyDraft(sizeKiB = 204800, unusedSeconds = 86400, automatic = true)
        fun allSteps() = listOf(SettingsStoragePolicyStep.DISABLE_AUTOMATIC, SettingsStoragePolicyStep.SIZE,
            SettingsStoragePolicyStep.UNUSED_TIME, SettingsStoragePolicyStep.ENABLE_AUTOMATIC)
        fun explicitNative(number: Long, automatic: Boolean): Map<String, TdApi.OptionValue> = KEYS.associateWith {
            if (it == AUTOMATIC) TdApi.OptionValueBoolean(automatic) else TdApi.OptionValueInteger(number)
        }
        fun defaultFor(key: String) = when (key) {
            SIZE -> 102400L
            TIME -> 82800L
            COUNT -> 40000L
            IMMUNITY -> 3600L
            else -> error("Not a numeric policy field")
        }
        fun withExplicitDefault(values: SettingsStoragePolicyValues, key: String) = when (key) {
            SIZE -> values.copy(sizeKiB = explicitNumber(102400))
            TIME -> values.copy(unusedSeconds = explicitNumber(82800))
            AUTOMATIC -> values.copy(automatic = explicitSwitch(false))
            COUNT -> values.copy(fileCount = explicitNumber(40000))
            IMMUNITY -> values.copy(immunitySeconds = explicitNumber(3600))
            else -> error("Unknown policy field")
        }
        fun option(field: SettingsStoragePolicyField) = when (field) {
            SettingsStoragePolicyField.SIZE -> SIZE
            SettingsStoragePolicyField.UNUSED_TIME -> TIME
            SettingsStoragePolicyField.AUTOMATIC -> AUTOMATIC
            SettingsStoragePolicyField.FILE_COUNT -> COUNT
            SettingsStoragePolicyField.IMMUNITY -> IMMUNITY
        }
        fun number(values: SettingsStoragePolicyValues, field: SettingsStoragePolicyField) = when (field) {
            SettingsStoragePolicyField.SIZE -> values.sizeKiB
            SettingsStoragePolicyField.UNUSED_TIME -> values.unusedSeconds
            SettingsStoragePolicyField.FILE_COUNT -> values.fileCount
            SettingsStoragePolicyField.IMMUNITY -> values.immunitySeconds
            SettingsStoragePolicyField.AUTOMATIC -> error("Not a numeric policy field")
        }
        fun assertNativeValue(expected: Any, actual: TdApi.OptionValue) {
            when (expected) {
                is Boolean -> assertEquals(expected, assertIs<TdApi.OptionValueBoolean>(actual).value)
                is Long -> assertEquals(expected, assertIs<TdApi.OptionValueInteger>(actual).value)
                else -> error("Unexpected expected policy value")
            }
        }
        fun assertOnlyOptions(fixture: Fixture) {
            assertTrue(fixture.calls.all { it.command is TdApi.GetOption || it.command is TdApi.SetOption },
                "Policy changes must never scan files, start OptimizeStorage or perform an account mutation.")
        }
    }
}
