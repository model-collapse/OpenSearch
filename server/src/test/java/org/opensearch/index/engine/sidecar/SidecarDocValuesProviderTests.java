/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.index.engine.sidecar;

import org.apache.lucene.document.Document;
import org.apache.lucene.document.SortedNumericDocValuesField;
import org.apache.lucene.document.SortedSetDocValuesField;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.SortedNumericDocValues;
import org.apache.lucene.index.SortedSetDocValues;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.util.BytesRef;
import org.opensearch.test.OpenSearchTestCase;

import java.nio.file.Path;

public class SidecarDocValuesProviderTests extends OpenSearchTestCase {

    public void testReadSortedSetDocValues() throws Exception {
        Path tmpDir = createTempDir();
        // Write a sidecar with keyword values
        IndexWriter writer = new IndexWriter(FSDirectory.open(tmpDir), new IndexWriterConfig());
        Document doc1 = new Document();
        doc1.add(new SortedSetDocValuesField("category", new BytesRef("science")));
        writer.addDocument(doc1);
        Document doc2 = new Document();
        doc2.add(new SortedSetDocValuesField("category", new BytesRef("art")));
        writer.addDocument(doc2);
        writer.commit();
        writer.close();

        try (SidecarDocValuesProvider provider = new SidecarDocValuesProvider(tmpDir, "category")) {
            SortedSetDocValues dv = provider.getSortedSetDocValues();
            assertNotNull(dv);
            assertTrue(dv.advanceExact(0));
            long ord = dv.nextOrd();
            BytesRef value = dv.lookupOrd(ord);
            // Values are sorted, so "art" gets ord 0, "science" gets ord 1
            assertNotNull(value);
        }
    }

    public void testReadSortedNumericDocValues() throws Exception {
        Path tmpDir = createTempDir();
        IndexWriter writer = new IndexWriter(FSDirectory.open(tmpDir), new IndexWriterConfig());
        Document doc1 = new Document();
        doc1.add(new SortedNumericDocValuesField("score", 100L));
        writer.addDocument(doc1);
        Document doc2 = new Document();
        doc2.add(new SortedNumericDocValuesField("score", 200L));
        writer.addDocument(doc2);
        writer.commit();
        writer.close();

        try (SidecarDocValuesProvider provider = new SidecarDocValuesProvider(tmpDir, "score")) {
            SortedNumericDocValues dv = provider.getSortedNumericDocValues();
            assertNotNull(dv);
            assertTrue(dv.advanceExact(0));
            assertEquals(100L, dv.nextValue());
            assertTrue(dv.advanceExact(1));
            assertEquals(200L, dv.nextValue());
        }
    }

    public void testNullForMissingField() throws Exception {
        Path tmpDir = createTempDir();
        IndexWriter writer = new IndexWriter(FSDirectory.open(tmpDir), new IndexWriterConfig());
        Document doc = new Document();
        doc.add(new SortedNumericDocValuesField("score", 100L));
        writer.addDocument(doc);
        writer.commit();
        writer.close();

        try (SidecarDocValuesProvider provider = new SidecarDocValuesProvider(tmpDir, "nonexistent")) {
            assertNull(provider.getSortedSetDocValues());
            assertNull(provider.getSortedNumericDocValues());
        }
    }
}
