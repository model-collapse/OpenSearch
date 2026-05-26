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

public class DataFormatAwareEngineLockingTests extends OpenSearchTestCase {

    public void testVersionMapLockSerializesConcurrentAccess() throws Exception {
        LiveVersionMap versionMap = new LiveVersionMap();
        BytesRef uid = new BytesRef("doc1");
        CountDownLatch t1HasLock = new CountDownLatch(1);
        CountDownLatch t2Waiting = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);
        AtomicBoolean t2WaitedForLock = new AtomicBoolean(false);

        Thread t1 = new Thread(() -> {
            try (var lock = versionMap.acquireLock(uid)) {
                t1HasLock.countDown(); // signal t1 holds lock
                t2Waiting.await(5, TimeUnit.SECONDS); // wait for t2 to be blocked
                Thread.sleep(20); // hold a bit longer to ensure t2 is really waiting
            } catch (Exception e) { /* */ }
            done.countDown();
        });

        Thread t2 = new Thread(() -> {
            try {
                t1HasLock.await(5, TimeUnit.SECONDS); // ensure t1 has lock first
                t2Waiting.countDown(); // signal we're about to try acquiring
                long start = System.nanoTime();
                try (var lock = versionMap.acquireLock(uid)) {
                    long waited = (System.nanoTime() - start) / 1_000_000;
                    t2WaitedForLock.set(waited >= 10); // should have waited
                }
            } catch (Exception e) { /* */ }
            done.countDown();
        });

        t1.start();
        t2.start();
        assertTrue("Threads should complete within 10s", done.await(10, TimeUnit.SECONDS));
        assertTrue("Thread 2 should have waited for lock", t2WaitedForLock.get());
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
