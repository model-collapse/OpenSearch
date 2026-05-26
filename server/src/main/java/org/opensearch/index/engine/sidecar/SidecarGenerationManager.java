/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.index.engine.sidecar;

import org.opensearch.core.concurrency.OpenSearchRejectedExecutionException;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Tracks sidecar generation count per (segment, field) pair and enforces
 * a maximum generation limit as backpressure. When the limit is reached,
 * an {@link OpenSearchRejectedExecutionException} is thrown to signal that
 * the caller should wait for background merges to consolidate generations.
 *
 * @opensearch.internal
 */
public class SidecarGenerationManager {

    private final int maxGenerationsPerSegment;
    private final Map<String, AtomicInteger> generationCounts = new ConcurrentHashMap<>();

    public SidecarGenerationManager(int maxGenerationsPerSegment) {
        this.maxGenerationsPerSegment = maxGenerationsPerSegment;
    }

    public long acquireGeneration(String segmentName, String fieldName) {
        String key = segmentName + ":" + fieldName;
        AtomicInteger count = generationCounts.computeIfAbsent(key, k -> new AtomicInteger(0));

        while (true) {
            int current = count.get();
            if (current >= maxGenerationsPerSegment) {
                throw new OpenSearchRejectedExecutionException(
                    "sidecar generation limit reached for field ["
                        + fieldName
                        + "] on segment ["
                        + segmentName
                        + "]: "
                        + current
                        + " >= "
                        + maxGenerationsPerSegment
                        + ". Wait for background merge to consolidate."
                );
            }
            if (count.compareAndSet(current, current + 1)) {
                return current + 1;
            }
            // CAS failed, retry
        }
    }

    public void onMergeCompleted(String segmentName, String fieldName) {
        String key = segmentName + ":" + fieldName;
        generationCounts.remove(key);
    }

    public int getGenerationCount(String segmentName, String fieldName) {
        String key = segmentName + ":" + fieldName;
        AtomicInteger count = generationCounts.get(key);
        return count != null ? count.get() : 0;
    }

    public int maxGenerations() {
        return maxGenerationsPerSegment;
    }
}
