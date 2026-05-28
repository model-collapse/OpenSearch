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

import java.util.HashMap;
import java.util.Map;
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
     * Returns a segment-aware exclusion filter that resolves the correct bitmap per leaf context.
     * This is suitable for use as a KnnFloatVectorQuery filter parameter, where Lucene handles
     * per-leaf dispatch internally. Each leaf gets its own segment-local bitmap, eliminating
     * the need for docBase offset calculations.
     *
     * @param fieldName the vector field name
     * @param maxDoc    the total maximum document count across all segments (i.e., IndexReader.maxDoc())
     * @return a {@link SidecarKnnFilter} that excludes dirty docs per-segment, or null if none are dirty
     */
    public Query getGlobalExclusionFilter(String fieldName, int maxDoc) {
        if (!registry.hasAnySidecars()) {
            return null;
        }

        Set<String> segments = registry.getSegmentsForField(fieldName);
        if (segments.isEmpty()) {
            return null;
        }

        Map<String, SidecarVersionBitmap> segmentBitmaps = new HashMap<>();
        for (String segment : segments) {
            SidecarVersionBitmap bitmap = registry.getBitmap(fieldName, segment);
            if (bitmap != null && bitmap.cardinality() > 0) {
                segmentBitmaps.put(segment, bitmap);
            }
        }

        if (segmentBitmaps.isEmpty()) {
            return null;
        }

        return new SidecarKnnFilter(segmentBitmaps, maxDoc);
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
