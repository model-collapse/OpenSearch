/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.index.engine.sidecar;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.SegmentReader;
import org.opensearch.common.annotation.ExperimentalApi;

import java.util.HashSet;
import java.util.Set;

/**
 * Detects when sidecar-tracked segments have been merged away and cleans up
 * orphaned entries from the registry.
 *
 * Called after each refresh to reconcile registry state with live segments.
 * When a background Lucene merge merges segments (e.g., _0 + _1 into _2),
 * any sidecars registered against the old segments become orphaned. This
 * listener removes those orphaned entries to prevent serving stale data.
 *
 * Phase 1: orphaned sidecars are simply removed (data reverts to base).
 * Phase 2: orphaned sidecars will be remapped into the merged segment.
 *
 * @opensearch.experimental
 */
@ExperimentalApi
public class SidecarMergeListener {

    private static final Logger logger = LogManager.getLogger(SidecarMergeListener.class);

    private final SidecarRegistry registry;

    public SidecarMergeListener(SidecarRegistry registry) {
        this.registry = registry;
    }

    /**
     * Reconcile registry with current live segments. Any registered segments
     * not present in the current reader have been merged away and their
     * sidecar entries are removed.
     *
     * @param reader the current DirectoryReader after refresh
     */
    public void onRefresh(DirectoryReader reader) {
        if (!registry.hasAnySidecars()) {
            return;
        }

        // Get live segment names from the reader
        Set<String> liveSegments = new HashSet<>();
        for (LeafReaderContext ctx : reader.leaves()) {
            if (ctx.reader() instanceof SegmentReader sr) {
                liveSegments.add(sr.getSegmentName());
            }
        }

        // Find orphaned segments in registry and remove them
        Set<String> registeredSegments = registry.getAllRegisteredSegments();
        for (String regSegment : registeredSegments) {
            if (!liveSegments.contains(regSegment)) {
                logger.debug("Segment [{}] merged away, removing orphaned sidecar entries", regSegment);
                registry.unregisterSegment(regSegment);
            }
        }
    }
}
