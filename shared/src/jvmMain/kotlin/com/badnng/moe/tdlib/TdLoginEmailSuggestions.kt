package com.badnng.moe.tdlib

import com.badnng.moe.settings.LoginEmailSuggestions
import org.drinkless.tdlib.TdApi

/** Created before Client.create: even a synchronous first update must be retained. */
internal class TdLoginEmailSuggestions(private val gate: TdUserInteractionGate = TdUserInteractionGate()) {
    private val lock = Any()
    private var current = LoginEmailSuggestions()
    private var observer: ((LoginEmailSuggestions) -> Unit)? = null

    fun snapshot(): LoginEmailSuggestions = synchronized(lock) { current }

    fun observe(value: ((LoginEmailSuggestions) -> Unit)?) {
        val initial = synchronized(lock) { observer = value; current }
        // Consumers compare revisions: an accept racing this replay cannot move them backwards.
        value?.invoke(initial)
    }

    fun accept(update: TdApi.Object) {
        if (update !is TdApi.UpdateSuggestedActions) return
        val emitted = synchronized(lock) {
            var next = current
            update.removedActions.orEmpty().forEach { action ->
                if (action is TdApi.SuggestedActionSetLoginEmailAddress) next = if (action.canBeHidden)
                    next.copy(optionalPresent = false) else next.copy(requiredPresent = false)
            }
            update.addedActions.orEmpty().forEach { action ->
                if (action is TdApi.SuggestedActionSetLoginEmailAddress) next = if (action.canBeHidden) {
                    if (next.optionalPresent) next else next.copy(optionalPresent = true, optionalEpoch = next.optionalEpoch + 1)
                } else {
                    if (next.requiredPresent) next else next.copy(requiredPresent = true, requiredEpoch = next.requiredEpoch + 1)
                }
            }
            if (next == current) return
            current = next.copy(revision = current.revision + 1)
            gate.update(current)
            current to observer
        }
        emitted.second?.invoke(emitted.first)
    }
}
