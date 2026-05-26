/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.action.update.fields;

import org.opensearch.action.support.replication.ReplicatedWriteRequest;
import org.opensearch.core.common.io.stream.StreamInput;
import org.opensearch.core.common.io.stream.StreamOutput;
import org.opensearch.core.index.shard.ShardId;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * A per-shard request carrying field updates destined for a single shard.
 *
 * @opensearch.internal
 */
public class ShardUpdateFieldsRequest extends ReplicatedWriteRequest<ShardUpdateFieldsRequest> {

    private String field;
    private List<UpdateFieldsRequest.FieldUpdate> updates;

    public ShardUpdateFieldsRequest(ShardId shardId, String field, List<UpdateFieldsRequest.FieldUpdate> updates) {
        super(shardId);
        this.field = field;
        this.updates = updates;
    }

    public ShardUpdateFieldsRequest(StreamInput in) throws IOException {
        super(in);
        this.field = in.readString();
        int size = in.readVInt();
        this.updates = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            updates.add(new UpdateFieldsRequest.FieldUpdate(in));
        }
    }

    @Override
    public void writeTo(StreamOutput out) throws IOException {
        super.writeTo(out);
        out.writeString(field);
        out.writeVInt(updates.size());
        for (UpdateFieldsRequest.FieldUpdate u : updates) {
            u.writeTo(out);
        }
    }

    public String field() {
        return field;
    }

    public List<UpdateFieldsRequest.FieldUpdate> updates() {
        return updates;
    }

    @Override
    public String toString() {
        return "ShardUpdateFieldsRequest{field=" + field + ", updates=" + updates.size() + ", shardId=" + shardId() + "}";
    }
}
