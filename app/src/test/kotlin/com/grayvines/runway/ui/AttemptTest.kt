package com.grayvines.runway.ui

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AttemptTest {
    private val logged = mutableListOf<Pair<String, Throwable>>()
    private val log = { message: String, e: Throwable -> logged += message to e }

    @Test
    fun `a write that goes through is reported as such, with nothing logged`() = runTest {
        assertTrue(attempt("save", log) {})
        assertEquals(emptyList<Pair<String, Throwable>>(), logged)
    }

    @Test
    fun `whatever a write throws is logged, and reported as a failure`() = runTest {
        val closed = IllegalStateException("database closed")
        assertFalse(attempt("save the move", log) { throw closed })
        assertEquals(listOf("could not save the move" to closed), logged)
    }

    @Test
    fun `cancellation is not a failure and passes through`() = runTest {
        assertThrows(CancellationException::class.java) {
            kotlinx.coroutines.runBlocking {
                attempt("save", log) { throw CancellationException() }
            }
        }
        assertEquals(emptyList<Pair<String, Throwable>>(), logged)
    }
}
