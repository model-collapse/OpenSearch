/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.action.admin.indices.updatablefields;

import org.opensearch.core.common.io.stream.StreamInput;
import org.opensearch.core.common.io.stream.StreamOutput;
import org.opensearch.core.common.io.stream.Writeable;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * Per-shard result for updatable fields statistics.
 * Contains per-field dirty doc count and segment count for a single shard.
 *
 * @opensearch.experimental
 */
public class ShardUpdatableFieldsStats implements Writeable {

    private final Map<String, long[]> fieldStats;

    /**
     * Creates stats for a single shard.
     *
     * @param fieldStats map of field name to [dirtyDocCount, segmentCount]
     */
    public ShardUpdatableFieldsStats(Map<String, long[]> fieldStats) {
        this.fieldStats = fieldStats;
    }

    public ShardUpdatableFieldsStats(StreamInput in) throws IOException {
        int size = in.readVInt();
        this.fieldStats = new HashMap<>(size);
        for (int i = 0; i < size; i++) {
            String field = in.readString();
            long dirtyDocs = in.readVLong();
            int segmentCount = in.readVInt();
            fieldStats.put(field, new long[] { dirtyDocs, segmentCount });
        }
    }

    @Override
    public void writeTo(StreamOutput out) throws IOException {
        out.writeVInt(fieldStats.size());
        for (Map.Entry<String, long[]> entry : fieldStats.entrySet()) {
            out.writeString(entry.getKey());
            out.writeVLong(entry.getValue()[0]);
            out.writeVInt((int) entry.getValue()[1]);
        }
    }

    /**
     * Returns per-field stats: field name to [dirtyDocCount, segmentCount].
     */
    public Map<String, long[]> fieldStats() {
        return fieldStats;
    }
}
