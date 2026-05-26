/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.index.engine;

import org.apache.lucene.util.BytesRef;
import org.opensearch.test.OpenSearchTestCase;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public class DataFormatAwareEngineLockingTests extends OpenSearchTestCase {

    public void testVersionMapLockSerializesConcurrentAccess() throws Exception {
        LiveVersionMap versionMap = new LiveVersionMap();
        BytesRef uid = new BytesRef("doc1");
        AtomicLong holdTime = new AtomicLong(0);
        CountDownLatch latch = new CountDownLatch(2);

        Thread t1 = new Thread(() -> {
            try (var lock = versionMap.acquireLock(uid)) {
                Thread.sleep(50);
            } catch (Exception e) { /* */ }
            latch.countDown();
        });

        Thread t2 = new Thread(() -> {
            try {
                Thread.sleep(10); // ensure t1 gets lock first
            } catch (InterruptedException e) { /* */ }
            long start = System.nanoTime();
            try (var lock = versionMap.acquireLock(uid)) {
                holdTime.set((System.nanoTime() - start) / 1_000_000);
            }
            latch.countDown();
        });

        t1.start();
        t2.start();
        latch.await();
        assertTrue(
            "Thread 2 should have waited at least 30ms for lock, waited " + holdTime.get() + "ms",
            holdTime.get() >= 30
        );
    }

    public void testDifferentDocIdsDoNotBlock() throws Exception {
        // Two threads locking different UIDs should NOT block each other
        LiveVersionMap versionMap = new LiveVersionMap();
        BytesRef uid1 = new BytesRef("doc1");
        BytesRef uid2 = new BytesRef("doc2");
        CountDownLatch bothAcquired = new CountDownLatch(2);
        AtomicBoolean conflict = new AtomicBoolean(false);

        Thread t1 = new Thread(() -> {
            try (var lock = versionMap.acquireLock(uid1)) {
                bothAcquired.countDown();
                bothAcquired.await();
            } catch (Exception e) {
                conflict.set(true);
            }
        });

        Thread t2 = new Thread(() -> {
            try (var lock = versionMap.acquireLock(uid2)) {
                bothAcquired.countDown();
                bothAcquired.await();
            } catch (Exception e) {
                conflict.set(true);
            }
        });

        t1.start();
        t2.start();
        boolean completed = bothAcquired.await(5, TimeUnit.SECONDS);
        assertTrue("Both threads should acquire locks concurrently for different UIDs", completed);
        assertFalse(conflict.get());
    }
}
