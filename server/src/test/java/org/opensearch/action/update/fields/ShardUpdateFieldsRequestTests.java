/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.action.update.fields;

import org.opensearch.common.io.stream.BytesStreamOutput;
import org.opensearch.core.common.io.stream.StreamInput;
import org.opensearch.core.index.shard.ShardId;
import org.opensearch.test.OpenSearchTestCase;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

/**
 * Tests for {@link ShardUpdateFieldsRequest} serialization and accessors.
 */
public class ShardUpdateFieldsRequestTests extends OpenSearchTestCase {

    public void testSerialization() throws IOException {
        ShardId shardId = new ShardId("test-index", "_na_", 0);
        List<UpdateFieldsRequest.FieldUpdate> updates = Arrays.asList(
            new UpdateFieldsRequest.FieldUpdate("doc1", new float[] { 0.1f, 0.2f, 0.3f }),
            new UpdateFieldsRequest.FieldUpdate("doc2", new float[] { 0.4f, 0.5f, 0.6f })
        );

        ShardUpdateFieldsRequest original = new ShardUpdateFieldsRequest(shardId, "embedding", updates);

        BytesStreamOutput out = new BytesStreamOutput();
        original.writeTo(out);

        StreamInput in = out.bytes().streamInput();
        ShardUpdateFieldsRequest deserialized = new ShardUpdateFieldsRequest(in);

        assertEquals("embedding", deserialized.field());
        assertEquals(2, deserialized.updates().size());
        assertEquals("doc1", deserialized.updates().get(0).getId());
        assertArrayEquals(new float[] { 0.1f, 0.2f, 0.3f }, deserialized.updates().get(0).getValue(), 0.0001f);
        assertEquals("doc2", deserialized.updates().get(1).getId());
        assertArrayEquals(new float[] { 0.4f, 0.5f, 0.6f }, deserialized.updates().get(1).getValue(), 0.0001f);
        assertEquals(shardId, deserialized.shardId());
    }

    public void testSerializationSingleUpdate() throws IOException {
        ShardId shardId = new ShardId("my-index", "_na_", 2);
        List<UpdateFieldsRequest.FieldUpdate> updates = Arrays.asList(
            new UpdateFieldsRequest.FieldUpdate("single-doc", new float[] { 1.0f })
        );

        ShardUpdateFieldsRequest original = new ShardUpdateFieldsRequest(shardId, "vector_field", updates);

        BytesStreamOutput out = new BytesStreamOutput();
        original.writeTo(out);

        StreamInput in = out.bytes().streamInput();
        ShardUpdateFieldsRequest deserialized = new ShardUpdateFieldsRequest(in);

        assertEquals("vector_field", deserialized.field());
        assertEquals(1, deserialized.updates().size());
        assertEquals("single-doc", deserialized.updates().get(0).getId());
        assertArrayEquals(new float[] { 1.0f }, deserialized.updates().get(0).getValue(), 0.0001f);
    }

    public void testAccessors() {
        ShardId shardId = new ShardId("test-index", "_na_", 0);
        List<UpdateFieldsRequest.FieldUpdate> updates = Arrays.asList(
            new UpdateFieldsRequest.FieldUpdate("doc1", new float[] { 1.0f, 2.0f })
        );

        ShardUpdateFieldsRequest request = new ShardUpdateFieldsRequest(shardId, "my_field", updates);

        assertEquals("my_field", request.field());
        assertEquals(1, request.updates().size());
        assertEquals("doc1", request.updates().get(0).getId());
        assertEquals(shardId, request.shardId());
    }

    public void testToString() {
        ShardId shardId = new ShardId("test-index", "_na_", 0);
        List<UpdateFieldsRequest.FieldUpdate> updates = Arrays.asList(
            new UpdateFieldsRequest.FieldUpdate("doc1", new float[] { 1.0f }),
            new UpdateFieldsRequest.FieldUpdate("doc2", new float[] { 2.0f })
        );

        ShardUpdateFieldsRequest request = new ShardUpdateFieldsRequest(shardId, "embedding", updates);
        String str = request.toString();

        assertTrue(str.contains("embedding"));
        assertTrue(str.contains("2"));
        assertTrue(str.contains("test-index"));
    }
}
