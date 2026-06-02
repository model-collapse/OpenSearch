/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.action.admin.indices.updatablefields;

import org.opensearch.action.support.broadcast.BroadcastRequest;
import org.opensearch.core.common.io.stream.StreamInput;

import java.io.IOException;

/**
 * Request for retrieving per-field sidecar statistics for an index.
 * Extends BroadcastRequest so the transport layer fans out to all nodes hosting shards.
 *
 * @opensearch.experimental
 */
public class UpdatableFieldsStatsRequest extends BroadcastRequest<UpdatableFieldsStatsRequest> {

    public UpdatableFieldsStatsRequest(String... indices) {
        super(indices);
    }

    public UpdatableFieldsStatsRequest(StreamInput in) throws IOException {
        super(in);
    }
}
