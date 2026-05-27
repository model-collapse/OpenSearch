/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.index.reindex;

import org.opensearch.action.search.SearchRequest;
import org.opensearch.common.io.stream.BytesStreamOutput;
import org.opensearch.core.common.io.stream.StreamInput;
import org.opensearch.test.OpenSearchTestCase;

import java.util.List;

public class UpdateFieldsByQueryRequestTests extends OpenSearchTestCase {

    public void testSerializationRoundTrip() throws Exception {
        UpdateFieldsByQueryRequest original = new UpdateFieldsByQueryRequest(new SearchRequest("test-index"));
        original.setField("embedding");
        original.setPipeline("my-pipeline");
        original.setSourceFields(List.of("title", "content"));
        original.setRequestsPerSecond(100.0f);
        original.setSlices(5);

        BytesStreamOutput out = new BytesStreamOutput();
        original.writeTo(out);
        StreamInput in = out.bytes().streamInput();
        UpdateFieldsByQueryRequest deserialized = new UpdateFieldsByQueryRequest(in);

        assertEquals("embedding", deserialized.getField());
        assertEquals("my-pipeline", deserialized.getPipeline());
        assertEquals(List.of("title", "content"), deserialized.getSourceFields());
        assertEquals(100.0f, deserialized.getRequestsPerSecond(), 0.001f);
        assertEquals(5, deserialized.getSlices());
    }

    public void testDefaultValues() {
        UpdateFieldsByQueryRequest request = new UpdateFieldsByQueryRequest();
        assertNull(request.getField());
        assertNull(request.getPipeline());
        assertNull(request.getSourceFields());
        assertEquals(Float.POSITIVE_INFINITY, request.getRequestsPerSecond(), 0.001f);
        // DEFAULT_SLICES is private, but its value is 1
        assertEquals(1, request.getSlices());
    }

    public void testSelf() {
        UpdateFieldsByQueryRequest request = new UpdateFieldsByQueryRequest();
        assertSame(request, request.setField("f").setPipeline("p").setSourceFields(List.of("a")));
    }

    public void testToString() {
        UpdateFieldsByQueryRequest request = new UpdateFieldsByQueryRequest();
        request.setField("embedding");
        request.setPipeline("embed-v2");
        String str = request.toString();
        assertTrue(str.contains("embedding"));
        assertTrue(str.contains("embed-v2"));
    }
}
