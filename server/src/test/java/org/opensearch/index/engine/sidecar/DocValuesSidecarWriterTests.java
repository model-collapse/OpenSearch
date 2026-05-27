/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.index.engine.sidecar;

import org.opensearch.test.OpenSearchTestCase;

import java.nio.file.Path;

public class DocValuesSidecarWriterTests extends OpenSearchTestCase {

    public void testWriteKeywordAndFlush() throws Exception {
        Path tmpDir = createTempDir();
        try (DocValuesSidecarWriter writer = new DocValuesSidecarWriter(
                tmpDir, "category", DocValuesSidecarWriter.DocValuesType.SORTED_SET, 1)) {
            writer.write(0, "science");
            writer.write(1, "technology");
            writer.write(5, "art");

            SidecarWriter.SidecarWriteResult result = writer.flush();
            assertNotNull(result);
            assertEquals(3, result.numDocs());
            assertTrue(result.files().size() > 0);
            assertTrue(result.bitmap().get(0));
            assertTrue(result.bitmap().get(1));
            assertTrue(result.bitmap().get(5));
            assertFalse(result.bitmap().get(2));
        }
    }

    public void testWriteLongAndFlush() throws Exception {
        Path tmpDir = createTempDir();
        try (DocValuesSidecarWriter writer = new DocValuesSidecarWriter(
                tmpDir, "score", DocValuesSidecarWriter.DocValuesType.SORTED_NUMERIC, 1)) {
            writer.write(0, 100L);
            writer.write(3, 200L);

            SidecarWriter.SidecarWriteResult result = writer.flush();
            assertNotNull(result);
            assertEquals(2, result.numDocs());
        }
    }

    public void testWriteDoubleConverted() throws Exception {
        Path tmpDir = createTempDir();
        try (DocValuesSidecarWriter writer = new DocValuesSidecarWriter(
                tmpDir, "relevance", DocValuesSidecarWriter.DocValuesType.SORTED_NUMERIC, 1)) {
            writer.write(0, 0.95);
            writer.write(1, 0.87);

            SidecarWriter.SidecarWriteResult result = writer.flush();
            assertNotNull(result);
            assertEquals(2, result.numDocs());
        }
    }

    public void testWriteBoolean() throws Exception {
        Path tmpDir = createTempDir();
        try (DocValuesSidecarWriter writer = new DocValuesSidecarWriter(
                tmpDir, "active", DocValuesSidecarWriter.DocValuesType.SORTED_NUMERIC, 1)) {
            writer.write(0, true);
            writer.write(1, false);

            SidecarWriter.SidecarWriteResult result = writer.flush();
            assertNotNull(result);
            assertEquals(2, result.numDocs());
        }
    }

    public void testEmptyFlushReturnsNull() throws Exception {
        Path tmpDir = createTempDir();
        try (DocValuesSidecarWriter writer = new DocValuesSidecarWriter(
                tmpDir, "field", DocValuesSidecarWriter.DocValuesType.SORTED_SET, 1)) {
            SidecarWriter.SidecarWriteResult result = writer.flush();
            assertNull(result);
        }
    }

    public void testNegativeDocIdThrows() throws Exception {
        Path tmpDir = createTempDir();
        try (DocValuesSidecarWriter writer = new DocValuesSidecarWriter(
                tmpDir, "field", DocValuesSidecarWriter.DocValuesType.SORTED_SET, 1)) {
            expectThrows(IllegalArgumentException.class, () -> writer.write(-1, "value"));
        }
    }

    public void testInvalidNumericTypeThrows() throws Exception {
        Path tmpDir = createTempDir();
        try (DocValuesSidecarWriter writer = new DocValuesSidecarWriter(
                tmpDir, "field", DocValuesSidecarWriter.DocValuesType.SORTED_NUMERIC, 1)) {
            expectThrows(IllegalArgumentException.class, () -> writer.write(0, new Object()));
        }
    }

    public void testGenerationTracked() throws Exception {
        Path tmpDir = createTempDir();
        try (DocValuesSidecarWriter writer = new DocValuesSidecarWriter(
                tmpDir, "field", DocValuesSidecarWriter.DocValuesType.SORTED_SET, 42)) {
            assertEquals(42, writer.generation());
        }
    }
}
