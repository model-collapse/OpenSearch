/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.index.engine.sidecar;

/**
 * Determines whether a sidecar update should be promoted to a full reindex
 * based on the percentage of documents being updated in a segment.
 */
public class AutoPromoteChecker {

    public static final float DEFAULT_THRESHOLD = 0.30f;

    private final float threshold;

    public AutoPromoteChecker(float threshold) {
        if (threshold <= 0 || threshold > 1.0f) {
            throw new IllegalArgumentException("threshold must be between 0 (exclusive) and 1.0 (inclusive), got: " + threshold);
        }
        this.threshold = threshold;
    }

    public AutoPromoteChecker() {
        this(DEFAULT_THRESHOLD);
    }

    /**
     * Check if the update should be promoted to full reindex.
     *
     * @param docsToUpdate number of documents being updated in this batch
     * @param totalDocsInSegment total documents in the target segment
     * @return true if the update should be promoted to full reindex
     */
    public boolean shouldPromote(long docsToUpdate, long totalDocsInSegment) {
        if (totalDocsInSegment <= 0) {
            return false; // empty segment, nothing to promote
        }
        float ratio = (float) docsToUpdate / totalDocsInSegment;
        return ratio > threshold;
    }

    /**
     * Check if cumulative updates (existing sidecars + new batch) exceed threshold.
     *
     * @param existingSidecarDocs docs already in sidecars for this field on this segment
     * @param newDocsToUpdate additional docs being updated now
     * @param totalDocsInSegment total documents in the segment
     * @return true if cumulative updates exceed threshold
     */
    public boolean shouldPromoteCumulative(long existingSidecarDocs, long newDocsToUpdate, long totalDocsInSegment) {
        if (totalDocsInSegment <= 0) {
            return false;
        }
        float ratio = (float) (existingSidecarDocs + newDocsToUpdate) / totalDocsInSegment;
        return ratio > threshold;
    }

    public float threshold() {
        return threshold;
    }
}
