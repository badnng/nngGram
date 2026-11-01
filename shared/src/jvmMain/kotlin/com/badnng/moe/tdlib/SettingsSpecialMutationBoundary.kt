package com.badnng.moe.tdlib

import com.badnng.moe.settings.SettingsError
import org.drinkless.tdlib.TdApi

/** A receipt is bookkeeping, never authority to dispatch another request or publish a page. */
internal enum class SettingsSpecialWriteOutcome { ACCEPTED, REJECTED, INDETERMINATE, NOT_DISPATCHED }

internal data class SettingsSpecialWriteReceipt(
    val outcome: SettingsSpecialWriteOutcome,
    val error: SettingsError? = null,
)

/** Injected by the engine; only private controller completion sites receive this capability. */
internal class SettingsSpecialMutationBoundary(
    private val post: (() -> Unit) -> Unit,
    private val authority: () -> (() -> Boolean) = { { true } },
    private val postReadback: ((() -> Boolean), () -> Unit) -> Unit = { current, work ->
        post { if (current()) work() }
    },
) {
    fun captureAuthority(): () -> Boolean = authority()
    fun enqueue(work: () -> Unit) = post(work)
    /** Only a new current page's read is allowed here, never a captured write or old UI callback. */
    fun readback(current: () -> Boolean, work: () -> Unit) = postReadback(current, work)
}

/** Exact native write sites opt in. Merely having a receipt cannot mark a different command. */
internal object SettingsSpecialMutationContext {
    internal class WriteScope internal constructor(val request: TdApi.Function<*>)
    private val command = ThreadLocal<WriteScope?>()
    private val callbackReceipt = ThreadLocal<SettingsSpecialWriteReceipt?>()
    private data class DispatchFailure(val scope: WriteScope, val receipt: SettingsSpecialWriteReceipt)
    private val dispatchFailure = ThreadLocal<DispatchFailure?>()
    internal fun dispatchFailureFor(scope: WriteScope): SettingsSpecialWriteReceipt? =
        dispatchFailure.get()?.takeIf { it.scope === scope }?.receipt

    fun receipt(): SettingsSpecialWriteReceipt? = callbackReceipt.get()
    internal fun scopeFor(request: TdApi.Function<*>): WriteScope? = command.get()?.takeIf { it.request === request }
    internal fun marks(request: TdApi.Function<*>): Boolean = scopeFor(request) != null

    fun <T> withReceipt(value: SettingsSpecialWriteReceipt?, work: () -> T): T {
        val previous = callbackReceipt.get()
        if (value == null) callbackReceipt.remove() else callbackReceipt.set(value)
        return try { work() } finally {
            if (previous == null) callbackReceipt.remove() else callbackReceipt.set(previous)
        }
    }

    internal fun withDispatchFailure(scope: WriteScope, value: SettingsSpecialWriteReceipt, work: () -> Unit) {
        val previous = dispatchFailure.get()
        dispatchFailure.set(DispatchFailure(scope, value))
        try { withReceipt(value, work) } finally {
            if (previous == null) dispatchFailure.remove() else dispatchFailure.set(previous)
        }
    }

    /** Captures receipt metadata only; no witness, page owner or dispatch marking is captured. */
    fun capture(): ((() -> Unit) -> Unit) {
        val value = receipt()
        return { work -> withReceipt(value, work) }
    }

    fun <T> without(work: () -> T): T {
        val previous = command.get()
        val previousFailure = dispatchFailure.get()
        command.remove(); dispatchFailure.remove()
        return try { withReceipt(null, work) } finally {
            if (previous == null) command.remove() else command.set(previous)
            if (previousFailure == null) dispatchFailure.remove() else dispatchFailure.set(previousFailure)
        }
    }

    fun write(request: TdApi.Function<*>,
        send: (TdApi.Function<*>, (TdApi.Object) -> Unit, () -> Unit) -> Unit,
        result: (TdApi.Object) -> Unit, failed: () -> Unit,
    ) {
        val previous = command.get()
        val terminalDelivered = java.util.concurrent.atomic.AtomicBoolean()
        val uncertaintyDelivered = java.util.concurrent.atomic.AtomicBoolean()
        val scope = WriteScope(request)
        command.set(scope)
        try {
            withReceipt(null) { send(request, { response ->
                terminalDelivered.set(true)
                val actual = when (response) {
                    is TdApi.Ok -> SettingsSpecialWriteReceipt(SettingsSpecialWriteOutcome.ACCEPTED)
                    is TdApi.Error -> SettingsSpecialWriteReceipt(SettingsSpecialWriteOutcome.REJECTED, settingsError(response))
                    // Some native writes return their newly created object instead of Ok.
                    else -> SettingsSpecialWriteReceipt(SettingsSpecialWriteOutcome.INDETERMINATE)
                }
                withReceipt(actual) { result(response) }
            }, {
                val actual = dispatchFailureFor(scope) ?: SettingsSpecialWriteReceipt(SettingsSpecialWriteOutcome.INDETERMINATE)
                val mayDeliver = !terminalDelivered.get()
                if (actual.outcome == SettingsSpecialWriteOutcome.NOT_DISPATCHED) terminalDelivered.set(true)
                if (mayDeliver &&
                    (actual.outcome != SettingsSpecialWriteOutcome.INDETERMINATE || uncertaintyDelivered.compareAndSet(false, true)))
                    withReceipt(actual, failed)
            }) }
        } catch (_: Exception) {
            // A consumer exception after a real synchronous result cannot change its receipt.
            if (!terminalDelivered.get() && uncertaintyDelivered.compareAndSet(false, true))
                withReceipt(SettingsSpecialWriteReceipt(SettingsSpecialWriteOutcome.INDETERMINATE), failed)
        } finally {
            if (previous == null) command.remove() else command.set(previous)
        }
    }
}
