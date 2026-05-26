/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.index.engine.sidecar;

import org.opensearch.core.concurrency.OpenSearchRejectedExecutionException;
import org.opensearch.test.OpenSearchTestCase;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class SidecarGenerationManagerTests extends OpenSearchTestCase {

    public void testAcquireGenerationIncrementsCount() {
        SidecarGenerationManager mgr = new SidecarGenerationManager(5);
        assertEquals(1, mgr.acquireGeneration("_0", "embedding"));
        assertEquals(2, mgr.acquireGeneration("_0", "embedding"));
        assertEquals(2, mgr.getGenerationCount("_0", "embedding"));
    }

    public void testBackpressureAtLimit() {
        SidecarGenerationManager mgr = new SidecarGenerationManager(3);
        mgr.acquireGeneration("_0", "embedding");
        mgr.acquireGeneration("_0", "embedding");
        mgr.acquireGeneration("_0", "embedding");

        expectThrows(OpenSearchRejectedExecutionException.class, () -> mgr.acquireGeneration("_0", "embedding"));
    }

    public void testDifferentFieldsIndependent() {
        SidecarGenerationManager mgr = new SidecarGenerationManager(2);
        mgr.acquireGeneration("_0", "embedding");
        mgr.acquireGeneration("_0", "embedding");
        assertEquals(1, mgr.acquireGeneration("_0", "tags"));
    }

    public void testDifferentSegmentsIndependent() {
        SidecarGenerationManager mgr = new SidecarGenerationManager(2);
        mgr.acquireGeneration("_0", "embedding");
        mgr.acquireGeneration("_0", "embedding");
        assertEquals(1, mgr.acquireGeneration("_1", "embedding"));
    }

    public void testMergeResetsCount() {
        SidecarGenerationManager mgr = new SidecarGenerationManager(3);
        mgr.acquireGeneration("_0", "embedding");
        mgr.acquireGeneration("_0", "embedding");
        assertEquals(2, mgr.getGenerationCount("_0", "embedding"));

        mgr.onMergeCompleted("_0", "embedding");
        assertEquals(0, mgr.getGenerationCount("_0", "embedding"));
        assertEquals(1, mgr.acquireGeneration("_0", "embedding"));
    }

    public void testZeroLimitRejectsImmediately() {
        SidecarGenerationManager mgr = new SidecarGenerationManager(0);
        expectThrows(OpenSearchRejectedExecutionException.class, () -> mgr.acquireGeneration("_0", "embedding"));
    }

    public void testConcurrentAcquireRespectsLimit() throws Exception {
        int maxGen = 10;
        SidecarGenerationManager mgr = new SidecarGenerationManager(maxGen);
        int numThreads = 20;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(numThreads);
        AtomicInteger successes = new AtomicInteger(0);
        AtomicInteger rejections = new AtomicInteger(0);

        for (int i = 0; i < numThreads; i++) {
            new Thread(() -> {
                try {
                    start.await();
                    mgr.acquireGeneration("_0", "embedding");
                    successes.incrementAndGet();
                } catch (OpenSearchRejectedExecutionException e) {
                    rejections.incrementAndGet();
                } catch (Exception e) {
                    // unexpected
                }
                done.countDown();
            }).start();
        }

        start.countDown(); // release all threads
        assertTrue(done.await(10, TimeUnit.SECONDS));

        assertEquals(maxGen, successes.get());
        assertEquals(numThreads - maxGen, rejections.get());
        assertEquals(maxGen, mgr.getGenerationCount("_0", "embedding"));
    }
}
