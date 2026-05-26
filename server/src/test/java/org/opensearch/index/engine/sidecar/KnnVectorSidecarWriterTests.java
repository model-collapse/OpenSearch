/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.index.engine.sidecar;

import org.apache.lucene.index.VectorSimilarityFunction;
import org.opensearch.test.OpenSearchTestCase;

import java.nio.file.Path;

public class KnnVectorSidecarWriterTests extends OpenSearchTestCase {

    public void testAddVectorAndFlush() throws Exception {
        Path tmpDir = createTempDir();
        try (KnnVectorSidecarWriter writer = new KnnVectorSidecarWriter(tmpDir, 128, 1)) {
            float[] vector = new float[128];
            for (int i = 0; i < 128; i++) vector[i] = randomFloat();

            writer.addVector(42, vector);
            writer.addVector(100, vector);

            SidecarWriter.SidecarWriteResult result = writer.flush();

            assertNotNull(result);
            assertTrue(result.files().size() > 0);
            assertEquals(2, result.numDocs());
            assertEquals(1, writer.generation());
        }
    }

    public void testBitmapTracksAddedDocs() throws Exception {
        Path tmpDir = createTempDir();
        try (KnnVectorSidecarWriter writer = new KnnVectorSidecarWriter(tmpDir, 64, 1)) {
            float[] vector = new float[64];
            for (int i = 0; i < 64; i++) vector[i] = randomFloat() + 0.01f;
            writer.addVector(10, vector);
            writer.addVector(50, vector);

            SidecarVersionBitmap bitmap = writer.getVersionBitmap();
            assertTrue(bitmap.get(10));
            assertTrue(bitmap.get(50));
            assertFalse(bitmap.get(11));
            assertEquals(2, bitmap.cardinality());
        }
    }

    public void testEmptyWriterFlushReturnsNull() throws Exception {
        Path tmpDir = createTempDir();
        try (KnnVectorSidecarWriter writer = new KnnVectorSidecarWriter(tmpDir, 128, 1)) {
            SidecarWriter.SidecarWriteResult result = writer.flush();
            assertNull(result);
        }
    }

    public void testDimensionMismatchThrows() throws Exception {
        Path tmpDir = createTempDir();
        try (KnnVectorSidecarWriter writer = new KnnVectorSidecarWriter(tmpDir, 128, 1)) {
            float[] wrongDim = new float[64];
            expectThrows(IllegalArgumentException.class, () -> writer.addVector(0, wrongDim));
        }
    }

    public void testBitmapAutoGrows() throws Exception {
        Path tmpDir = createTempDir();
        try (KnnVectorSidecarWriter writer = new KnnVectorSidecarWriter(tmpDir, 32, 1)) {
            float[] vector = new float[32];
            // Add doc ID much larger than initial bitmap capacity
            writer.addVector(100000, vector);
            assertTrue(writer.getVersionBitmap().get(100000));
        }
    }

    public void testMultipleVectorsProduceValidFiles() throws Exception {
        Path tmpDir = createTempDir();
        try (KnnVectorSidecarWriter writer = new KnnVectorSidecarWriter(tmpDir, 64, 1)) {
            for (int i = 0; i < 100; i++) {
                float[] vector = new float[64];
                for (int j = 0; j < 64; j++) vector[j] = randomFloat();
                writer.addVector(i * 10, vector);
            }

            SidecarWriter.SidecarWriteResult result = writer.flush();
            assertNotNull(result);
            assertEquals(100, result.numDocs());
            assertEquals(100, result.bitmap().cardinality());

            // Verify files exist on disk
            for (String file : result.files()) {
                assertTrue(java.nio.file.Files.exists(tmpDir.resolve(file)));
            }
        }
    }

    public void testGenerationIsCorrect() throws Exception {
        Path tmpDir = createTempDir();
        try (KnnVectorSidecarWriter writer = new KnnVectorSidecarWriter(tmpDir, 32, 42)) {
            assertEquals(42, writer.generation());
            float[] v = new float[32];
            for (int i = 0; i < 32; i++) v[i] = randomFloat() + 0.01f;
            writer.addVector(0, v);
            SidecarWriter.SidecarWriteResult result = writer.flush();
            assertEquals(42, result.generation());
        }
    }

    public void testDocsAddedCount() throws Exception {
        Path tmpDir = createTempDir();
        try (KnnVectorSidecarWriter writer = new KnnVectorSidecarWriter(tmpDir, 16, 1)) {
            assertEquals(0, writer.docsAdded());
            float[] v = new float[16];
            for (int i = 0; i < 16; i++) v[i] = randomFloat() + 0.01f;
            writer.addVector(5, v);
            assertEquals(1, writer.docsAdded());
            writer.addVector(10, v);
            assertEquals(2, writer.docsAdded());
        }
    }

    public void testCustomSimilarityFunction() throws Exception {
        Path tmpDir = createTempDir();
        try (KnnVectorSidecarWriter writer = new KnnVectorSidecarWriter(
                tmpDir, 32, 1, VectorSimilarityFunction.DOT_PRODUCT)) {
            float[] v = new float[32];
            for (int i = 0; i < 32; i++) v[i] = randomFloat() + 0.01f;
            writer.write(0, v);
            SidecarWriter.SidecarWriteResult result = writer.flush();
            assertNotNull(result);
            assertEquals(1, result.numDocs());
        }
    }

    public void testDocsWrittenDelegatesToDocsAdded() throws Exception {
        Path tmpDir = createTempDir();
        try (KnnVectorSidecarWriter writer = new KnnVectorSidecarWriter(tmpDir, 16, 1)) {
            assertEquals(0, writer.docsWritten());
            float[] v = new float[16];
            for (int i = 0; i < 16; i++) v[i] = randomFloat() + 0.01f;
            writer.write(5, v);
            assertEquals(1, writer.docsWritten());
            assertEquals(writer.docsAdded(), writer.docsWritten());
        }
    }

    public void testNegativeDocIdThrows() throws Exception {
        Path tmpDir = createTempDir();
        try (KnnVectorSidecarWriter writer = new KnnVectorSidecarWriter(tmpDir, 32, 1)) {
            float[] v = new float[32];
            for (int i = 0; i < 32; i++) v[i] = randomFloat() + 0.01f;
            expectThrows(IllegalArgumentException.class, () -> writer.addVector(-1, v));
        }
    }

    public void testCloseWithoutFlush() throws Exception {
        Path tmpDir = createTempDir();
        KnnVectorSidecarWriter writer = new KnnVectorSidecarWriter(tmpDir, 32, 1);
        float[] v = new float[32];
        for (int i = 0; i < 32; i++) v[i] = randomFloat() + 0.01f;
        writer.addVector(0, v);
        // Close without flushing — should not throw
        writer.close();
    }
}
