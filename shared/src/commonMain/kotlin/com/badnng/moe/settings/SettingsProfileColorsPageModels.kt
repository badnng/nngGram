package com.badnng.moe.settings

data class SettingsProfileColorsToken(val generation: Long = 0, val owner: Long = 0, val userId: Long = 0)

data class SettingsProfileColorEdit(
    val part: SettingsProfileColorPart,
    val base: SettingsProfileColorValue,
    val desired: SettingsProfileColorValue,
    val contentRevision: Long,
    val replaceGift: Boolean = false,
) {
    override fun toString() = "SettingsProfileColorEdit(<redacted>)"
}

data class SettingsProfileColorAttempt(val id: Long, val edit: SettingsProfileColorEdit) {
    override fun toString() = "SettingsProfileColorAttempt(<redacted>)"
}

data class SettingsProfileColorOutcome(
    val attempt: SettingsProfileColorAttempt,
    val locallyAccepted: Boolean = false,
    val error: SettingsError? = null,
) {
    override fun toString() = "SettingsProfileColorOutcome(<redacted>, accepted=$locallyAccepted, error=$error)"
}

data class SettingsProfileColorsSnapshot(
    val token: SettingsProfileColorsToken = SettingsProfileColorsToken(),
    val opened: Boolean = false, val loading: Boolean = false, val busy: Boolean = false,
    val revision: Long = 0, val data: SettingsProfileColorsData? = null,
    val error: SettingsError? = null, val refreshRequired: Boolean = false,
    val readbackPending: Set<SettingsProfileColorPart> = emptySet(),
    val activeSave: SettingsProfileColorAttempt? = null,
    val outcomes: Map<SettingsProfileColorPart, SettingsProfileColorOutcome> = emptyMap(),
    val backgroundsOpened: Boolean = false, val backgroundsLoading: Boolean = false,
    val backgrounds: List<SettingsBackgroundEmoji> = emptyList(), val backgroundsError: SettingsError? = null,
) {
    override fun toString() = "SettingsProfileColorsSnapshot(<redacted>, opened=$opened)"
}

data class SettingsProfileColorsActions(
    val open: (SettingsToken, Long, () -> Boolean) -> Unit = { _, _, _ -> },
    val close: (SettingsProfileColorsToken) -> Unit = {},
    val refresh: (SettingsProfileColorsToken) -> Unit = {},
    val save: (SettingsProfileColorsToken, SettingsProfileColorEdit) -> Unit = { _, _ -> },
    val openBackgrounds: (SettingsProfileColorsToken) -> Unit = {},
    val resolveBackgrounds: (SettingsProfileColorsToken, List<Long>) -> Unit = { _, _ -> },
    val closeBackgrounds: (SettingsProfileColorsToken) -> Unit = {},
)
