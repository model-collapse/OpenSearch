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

/**
 * Response for the updatable fields merge action.
 *
 * @opensearch.experimental
 */
public class UpdatableFieldsMergeResponse extends ActionResponse implements ToXContentObject {

    private final int mergedSegments;
    private final long tookMillis;
    private final boolean acknowledged;

    public UpdatableFieldsMergeResponse(int mergedSegments, long tookMillis, boolean acknowledged) {
        this.mergedSegments = mergedSegments;
        this.tookMillis = tookMillis;
        this.acknowledged = acknowledged;
    }

    public UpdatableFieldsMergeResponse(StreamInput in) throws IOException {
        super(in);
        this.mergedSegments = in.readVInt();
        this.tookMillis = in.readVLong();
        this.acknowledged = in.readBoolean();
    }

    @Override
    public void writeTo(StreamOutput out) throws IOException {
        out.writeVInt(mergedSegments);
        out.writeVLong(tookMillis);
        out.writeBoolean(acknowledged);
    }

    @Override
    public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
        builder.startObject();
        builder.field("acknowledged", acknowledged);
        builder.field("merged_segments", mergedSegments);
        builder.field("took_millis", tookMillis);
        builder.endObject();
        return builder;
    }

    public int mergedSegments() {
        return mergedSegments;
    }

    public long tookMillis() {
        return tookMillis;
    }

    public boolean acknowledged() {
        return acknowledged;
    }
}
