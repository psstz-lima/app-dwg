package com.appdwg.core

import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max
import kotlin.math.min

/**
 * Splits independent work over all CPU cores. Each job is cut into more pieces than there are
 * cores and the threads take them as they free up, so fast and slow cores (big.LITTLE) stay busy.
 * Results are written by index, so the output is the same as running in one thread. A call made
 * from inside a job runs in the calling thread, so nesting never deadlocks.
 */
object Parallel {
    /** Threads used; 1 runs everything in the calling thread (tests set it to compare). */
    @Volatile var threads: Int = max(1, Runtime.getRuntime().availableProcessors())

    private val inWorker = ThreadLocal.withInitial { false }

    private val pool by lazy {
        Executors.newCachedThreadPool { r ->
            Thread({ inWorker.set(true); r.run() }, "appdwg-calc").apply {
                isDaemon = true
                // Just below normal so drawing and touch stay smooth while a job runs.
                priority = Thread.NORM_PRIORITY - 1
            }
        }
    }

    /**
     * Calls [body] on contiguous pieces [from, until) covering 0 until [n], at least [minChunk]
     * long, until one returns false. Returns false when one did (the job was cancelled).
     */
    fun forChunks(n: Int, minChunk: Int = 1, body: (from: Int, until: Int) -> Boolean): Boolean {
        if (n <= 0) return true
        val workers = min(threads, (n + minChunk - 1) / max(1, minChunk))
        if (workers <= 1 || inWorker.get()) return body(0, n)
        val chunk = max(minChunk, (n + workers * 4 - 1) / (workers * 4))
        val pieces = (n + chunk - 1) / chunk
        val next = AtomicInteger()
        val stop = AtomicBoolean()
        val run = Callable {
            while (!stop.get()) {
                val k = next.getAndIncrement()
                if (k >= pieces) break
                if (!body(k * chunk, min(n, (k + 1) * chunk))) stop.set(true)
            }
        }
        val futures = (1 until workers).map { pool.submit(run) }
        // The calling thread takes pieces too.
        val wasWorker = inWorker.get()
        inWorker.set(true)
        try {
            try { run.call() } catch (e: Throwable) { stop.set(true); throw e }
        } finally {
            inWorker.set(wasWorker)
            for (f in futures) {
                try { f.get() } catch (e: ExecutionException) { throw e.cause ?: e }
            }
        }
        return !stop.get()
    }

    /** [transform] of each of 0 until [n] in order, computed on all cores. */
    fun <T> map(n: Int, minChunk: Int = 1, transform: (Int) -> T): List<T> {
        val out = arrayOfNulls<Any?>(n)
        forChunks(n, minChunk) { a, b -> for (i in a until b) out[i] = transform(i); true }
        @Suppress("UNCHECKED_CAST")
        return out.asList() as List<T>
    }
}
