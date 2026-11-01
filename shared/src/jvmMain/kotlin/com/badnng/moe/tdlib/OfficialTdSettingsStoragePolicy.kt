package com.badnng.moe.tdlib

import com.badnng.moe.settings.*
import org.drinkless.tdlib.TdApi
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

interface TdSettingsStoragePolicySession {
    fun observe(observer: (() -> Unit)?) {}
    fun load(active: () -> Boolean, result: (SettingsStoragePolicyRead) -> Unit)
    fun save(expected: SettingsStoragePolicyValues, draft: SettingsStoragePolicyDraft, active: () -> Boolean,
        progress: (List<SettingsStoragePolicyStepResult>) -> Unit, result: (SettingsStoragePolicySaveOutcome) -> Unit)
    /** A late physical terminal after an indeterminate logical completion settles only this save. */
    fun saveOwned(expected: SettingsStoragePolicyValues, draft: SettingsStoragePolicyDraft, active: () -> Boolean,
        progress: (List<SettingsStoragePolicyStepResult>) -> Unit, result: (SettingsStoragePolicySaveOutcome) -> Unit,
        settled: (SettingsStoragePolicySaveOutcome) -> Unit,
    ) = save(expected, draft, active, progress, result)
    fun retire() {}
}

data class SettingsStoragePolicySaveOutcome(
    val status: SettingsStoragePolicyStatus,
    val steps: List<SettingsStoragePolicyStepResult> = emptyList(),
    val actual: SettingsStoragePolicyRead? = null,
    val error: SettingsError? = null,
    val refreshRequired: Boolean = false,
    val remainingSteps: List<SettingsStoragePolicyStepResult> = emptyList(),
    val physicalPending: Boolean = false,
)

/** One instance owns the bounded option sequence. It never requests a scan, deletion or rollback. */
internal class OfficialTdSettingsStoragePolicy(
    private val send: (TdApi.Function<*>, (TdApi.Object) -> Unit, () -> Unit) -> Unit,
) : TdSettingsStoragePolicySession {
    private val alive = AtomicBoolean(true)
    private val writing = AtomicBoolean()
    private val updates = AtomicLong()
    private val observer = AtomicReference<(() -> Unit)?>(null)

    override fun observe(observer: (() -> Unit)?) { this.observer.set(observer) }
    override fun retire() { alive.set(false); observer.set(null) }
    fun accept(update: TdApi.Object) {
        if (alive.get() && update is TdApi.UpdateOption && update.name in OPTIONS.values) {
            updates.incrementAndGet()
            observer.get()?.invoke()
        }
    }

    override fun load(active: () -> Boolean, result: (SettingsStoragePolicyRead) -> Unit) =
        read(active) { value, _ -> result(value) }

    private fun read(active: () -> Boolean, result: (SettingsStoragePolicyRead, Long) -> Unit) {
        val start = updates.get()
        var values = SettingsStoragePolicyValues()
        val errors = linkedMapOf<SettingsStoragePolicyField, SettingsError>()
        val fields = SettingsStoragePolicyField.entries
        fun finish() {
            val revision = updates.get()
            if (revision != start) fields.forEach { errors[it] = SettingsError.CONFLICT }
            result(SettingsStoragePolicyRead(values, errors.toMap()), revision)
        }
        fun next(index: Int) {
            if (index == fields.size) { finish(); return }
            val field = fields[index]
            if (!current(active)) {
                fields.drop(index).forEach { errors[it] = SettingsError.NOT_READY }
                finish(); return
            }
            request(TdApi.GetOption(OPTIONS.getValue(field)), active) { response ->
                if (!current(active)) {
                    fields.drop(index).forEach { errors[it] = SettingsError.NOT_READY }
                    finish()
                } else {
                    val mapped = map(field, response)
                    if (mapped == null) errors[field] = response?.let(::settingsError) ?: SettingsError.NOT_READY
                    else values = when (field) {
                        SettingsStoragePolicyField.SIZE -> values.copy(sizeKiB = mapped.first!!)
                        SettingsStoragePolicyField.UNUSED_TIME -> values.copy(unusedSeconds = mapped.first!!)
                        SettingsStoragePolicyField.AUTOMATIC -> values.copy(automatic = mapped.second!!)
                        SettingsStoragePolicyField.FILE_COUNT -> values.copy(fileCount = mapped.first!!)
                        SettingsStoragePolicyField.IMMUNITY -> values.copy(immunitySeconds = mapped.first!!)
                    }
                    next(index + 1)
                }
            }
        }
        next(0)
    }

    override fun save(expected: SettingsStoragePolicyValues, draft: SettingsStoragePolicyDraft, active: () -> Boolean,
        progress: (List<SettingsStoragePolicyStepResult>) -> Unit, result: (SettingsStoragePolicySaveOutcome) -> Unit,
    ) = saveOwned(expected, draft, active, progress, result, {})

    override fun saveOwned(expected: SettingsStoragePolicyValues, draft: SettingsStoragePolicyDraft, active: () -> Boolean,
        progress: (List<SettingsStoragePolicyStepResult>) -> Unit, result: (SettingsStoragePolicySaveOutcome) -> Unit,
        settled: (SettingsStoragePolicySaveOutcome) -> Unit,
    ) {
        if (!expected.known || !draft.valid || !current(active)) {
            result(SettingsStoragePolicySaveOutcome(SettingsStoragePolicyStatus.REJECTED,
                error = if (!draft.valid) SettingsError.INVALID_VALUE else SettingsError.NOT_READY)); return
        }
        if (!writing.compareAndSet(false, true)) {
            result(SettingsStoragePolicySaveOutcome(SettingsStoragePolicyStatus.REJECTED, error = SettingsError.NOT_READY)); return
        }
        val completed = AtomicBoolean()
        val steps = mutableListOf<SettingsStoragePolicyStepResult>()
        var plan = emptyList<SettingsStoragePolicyStep>()
        fun remaining() = plan.drop(steps.size).map {
            SettingsStoragePolicyStepResult(it, receipt = SettingsStoragePolicyReceipt.NOT_EXECUTED)
        }
        fun finish(status: SettingsStoragePolicyStatus, actual: SettingsStoragePolicyRead? = null,
            error: SettingsError? = null, refreshRequired: Boolean = false, physicalPending: Boolean = false,
        ) {
            if (!completed.compareAndSet(false, true)) return
            // An exception/failed callback after send entry does not release the physical slot.
            if (!physicalPending) writing.set(false)
            result(SettingsStoragePolicySaveOutcome(status, steps.toList(), actual, error, refreshRequired,
                remaining(), physicalPending))
        }
        fun stopped() = finish(if (steps.any { it.accepted || it.receipt == SettingsStoragePolicyReceipt.INDETERMINATE })
                SettingsStoragePolicyStatus.INCOMPLETE else SettingsStoragePolicyStatus.REJECTED,
            error = SettingsError.NOT_READY, refreshRequired = steps.any { it.accepted || it.receipt == SettingsStoragePolicyReceipt.INDETERMINATE })
        fun failed(error: SettingsError) {
            if (!current(active)) { stopped(); return }
            read(active) { actual, _ -> finish(SettingsStoragePolicyStatus.INCOMPLETE, actual, error, refreshRequired = true) }
        }
        read(active) initial@ { initial, initialRevision ->
            if (!current(active)) { stopped(); return@initial }
            if (!initial.complete) {
                finish(SettingsStoragePolicyStatus.CONFLICT, initial, initial.errors.values.firstOrNull() ?: SettingsError.NOT_READY, true)
                return@initial
            }
            if (initial.values != expected) {
                finish(SettingsStoragePolicyStatus.CONFLICT, initial, SettingsError.CONFLICT, true); return@initial
            }
            val size = draft.sizeKiB?.takeIf { it != expected.sizeKiB.effective }
            val time = draft.unusedSeconds?.takeIf { it != expected.unusedSeconds.effective }
            val automatic = draft.automatic ?: expected.automatic.effective!!
            val parametersChanged = size != null || time != null
            plan = buildList {
                if (expected.automatic.effective == true && (parametersChanged || !automatic)) add(SettingsStoragePolicyStep.DISABLE_AUTOMATIC)
                if (size != null) add(SettingsStoragePolicyStep.SIZE)
                if (time != null) add(SettingsStoragePolicyStep.UNUSED_TIME)
                if (automatic && (expected.automatic.effective != true || parametersChanged)) add(SettingsStoragePolicyStep.ENABLE_AUTOMATIC)
            }
            if (plan.isEmpty()) { finish(SettingsStoragePolicyStatus.UNCHANGED, initial); return@initial }
            fun next(index: Int, baseline: SettingsStoragePolicyValues, revision: Long, actual: SettingsStoragePolicyRead) {
                if (!current(active)) { stopped(); return }
                if (index == plan.size) { finish(SettingsStoragePolicyStatus.CONFIRMED, actual); return }
                if (updates.get() != revision) { failed(SettingsError.CONFLICT); return }
                val step = plan[index]
                val field = when (step) {
                    SettingsStoragePolicyStep.SIZE -> SettingsStoragePolicyField.SIZE
                    SettingsStoragePolicyStep.UNUSED_TIME -> SettingsStoragePolicyField.UNUSED_TIME
                    else -> SettingsStoragePolicyField.AUTOMATIC
                }
                val value: TdApi.OptionValue = when (step) {
                    SettingsStoragePolicyStep.SIZE -> TdApi.OptionValueInteger(size!!)
                    SettingsStoragePolicyStep.UNUSED_TIME -> TdApi.OptionValueInteger(time!!)
                    SettingsStoragePolicyStep.DISABLE_AUTOMATIC -> TdApi.OptionValueBoolean(false)
                    SettingsStoragePolicyStep.ENABLE_AUTOMATIC -> TdApi.OptionValueBoolean(true)
                }
                val after = when (step) {
                    SettingsStoragePolicyStep.SIZE -> baseline.copy(sizeKiB = explicit(size!!))
                    SettingsStoragePolicyStep.UNUSED_TIME -> baseline.copy(unusedSeconds = explicit(time!!))
                    SettingsStoragePolicyStep.DISABLE_AUTOMATIC -> baseline.copy(automatic = explicit(false))
                    SettingsStoragePolicyStep.ENABLE_AUTOMATIC -> baseline.copy(automatic = explicit(true))
                }
                val terminal = AtomicBoolean()
                val uncertain = AtomicBoolean()
                val callbackLock = Any()
                fun record(receipt: SettingsStoragePolicyReceipt, error: SettingsError? = null) {
                    val value = SettingsStoragePolicyStepResult(step,
                        accepted = receipt == SettingsStoragePolicyReceipt.ACCEPTED, error = error, receipt = receipt)
                    if (steps.size == index) steps += value else steps[index] = value
                }
                fun notDispatched() {
                    synchronized(callbackLock) {
                        if (!terminal.compareAndSet(false, true)) return
                        record(SettingsStoragePolicyReceipt.NOT_DISPATCHED, SettingsError.NOT_READY)
                        progress(steps.toList())
                        stopped()
                    }
                }
                fun indeterminate() {
                    synchronized(callbackLock) {
                        if (terminal.get() || !uncertain.compareAndSet(false, true)) return
                        record(SettingsStoragePolicyReceipt.INDETERMINATE, SettingsError.REQUEST_FAILED)
                        progress(steps.toList())
                        finish(SettingsStoragePolicyStatus.INCOMPLETE, error = SettingsError.REQUEST_FAILED,
                            refreshRequired = true, physicalPending = true)
                    }
                }
                fun written(response: TdApi.Object) {
                    synchronized(callbackLock) {
                        if (!terminal.compareAndSet(false, true)) return
                        val receipt = when (response) {
                            is TdApi.Ok -> SettingsStoragePolicyReceipt.ACCEPTED
                            is TdApi.Error -> SettingsStoragePolicyReceipt.REJECTED
                            else -> SettingsStoragePolicyReceipt.INDETERMINATE
                        }
                        val error = if (response is TdApi.Ok) null else settingsError(response)
                        record(receipt, error)
                        if (uncertain.get()) {
                            // The logical completion already stopped this plan. A late true response
                            // releases its own slot; it never resumes a read, write or previous owner.
                            writing.set(false)
                            settled(SettingsStoragePolicySaveOutcome(SettingsStoragePolicyStatus.INCOMPLETE,
                                steps.toList(), error = error, refreshRequired = true, remainingSteps = remaining()))
                            return
                        }
                        progress(steps.toList())
                        if (response !is TdApi.Ok) {
                            if (!current(active)) {
                                finish(SettingsStoragePolicyStatus.INCOMPLETE, error = error, refreshRequired = true)
                            } else failed(error ?: SettingsError.REQUEST_FAILED)
                            return
                        }
                        if (!current(active)) { stopped(); return }
                        read(active) confirmed@ { fresh, freshRevision ->
                            if (!current(active)) { stopped(); return@confirmed }
                            if (!fresh.complete || fresh.values != after) {
                                val readError = fresh.errors.values.firstOrNull() ?: SettingsError.CONFLICT
                                steps[index] = steps[index].copy(error = readError)
                                progress(steps.toList())
                                finish(SettingsStoragePolicyStatus.INCOMPLETE, fresh, readError, true)
                            } else {
                                steps[index] = steps[index].copy(confirmed = true)
                                progress(steps.toList())
                                next(index + 1, after, freshRevision, fresh)
                            }
                        }
                    }
                }
                if (!current(active) || updates.get() != revision) { notDispatched(); return }
                // Scope exactly the final SetOption, never the prerequisite/readback reads.
                try {
                    SettingsSpecialMutationContext.write(TdApi.SetOption(OPTIONS.getValue(field), value), send, ::written) {
                        if (SettingsSpecialMutationContext.receipt()?.outcome == SettingsSpecialWriteOutcome.NOT_DISPATCHED)
                            notDispatched() else indeterminate()
                    }
                } catch (_: Exception) { indeterminate() }
            }
            next(0, expected, initialRevision, initial)
        }
    }

    private fun current(active: () -> Boolean) = alive.get() && active()
    private fun request(command: TdApi.Function<*>, active: () -> Boolean, result: (TdApi.Object?) -> Unit) {
        val once = AtomicBoolean()
        fun finish(value: TdApi.Object?) { if (once.compareAndSet(false, true)) result(value) }
        if (!current(active)) { finish(null); return }
        try { send(command, { finish(it) }, { finish(null) }) } catch (_: Exception) { finish(null) }
    }
    private fun map(field: SettingsStoragePolicyField, response: TdApi.Object?): Pair<SettingsStoragePolicyNumber?, SettingsStoragePolicySwitch?>? {
        if (field == SettingsStoragePolicyField.AUTOMATIC) return when (response) {
            is TdApi.OptionValueBoolean -> null to explicit(response.value)
            is TdApi.OptionValueEmpty -> null to SettingsStoragePolicySwitch(effective = false, source = SettingsStoragePolicySource.DEFAULT)
            else -> null
        }
        val number = when (response) {
            is TdApi.OptionValueInteger -> response.value.takeIf { it in 0..SETTINGS_STORAGE_POLICY_MAX }?.let(::explicit)
            is TdApi.OptionValueEmpty -> SettingsStoragePolicyNumber(effective = DEFAULTS.getValue(field), source = SettingsStoragePolicySource.DEFAULT)
            else -> null
        } ?: return null
        return number to null
    }
    private fun explicit(value: Long) = SettingsStoragePolicyNumber(value, value, SettingsStoragePolicySource.EXPLICIT)
    private fun explicit(value: Boolean) = SettingsStoragePolicySwitch(value, value, SettingsStoragePolicySource.EXPLICIT)

    private companion object {
        val OPTIONS = linkedMapOf(SettingsStoragePolicyField.SIZE to "storage_max_files_size",
            SettingsStoragePolicyField.UNUSED_TIME to "storage_max_time_from_last_access",
            SettingsStoragePolicyField.AUTOMATIC to "use_storage_optimizer", SettingsStoragePolicyField.FILE_COUNT to "storage_max_file_count",
            SettingsStoragePolicyField.IMMUNITY to "storage_immunity_delay")
        val DEFAULTS = mapOf(SettingsStoragePolicyField.SIZE to 102400L, SettingsStoragePolicyField.UNUSED_TIME to 82800L,
            SettingsStoragePolicyField.FILE_COUNT to 40000L, SettingsStoragePolicyField.IMMUNITY to 3600L)
    }
}
