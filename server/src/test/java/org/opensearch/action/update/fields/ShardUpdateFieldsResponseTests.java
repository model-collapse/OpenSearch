/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.action.update.fields;

import org.opensearch.action.support.replication.ReplicationResponse;
import org.opensearch.common.io.stream.BytesStreamOutput;
import org.opensearch.core.common.io.stream.StreamInput;
import org.opensearch.test.OpenSearchTestCase;

import java.io.IOException;

/**
 * Tests for {@link ShardUpdateFieldsResponse} serialization and accessors.
 */
public class ShardUpdateFieldsResponseTests extends OpenSearchTestCase {

    private ShardUpdateFieldsResponse createResponse(int updated, int failed) {
        ShardUpdateFieldsResponse response = new ShardUpdateFieldsResponse(updated, failed);
        response.setShardInfo(new ReplicationResponse.ShardInfo(1, 1));
        return response;
    }

    public void testSerialization() throws IOException {
        ShardUpdateFieldsResponse original = createResponse(5, 2);

        BytesStreamOutput out = new BytesStreamOutput();
        original.writeTo(out);

        StreamInput in = out.bytes().streamInput();
        ShardUpdateFieldsResponse deserialized = new ShardUpdateFieldsResponse(in);

        assertEquals(5, deserialized.updated());
        assertEquals(2, deserialized.failed());
    }

    public void testSerializationZeroValues() throws IOException {
        ShardUpdateFieldsResponse original = createResponse(0, 0);

        BytesStreamOutput out = new BytesStreamOutput();
        original.writeTo(out);

        StreamInput in = out.bytes().streamInput();
        ShardUpdateFieldsResponse deserialized = new ShardUpdateFieldsResponse(in);

        assertEquals(0, deserialized.updated());
        assertEquals(0, deserialized.failed());
    }

    public void testSerializationLargeValues() throws IOException {
        ShardUpdateFieldsResponse original = createResponse(10000, 500);

        BytesStreamOutput out = new BytesStreamOutput();
        original.writeTo(out);

        StreamInput in = out.bytes().streamInput();
        ShardUpdateFieldsResponse deserialized = new ShardUpdateFieldsResponse(in);

        assertEquals(10000, deserialized.updated());
        assertEquals(500, deserialized.failed());
    }

    public void testAccessors() {
        ShardUpdateFieldsResponse response = new ShardUpdateFieldsResponse(3, 1);

        assertEquals(3, response.updated());
        assertEquals(1, response.failed());
    }

    public void testSetForcedRefresh() {
        ShardUpdateFieldsResponse response = new ShardUpdateFieldsResponse(1, 0);
        // setForcedRefresh should not throw
        response.setForcedRefresh(true);
        response.setForcedRefresh(false);
    }
}
