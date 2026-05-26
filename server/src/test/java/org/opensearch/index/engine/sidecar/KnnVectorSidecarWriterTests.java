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

public class KnnVectorSidecarWriterTests extends OpenSearchTestCase {

    public void testAddVectorAndFlush() throws Exception {
        Path tmpDir = createTempDir();
        try (KnnVectorSidecarWriter writer = new KnnVectorSidecarWriter(tmpDir, 128, 1)) {
            float[] vector = new float[128];
            for (int i = 0; i < 128; i++) vector[i] = randomFloat();

            writer.addVector(42, vector);
            writer.addVector(100, vector);

            KnnVectorSidecarWriter.SidecarWriteResult result = writer.flush();

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
            KnnVectorSidecarWriter.SidecarWriteResult result = writer.flush();
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
}
