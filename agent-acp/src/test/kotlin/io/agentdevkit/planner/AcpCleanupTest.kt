package io.agentdevkit.planner

import java.io.*
import java.util.Optional
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicReference
import java.util.stream.Stream
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import kotlin.test.*

class AcpCleanupTest {
    private fun start(root: File) = ProcessBuilder(File(System.getProperty("java.home"), "bin/java").path,
        "-cp", System.getProperty("fixture.classpath"), AcpFixture::class.java.name, "hang").directory(root).start()

    private open class WrappedProcess(val delegate: Process) : Process() {
        override fun getInputStream(): InputStream = delegate.inputStream
        override fun getOutputStream(): OutputStream = delegate.outputStream
        override fun getErrorStream(): InputStream = delegate.errorStream
        override fun waitFor(): Int = delegate.waitFor()
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = delegate.waitFor(timeout, unit)
        override fun exitValue(): Int = delegate.exitValue()
        override fun destroy() = delegate.destroy()
        override fun destroyForcibly(): Process { delegate.destroyForcibly(); return this }
        override fun isAlive(): Boolean = delegate.isAlive
        override fun descendants(): Stream<ProcessHandle> = delegate.descendants()
    }

    @Test fun `throwing stream close cannot bypass reader termination`(@TempDir root: File) {
        val entered = CountDownLatch(1)
        val closeAttempted = CountDownLatch(1)
        val release = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val process = object : WrappedProcess(start(root)) {
            override fun getInputStream() = object : InputStream() {
                override fun read(): Int { entered.countDown(); release.await(); return -1 }
                override fun close() { closeAttempted.countDown(); throw IOException("injected close failure") }
            }
        }
        val connection = AcpConnection(process, 5000, 4096)
        val result = AtomicReference<Boolean>()
        val worker = Thread { result.set(connection.close(false)); finished.countDown() }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            worker.start()
            assertTrue(closeAttempted.await(5, TimeUnit.SECONDS))
            assertFalse(finished.await(100, TimeUnit.MILLISECONDS), "Cleanup must not finish while its reader is blocked")
            release.countDown()
            assertTrue(finished.await(5, TimeUnit.SECONDS))
            assertTrue(result.get())
        } finally { release.countDown(); process.destroyForcibly(); worker.join(5000) }
    }

    @Test fun `interrupt during cancellation write is recorded`(@TempDir root: File) {
        val writing = CountDownLatch(1)
        val release = CountDownLatch(1)
        val process = object : WrappedProcess(start(root)) {
            override fun getOutputStream() = object : OutputStream() {
                override fun write(value: Int) { writing.countDown(); release.await() }
            }
        }
        val connection = AcpConnection(process, 5000, 4096).apply { sessionId = "s1" }
        val worker = Thread { connection.close(true) }
        try {
            worker.start()
            assertTrue(writing.await(5, TimeUnit.SECONDS))
            worker.interrupt()
            worker.join(5000)
            assertFalse(worker.isAlive)
            assertTrue(connection.interruptedDuringCleanup)
        } finally { release.countDown(); process.destroyForcibly(); worker.join(5000) }
    }

    @Test fun `blocking stream close returns cleanup failure within deadline`(@TempDir root: File) {
        val release = CountDownLatch(1)
        val closing = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val process = object : WrappedProcess(start(root)) {
            override fun getInputStream() = object : FilterInputStream(delegate.inputStream) {
                override fun close() {
                    closing.countDown()
                    while (release.count > 0) try { release.await() } catch (_: InterruptedException) { /* deliberately uninterruptible fixture */ }
                    super.close()
                }
            }
        }
        val connection = AcpConnection(process, 5000, 4096)
        val result = AtomicReference<Boolean>()
        val worker = Thread { result.set(connection.close(false)); finished.countDown() }
        try {
            worker.start()
            assertTrue(closing.await(5, TimeUnit.SECONDS))
            assertTrue(finished.await(5, TimeUnit.SECONDS), "Blocking close must not hang caller")
            assertFalse(result.get(), "Unfinished close must be explicit cleanup failure")
        } finally { release.countDown(); process.destroyForcibly(); worker.join(5000) }
    }

    @Test fun `cleanup waits for tracked descendant termination`(@TempDir root: File) {
        val destroyed = CountDownLatch(1)
        val terminated = CompletableFuture<ProcessHandle>()
        val delegate = start(root)
        val child = object : ProcessHandle {
            override fun pid(): Long = Long.MAX_VALUE
            override fun parent(): Optional<ProcessHandle> = Optional.of(delegate.toHandle())
            override fun children(): Stream<ProcessHandle> = Stream.empty()
            override fun descendants(): Stream<ProcessHandle> = Stream.empty()
            override fun info(): ProcessHandle.Info = delegate.info()
            override fun onExit(): CompletableFuture<ProcessHandle> = terminated
            override fun supportsNormalTermination() = true
            override fun destroy(): Boolean { destroyed.countDown(); return true }
            override fun destroyForcibly(): Boolean { destroyed.countDown(); return true }
            override fun isAlive(): Boolean = !terminated.isDone
            override fun compareTo(other: ProcessHandle): Int = pid().compareTo(other.pid())
        }
        val process = object : WrappedProcess(delegate) { override fun descendants(): Stream<ProcessHandle> = Stream.of(child) }
        val connection = AcpConnection(process, 5000, 4096)
        val result = AtomicReference<Boolean>()
        val finished = CountDownLatch(1)
        val worker = Thread { result.set(connection.close(false)); finished.countDown() }
        try {
            worker.start()
            assertTrue(destroyed.await(5, TimeUnit.SECONDS))
            assertFalse(finished.await(100, TimeUnit.MILLISECONDS), "Live descendants cannot count as cleaned up")
            terminated.complete(child)
            assertTrue(finished.await(5, TimeUnit.SECONDS))
            assertTrue(result.get())
        } finally { terminated.complete(child); process.destroyForcibly(); worker.join(5000) }
    }
}
