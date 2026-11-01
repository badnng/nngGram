package com.badnng.moe.tdlib

import org.drinkless.tdlib.TdApi

/**
 * Keeps the latest early TDLib accent directory updates until the settings receiver is ready.
 * Construct this before Client.create and bind it once the session receiver exists.
 */
internal class TdProfileColorsEarlyUpdates {
    private val monitor = Any()

    private var retired = false
    private var bound = false
    private var target: ((TdApi.Object) -> Unit)? = null
    private var draining = false
    private var arrivalSequence = 0L
    private var accentPending: Pending? = null
    private var profilePending: Pending? = null

    /** Accepts only the two directory updates; all other TDLib objects are ignored. */
    fun accept(update: TdApi.Object) {
        val shouldDrain = synchronized(monitor) {
            if (retired) return
            val snapshot = when (update) {
                is TdApi.UpdateAccentColors -> accentSnapshot(update)
                is TdApi.UpdateProfileAccentColors -> profileSnapshot(update)
                else -> return
            }
            val pending = Pending(++arrivalSequence, snapshot)
            when (snapshot.kind) {
                Kind.ACCENT -> accentPending = pending
                Kind.PROFILE -> profilePending = pending
            }
            if (target != null && !draining) {
                draining = true
                true
            } else false
        }
        if (shouldDrain) drain()
    }

    /** Binds the receiver at most once and synchronously starts an ordered serial drain. */
    fun bind(target: (TdApi.Object) -> Unit) {
        val shouldDrain = synchronized(monitor) {
            if (retired || bound) return
            bound = true
            this.target = target
            if (!draining && (accentPending != null || profilePending != null)) {
                draining = true
                true
            } else false
        }
        if (shouldDrain) drain()
    }

    /** Drops both cached entries and prevents any later forwarding or binding. */
    fun retire() {
        synchronized(monitor) {
            retired = true
            target = null
            accentPending = null
            profilePending = null
        }
    }

    private fun drain() {
        while (true) {
            val delivery = synchronized(monitor) {
                if (retired || target == null) {
                    accentPending = null
                    profilePending = null
                    draining = false
                    return
                }
                val next = when {
                    accentPending == null -> profilePending
                    profilePending == null -> accentPending
                    accentPending!!.sequence < profilePending!!.sequence -> accentPending
                    else -> profilePending
                }
                if (next == null) {
                    draining = false
                    return
                }
                when (next.snapshot.kind) {
                    Kind.ACCENT -> accentPending = null
                    Kind.PROFILE -> profilePending = null
                }
                Delivery(target!!, next.snapshot)
            }
            // The settings receiver is foreign code and may reenter this collector.
            try {
                delivery.target(delivery.snapshot.toTdApi())
            } catch (_: Exception) {
                // A failed receiver callback must not wedge the serial drain or retain old updates.
            }
        }
    }

    private fun accentSnapshot(update: TdApi.UpdateAccentColors): Snapshot {
        val colors = update.colors ?: return InvalidSnapshot(Kind.ACCENT)
        val availableIds = update.availableAccentColorIds ?: return InvalidSnapshot(Kind.ACCENT)
        if (colors.size > MAX_DIRECTORY_ITEMS || availableIds.size > MAX_DIRECTORY_ITEMS) {
            return InvalidSnapshot(Kind.ACCENT)
        }

        val copied = ArrayList<AccentColorSnapshot>(colors.size)
        for (color in colors) {
            if (color == null) return InvalidSnapshot(Kind.ACCENT)
            val light = copyRgb(color.lightThemeColors) ?: return InvalidSnapshot(Kind.ACCENT)
            val dark = copyRgb(color.darkThemeColors) ?: return InvalidSnapshot(Kind.ACCENT)
            copied += AccentColorSnapshot(color.id, color.builtInAccentColorId, light, dark,
                color.minChannelChatBoostLevel)
        }
        return AccentSnapshot(copied.toList(), availableIds.toList())
    }

    private fun profileSnapshot(update: TdApi.UpdateProfileAccentColors): Snapshot {
        val colors = update.colors ?: return InvalidSnapshot(Kind.PROFILE)
        val availableIds = update.availableAccentColorIds ?: return InvalidSnapshot(Kind.PROFILE)
        if (colors.size > MAX_DIRECTORY_ITEMS || availableIds.size > MAX_DIRECTORY_ITEMS) {
            return InvalidSnapshot(Kind.PROFILE)
        }

        val copied = ArrayList<ProfileAccentColorSnapshot>(colors.size)
        for (color in colors) {
            if (color == null) return InvalidSnapshot(Kind.PROFILE)
            val light = profileThemeSnapshot(color.lightThemeColors) ?: return InvalidSnapshot(Kind.PROFILE)
            val dark = profileThemeSnapshot(color.darkThemeColors) ?: return InvalidSnapshot(Kind.PROFILE)
            copied += ProfileAccentColorSnapshot(color.id, light, dark,
                color.minSupergroupChatBoostLevel, color.minChannelChatBoostLevel)
        }
        return ProfileSnapshot(copied.toList(), availableIds.toList())
    }

    private fun profileThemeSnapshot(colors: TdApi.ProfileAccentColors?): ProfileThemeSnapshot? {
        if (colors == null) return null
        val palette = copyBounded(colors.paletteColors, 1, 2) ?: return null
        val background = copyBounded(colors.backgroundColors, 1, 2) ?: return null
        val story = copyBounded(colors.storyColors, 2, 2) ?: return null
        return ProfileThemeSnapshot(palette, background, story)
    }

    private fun copyRgb(colors: IntArray?): List<Int>? = copyBounded(colors, 1, 3)

    /** Invalid or oversized arrays are represented as a null-array native update, never truncated. */
    private fun copyBounded(values: IntArray?, minimum: Int, maximum: Int): List<Int>? {
        if (values == null || values.size !in minimum..maximum) return null
        return values.toList()
    }

    private data class Pending(val sequence: Long, val snapshot: Snapshot)
    private data class Delivery(val target: (TdApi.Object) -> Unit, val snapshot: Snapshot)

    private enum class Kind { ACCENT, PROFILE }

    private sealed interface Snapshot {
        val kind: Kind
        fun toTdApi(): TdApi.Object
    }

    private data class InvalidSnapshot(override val kind: Kind) : Snapshot {
        override fun toTdApi(): TdApi.Object = when (kind) {
            Kind.ACCENT -> TdApi.UpdateAccentColors()
            Kind.PROFILE -> TdApi.UpdateProfileAccentColors()
        }
    }

    private data class AccentSnapshot(
        val colors: List<AccentColorSnapshot>,
        val availableIds: List<Int>,
    ) : Snapshot {
        override val kind = Kind.ACCENT
        override fun toTdApi(): TdApi.Object = TdApi.UpdateAccentColors(
            Array(colors.size) { colors[it].toTdApi() }, availableIds.toIntArray())
    }

    private data class AccentColorSnapshot(
        val id: Int,
        val builtInAccentColorId: Int,
        val lightThemeColors: List<Int>,
        val darkThemeColors: List<Int>,
        val minChannelChatBoostLevel: Int,
    ) {
        fun toTdApi() = TdApi.AccentColor(id, builtInAccentColorId, lightThemeColors.toIntArray(),
            darkThemeColors.toIntArray(), minChannelChatBoostLevel)
    }

    private data class ProfileSnapshot(
        val colors: List<ProfileAccentColorSnapshot>,
        val availableIds: List<Int>,
    ) : Snapshot {
        override val kind = Kind.PROFILE
        override fun toTdApi(): TdApi.Object = TdApi.UpdateProfileAccentColors(
            Array(colors.size) { colors[it].toTdApi() }, availableIds.toIntArray())
    }

    private data class ProfileAccentColorSnapshot(
        val id: Int,
        val lightThemeColors: ProfileThemeSnapshot,
        val darkThemeColors: ProfileThemeSnapshot,
        val minSupergroupChatBoostLevel: Int,
        val minChannelChatBoostLevel: Int,
    ) {
        fun toTdApi() = TdApi.ProfileAccentColor(id, lightThemeColors.toTdApi(), darkThemeColors.toTdApi(),
            minSupergroupChatBoostLevel, minChannelChatBoostLevel)
    }

    private data class ProfileThemeSnapshot(
        val paletteColors: List<Int>,
        val backgroundColors: List<Int>,
        val storyColors: List<Int>,
    ) {
        fun toTdApi() = TdApi.ProfileAccentColors(paletteColors.toIntArray(), backgroundColors.toIntArray(),
            storyColors.toIntArray())
    }

    internal companion object {
        const val MAX_DIRECTORY_ITEMS = 4096
    }
}
