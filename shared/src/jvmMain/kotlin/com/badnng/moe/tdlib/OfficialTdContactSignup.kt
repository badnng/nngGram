package com.badnng.moe.tdlib

import com.badnng.moe.settings.*
import org.drinkless.tdlib.TdApi
import java.util.concurrent.atomic.AtomicBoolean

interface TdContactSignupSession {
    fun observe(observer: (() -> Unit)?)
    fun load(active: () -> Boolean, result: (SettingsResult<ContactSignupValue>) -> Unit)
    fun save(expected: ContactSignupValue, desired: Boolean, active: () -> Boolean, result: (ContactSignupSaveResult) -> Unit)
    fun retire() {}
}

/** Public GetOption can return the local value even when its internal server query failed. */
internal class OfficialTdContactSignup(
    private val send: (TdApi.Function<*>, (TdApi.Object) -> Unit, () -> Unit) -> Unit,
) : TdContactSignupSession {
    private val alive = AtomicBoolean(true)
    private val stateLock = Any()
    private var observed = false
    private var latest: ContactSignupValue? = null
    private var revision = 0L
    @Volatile private var observer: (() -> Unit)? = null
    override fun observe(observer: (() -> Unit)?) { if (alive.get()) this.observer = observer }
    override fun retire() { alive.set(false); observer = null }
    fun accept(update: TdApi.Object) {
        if (!alive.get() || update !is TdApi.UpdateOption || update.name != OPTION) return
        val value = decode(update.value)
        val changed = synchronized(stateLock) {
            if (value != null && observed && latest == value) false
            else { observed = true; latest = value; revision++; true }
        }
        // Never call consumers under the native state lock. Unknown values invalidate conservatively.
        if (changed && alive.get()) observer?.invoke()
    }

    override fun load(active: () -> Boolean, result: (SettingsResult<ContactSignupValue>) -> Unit) {
        val started = synchronized(stateLock) { revision }
        request(TdApi.GetOption(OPTION), active, { response ->
            val value = decode(response)
            if (value == null) SettingsResult(error = settingsError(response))
            else synchronized(stateLock) {
                if (revision != started && latest != value) SettingsResult(error = SettingsError.CONFLICT)
                else {
                    if (!observed || latest != value) { observed = true; latest = value; revision++ }
                    SettingsResult(value)
                }
            }
        }, result)
    }

    private fun decode(response: TdApi.Object?): ContactSignupValue? = when (response) {
        is TdApi.OptionValueBoolean -> ContactSignupValue(!response.value, ContactSignupValueSource.EXPLICIT)
        is TdApi.OptionValueEmpty -> ContactSignupValue(true, ContactSignupValueSource.DEFAULT)
        else -> null
    }

    override fun save(expected: ContactSignupValue, desired: Boolean, active: () -> Boolean,
        result: (ContactSignupSaveResult) -> Unit,
    ) {
        load(active) fresh@ { fresh ->
            val value = fresh.value ?: run { result(ContactSignupSaveResult(desired, error = fresh.error)); return@fresh }
            if (value.enabled != expected.enabled) {
                result(ContactSignupSaveResult(desired, actual = value, error = SettingsError.CONFLICT)); return@fresh
            }
            if (value.enabled == desired) { result(ContactSignupSaveResult(desired, actual = value)); return@fresh }
            request(TdApi.SetOption(OPTION, TdApi.OptionValueBoolean(!desired)), active,
                { if (it is TdApi.Ok) SettingsResult(Unit) else SettingsResult(error = settingsError(it)) }, accepted@ { accepted ->
                    if (accepted.value == null) { result(ContactSignupSaveResult(desired, error = accepted.error)); return@accepted }
                    // Final acceptance survives the loss of page authority; no old continuation is dispatched.
                    val receipt = SettingsSpecialMutationContext.receipt()
                    if (!current(active)) {
                        result(ContactSignupSaveResult(desired, locallyAccepted = true, error = SettingsError.NOT_READY))
                    } else load(active) { read -> SettingsSpecialMutationContext.withReceipt(receipt) {
                        result(ContactSignupSaveResult(desired, locallyAccepted = true, actual = read.value, error = read.error))
                    } }
                }, preserveAcceptance = true)
        }
    }

    private fun current(active: () -> Boolean) = alive.get() && runCatching(active).getOrDefault(false)
    private fun <T> request(command: TdApi.Function<*>, active: () -> Boolean, map: (TdApi.Object) -> SettingsResult<T>,
        result: (SettingsResult<T>) -> Unit, preserveAcceptance: Boolean = false,
    ) {
        val once = AtomicBoolean()
        fun finish(value: SettingsResult<T>) {
            if (preserveAcceptance && SettingsSpecialMutationContext.receipt()?.outcome == SettingsSpecialWriteOutcome.INDETERMINATE) {
                if (!once.get()) result(value)
            } else if (once.compareAndSet(false, true)) result(value)
        }
        if (!current(active)) { finish(SettingsResult(error = SettingsError.NOT_READY)); return }
        try {
            val dispatch: (TdApi.Function<*>, (TdApi.Object) -> Unit, () -> Unit) -> Unit =
            if (preserveAcceptance) { command, result, failed -> SettingsSpecialMutationContext.write(command, send, result, failed) } else send
            dispatch(command, received@ { response ->
                if (!once.compareAndSet(false, true)) return@received
                if (!current(active) && !preserveAcceptance) {
                    result(SettingsResult(error = SettingsError.NOT_READY)); return@received
                }
                val mapped = runCatching { map(response) }.getOrElse { SettingsResult(error = SettingsError.REQUEST_FAILED) }
                result(when {
                    preserveAcceptance -> mapped
                    !current(active) -> SettingsResult(error = SettingsError.NOT_READY)
                    else -> mapped
                })
            }, { finish(SettingsResult(error = if (current(active)) SettingsError.REQUEST_FAILED else SettingsError.NOT_READY)) })
        } catch (_: Exception) {
            finish(SettingsResult(error = if (current(active)) SettingsError.REQUEST_FAILED else SettingsError.NOT_READY))
        }
    }

    private companion object { const val OPTION = "disable_contact_registered_notifications" }
}
