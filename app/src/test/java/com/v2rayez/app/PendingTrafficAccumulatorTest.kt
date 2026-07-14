package com.v2rayez.app

import com.v2rayez.app.data.service.PendingTrafficAccumulator
import com.v2rayez.app.data.service.PendingTrafficBatch
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import org.junit.Assert.assertEquals
import org.junit.Test

class PendingTrafficAccumulatorTest {

    @Test
    fun concurrentAddsAndDrainsAccountForEveryByteExactlyOnce() {
        val accumulator = PendingTrafficAccumulator()
        val drained = ConcurrentLinkedQueue<PendingTrafficBatch>()
        val workers = 8
        val additionsPerWorker = 2_000
        val start = CountDownLatch(1)
        val done = CountDownLatch(workers)
        val executor = Executors.newFixedThreadPool(workers)

        repeat(workers) {
            executor.execute {
                start.await()
                repeat(additionsPerWorker) {
                    accumulator.add(downDelta = 3L, upDelta = 2L)?.let(drained::add)
                    if (it % 17 == 0) accumulator.drain()?.let(drained::add)
                }
                done.countDown()
            }
        }

        start.countDown()
        done.await()
        accumulator.drain()?.let(drained::add)
        executor.shutdown()

        assertEquals(workers * additionsPerWorker * 3L, drained.sumOf { it.down })
        assertEquals(workers * additionsPerWorker * 2L, drained.sumOf { it.up })
    }
}
