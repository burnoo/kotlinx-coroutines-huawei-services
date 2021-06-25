package kotlinx.coroutines.tasks

import com.huawei.hmf.tasks.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.*
import kotlin.concurrent.*

@DelicateCoroutinesApi
@ExperimentalCoroutinesApi
class TaskTest {
    private var actionIndex = AtomicInteger()
    private var finished = AtomicBoolean()

    @Test
    fun testCompletedDeferredAsTask() = runBlocking {
        expect(1)
        val deferred = async(start = CoroutineStart.UNDISPATCHED) {
            expect(2) // Completed immediately
            "OK"
        }
        expect(3)
        val task = deferred.asTask()
        assertEquals("OK", task.await())
        finish(4)
    }

    @Test
    fun testDeferredAsTask() = runBlocking {
        expect(1)
        val deferred = async {
            expect(3) // Completed later
            "OK"
        }
        expect(2)
        val task = deferred.asTask()
        assertEquals("OK", task.await())
        finish(4)
    }

    @Test
    fun testCancelledAsTask() {
        val deferred = GlobalScope.async {
            delay(100)
        }.apply { cancel() }

        val task = deferred.asTask()
        try {
            runBlocking { task.await() }
        } catch (e: Exception) {
            assertTrue(e is CancellationException)
            assertTrue(task.isCanceled)
        }
    }

    @Test(expected = TestException::class)
    fun testThrowingAsTask() {
        val deferred = GlobalScope.async<Int> {
            throw TestException("Fail")
        }

        val task = deferred.asTask()
        runBlocking { task.await() }
    }

    @Test
    fun testStateAsTask() = runBlocking {
        val lock = ReentrantLock().apply { lock() }

        val deferred: Deferred<Int> = Tasks.callInBackground {
            lock.withLock { 42 }
        }.asDeferred()

        assertFalse(deferred.isCompleted)
        lock.unlock()

        assertEquals(42, deferred.await())
        assertTrue(deferred.isCompleted)
    }

    @Test
    fun testTaskAsDeferred() = runBlocking {
        val deferred = Tasks.fromResult(42).asDeferred()
        assertEquals(42, deferred.await())
    }

    @Test
    fun testNullResultTaskAsDeferred() = runBlocking {
        assertNull(Tasks.fromResult(null).asDeferred().await())
    }

    @Test
    fun testCancelledTaskAsDeferred() = runBlocking {
        val deferred = Tasks.fromCanceled<Int>().asDeferred()

        assertTrue(deferred.isCancelled)
        try {
            deferred.await()
            fail("deferred.await() should be cancelled")
        } catch (e: Exception) {
            assertTrue(e is CancellationException)
        }
    }

    @Test
    fun testFailedTaskAsDeferred() = runBlocking {
        val deferred = Tasks.fromException<Int>(TestException("something went wrong")).asDeferred()

        assertTrue(deferred.isCancelled && deferred.isCompleted)
        val completionException = deferred.getCompletionExceptionOrNull()!!
        assertTrue(completionException is TestException)
        assertEquals("something went wrong", completionException.message)

        try {
            deferred.await()
            fail("deferred.await() should throw an exception")
        } catch (e: Exception) {
            assertTrue(e is TestException)
            assertEquals("something went wrong", e.message)
        }
    }

    @Test
    fun testFailingTaskAsDeferred() = runBlocking {
        val lock = ReentrantLock().apply { lock() }

        val deferred: Deferred<Int> = Tasks.callInBackground {
            lock.withLock { throw TestException("something went wrong") }
        }.asDeferred()

        assertFalse(deferred.isCompleted)
        lock.unlock()

        try {
            deferred.await()
            fail("deferred.await() should throw an exception")
        } catch (e: Exception) {
            assertTrue(e is TestException)
            assertEquals("something went wrong", e.message)
            assertSame(e.cause, deferred.getCompletionExceptionOrNull()) // debug mode stack augmentation
        }
    }

    class TestException(message: String) : Exception(message)

    private fun expect(index: Int) {
        val wasIndex = actionIndex.incrementAndGet()
        check(index == wasIndex) { "Expecting action index $index but it is actually $wasIndex" }
    }

    private fun finish(index: Int) {
        expect(index)
        check(!finished.getAndSet(true)) { "Should call 'finish(...)' at most once" }
    }
}
