/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.index.engine.sidecar;

import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StringField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.NoMergePolicy;
import org.apache.lucene.index.SegmentReader;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.apache.lucene.store.Directory;
import org.opensearch.test.OpenSearchTestCase;

import java.util.HashSet;
import java.util.Set;

public class SidecarMergeListenerTests extends OpenSearchTestCase {

    public void testOrphanedSidecarsRemovedAfterMerge() throws Exception {
        SidecarRegistry registry = new SidecarRegistry();
        SidecarMergeListener listener = new SidecarMergeListener(registry);

        // Register sidecars for segments that won't exist in the reader
        SidecarVersionBitmap bitmap0 = new SidecarVersionBitmap(100);
        bitmap0.set(5);
        SidecarVersionBitmap bitmap1 = new SidecarVersionBitmap(100);
        bitmap1.set(10);

        registry.register("embedding", "_orphan0", bitmap0);
        registry.register("embedding", "_orphan1", bitmap1);
        assertTrue(registry.hasAnySidecars());
        assertEquals(2, registry.getSegmentCount("embedding"));

        // Create a directory with a single segment (simulating post-merge state)
        try (Directory dir = new ByteBuffersDirectory()) {
            IndexWriterConfig config = new IndexWriterConfig();
            config.setMergePolicy(NoMergePolicy.INSTANCE);
            try (IndexWriter writer = new IndexWriter(dir, config)) {
                Document doc = new Document();
                doc.add(new StringField("id", "1", Field.Store.NO));
                writer.addDocument(doc);
                writer.commit();
            }

            try (DirectoryReader reader = DirectoryReader.open(dir)) {
                // Verify that the reader's segment names don't match our orphaned ones
                Set<String> liveNames = getLiveSegmentNames(reader);
                assertFalse(liveNames.contains("_orphan0"));
                assertFalse(liveNames.contains("_orphan1"));

                listener.onRefresh(reader);
            }
        }

        // Both orphaned segments should be removed
        assertFalse(registry.hasAnySidecars());
        assertEquals(0, registry.getSegmentCount("embedding"));
    }

    public void testLiveSegmentsNotRemoved() throws Exception {
        SidecarRegistry registry = new SidecarRegistry();
        SidecarMergeListener listener = new SidecarMergeListener(registry);

        // Create a directory with two segments using NoMergePolicy
        try (Directory dir = new ByteBuffersDirectory()) {
            IndexWriterConfig config = new IndexWriterConfig();
            config.setMergePolicy(NoMergePolicy.INSTANCE);
            try (IndexWriter writer = new IndexWriter(dir, config)) {
                Document doc1 = new Document();
                doc1.add(new StringField("id", "1", Field.Store.NO));
                writer.addDocument(doc1);
                writer.commit();

                Document doc2 = new Document();
                doc2.add(new StringField("id", "2", Field.Store.NO));
                writer.addDocument(doc2);
                writer.commit();
            }

            try (DirectoryReader reader = DirectoryReader.open(dir)) {
                // Get the actual segment names from the reader
                Set<String> liveNames = getLiveSegmentNames(reader);
                assertEquals(2, liveNames.size());

                // Register sidecars matching the live segment names
                SidecarVersionBitmap bitmap = new SidecarVersionBitmap(100);
                bitmap.set(0);
                for (String name : liveNames) {
                    registry.register("embedding", name, bitmap);
                }
                assertEquals(2, registry.getSegmentCount("embedding"));

                // Also register one orphaned segment
                registry.register("embedding", "_orphaned_segment", bitmap);
                assertEquals(3, registry.getSegmentCount("embedding"));

                // After refresh, only the orphaned one should be removed
                listener.onRefresh(reader);

                // Live segments should still be registered
                assertEquals(2, registry.getSegmentCount("embedding"));
                assertTrue(registry.hasAnySidecars());

                // The orphaned one should be gone
                assertFalse(registry.isDirty("embedding", "_orphaned_segment", 0));
            }
        }
    }

    public void testNoOpWhenNoSidecars() throws Exception {
        SidecarRegistry registry = new SidecarRegistry();
        SidecarMergeListener listener = new SidecarMergeListener(registry);

        // No sidecars registered - should be a no-op (no exception)
        try (Directory dir = new ByteBuffersDirectory()) {
            IndexWriterConfig config = new IndexWriterConfig();
            try (IndexWriter writer = new IndexWriter(dir, config)) {
                Document doc = new Document();
                doc.add(new StringField("id", "1", Field.Store.NO));
                writer.addDocument(doc);
                writer.commit();
            }

            try (DirectoryReader reader = DirectoryReader.open(dir)) {
                listener.onRefresh(reader);
            }
        }

        assertFalse(registry.hasAnySidecars());
    }

    public void testUnregisterSegmentRemovesAllFieldsForSegment() {
        SidecarRegistry registry = new SidecarRegistry();

        // Register sidecars for multiple fields on the same segment
        SidecarVersionBitmap bitmap1 = new SidecarVersionBitmap(100);
        bitmap1.set(5);
        SidecarVersionBitmap bitmap2 = new SidecarVersionBitmap(100);
        bitmap2.set(10);
        SidecarVersionBitmap bitmap3 = new SidecarVersionBitmap(100);
        bitmap3.set(15);

        registry.register("embedding", "_0", bitmap1);
        registry.register("tags", "_0", bitmap2);
        registry.register("embedding", "_1", bitmap3);

        assertTrue(registry.isDirty("embedding", "_0", 5));
        assertTrue(registry.isDirty("tags", "_0", 10));
        assertTrue(registry.isDirty("embedding", "_1", 15));

        // Unregister segment _0 across all fields
        registry.unregisterSegment("_0");

        assertFalse(registry.isDirty("embedding", "_0", 5));
        assertFalse(registry.isDirty("tags", "_0", 10));
        // _1 should still be there
        assertTrue(registry.isDirty("embedding", "_1", 15));
        assertEquals(1, registry.getSegmentCount("embedding"));
    }

    public void testUnregisterSegmentCleansUpFiles() {
        SidecarRegistry registry = new SidecarRegistry();

        SidecarVersionBitmap bitmap = new SidecarVersionBitmap(100);
        bitmap.set(5);
        registry.register("embedding", "_0", bitmap);
        registry.registerFiles("embedding:_0", Set.of("file1.vec", "file2.vem"));

        assertEquals(2, registry.getAllSidecarFiles().size());

        registry.unregisterSegment("_0");

        // Files should be cleaned up
        assertTrue(registry.getAllSidecarFiles().isEmpty());
    }

    public void testUnregisterSegmentNonExistentIsNoOp() {
        SidecarRegistry registry = new SidecarRegistry();

        SidecarVersionBitmap bitmap = new SidecarVersionBitmap(100);
        bitmap.set(5);
        registry.register("embedding", "_0", bitmap);

        // Should not throw
        registry.unregisterSegment("_nonexistent");

        // Original data still intact
        assertTrue(registry.isDirty("embedding", "_0", 5));
    }

    public void testGetAllRegisteredSegments() {
        SidecarRegistry registry = new SidecarRegistry();

        assertTrue(registry.getAllRegisteredSegments().isEmpty());

        SidecarVersionBitmap bitmap = new SidecarVersionBitmap(100);
        bitmap.set(1);

        registry.register("embedding", "_0", bitmap);
        registry.register("embedding", "_1", bitmap);
        registry.register("tags", "_1", bitmap);
        registry.register("tags", "_2", bitmap);

        Set<String> segments = registry.getAllRegisteredSegments();
        assertEquals(3, segments.size());
        assertTrue(segments.contains("_0"));
        assertTrue(segments.contains("_1"));
        assertTrue(segments.contains("_2"));
    }

    public void testMultipleFieldsPartialMerge() throws Exception {
        SidecarRegistry registry = new SidecarRegistry();
        SidecarMergeListener listener = new SidecarMergeListener(registry);

        // Register sidecars across multiple fields with orphaned segment names
        SidecarVersionBitmap bitmap = new SidecarVersionBitmap(100);
        bitmap.set(1);

        registry.register("embedding", "_orphan_a", bitmap);
        registry.register("embedding", "_orphan_b", bitmap);
        registry.register("tags", "_orphan_a", bitmap);
        registry.register("tags", "_orphan_c", bitmap);

        assertEquals(Set.of("_orphan_a", "_orphan_b", "_orphan_c"), registry.getAllRegisteredSegments());

        // Simulate: after merge, none of the orphaned segment names exist
        try (Directory dir = new ByteBuffersDirectory()) {
            IndexWriterConfig config = new IndexWriterConfig();
            config.setMergePolicy(NoMergePolicy.INSTANCE);
            try (IndexWriter writer = new IndexWriter(dir, config)) {
                Document doc = new Document();
                doc.add(new StringField("id", "1", Field.Store.NO));
                writer.addDocument(doc);
                writer.commit();
            }

            try (DirectoryReader reader = DirectoryReader.open(dir)) {
                listener.onRefresh(reader);
            }
        }

        // All should be cleaned
        assertFalse(registry.hasAnySidecars());
        assertTrue(registry.getAllRegisteredSegments().isEmpty());
    }

    public void testMixedLiveAndOrphanedSegments() throws Exception {
        SidecarRegistry registry = new SidecarRegistry();
        SidecarMergeListener listener = new SidecarMergeListener(registry);

        try (Directory dir = new ByteBuffersDirectory()) {
            IndexWriterConfig config = new IndexWriterConfig();
            config.setMergePolicy(NoMergePolicy.INSTANCE);
            try (IndexWriter writer = new IndexWriter(dir, config)) {
                Document doc = new Document();
                doc.add(new StringField("id", "1", Field.Store.NO));
                writer.addDocument(doc);
                writer.commit();
            }

            try (DirectoryReader reader = DirectoryReader.open(dir)) {
                // Get actual live segment name
                Set<String> liveNames = getLiveSegmentNames(reader);
                assertEquals(1, liveNames.size());
                String liveName = liveNames.iterator().next();

                SidecarVersionBitmap bitmap = new SidecarVersionBitmap(100);
                bitmap.set(0);

                // Register: one live, two orphaned
                registry.register("embedding", liveName, bitmap);
                registry.register("embedding", "_dead_seg_x", bitmap);
                registry.register("tags", "_dead_seg_y", bitmap);

                assertEquals(3, registry.getAllRegisteredSegments().size());

                listener.onRefresh(reader);

                // Only the live segment should remain
                Set<String> remaining = registry.getAllRegisteredSegments();
                assertEquals(1, remaining.size());
                assertTrue(remaining.contains(liveName));
                assertTrue(registry.isDirty("embedding", liveName, 0));
                assertFalse(registry.isDirty("embedding", "_dead_seg_x", 0));
                assertFalse(registry.isDirty("tags", "_dead_seg_y", 0));
            }
        }
    }

    private Set<String> getLiveSegmentNames(DirectoryReader reader) {
        Set<String> names = new HashSet<>();
        for (LeafReaderContext ctx : reader.leaves()) {
            if (ctx.reader() instanceof SegmentReader sr) {
                names.add(sr.getSegmentName());
            }
        }
        return names;
    }
}
