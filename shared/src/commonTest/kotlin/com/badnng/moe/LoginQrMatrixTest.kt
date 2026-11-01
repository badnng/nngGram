package com.badnng.moe

import kotlin.test.*

class LoginQrMatrixTest {
    @Test fun qrMatrixIsBoundedAndNeverPrintsThePayload() {
        assertNull(LoginQrMatrix.encode(""))
        assertNull(LoginQrMatrix.encode("a".repeat(1664)))
        val payload = "tg://login?token=synthetic_fixture"
        val matrix = assertNotNull(LoginQrMatrix.encode(payload))
        assertTrue(matrix.size in 21..177)
        assertEquals(1, matrix.size % 4)
        assertFalse(matrix.toString().contains("synthetic_fixture"))
        assertTrue(matrix[0, 0]); assertTrue(matrix[6, 6]); assertFalse(matrix[7, 7])
    }

    @Test fun aNativeTokenChangeChangesTheDataModules() {
        val first = assertNotNull(LoginQrMatrix.encode("tg://login?token=synthetic_fixture_1"))
        val second = assertNotNull(LoginQrMatrix.encode("tg://login?token=synthetic_fixture_2"))
        assertEquals(first.size, second.size)
        assertTrue((0 until first.size).any { y -> (0 until first.size).any { x -> first[x, y] != second[x, y] } })
    }
}
