/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.action.admin.indices.updatablefields;

import org.opensearch.core.action.ActionResponse;
import org.opensearch.core.common.io.stream.StreamInput;
import org.opensearch.core.common.io.stream.StreamOutput;
import org.opensearch.core.xcontent.ToXContentObject;
import org.opensearch.core.xcontent.XContentBuilder;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * Response containing per-field sidecar statistics for an index.
 *
 * @opensearch.experimental
 */
public class UpdatableFieldsStatsResponse extends ActionResponse implements ToXContentObject {

    private final Map<String, FieldStats> fieldStats;
    private final int totalShards;
    private final int localShardsQueried;

    public UpdatableFieldsStatsResponse(Map<String, FieldStats> fieldStats, int totalShards, int localShardsQueried) {
        this.fieldStats = fieldStats;
        this.totalShards = totalShards;
        this.localShardsQueried = localShardsQueried;
    }

    public UpdatableFieldsStatsResponse(StreamInput in) throws IOException {
        super(in);
        int size = in.readVInt();
        Map<String, FieldStats> map = new HashMap<>();
        for (int i = 0; i < size; i++) {
            String field = in.readString();
            map.put(field, new FieldStats(in.readVInt(), in.readVLong(), in.readVInt()));
        }
        this.fieldStats = map;
        this.totalShards = in.readVInt();
        this.localShardsQueried = in.readVInt();
    }

    @Override
    public void writeTo(StreamOutput out) throws IOException {
        out.writeVInt(fieldStats.size());
        for (Map.Entry<String, FieldStats> entry : fieldStats.entrySet()) {
            out.writeString(entry.getKey());
            out.writeVInt(entry.getValue().generations);
            out.writeVLong(entry.getValue().docsUpdated);
            out.writeVInt(entry.getValue().segmentsWithSidecars);
        }
        out.writeVInt(totalShards);
        out.writeVInt(localShardsQueried);
    }

    @Override
    public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
        builder.startObject();
        builder.field("total_shards", totalShards);
        builder.field("shards_queried", localShardsQueried);
        builder.field("_local_only", localShardsQueried < totalShards);
        builder.startObject("fields");
        for (Map.Entry<String, FieldStats> entry : fieldStats.entrySet()) {
            builder.startObject(entry.getKey());
            builder.field("generations", entry.getValue().generations);
            builder.field("docs_updated", entry.getValue().docsUpdated);
            builder.field("segments_with_sidecars", entry.getValue().segmentsWithSidecars);
            builder.endObject();
        }
        builder.endObject();
        builder.endObject();
        return builder;
    }

    public Map<String, FieldStats> fieldStats() {
        return fieldStats;
    }

    public int totalShards() {
        return totalShards;
    }

    public int localShardsQueried() {
        return localShardsQueried;
    }

    /**
     * Per-field sidecar statistics.
     */
    public static class FieldStats {
        private final int generations;
        private final long docsUpdated;
        private final int segmentsWithSidecars;

        public FieldStats(int generations, long docsUpdated, int segmentsWithSidecars) {
            this.generations = generations;
            this.docsUpdated = docsUpdated;
            this.segmentsWithSidecars = segmentsWithSidecars;
        }

        public int generations() {
            return generations;
        }

        public long docsUpdated() {
            return docsUpdated;
        }

        public int segmentsWithSidecars() {
            return segmentsWithSidecars;
        }
    }
}
