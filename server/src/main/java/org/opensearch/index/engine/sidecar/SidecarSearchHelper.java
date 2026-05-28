/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.index.engine.sidecar;

import org.apache.lucene.search.Query;
import org.opensearch.common.annotation.ExperimentalApi;

import java.util.Set;

/**
 * Helper that provides search-time integration for sidecar fields.
 * Used by the search layer to augment queries when sidecars are active,
 * particularly KNN queries where stale vectors in the base segment must
 * be excluded.
 *
 * @opensearch.experimental
 */
@ExperimentalApi
public class SidecarSearchHelper {

    private final SidecarRegistry registry;

    public SidecarSearchHelper(SidecarRegistry registry) {
        this.registry = registry;
    }

    /**
     * Returns a filter query that excludes documents with sidecar updates for the given field.
     * Returns null if no sidecars are active for this field and segment.
     *
     * @param fieldName   the vector field name
     * @param segmentName the segment identifier
     * @param maxDoc      the maximum document count in the segment
     * @return a {@link SidecarKnnFilter} that excludes dirty docs, or null if none are dirty
     */
    public Query getExclusionFilter(String fieldName, String segmentName, int maxDoc) {
        if (!registry.hasAnySidecars()) {
            return null;
        }

        SidecarVersionBitmap bitmap = registry.getBitmap(fieldName, segmentName);
        if (bitmap == null || bitmap.cardinality() == 0) {
            return null;
        }

        return new SidecarKnnFilter(bitmap, maxDoc);
    }

    /**
     * Returns a global exclusion filter that unions per-segment bitmaps for a field into a single
     * filter covering all segments. This is suitable for use as a KnnFloatVectorQuery filter
     * parameter, where Lucene handles per-leaf dispatch internally.
     *
     * @param fieldName the vector field name
     * @param maxDoc    the total maximum document count across all segments (i.e., IndexReader.maxDoc())
     * @return a {@link SidecarKnnFilter} that excludes all dirty docs across segments, or null if none are dirty
     */
    public Query getGlobalExclusionFilter(String fieldName, int maxDoc) {
        if (!registry.hasAnySidecars()) {
            return null;
        }

        Set<String> segments = registry.getSegmentsForField(fieldName);
        if (segments.isEmpty()) {
            return null;
        }

        SidecarVersionBitmap globalBitmap = new SidecarVersionBitmap(maxDoc);
        boolean anySet = false;
        for (String segment : segments) {
            SidecarVersionBitmap segBitmap = registry.getBitmap(fieldName, segment);
            if (segBitmap != null && segBitmap.cardinality() > 0) {
                for (int doc = segBitmap.nextSetBit(0); doc != -1 && doc < segBitmap.maxDoc(); doc = doc + 1 < segBitmap.maxDoc()
                    ? segBitmap.nextSetBit(doc + 1)
                    : -1) {
                    if (doc < maxDoc) {
                        globalBitmap.set(doc);
                        anySet = true;
                    }
                }
            }
        }

        if (!anySet) {
            return null;
        }

        return new SidecarKnnFilter(globalBitmap, maxDoc);
    }

    /**
     * Checks if a given field has active sidecar data that needs search-time handling.
     *
     * @param fieldName the field to check
     * @return true if the field has at least one segment with sidecar data
     */
    public boolean hasActiveSidecars(String fieldName) {
        return registry.getSegmentCount(fieldName) > 0;
    }
}
