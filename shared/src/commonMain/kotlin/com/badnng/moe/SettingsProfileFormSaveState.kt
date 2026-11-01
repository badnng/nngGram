package com.badnng.moe

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.setValue
import com.badnng.moe.settings.ProfileField
import com.badnng.moe.settings.SettingsProfile
import com.badnng.moe.settings.SettingsProfileEdit
import com.badnng.moe.settings.SettingsSection
import com.badnng.moe.settings.SettingsSnapshot
import com.badnng.moe.settings.SettingsToken

/** A bounded, frozen batch. Native profile writes remain separate, serial operations. */
internal class SettingsProfileFormSaveState {
    private var remaining by mutableStateOf<List<SettingsProfileEdit>>(emptyList())
    private var baseline by mutableStateOf(0L)
    private var expected by mutableStateOf<Long?>(null)
    private var dispatched by mutableStateOf(false)
    private var contentRevision by mutableStateOf(0L)
    private var token = SettingsToken()
    var acceptedCount by mutableStateOf(0); private set
    var failed by mutableStateOf(false); private set
    var interrupted by mutableStateOf(false); private set
    val pending get() = remaining.isNotEmpty()
    val awaiting get() = pending && dispatched

    fun begin(owner: SettingsToken, edits: List<SettingsProfileEdit>, revision: Long) {
        check(!pending)
        token = owner; remaining = edits.toList(); contentRevision = revision
        acceptedCount = 0; failed = false; interrupted = false; dispatched = false; expected = null
    }

    fun cancel() {
        remaining = emptyList(); expected = null; dispatched = false; failed = false; interrupted = false; acceptedCount = 0
    }

    /** Binding happens once: ordinary refreshes also consume native request revisions. */
    fun reconcile(snapshot: SettingsSnapshot, currentContentRevision: Long): Boolean {
        if (!pending) return false
        if (snapshot.token != token) {
            remaining = emptyList(); dispatched = false; expected = null; interrupted = true
            return false
        }
        if (!awaiting) return false
        val section = SettingsSection.PROFILE
        val started = snapshot.startedSaveRevision[section] ?: 0L
        if (expected == null && started > baseline) expected = started
        val owned = expected ?: return false
        if (snapshot.failedSaveRevision[section] == owned) {
            remaining = emptyList(); dispatched = false; expected = null; failed = true
            return false
        }
        if (snapshot.completedSaveRevision[section] != owned) return false
        ++acceptedCount
        remaining = remaining.drop(1); dispatched = false; expected = null
        // An old accepted field cannot consume, or continue writing over, a newer local form.
        if (currentContentRevision != contentRevision) { remaining = emptyList(); return false }
        return remaining.isEmpty()
    }

    fun next(snapshot: SettingsSnapshot): SettingsProfileEdit? {
        if (!pending || dispatched) return null
        baseline = snapshot.startedSaveRevision[SettingsSection.PROFILE] ?: 0L
        expected = null; dispatched = true
        return remaining.first()
    }

    override fun toString() = "SettingsProfileFormSaveState(<redacted>)"

    companion object {
        val Saver = listSaver<SettingsProfileFormSaveState, Any>(
            save = { state -> buildList {
                add(state.baseline); add(state.expected ?: -1L); add(state.dispatched); add(state.contentRevision)
                add(state.acceptedCount); add(state.failed)
                add(state.token.generation); add(state.token.revision); add(state.interrupted)
                state.remaining.forEach { add(it.field.name); add(it.value); add(it.secondary) }
            } },
            restore = { saved -> SettingsProfileFormSaveState().apply {
                baseline = saved[0] as Long; expected = (saved[1] as Long).takeIf { it >= 0 }
                dispatched = saved[2] as Boolean; contentRevision = saved[3] as Long
                acceptedCount = saved[4] as Int; failed = saved[5] as Boolean
                token = SettingsToken(saved[6] as Long, saved[7] as Long)
                interrupted = saved[8] as Boolean
                remaining = saved.drop(9).chunked(3).map {
                    SettingsProfileEdit(ProfileField.valueOf(it[0] as String), it[1] as String, it[2] as String)
                }
            } },
        )
    }
}

internal fun settingsProfileFormEdits(profile: SettingsProfile, firstName: String, lastName: String,
    bio: String, username: String): List<SettingsProfileEdit> = buildList {
    if (firstName != profile.firstName || lastName != profile.lastName)
        add(SettingsProfileEdit(ProfileField.NAME, firstName, lastName))
    if (bio != profile.bio) add(SettingsProfileEdit(ProfileField.BIO, bio))
    val candidate = settingsUsernameCandidate(username)
    if (candidate != profile.username) add(SettingsProfileEdit(ProfileField.USERNAME, candidate))
}
