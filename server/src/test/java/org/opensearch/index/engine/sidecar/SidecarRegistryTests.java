/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.index.engine.sidecar;

import org.apache.lucene.document.Document;
import org.apache.lucene.document.KnnFloatVectorField;
import org.apache.lucene.document.SortedNumericDocValuesField;
import org.apache.lucene.document.SortedSetDocValuesField;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.VectorSimilarityFunction;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.util.BytesRef;
import org.opensearch.test.OpenSearchTestCase;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class SidecarRegistryTests extends OpenSearchTestCase {

    public void testRegisterAndIsDirty() {
        SidecarRegistry registry = new SidecarRegistry();
        SidecarVersionBitmap bitmap = new SidecarVersionBitmap(100);
        bitmap.set(5);
        bitmap.set(42);

        registry.register("embedding", "_0", bitmap);

        assertTrue(registry.isDirty("embedding", "_0", 5));
        assertTrue(registry.isDirty("embedding", "_0", 42));
        assertFalse(registry.isDirty("embedding", "_0", 10));
    }

    public void testNotDirtyWhenEmpty() {
        SidecarRegistry registry = new SidecarRegistry();

        assertFalse(registry.isDirty("embedding", "_0", 0));
        assertFalse(registry.isDirty("embedding", "_0", 5));
        assertFalse(registry.hasAnySidecars());
    }

    public void testUnregister() {
        SidecarRegistry registry = new SidecarRegistry();
        SidecarVersionBitmap bitmap = new SidecarVersionBitmap(100);
        bitmap.set(7);

        registry.register("embedding", "_0", bitmap);
        assertTrue(registry.isDirty("embedding", "_0", 7));
        assertTrue(registry.hasAnySidecars());

        registry.unregister("embedding", "_0");
        assertFalse(registry.isDirty("embedding", "_0", 7));
        assertFalse(registry.hasAnySidecars());
    }

    public void testMultipleFieldsIndependent() {
        SidecarRegistry registry = new SidecarRegistry();

        SidecarVersionBitmap embeddingBitmap = new SidecarVersionBitmap(100);
        embeddingBitmap.set(3);

        SidecarVersionBitmap tagsBitmap = new SidecarVersionBitmap(100);
        tagsBitmap.set(7);

        registry.register("embedding", "_0", embeddingBitmap);
        registry.register("tags", "_0", tagsBitmap);

        assertTrue(registry.isDirty("embedding", "_0", 3));
        assertFalse(registry.isDirty("embedding", "_0", 7));

        assertTrue(registry.isDirty("tags", "_0", 7));
        assertFalse(registry.isDirty("tags", "_0", 3));
    }

    public void testMultipleSegmentsIndependent() {
        SidecarRegistry registry = new SidecarRegistry();

        SidecarVersionBitmap bitmap0 = new SidecarVersionBitmap(100);
        bitmap0.set(5);

        SidecarVersionBitmap bitmap1 = new SidecarVersionBitmap(200);
        bitmap1.set(150);

        registry.register("embedding", "_0", bitmap0);
        registry.register("embedding", "_1", bitmap1);

        assertTrue(registry.isDirty("embedding", "_0", 5));
        assertFalse(registry.isDirty("embedding", "_0", 150));

        assertTrue(registry.isDirty("embedding", "_1", 150));
        assertFalse(registry.isDirty("embedding", "_1", 5));
    }

    public void testGetSidecarValuesWhenDirtyWithVectorPath() throws Exception {
        SidecarRegistry registry = new SidecarRegistry();
        SidecarVersionBitmap bitmap = new SidecarVersionBitmap(100);
        bitmap.set(10);

        // Create a real sidecar Lucene index with a vector
        Path sidecarDir = Files.createTempDirectory("sidecar_test_vector");
        float[] expectedVector = new float[] { 1.0f, 2.0f, 3.0f };
        try (Directory dir = FSDirectory.open(sidecarDir)) {
            IndexWriterConfig config = new IndexWriterConfig();
            try (IndexWriter writer = new IndexWriter(dir, config)) {
                Document doc = new Document();
                doc.add(new StoredField("original_doc_id", 10));
                doc.add(new KnnFloatVectorField("embedding", expectedVector, VectorSimilarityFunction.COSINE));
                writer.addDocument(doc);
                writer.commit();
            }
        }

        registry.register("embedding", "_0", bitmap, sidecarDir);

        Map<String, Object> values = registry.getSidecarValues("embedding", "_0", 10);
        assertFalse("Should return non-empty values for dirty doc with sidecar path", values.isEmpty());
        assertTrue(values.containsKey("embedding"));

        @SuppressWarnings("unchecked")
        List<Double> vectorResult = (List<Double>) values.get("embedding");
        assertEquals(3, vectorResult.size());
        assertEquals(1.0, vectorResult.get(0), 0.001);
        assertEquals(2.0, vectorResult.get(1), 0.001);
        assertEquals(3.0, vectorResult.get(2), 0.001);
    }

    public void testGetSidecarValuesWhenDirtyWithKeywordPath() throws Exception {
        SidecarRegistry registry = new SidecarRegistry();
        SidecarVersionBitmap bitmap = new SidecarVersionBitmap(100);
        bitmap.set(5);

        // Create a real sidecar Lucene index with a keyword (SortedSetDocValues)
        Path sidecarDir = Files.createTempDirectory("sidecar_test_keyword");
        try (Directory dir = FSDirectory.open(sidecarDir)) {
            IndexWriterConfig config = new IndexWriterConfig();
            try (IndexWriter writer = new IndexWriter(dir, config)) {
                Document doc = new Document();
                doc.add(new StoredField("original_doc_id", 5));
                doc.add(new SortedSetDocValuesField("status", new BytesRef("active")));
                writer.addDocument(doc);
                writer.commit();
            }
        }

        registry.register("status", "_0", bitmap, sidecarDir);

        Map<String, Object> values = registry.getSidecarValues("status", "_0", 5);
        assertFalse("Should return non-empty values for dirty doc with keyword sidecar", values.isEmpty());
        assertEquals("active", values.get("status"));
    }

    public void testGetSidecarValuesWhenDirtyWithNumericPath() throws Exception {
        SidecarRegistry registry = new SidecarRegistry();
        SidecarVersionBitmap bitmap = new SidecarVersionBitmap(100);
        bitmap.set(3);

        // Create a real sidecar Lucene index with a numeric (SortedNumericDocValues)
        Path sidecarDir = Files.createTempDirectory("sidecar_test_numeric");
        try (Directory dir = FSDirectory.open(sidecarDir)) {
            IndexWriterConfig config = new IndexWriterConfig();
            try (IndexWriter writer = new IndexWriter(dir, config)) {
                Document doc = new Document();
                doc.add(new StoredField("original_doc_id", 3));
                doc.add(new SortedNumericDocValuesField("age", 42L));
                writer.addDocument(doc);
                writer.commit();
            }
        }

        registry.register("age", "_0", bitmap, sidecarDir);

        Map<String, Object> values = registry.getSidecarValues("age", "_0", 3);
        assertFalse("Should return non-empty values for dirty doc with numeric sidecar", values.isEmpty());
        assertEquals(42L, values.get("age"));
    }

    public void testGetSidecarValuesWhenDirtyButNoPath() {
        SidecarRegistry registry = new SidecarRegistry();
        SidecarVersionBitmap bitmap = new SidecarVersionBitmap(100);
        bitmap.set(10);

        // Register without a path (backward-compat overload)
        registry.register("embedding", "_0", bitmap);

        // Should return empty since no sidecar path is available
        Map<String, Object> values = registry.getSidecarValues("embedding", "_0", 10);
        assertTrue(values.isEmpty());
    }

    public void testGetSidecarValuesWhenClean() {
        SidecarRegistry registry = new SidecarRegistry();
        SidecarVersionBitmap bitmap = new SidecarVersionBitmap(100);
        bitmap.set(10);

        registry.register("embedding", "_0", bitmap);

        Map<String, Object> values = registry.getSidecarValues("embedding", "_0", 20);
        assertTrue(values.isEmpty());
    }

    public void testGetSidecarPathReturnsNullWhenNotRegistered() {
        SidecarRegistry registry = new SidecarRegistry();
        assertNull(registry.getSidecarPath("nonexistent", "_0"));
    }

    public void testGetSidecarPathReturnsRegisteredPath() throws Exception {
        SidecarRegistry registry = new SidecarRegistry();
        SidecarVersionBitmap bitmap = new SidecarVersionBitmap(100);
        bitmap.set(1);

        Path sidecarDir = Files.createTempDirectory("sidecar_test_path");
        registry.register("field1", "_0", bitmap, sidecarDir);

        assertEquals(sidecarDir, registry.getSidecarPath("field1", "_0"));
        assertNull(registry.getSidecarPath("field1", "_1"));
        assertNull(registry.getSidecarPath("field2", "_0"));
    }

    public void testGetUpdatableFields() {
        SidecarRegistry registry = new SidecarRegistry();

        assertTrue(registry.getUpdatableFields().isEmpty());

        SidecarVersionBitmap bitmap = new SidecarVersionBitmap(100);
        bitmap.set(1);
        registry.register("embedding", "_0", bitmap);
        registry.register("tags", "_1", bitmap);

        assertEquals(2, registry.getUpdatableFields().size());
        assertTrue(registry.getUpdatableFields().contains("embedding"));
        assertTrue(registry.getUpdatableFields().contains("tags"));
    }

    public void testDocIdBeyondMaxDocIsNotDirty() {
        SidecarRegistry registry = new SidecarRegistry();
        SidecarVersionBitmap bitmap = new SidecarVersionBitmap(10);
        bitmap.set(5);

        registry.register("embedding", "_0", bitmap);

        // docId beyond maxDoc should not be dirty
        assertFalse(registry.isDirty("embedding", "_0", 50));
    }

    public void testUnregisterNonExistentFieldIsNoOp() {
        SidecarRegistry registry = new SidecarRegistry();
        // Should not throw
        registry.unregister("nonexistent", "_0");
        assertFalse(registry.hasAnySidecars());
    }

    public void testRegisterOverwritesBitmap() {
        SidecarRegistry registry = new SidecarRegistry();

        SidecarVersionBitmap bitmap1 = new SidecarVersionBitmap(100);
        bitmap1.set(5);

        SidecarVersionBitmap bitmap2 = new SidecarVersionBitmap(100);
        bitmap2.set(10);

        registry.register("embedding", "_0", bitmap1);
        assertTrue(registry.isDirty("embedding", "_0", 5));
        assertFalse(registry.isDirty("embedding", "_0", 10));

        registry.register("embedding", "_0", bitmap2);
        assertFalse(registry.isDirty("embedding", "_0", 5));
        assertTrue(registry.isDirty("embedding", "_0", 10));
    }

    public void testRegisterAndGetAllSidecarFiles() {
        SidecarRegistry registry = new SidecarRegistry();

        Set<String> files1 = Set.of("_sidecar_embedding_0.vec", "_sidecar_embedding_0.vem");
        Set<String> files2 = Set.of("_sidecar_tags_1.dvm", "_sidecar_tags_1.dvd");

        registry.registerFiles("embedding:_0", files1);
        registry.registerFiles("tags:_1", files2);

        Set<String> allFiles = registry.getAllSidecarFiles();
        Set<String> expected = new HashSet<>();
        expected.addAll(files1);
        expected.addAll(files2);
        assertEquals(expected, allFiles);
    }

    public void testGetAllSidecarFilesWhenEmpty() {
        SidecarRegistry registry = new SidecarRegistry();
        assertTrue(registry.getAllSidecarFiles().isEmpty());
    }

    public void testUnregisterFiles() {
        SidecarRegistry registry = new SidecarRegistry();

        Set<String> files = Set.of("_sidecar_embedding_0.vec", "_sidecar_embedding_0.vem");
        registry.registerFiles("embedding:_0", files);
        assertEquals(2, registry.getAllSidecarFiles().size());

        registry.unregisterFiles("embedding:_0");
        assertTrue(registry.getAllSidecarFiles().isEmpty());
    }

    public void testUnregisterFilesNonExistentKeyIsNoOp() {
        SidecarRegistry registry = new SidecarRegistry();
        // Should not throw
        registry.unregisterFiles("nonexistent:_0");
        assertTrue(registry.getAllSidecarFiles().isEmpty());
    }

    public void testRegisterFilesAccumulates() {
        SidecarRegistry registry = new SidecarRegistry();

        Set<String> batch1 = Set.of("file1.vec");
        Set<String> batch2 = Set.of("file2.vec");

        registry.registerFiles("embedding:_0", batch1);
        registry.registerFiles("embedding:_0", batch2);

        Set<String> allFiles = registry.getAllSidecarFiles();
        assertTrue(allFiles.contains("file1.vec"));
        assertTrue(allFiles.contains("file2.vec"));
        assertEquals(2, allFiles.size());
    }

    public void testReaderCacheReusesReader() throws Exception {
        SidecarRegistry registry = new SidecarRegistry();
        SidecarVersionBitmap bitmap = new SidecarVersionBitmap(100);
        bitmap.set(7);

        // Create a real sidecar Lucene index
        Path sidecarDir = Files.createTempDirectory("sidecar_test_cache");
        float[] expectedVector = new float[] { 4.0f, 5.0f, 6.0f };
        try (Directory dir = FSDirectory.open(sidecarDir)) {
            IndexWriterConfig config = new IndexWriterConfig();
            try (IndexWriter writer = new IndexWriter(dir, config)) {
                Document doc = new Document();
                doc.add(new StoredField("original_doc_id", 7));
                doc.add(new KnnFloatVectorField("embedding", expectedVector, VectorSimilarityFunction.COSINE));
                writer.addDocument(doc);
                writer.commit();
            }
        }

        registry.register("embedding", "_0", bitmap, sidecarDir);

        // First read - opens and caches the reader
        Map<String, Object> values1 = registry.getSidecarValues("embedding", "_0", 7);
        assertFalse(values1.isEmpty());

        // Second read - should reuse the cached reader without error
        Map<String, Object> values2 = registry.getSidecarValues("embedding", "_0", 7);
        assertFalse(values2.isEmpty());

        @SuppressWarnings("unchecked")
        List<Double> vectorResult = (List<Double>) values2.get("embedding");
        assertEquals(3, vectorResult.size());
        assertEquals(4.0, vectorResult.get(0), 0.001);
        assertEquals(5.0, vectorResult.get(1), 0.001);
        assertEquals(6.0, vectorResult.get(2), 0.001);

        // Cleanup
        registry.closeAllReaders();
    }

    public void testCloseAllReadersReleasesResources() throws Exception {
        SidecarRegistry registry = new SidecarRegistry();
        SidecarVersionBitmap bitmap = new SidecarVersionBitmap(100);
        bitmap.set(1);

        Path sidecarDir = Files.createTempDirectory("sidecar_test_closeall");
        try (Directory dir = FSDirectory.open(sidecarDir)) {
            IndexWriterConfig config = new IndexWriterConfig();
            try (IndexWriter writer = new IndexWriter(dir, config)) {
                Document doc = new Document();
                doc.add(new StoredField("original_doc_id", 1));
                doc.add(new SortedNumericDocValuesField("count", 99L));
                writer.addDocument(doc);
                writer.commit();
            }
        }

        registry.register("count", "_0", bitmap, sidecarDir);

        // Read to populate cache
        Map<String, Object> values = registry.getSidecarValues("count", "_0", 1);
        assertEquals(99L, values.get("count"));

        // Close all readers - should not throw
        registry.closeAllReaders();
    }

    public void testUnregisterClosesCachedReader() throws Exception {
        SidecarRegistry registry = new SidecarRegistry();
        SidecarVersionBitmap bitmap = new SidecarVersionBitmap(100);
        bitmap.set(2);

        Path sidecarDir = Files.createTempDirectory("sidecar_test_unregister_cache");
        try (Directory dir = FSDirectory.open(sidecarDir)) {
            IndexWriterConfig config = new IndexWriterConfig();
            try (IndexWriter writer = new IndexWriter(dir, config)) {
                Document doc = new Document();
                doc.add(new StoredField("original_doc_id", 2));
                doc.add(new SortedNumericDocValuesField("price", 500L));
                writer.addDocument(doc);
                writer.commit();
            }
        }

        registry.register("price", "_0", bitmap, sidecarDir);

        // Read to populate cache
        Map<String, Object> values = registry.getSidecarValues("price", "_0", 2);
        assertEquals(500L, values.get("price"));

        // Unregister should close the cached reader
        registry.unregister("price", "_0");

        // After unregister, the path should be gone
        assertNull(registry.getSidecarPath("price", "_0"));
    }

    public void testConcurrentRegisterAndRead() throws Exception {
        SidecarRegistry registry = new SidecarRegistry();
        int numThreads = 10;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(numThreads);
        AtomicInteger errors = new AtomicInteger(0);

        for (int t = 0; t < numThreads; t++) {
            final int threadId = t;
            new Thread(() -> {
                try {
                    start.await();
                    // Each thread registers and reads its own segment
                    String segment = "_seg" + threadId;
                    SidecarVersionBitmap bitmap = new SidecarVersionBitmap(100);
                    bitmap.set(threadId);
                    registry.register("field1", segment, bitmap);

                    // Verify it's registered
                    assertTrue(registry.isDirty("field1", segment, threadId));
                    assertFalse(registry.isDirty("field1", segment, threadId + 50));
                } catch (Exception e) {
                    errors.incrementAndGet();
                }
                done.countDown();
            }).start();
        }

        start.countDown();
        assertTrue(done.await(10, TimeUnit.SECONDS));
        assertEquals(0, errors.get());
        assertEquals(numThreads, registry.getSegmentCount("field1"));
    }
}
