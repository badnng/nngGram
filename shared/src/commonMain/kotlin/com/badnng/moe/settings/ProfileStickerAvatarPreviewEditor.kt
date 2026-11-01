package com.badnng.moe.settings

/**
 * UI-only state. S must be a caller-provided opaque selection ticket; possession of S here grants
 * no media or mutation authority. Actor admission and current native identity checks remain required.
 * Confine this object to the UI dispatcher; marshal and revalidate asynchronous callbacks there.
 */
internal class AvatarPreviewEditor<S : Any>(initial: AvatarPreviewColors) {
    internal class ColorsEdit internal constructor(internal var value: AvatarPreviewColors) {
        var selectedIndex: Int = value.size - 1
            internal set
        fun colors(): List<Int> = value.values()
    }

    internal class Confirmation<S : Any> internal constructor(
        val selection: S,
        val colors: AvatarPreviewColors,
        val revision: Long,
    )

    internal class Submission<S : Any> internal constructor(val confirmation: Confirmation<S>) {
        internal var admissionResolved = false
        internal var admittedRevision: Long? = null
        internal var earlyResult: Pair<Long, Boolean>? = null
        internal var settled: Boolean? = null
    }

    private var open = true
    private var colorsEdit: ColorsEdit? = null
    private var confirmation: Confirmation<S>? = null
    private var submission: Submission<S>? = null
    private var customColors: AvatarPreviewColors? = null
    var selection: S? = null
        private set
    var colors: AvatarPreviewColors = initial
        private set
    var selectedPalette: Int? = (0 until AvatarPreviewPalettes.size).firstOrNull { AvatarPreviewPalettes.get(it) == initial }
        private set
    val hasCustomColors: Boolean get() = customColors != null
    var revision: Long = 0
        private set
    var accepted: Boolean = false
        private set
    val pending: Boolean get() = submission != null

    private fun editable(current: Boolean): Boolean = open && current && !accepted &&
        submission == null && confirmation == null && colorsEdit == null

    /** Re-selecting the same object still creates a new local revision. */
    fun select(value: S, current: Boolean): Boolean {
        if (!editable(current)) return false
        selection = value
        revision++
        return true
    }

    fun choosePalette(index: Int, current: Boolean): Boolean {
        if (!editable(current)) return false
        val value = AvatarPreviewPalettes.get(index) ?: return false
        if (colors != value) { colors = value; revision++ }
        selectedPalette = index
        return true
    }

    /** The special swatch can restore its last confirmed custom colors after selecting a preset. */
    fun chooseCustom(current: Boolean): Boolean {
        if (!editable(current)) return false
        val value = customColors ?: return false
        if (colors != value) { colors = value; revision++ }
        selectedPalette = null
        return true
    }

    fun editColors(current: Boolean): ColorsEdit? {
        if (!editable(current)) return null
        return ColorsEdit(colors).also { colorsEdit = it }
    }

    private fun editing(edit: ColorsEdit, current: Boolean) = open && current && !accepted &&
        submission == null && confirmation == null && colorsEdit === edit

    fun selectColor(edit: ColorsEdit, index: Int, current: Boolean): Boolean {
        if (!editing(edit, current) || index !in 0 until edit.value.size) return false
        edit.selectedIndex = index
        return true
    }

    fun changeColor(edit: ColorsEdit, rgb: Int, current: Boolean): Boolean {
        if (!editing(edit, current)) return false
        edit.value = edit.value.replace(edit.selectedIndex, rgb) ?: return false
        return true
    }

    /** The UI chooses an actual new HSL-derived RGB; no random state is hidden in this model. */
    fun addColor(edit: ColorsEdit, rgb: Int, current: Boolean): Boolean {
        if (!editing(edit, current)) return false
        edit.value = edit.value.append(rgb) ?: return false
        edit.selectedIndex = edit.value.size - 1
        return true
    }

    fun removeColor(edit: ColorsEdit, current: Boolean): Boolean {
        if (!editing(edit, current)) return false
        edit.value = edit.value.removeLast() ?: return false
        edit.selectedIndex = edit.value.size - 1
        return true
    }

    fun finishColors(edit: ColorsEdit, apply: Boolean, current: Boolean): Boolean {
        if (!editing(edit, current)) return false
        if (apply) {
            customColors = edit.value
            selectedPalette = null
            if (colors != edit.value) { colors = edit.value; revision++ }
        }
        colorsEdit = null
        return true
    }

    fun confirm(current: Boolean): Confirmation<S>? {
        if (!editable(current)) return null
        val chosen = selection ?: return null
        return Confirmation(chosen, colors, revision).also { confirmation = it }
    }

    fun cancelConfirmation(value: Confirmation<S>, current: Boolean): Boolean {
        if (!open || !current || submission != null || confirmation !== value) return false
        confirmation = null
        return true
    }

    /** UI gate only: the actor must atomically verify current owner, expected photo and ticket. */
    fun submit(value: Confirmation<S>, current: Boolean): Submission<S>? {
        if (!open || !current || accepted || submission != null || confirmation !== value ||
            selection !== value.selection || revision != value.revision) return null
        return Submission(value).also { submission = it }
    }

    /** null means admission failed; a duplicate or late callback cannot settle another submission. */
    fun admitted(value: Submission<S>, actorRevision: Long?, current: Boolean): Boolean {
        if (!open || !current || submission !== value || value.admissionResolved) return false
        value.admissionResolved = true
        if (actorRevision == null) {
            submission = null
            value.settled = false
        } else {
            value.admittedRevision = actorRevision
            value.earlyResult?.takeIf { it.first == actorRevision }?.let { settle(value, it.second) }
        }
        return true
    }

    /** Native results remain actor-owned even if this UI state has already closed. */
    fun completed(value: Submission<S>, actorRevision: Long, success: Boolean, current: Boolean): Boolean {
        if (!open || !current || submission !== value) return false
        if (!value.admissionResolved) {
            if (value.earlyResult != null) return false
            value.earlyResult = actorRevision to success
            return true
        }
        if (value.admittedRevision != actorRevision) return false
        settle(value, success)
        return true
    }

    private fun settle(value: Submission<S>, success: Boolean) {
        if (submission !== value) return
        submission = null
        value.settled = success
        if (success) accepted = true
    }

    /** A native catalog/owner epoch change also invalidates its selected ticket. */
    fun invalidateSelection() {
        if (!open) return
        selection = null
        revision++
        colorsEdit = null
        confirmation = null
        // An admitted native write still belongs to the actor; this only retires its UI callback.
        submission = null
    }

    fun close() {
        open = false
        colorsEdit = null
        confirmation = null
        submission = null
    }
}
