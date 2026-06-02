/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.index.engine.sidecar;

import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.FieldType;
import org.apache.lucene.document.StringField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexOptions;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.LeafReader;
import org.apache.lucene.index.PostingsEnum;
import org.apache.lucene.index.Terms;
import org.apache.lucene.index.TermsEnum;
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.apache.lucene.util.BytesRef;
import org.opensearch.test.OpenSearchTestCase;

import java.nio.file.Path;

public class RemappingPostingsEnumTests extends OpenSearchTestCase {

    public void testMonotonicRemapping() throws Exception {
        // Mapping: sidecar doc 0 -> base doc 5, sidecar doc 1 -> base doc 12, sidecar doc 2 -> base doc 47
        int[] mapping = { 5, 12, 47 };

        // Create a sidecar segment with 3 docs
        ByteBuffersDirectory dir = new ByteBuffersDirectory();
        IndexWriter writer = new IndexWriter(dir, new IndexWriterConfig(new StandardAnalyzer()));
        for (int i = 0; i < 3; i++) {
            Document doc = new Document();
            FieldType ft = new FieldType();
            ft.setTokenized(true);
            ft.setIndexOptions(IndexOptions.DOCS_AND_FREQS_AND_POSITIONS);
            ft.freeze();
            doc.add(new Field("content", "hello world", ft));
            writer.addDocument(doc);
        }
        writer.commit();

        DirectoryReader reader = DirectoryReader.open(dir);
        LeafReader leaf = reader.leaves().get(0).reader();
        Terms terms = leaf.terms("content");
        TermsEnum termsEnum = terms.iterator();
        assertTrue(termsEnum.seekExact(new BytesRef("hello")));

        PostingsEnum basePostings = termsEnum.postings(null, PostingsEnum.ALL);
        RemappingPostingsEnum remapped = new RemappingPostingsEnum(basePostings, mapping);

        // nextDoc should return remapped IDs in order
        assertEquals(5, remapped.nextDoc());
        assertEquals(12, remapped.nextDoc());
        assertEquals(47, remapped.nextDoc());
        assertEquals(DocIdSetIterator.NO_MORE_DOCS, remapped.nextDoc());

        reader.close();
        writer.close();
    }

    public void testAdvanceWithBinarySearch() throws Exception {
        int[] mapping = { 5, 12, 47, 100, 200 };

        ByteBuffersDirectory dir = new ByteBuffersDirectory();
        IndexWriter writer = new IndexWriter(dir, new IndexWriterConfig(new StandardAnalyzer()));
        for (int i = 0; i < 5; i++) {
            Document doc = new Document();
            FieldType ft = new FieldType();
            ft.setTokenized(true);
            ft.setIndexOptions(IndexOptions.DOCS_AND_FREQS_AND_POSITIONS);
            ft.freeze();
            doc.add(new Field("content", "term", ft));
            writer.addDocument(doc);
        }
        writer.commit();

        DirectoryReader reader = DirectoryReader.open(dir);
        LeafReader leaf = reader.leaves().get(0).reader();
        Terms terms = leaf.terms("content");
        TermsEnum termsEnum = terms.iterator();
        assertTrue(termsEnum.seekExact(new BytesRef("term")));

        PostingsEnum basePostings = termsEnum.postings(null, PostingsEnum.NONE);
        RemappingPostingsEnum remapped = new RemappingPostingsEnum(basePostings, mapping);

        // advance to 10 -> should land on 12 (first >= 10)
        assertEquals(12, remapped.advance(10));
        // advance to 50 -> should land on 100
        assertEquals(100, remapped.advance(50));
        // advance to 201 -> NO_MORE_DOCS
        assertEquals(DocIdSetIterator.NO_MORE_DOCS, remapped.advance(201));

        reader.close();
        writer.close();
    }

    public void testConjunctionWithRemappedPostings() throws Exception {
        // Simulate: base has "title" field for all docs, sidecar has re-analyzed "content" for some docs
        int[] mapping = { 2, 5, 8 }; // sidecar docs map to base docs 2, 5, 8

        // Create base segment with 10 docs having "title" field
        ByteBuffersDirectory baseDir = new ByteBuffersDirectory();
        IndexWriter baseWriter = new IndexWriter(baseDir, new IndexWriterConfig(new StandardAnalyzer()));
        for (int i = 0; i < 10; i++) {
            Document doc = new Document();
            doc.add(new StringField("title", "cancer", Field.Store.NO));
            doc.add(new StringField("id", String.valueOf(i), Field.Store.YES));
            baseWriter.addDocument(doc);
        }
        baseWriter.commit();

        // Create sidecar with 3 docs having "content" = "treatment"
        ByteBuffersDirectory sidecarDir = new ByteBuffersDirectory();
        IndexWriter sidecarWriter = new IndexWriter(sidecarDir, new IndexWriterConfig(new StandardAnalyzer()));
        for (int i = 0; i < 3; i++) {
            Document doc = new Document();
            FieldType ft = new FieldType();
            ft.setTokenized(true);
            ft.setIndexOptions(IndexOptions.DOCS_AND_FREQS);
            ft.freeze();
            doc.add(new Field("content", "treatment", ft));
            sidecarWriter.addDocument(doc);
        }
        sidecarWriter.commit();

        // Search: title:cancer AND content:treatment (with remapping)
        DirectoryReader baseReader = DirectoryReader.open(baseDir);
        DirectoryReader sidecarReader = DirectoryReader.open(sidecarDir);

        IndexSearcher searcher = new IndexSearcher(baseReader);

        // Build a TermQuery that uses remapped postings
        // In production this would be via SidecarAwareLeafReader; here we test manually
        LeafReader sidecarLeaf = sidecarReader.leaves().get(0).reader();
        Terms sidecarTerms = sidecarLeaf.terms("content");
        RemappingTerms remappedTerms = new RemappingTerms(sidecarTerms, mapping);

        // Verify the remapped terms work
        TermsEnum te = remappedTerms.iterator();
        assertTrue(te.seekExact(new BytesRef("treatment")));
        PostingsEnum pe = te.postings(null, PostingsEnum.NONE);
        assertEquals(2, pe.nextDoc());
        assertEquals(5, pe.nextDoc());
        assertEquals(8, pe.nextDoc());
        assertEquals(DocIdSetIterator.NO_MORE_DOCS, pe.nextDoc());

        baseReader.close();
        sidecarReader.close();
        baseWriter.close();
        sidecarWriter.close();
    }

    public void testSortedWriteProducesMonotonicMapping() throws Exception {
        Path tmpDir = createTempDir();
        try (
            InvertedIndexSidecarWriter writer = new InvertedIndexSidecarWriter(tmpDir, "content", new StandardAnalyzer(), 1)
        ) {
            // Write in non-sorted order
            writer.write(47, "third document");
            writer.write(5, "first document");
            writer.write(12, "second document");

            SidecarWriter.SidecarWriteResult result = writer.flush();
            assertNotNull(result);
            assertEquals(3, result.numDocs());

            // Read the mapping array
            int[] mapping = InvertedIndexSidecarWriter.readMappingArray(tmpDir);
            assertNotNull(mapping);
            assertEquals(3, mapping.length);
            // Should be sorted: 5, 12, 47
            assertEquals(5, mapping[0]);
            assertEquals(12, mapping[1]);
            assertEquals(47, mapping[2]);
        }
    }

    public void testPositionsPreservedThroughRemapping() throws Exception {
        int[] mapping = { 10, 20 };

        ByteBuffersDirectory dir = new ByteBuffersDirectory();
        IndexWriter writer = new IndexWriter(dir, new IndexWriterConfig(new StandardAnalyzer()));
        for (int i = 0; i < 2; i++) {
            Document doc = new Document();
            FieldType ft = new FieldType();
            ft.setTokenized(true);
            ft.setIndexOptions(IndexOptions.DOCS_AND_FREQS_AND_POSITIONS);
            ft.freeze();
            doc.add(new Field("content", "quick brown fox", ft));
            writer.addDocument(doc);
        }
        writer.commit();

        DirectoryReader reader = DirectoryReader.open(dir);
        LeafReader leaf = reader.leaves().get(0).reader();
        Terms terms = leaf.terms("content");
        TermsEnum te = terms.iterator();
        assertTrue(te.seekExact(new BytesRef("quick")));

        PostingsEnum pe = te.postings(null, PostingsEnum.ALL);
        RemappingPostingsEnum remapped = new RemappingPostingsEnum(pe, mapping);

        // First doc
        assertEquals(10, remapped.nextDoc());
        assertEquals(1, remapped.freq());
        assertEquals(0, remapped.nextPosition()); // "quick" is at position 0

        // Second doc
        assertEquals(20, remapped.nextDoc());
        assertEquals(1, remapped.freq());
        assertEquals(0, remapped.nextPosition());

        reader.close();
        writer.close();
    }
}
