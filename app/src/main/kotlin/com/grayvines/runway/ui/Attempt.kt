package com.grayvines.runway.ui

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Runs [block], a layout write, and reports whether it went through. Whatever goes wrong in it, a
 * database that is closed or would not migrate as much as a constraint, is logged as "could not
 * [what]", and the launcher stays up: what is on screen simply does not change. Cancellation is not
 * a failure and passes through. [log] is only ever replaced by a test.
 */
@Suppress("TooGenericExceptionCaught")
suspend fun attempt(
    what: String,
    log: (String, Throwable) -> Unit = { message, e -> Log.e(TAG, message, e) },
    block: suspend () -> Unit,
): Boolean =
    try {
        block()
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log("could not $what", e)
        false
    }

/** [attempt] in [this] scope, for callers with nothing to do about a failure. */
fun CoroutineScope.writing(what: String, block: suspend () -> Unit): Job = launch {
    attempt(what, block = block)
}

private const val TAG = "Runway"
