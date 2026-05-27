/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.action.update.fields;

import org.opensearch.action.ActionRequestValidationException;
import org.opensearch.common.io.stream.BytesStreamOutput;
import org.opensearch.core.common.io.stream.StreamInput;
import org.opensearch.test.OpenSearchTestCase;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Tests for {@link UpdateFieldsRequest} validation and serialization.
 */
public class UpdateFieldsRequestTests extends OpenSearchTestCase {

    public void testValidRequestPassesValidation() {
        UpdateFieldsRequest request = new UpdateFieldsRequest(
            "my-index",
            "embedding",
            Arrays.asList(new UpdateFieldsRequest.FieldUpdate("doc1", new float[] { 0.1f, 0.2f, 0.3f }))
        );
        assertNull(request.validate());
    }

    public void testMissingIndexFailsValidation() {
        UpdateFieldsRequest request = new UpdateFieldsRequest(
            null,
            "embedding",
            Arrays.asList(new UpdateFieldsRequest.FieldUpdate("doc1", new float[] { 0.1f }))
        );
        ActionRequestValidationException ex = request.validate();
        assertNotNull(ex);
        assertTrue(ex.getMessage().contains("index is missing"));
    }

    public void testEmptyIndexFailsValidation() {
        UpdateFieldsRequest request = new UpdateFieldsRequest(
            "",
            "embedding",
            Arrays.asList(new UpdateFieldsRequest.FieldUpdate("doc1", new float[] { 0.1f }))
        );
        ActionRequestValidationException ex = request.validate();
        assertNotNull(ex);
        assertTrue(ex.getMessage().contains("index is missing"));
    }

    public void testMissingFieldFailsValidation() {
        UpdateFieldsRequest request = new UpdateFieldsRequest(
            "my-index",
            null,
            Arrays.asList(new UpdateFieldsRequest.FieldUpdate("doc1", new float[] { 0.1f }))
        );
        ActionRequestValidationException ex = request.validate();
        assertNotNull(ex);
        assertTrue(ex.getMessage().contains("field is missing"));
    }

    public void testEmptyFieldFailsValidation() {
        UpdateFieldsRequest request = new UpdateFieldsRequest(
            "my-index",
            "",
            Arrays.asList(new UpdateFieldsRequest.FieldUpdate("doc1", new float[] { 0.1f }))
        );
        ActionRequestValidationException ex = request.validate();
        assertNotNull(ex);
        assertTrue(ex.getMessage().contains("field is missing"));
    }

    public void testNullUpdatesFailsValidation() {
        UpdateFieldsRequest request = new UpdateFieldsRequest("my-index", "embedding", null);
        ActionRequestValidationException ex = request.validate();
        assertNotNull(ex);
        assertTrue(ex.getMessage().contains("updates are missing"));
    }

    public void testEmptyUpdatesFailsValidation() {
        UpdateFieldsRequest request = new UpdateFieldsRequest("my-index", "embedding", Collections.emptyList());
        ActionRequestValidationException ex = request.validate();
        assertNotNull(ex);
        assertTrue(ex.getMessage().contains("updates are missing"));
    }

    public void testMultipleValidationErrors() {
        UpdateFieldsRequest request = new UpdateFieldsRequest(null, null, null);
        ActionRequestValidationException ex = request.validate();
        assertNotNull(ex);
        assertTrue(ex.getMessage().contains("index is missing"));
        assertTrue(ex.getMessage().contains("field is missing"));
        assertTrue(ex.getMessage().contains("updates are missing"));
    }

    public void testSerialization() throws IOException {
        List<UpdateFieldsRequest.FieldUpdate> updates = Arrays.asList(
            new UpdateFieldsRequest.FieldUpdate("doc1", new float[] { 0.1f, 0.2f, 0.3f }),
            new UpdateFieldsRequest.FieldUpdate("doc2", new float[] { 0.4f, 0.5f, 0.6f })
        );
        UpdateFieldsRequest original = new UpdateFieldsRequest("my-index", "embedding", updates);

        BytesStreamOutput out = new BytesStreamOutput();
        original.writeTo(out);

        StreamInput in = out.bytes().streamInput();
        UpdateFieldsRequest deserialized = new UpdateFieldsRequest(in);

        assertEquals("my-index", deserialized.getIndex());
        assertEquals("embedding", deserialized.getField());
        assertEquals(2, deserialized.getUpdates().size());
        assertEquals("doc1", deserialized.getUpdates().get(0).getId());
        assertArrayEquals(new float[] { 0.1f, 0.2f, 0.3f }, deserialized.getUpdates().get(0).getValue(), 0.0001f);
        assertEquals("doc2", deserialized.getUpdates().get(1).getId());
        assertArrayEquals(new float[] { 0.4f, 0.5f, 0.6f }, deserialized.getUpdates().get(1).getValue(), 0.0001f);
    }

    public void testResponseSerialization() throws IOException {
        UpdateFieldsResponse original = new UpdateFieldsResponse(123, 5, 0, 1);

        BytesStreamOutput out = new BytesStreamOutput();
        original.writeTo(out);

        StreamInput in = out.bytes().streamInput();
        UpdateFieldsResponse deserialized = new UpdateFieldsResponse(in);

        assertEquals(123, deserialized.getTookInMillis());
        assertEquals(5, deserialized.getUpdated());
        assertEquals(0, deserialized.getFailed());
        assertEquals(1, deserialized.getSidecarsCreated());
    }

    public void testFieldUpdateSerialization() throws IOException {
        UpdateFieldsRequest.FieldUpdate original = new UpdateFieldsRequest.FieldUpdate("doc1", new float[] { 1.0f, 2.0f, 3.0f });

        BytesStreamOutput out = new BytesStreamOutput();
        original.writeTo(out);

        StreamInput in = out.bytes().streamInput();
        UpdateFieldsRequest.FieldUpdate deserialized = new UpdateFieldsRequest.FieldUpdate(in);

        assertEquals("doc1", deserialized.getId());
        assertArrayEquals(new float[] { 1.0f, 2.0f, 3.0f }, deserialized.getValue(), 0.0001f);
    }

    public void testFieldUpdateWithStringValue() {
        UpdateFieldsRequest.FieldUpdate update = new UpdateFieldsRequest.FieldUpdate("doc1", (Object) "science");
        assertEquals("doc1", update.getId());
        assertNull(update.getValue());
        assertEquals("science", update.getScalarValue());
        assertTrue(update.isScalarUpdate());
        assertFalse(update.isVectorUpdate());
    }

    public void testFieldUpdateWithLongValue() {
        UpdateFieldsRequest.FieldUpdate update = new UpdateFieldsRequest.FieldUpdate("doc1", (Object) 42L);
        assertEquals(42L, update.getScalarValue());
        assertTrue(update.isScalarUpdate());
        assertFalse(update.isVectorUpdate());
    }

    public void testFieldUpdateWithDoubleValue() {
        UpdateFieldsRequest.FieldUpdate update = new UpdateFieldsRequest.FieldUpdate("doc1", (Object) 3.14);
        assertEquals(3.14, update.getScalarValue());
        assertTrue(update.isScalarUpdate());
        assertFalse(update.isVectorUpdate());
    }

    public void testFieldUpdateWithBooleanValue() {
        UpdateFieldsRequest.FieldUpdate update = new UpdateFieldsRequest.FieldUpdate("doc1", (Object) true);
        assertEquals(true, update.getScalarValue());
        assertTrue(update.isScalarUpdate());
        assertFalse(update.isVectorUpdate());
    }

    public void testFieldUpdateSerializationScalar() throws IOException {
        UpdateFieldsRequest.FieldUpdate original = new UpdateFieldsRequest.FieldUpdate("doc1", (Object) "keyword-value");
        BytesStreamOutput out = new BytesStreamOutput();
        original.writeTo(out);
        StreamInput in = out.bytes().streamInput();
        UpdateFieldsRequest.FieldUpdate deserialized = new UpdateFieldsRequest.FieldUpdate(in);
        assertEquals(original.getId(), deserialized.getId());
        assertEquals(original.getScalarValue(), deserialized.getScalarValue());
        assertNull(deserialized.getValue());
        assertTrue(deserialized.isScalarUpdate());
        assertFalse(deserialized.isVectorUpdate());
    }

    public void testFieldUpdateSerializationScalarLong() throws IOException {
        UpdateFieldsRequest.FieldUpdate original = new UpdateFieldsRequest.FieldUpdate("doc1", (Object) 42L);
        BytesStreamOutput out = new BytesStreamOutput();
        original.writeTo(out);
        StreamInput in = out.bytes().streamInput();
        UpdateFieldsRequest.FieldUpdate deserialized = new UpdateFieldsRequest.FieldUpdate(in);
        assertEquals(original.getId(), deserialized.getId());
        assertEquals(42L, deserialized.getScalarValue());
        assertNull(deserialized.getValue());
    }

    public void testFieldUpdateSerializationScalarDouble() throws IOException {
        UpdateFieldsRequest.FieldUpdate original = new UpdateFieldsRequest.FieldUpdate("doc1", (Object) 2.718);
        BytesStreamOutput out = new BytesStreamOutput();
        original.writeTo(out);
        StreamInput in = out.bytes().streamInput();
        UpdateFieldsRequest.FieldUpdate deserialized = new UpdateFieldsRequest.FieldUpdate(in);
        assertEquals(original.getId(), deserialized.getId());
        assertEquals(2.718, deserialized.getScalarValue());
        assertNull(deserialized.getValue());
    }

    public void testFieldUpdateSerializationScalarBoolean() throws IOException {
        UpdateFieldsRequest.FieldUpdate original = new UpdateFieldsRequest.FieldUpdate("doc1", (Object) true);
        BytesStreamOutput out = new BytesStreamOutput();
        original.writeTo(out);
        StreamInput in = out.bytes().streamInput();
        UpdateFieldsRequest.FieldUpdate deserialized = new UpdateFieldsRequest.FieldUpdate(in);
        assertEquals(original.getId(), deserialized.getId());
        assertEquals(true, deserialized.getScalarValue());
        assertNull(deserialized.getValue());
    }

    public void testFieldUpdateBackwardCompatVector() throws IOException {
        // Existing vector updates still work
        UpdateFieldsRequest.FieldUpdate original = new UpdateFieldsRequest.FieldUpdate("doc1", new float[] { 1.0f, 2.0f });
        BytesStreamOutput out = new BytesStreamOutput();
        original.writeTo(out);
        StreamInput in = out.bytes().streamInput();
        UpdateFieldsRequest.FieldUpdate deserialized = new UpdateFieldsRequest.FieldUpdate(in);
        assertEquals(original.getId(), deserialized.getId());
        assertArrayEquals(original.getValue(), deserialized.getValue(), 0.001f);
        assertNull(deserialized.getScalarValue());
        assertTrue(deserialized.isVectorUpdate());
        assertFalse(deserialized.isScalarUpdate());
    }
}
