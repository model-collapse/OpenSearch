/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.action.update.fields;

import org.opensearch.action.support.WriteResponse;
import org.opensearch.action.support.replication.ReplicationResponse;
import org.opensearch.core.common.io.stream.StreamInput;
import org.opensearch.core.common.io.stream.StreamOutput;

import java.io.IOException;

/**
 * Response from a shard-level update_fields operation.
 *
 * @opensearch.internal
 */
public class ShardUpdateFieldsResponse extends ReplicationResponse implements WriteResponse {

    private int updated;
    private int failed;
    private boolean forcedRefresh;

    public ShardUpdateFieldsResponse() {}

    public ShardUpdateFieldsResponse(int updated, int failed) {
        this.updated = updated;
        this.failed = failed;
    }

    public ShardUpdateFieldsResponse(StreamInput in) throws IOException {
        super(in);
        this.updated = in.readVInt();
        this.failed = in.readVInt();
    }

    @Override
    public void writeTo(StreamOutput out) throws IOException {
        super.writeTo(out);
        out.writeVInt(updated);
        out.writeVInt(failed);
    }

    @Override
    public void setForcedRefresh(boolean forcedRefresh) {
        this.forcedRefresh = forcedRefresh;
    }

    public int updated() {
        return updated;
    }

    public int failed() {
        return failed;
    }
}
