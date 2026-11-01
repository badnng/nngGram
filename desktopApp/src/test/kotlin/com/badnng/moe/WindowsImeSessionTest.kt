package com.badnng.moe

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class WindowsImeSessionTest {
    @Test fun aNewSessionCancelsOnlyARealNativeCompositionAndBorrowsNoText() {
        val native = FakeContext().apply { bytes = 6 }
        val sessions = WindowsImeSessionOwners(native)
        val owner = sessions.begin(10)
        assertEquals(1, native.cancels)
        assertEquals(listOf(10L to 99L), native.released)
        assertTrue(sessions.isCurrent(owner))
        sessions.end(owner)
        assertEquals(1, native.cancels, "No composition remains to cancel at the end")
        assertFalse(sessions.isCurrent(owner))
    }

    @Test fun replacingAFieldAndALateOldFinallyCannotCancelTheNewFieldsComposition() {
        val native = FakeContext()
        val sessions = WindowsImeSessionOwners(native)
        val first = sessions.begin(10)
        native.bytes = 4
        val second = sessions.begin(10)
        assertEquals(1, native.cancels)
        native.bytes = 8
        val reads = native.reads
        sessions.end(first)
        assertEquals(reads, native.reads)
        assertEquals(8, native.bytes)
        assertTrue(sessions.isCurrent(second))
        sessions.end(second)
        assertEquals(2, native.cancels)
    }

    @Test fun ordinaryTypingEmptyContextsAndMissingContextsNeverIssueNativeCancellation() {
        val native = FakeContext()
        val sessions = WindowsImeSessionOwners(native)
        for (bytes in listOf(0, -1)) {
            native.bytes = bytes
            sessions.end(sessions.begin(10))
        }
        native.context = null
        sessions.end(sessions.begin(10))
        assertEquals(0, native.cancels)
        assertEquals(4, native.released.size, "Every real borrowed HIMC is returned, including read failure")
    }

    @Test fun wrongThreadSkipsNativeAccessWithoutBlockingTheComposeDelegate() = runBlocking {
        val native = FakeContext().apply { windowThread = false; bytes = 4 }
        val sessions = WindowsImeSessionOwners(native)
        assertEquals(42, sessions.withSession(10) { 42 })
        assertEquals(0, native.acquires)
        assertEquals(0, native.cancels)
    }

    @Test fun cancellationCleanupFailuresCannotReplaceTheOriginalClosureException() = runBlocking {
        val native = FakeContext()
        val sessions = WindowsImeSessionOwners(native)
        val original = CancellationException("synthetic cancellation")
        val actual = assertFailsWith<CancellationException> {
            sessions.withSession(10) {
                native.bytes = 6
                native.cancelFails = true
                native.releaseFails = true
                throw original
            }
        }
        assertSame(original, actual)
        assertEquals(2, native.released.size)
        native.releaseFails = false
        native.cancelFails = false
        assertEquals("delegate", sessions.withSession(10) { "delegate" })
    }

    @Test fun nativeReentryDuringOldEndKeepsTheNewOwnerAndCannotNestCancellation() {
        val native = FakeContext()
        val sessions = WindowsImeSessionOwners(native)
        val first = sessions.begin(10)
        var replacement: WindowsImeOwner? = null
        native.bytes = 6
        native.onCancel = {
            replacement = sessions.begin(10)
            native.bytes = 12
        }
        sessions.end(first)
        assertTrue(sessions.isCurrent(assertNotNull(replacement)))
        assertEquals(1, native.cancels)
        assertEquals(12, native.bytes)
        native.onCancel = null
        sessions.end(assertNotNull(replacement))
        assertEquals(2, native.cancels)
    }

    @Test fun aReentrantReplacementBeforeTheOldCancelCheckLeavesNewNativeTextAlone() {
        val native = FakeContext()
        val sessions = WindowsImeSessionOwners(native)
        val first = sessions.begin(10)
        var replacement: WindowsImeOwner? = null
        native.onAcquire = {
            native.onAcquire = null
            replacement = sessions.begin(10)
            native.bytes = 10
        }
        sessions.end(first)
        assertTrue(sessions.isCurrent(assertNotNull(replacement)))
        assertEquals(0, native.cancels)
        assertEquals(10, native.bytes)
        sessions.end(assertNotNull(replacement))
        assertEquals(1, native.cancels)
    }

    @Test fun clearingTheCurrentEditorRechecksTheValueAndCancelsOnlyAnActualNativeResidue() {
        val native = FakeContext()
        val sessions = WindowsImeSessionOwners(native)
        val first = sessions.begin(10)
        native.bytes = 8
        sessions.editorCleared(first) { false }
        assertEquals(0, native.cancels)
        sessions.editorCleared(first) { true }
        assertEquals(1, native.cancels)
        sessions.editorCleared(first) { true }
        assertEquals(1, native.cancels, "An already empty native composition is not reset again")
        val second = sessions.begin(10)
        native.bytes = 12
        sessions.editorCleared(first) { true }
        assertEquals(12, native.bytes)
        sessions.editorCleared(second) { error("value unavailable") }
        assertEquals(12, native.bytes)
        sessions.end(second)
    }

    @Test fun aDialogWindowOwnsItsNativeCompositionIndependentlyOfTheMainWindow() {
        val bytes = mutableMapOf<Long, Int>()
        val cancelled = mutableListOf<Long>()
        val released = mutableListOf<Pair<Long, Long>>()
        val native = object : WindowsImeContext {
            override fun isWindowThread(window: Long) = true
            override fun acquire(window: Long) = window + 1_000
            override fun compositionBytes(context: Long) = bytes[context] ?: 0
            override fun cancel(context: Long) { cancelled += context; bytes[context] = 0 }
            override fun release(window: Long, context: Long) { released += window to context }
        }
        val sessions = WindowsImeSessionOwners(native)
        val main = sessions.begin(10)
        val dialog = sessions.begin(20)
        bytes[1_010] = 6
        bytes[1_020] = 8
        sessions.end(dialog)
        assertEquals(listOf(1_020L), cancelled)
        assertEquals(6, bytes[1_010])
        assertTrue(sessions.isCurrent(main))
        assertFalse(sessions.isCurrent(dialog))
        assertEquals(20L to 1_020L, released.last())
        sessions.end(main)
        assertEquals(listOf(1_020L, 1_010L), cancelled)
        assertEquals(10L to 1_010L, released.last())
    }

    @Test fun aNewPreeditDuringTheNativeQuerySurvivesAnEarlierEditorClear() {
        val native = FakeContext()
        val sessions = WindowsImeSessionOwners(native)
        val owner = sessions.begin(10)
        var empty = true
        native.bytes = 8
        native.onAcquire = { empty = false }
        sessions.editorCleared(owner) { empty }
        assertTrue(sessions.isCurrent(owner))
        assertEquals(0, native.cancels, "The same owner may already be composing new text after native reentry.")
        assertEquals(8, native.bytes)
        assertEquals(2, native.released.size)
        native.onAcquire = null
        sessions.end(owner)
        assertEquals(1, native.cancels)
    }

    private class FakeContext : WindowsImeContext {
        var windowThread = true
        var context: Long? = 99
        var bytes = 0
        var acquires = 0
        var reads = 0
        var cancels = 0
        var cancelFails = false
        var releaseFails = false
        var onAcquire: (() -> Unit)? = null
        var onCancel: (() -> Unit)? = null
        val released = mutableListOf<Pair<Long, Long>>()
        override fun isWindowThread(window: Long) = windowThread
        override fun acquire(window: Long): Long? { acquires++; onAcquire?.invoke(); return context }
        override fun compositionBytes(context: Long): Int { reads++; return bytes }
        override fun cancel(context: Long) {
            cancels++
            if (cancelFails) error("native cancel failed")
            bytes = 0
            onCancel?.invoke()
        }
        override fun release(window: Long, context: Long) {
            released += window to context
            if (releaseFails) error("native release failed")
        }
    }
}
