/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.index.engine.sidecar;

import org.opensearch.common.annotation.ExperimentalApi;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-shard registry that tracks active sidecar bitmaps per (field, segment) pair.
 * Provides the lookup function for {@link SidecarAwareDirectoryReader} to determine
 * which documents need _source patching at read time.
 *
 * @opensearch.experimental
 */
@ExperimentalApi
public class SidecarRegistry {

    // Map: fieldName -> Map<segmentName, SidecarVersionBitmap>
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, SidecarVersionBitmap>> activeBitmaps = new ConcurrentHashMap<>();

    /**
     * Registers a sidecar bitmap for a given field and segment.
     */
    public void register(String fieldName, String segmentName, SidecarVersionBitmap bitmap) {
        activeBitmaps.computeIfAbsent(fieldName, k -> new ConcurrentHashMap<>())
            .put(segmentName, bitmap);
    }

    /**
     * Unregisters a sidecar bitmap for a given field and segment.
     */
    public void unregister(String fieldName, String segmentName) {
        ConcurrentHashMap<String, SidecarVersionBitmap> segments = activeBitmaps.get(fieldName);
        if (segments != null) {
            segments.remove(segmentName);
            if (segments.isEmpty()) {
                activeBitmaps.remove(fieldName, segments);
            }
        }
    }

    /**
     * Returns true if the given document in the given segment has a dirty sidecar value for the field.
     */
    public boolean isDirty(String fieldName, String segmentName, int docId) {
        ConcurrentHashMap<String, SidecarVersionBitmap> segments = activeBitmaps.get(fieldName);
        if (segments == null) return false;
        SidecarVersionBitmap bitmap = segments.get(segmentName);
        if (bitmap == null) return false;
        return docId < bitmap.maxDoc() && bitmap.get(docId);
    }

    /**
     * Returns sidecar overlay values for a given document in a given segment.
     * Phase 1: sidecar values are not available for _source overlay. The search/agg
     * paths read directly from sidecar DocValues/vectors via SidecarDocValuesProvider.
     * Returning empty map means "don't patch _source" which is correct for now.
     */
    public Map<String, Object> getSidecarValues(String fieldName, String segmentName, int docId) {
        // Phase 1: sidecar values not available for _source overlay.
        // Search/agg paths read directly from sidecar DocValues/vectors.
        return Collections.emptyMap();
    }

    /**
     * Returns the set of fields that have active sidecars.
     */
    public Set<String> getUpdatableFields() {
        return Collections.unmodifiableSet(activeBitmaps.keySet());
    }

    /**
     * Returns true if there are any active sidecars registered.
     */
    public boolean hasAnySidecars() {
        return !activeBitmaps.isEmpty();
    }

    /**
     * Returns the total number of dirty documents across all segments for a given field.
     */
    public long getDirtyDocCount(String fieldName) {
        ConcurrentHashMap<String, SidecarVersionBitmap> segments = activeBitmaps.get(fieldName);
        if (segments == null) return 0;
        return segments.values().stream().mapToLong(SidecarVersionBitmap::cardinality).sum();
    }

    /**
     * Returns the number of segments that have sidecar bitmaps for a given field.
     */
    public int getSegmentCount(String fieldName) {
        ConcurrentHashMap<String, SidecarVersionBitmap> segments = activeBitmaps.get(fieldName);
        return segments != null ? segments.size() : 0;
    }
}
