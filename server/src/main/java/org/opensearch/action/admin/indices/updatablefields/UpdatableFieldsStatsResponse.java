/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.action.admin.indices.updatablefields;

import org.opensearch.action.support.broadcast.BroadcastResponse;
import org.opensearch.core.action.support.DefaultShardOperationFailedException;
import org.opensearch.core.common.io.stream.StreamInput;
import org.opensearch.core.common.io.stream.StreamOutput;
import org.opensearch.core.xcontent.XContentBuilder;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Response containing per-field sidecar statistics aggregated across all shards of an index.
 * Extends BroadcastResponse to inherit standard _shards header (total, successful, failed).
 *
 * @opensearch.experimental
 */
public class UpdatableFieldsStatsResponse extends BroadcastResponse {

    private final Map<String, FieldStats> fieldStats;

    public UpdatableFieldsStatsResponse(
        Map<String, FieldStats> fieldStats,
        int totalShards,
        int successfulShards,
        int failedShards,
        List<DefaultShardOperationFailedException> shardFailures
    ) {
        super(totalShards, successfulShards, failedShards, shardFailures);
        this.fieldStats = fieldStats;
    }

    public UpdatableFieldsStatsResponse(StreamInput in) throws IOException {
        super(in);
        int size = in.readVInt();
        Map<String, FieldStats> map = new HashMap<>();
        for (int i = 0; i < size; i++) {
            String field = in.readString();
            map.put(field, new FieldStats(in.readVLong(), in.readVInt()));
        }
        this.fieldStats = map;
    }

    @Override
    public void writeTo(StreamOutput out) throws IOException {
        super.writeTo(out);
        out.writeVInt(fieldStats.size());
        for (Map.Entry<String, FieldStats> entry : fieldStats.entrySet()) {
            out.writeString(entry.getKey());
            out.writeVLong(entry.getValue().docsUpdated);
            out.writeVInt(entry.getValue().segmentsWithSidecars);
        }
    }

    @Override
    protected void addCustomXContentFields(XContentBuilder builder, Params params) throws IOException {
        builder.startObject("fields");
        for (Map.Entry<String, FieldStats> entry : fieldStats.entrySet()) {
            builder.startObject(entry.getKey());
            builder.field("docs_updated", entry.getValue().docsUpdated);
            builder.field("segments_with_sidecars", entry.getValue().segmentsWithSidecars);
            builder.endObject();
        }
        builder.endObject();
    }

    public Map<String, FieldStats> fieldStats() {
        return fieldStats;
    }

    /**
     * Per-field sidecar statistics aggregated across all shards.
     */
    public static class FieldStats {
        private final long docsUpdated;
        private final int segmentsWithSidecars;

        public FieldStats(long docsUpdated, int segmentsWithSidecars) {
            this.docsUpdated = docsUpdated;
            this.segmentsWithSidecars = segmentsWithSidecars;
        }

        public long docsUpdated() {
            return docsUpdated;
        }

        public int segmentsWithSidecars() {
            return segmentsWithSidecars;
        }
    }
}
