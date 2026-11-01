package com.badnng.moe

import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.snapshots.SnapshotStateObserver
import com.badnng.moe.chat.ChatItem
import com.badnng.moe.chat.ChatMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ChatListMenuOrderTest {
    @Test
    fun withoutAHoldTheNativeSequenceIsReturnedUnchanged() {
        val state = ChatListMenuOrder()
        val native = listOf(chat(3), chat(1), chat(2))
        assertSame(native, state.display(native))
        assertSame(native, state.display(native, listOf(chat(4))))
    }

    @Test
    fun incomingMessagesCannotMoveOrInsertCardsUntilTheOwnerReleases() {
        val state = ChatListMenuOrder()
        val owner = Any()
        val displayed = listOf(chat(1), chat(2), chat(3))
        state.hold(owner, displayed)

        val native = listOf(chat(4), chat(3), chat(1), chat(2))
        assertEquals(listOf(1L, 2L, 3L), state.display(native).map { it.id })

        state.release(owner)
        assertSame(native, state.display(native))
    }

    @Test
    fun heldRowsKeepCurrentNativeFieldsInsteadOfFreezingTheirContent() {
        val state = ChatListMenuOrder()
        val initial = chat(2)
        state.hold(Any(), listOf(chat(1), initial, chat(3)))
        val updated = initial.copy(
            title = "updated",
            unreadCount = 8,
            isMuted = true,
            canSend = false,
            lastMessage = ChatMessage(id = 20, chatId = 2, text = "new message"),
        )

        val result = state.display(listOf(chat(3), updated, chat(1)))
        assertEquals(listOf(1L, 2L, 3L), result.map { it.id })
        assertSame(updated, result[1])
    }

    @Test
    fun anIndependentLatestSnapshotUpdatesFieldsWithoutChangingCapturedMembership() {
        val state = ChatListMenuOrder()
        state.hold(Any(), listOf(chat(1), chat(2), chat(3)))
        val updated = chat(2).copy(markedUnread = true)

        val result = state.display(
            items = listOf(chat(4), chat(3)),
            latestItems = listOf(chat(4), updated, chat(1), chat(3)),
        )
        assertEquals(listOf(1L, 2L, 3L), result.map { it.id })
        assertSame(updated, result[1])
    }

    @Test
    fun removalsRetainTheirSlotsThroughExitAndDisappearAfterRelease() {
        val state = ChatListMenuOrder()
        val owner = Any()
        val removedAbove = chat(1)
        val operated = chat(2)
        val removedBelow = chat(3)
        state.hold(owner, listOf(removedAbove, operated, removedBelow))

        val native = listOf(operated)
        val held = state.display(native)
        assertEquals(listOf(1L, 2L, 3L), held.map { it.id })
        assertSame(removedAbove, held[0])
        assertSame(operated, held[1])
        assertSame(removedBelow, held[2])
        assertEquals(listOf(1L, 2L, 3L), state.display(emptyList()).map { it.id })

        state.release(owner)
        assertSame(native, state.display(native))
    }

    @Test
    fun anOldOwnerCannotReleaseAReplacementEvenWhenOwnersCompareEqual() {
        data class Owner(val value: Int)
        val state = ChatListMenuOrder()
        val oldOwner = Owner(1)
        val newOwner = Owner(1)
        assertFalse(state.isHeldBy(oldOwner))
        state.hold(oldOwner, listOf(chat(1), chat(2)))
        assertTrue(state.isHeldBy(oldOwner))
        assertFalse(state.isHeldBy(newOwner))
        state.hold(newOwner, listOf(chat(2), chat(1)))
        assertFalse(state.isHeldBy(oldOwner))
        assertTrue(state.isHeldBy(newOwner))
        val native = listOf(chat(3), chat(1), chat(2))

        state.release(oldOwner)
        assertEquals(listOf(2L, 1L), state.display(native).map { it.id })
        state.release(newOwner)
        assertFalse(state.isHeldBy(newOwner))
        assertSame(native, state.display(native))
    }

    @Test
    fun captureCopiesTheSequenceBeforeTheSourceCanChange() {
        val state = ChatListMenuOrder()
        val source = mutableListOf(chat(1), chat(2), chat(3))
        state.hold(Any(), source)
        source.clear()
        source.add(chat(4))

        assertEquals(listOf(1L, 2L, 3L), state.display(source).map { it.id })
    }

    @Test
    fun holdingAndReleasingInvalidateObserversOfTheDisplayedSequence() {
        Snapshot.sendApplyNotifications()
        val state = ChatListMenuOrder()
        val owner = Any()
        val native = listOf(chat(1), chat(2))
        val observer = SnapshotStateObserver { callback -> callback() }
        val scope = Any()
        var invalidations = 0
        val onChanged: (Any) -> Unit = { invalidations += 1 }
        observer.start()
        try {
            observer.observeReads(scope, onChanged) { state.display(native) }
            state.hold(owner, native)
            Snapshot.sendApplyNotifications()
            assertEquals(1, invalidations)

            observer.observeReads(scope, onChanged) { state.display(native) }
            state.release(owner)
            Snapshot.sendApplyNotifications()
            assertEquals(2, invalidations)
        } finally {
            observer.stop()
            observer.clear()
        }
    }

    private fun chat(id: Long) = ChatItem(id = id, title = "chat $id")
}
