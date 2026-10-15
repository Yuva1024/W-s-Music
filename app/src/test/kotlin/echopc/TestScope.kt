package echopc

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking

/**
 * A Controller's scope in tests. Its Room flow collectors keep re-querying the database, so the
 * scope must be cancelled and joined BEFORE the database is closed: closing SQLite under a running
 * query crashes the JVM natively.
 */
internal class TestScope : CoroutineScope by CoroutineScope(SupervisorJob() + Dispatchers.Default) {
    init { installDesktopHooks() } // same lyrics/colour behaviour as the app
    fun stop() {
        val job = this.coroutineContext[Job] as Job // read here: inside runBlocking `coroutineContext` is runBlocking's own
        runBlocking { job.cancelAndJoin() }
    }
}
