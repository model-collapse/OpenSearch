/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.action.update.fields;

import org.opensearch.core.action.ActionResponse;
import org.opensearch.core.common.io.stream.StreamInput;
import org.opensearch.core.common.io.stream.StreamOutput;
import org.opensearch.core.xcontent.ToXContent;
import org.opensearch.core.xcontent.ToXContentObject;
import org.opensearch.core.xcontent.XContentBuilder;

import java.io.IOException;

/**
 * Response for the _update_fields action containing metrics about the batch operation.
 *
 * @opensearch.internal
 */
public class UpdateFieldsResponse extends ActionResponse implements ToXContentObject {

    private final long tookInMillis;
    private final int updated;
    private final int failed;
    private final int sidecarsCreated;

    public UpdateFieldsResponse(long tookInMillis, int updated, int failed, int sidecarsCreated) {
        this.tookInMillis = tookInMillis;
        this.updated = updated;
        this.failed = failed;
        this.sidecarsCreated = sidecarsCreated;
    }

    public UpdateFieldsResponse(StreamInput in) throws IOException {
        super(in);
        this.tookInMillis = in.readVLong();
        this.updated = in.readVInt();
        this.failed = in.readVInt();
        this.sidecarsCreated = in.readVInt();
    }

    @Override
    public void writeTo(StreamOutput out) throws IOException {
        out.writeVLong(tookInMillis);
        out.writeVInt(updated);
        out.writeVInt(failed);
        out.writeVInt(sidecarsCreated);
    }

    @Override
    public XContentBuilder toXContent(XContentBuilder builder, ToXContent.Params params) throws IOException {
        builder.startObject();
        builder.field("took", tookInMillis);
        builder.field("updated", updated);
        builder.field("failed", failed);
        builder.field("sidecars_created", sidecarsCreated);
        builder.endObject();
        return builder;
    }

    public long getTookInMillis() {
        return tookInMillis;
    }

    public int getUpdated() {
        return updated;
    }

    public int getFailed() {
        return failed;
    }

    public int getSidecarsCreated() {
        return sidecarsCreated;
    }
}
