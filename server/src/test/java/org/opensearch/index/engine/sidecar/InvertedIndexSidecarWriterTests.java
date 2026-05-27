/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.index.engine.sidecar;

import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexOptions;
import org.apache.lucene.index.LeafReader;
import org.apache.lucene.index.Terms;
import org.apache.lucene.store.FSDirectory;
import org.opensearch.test.OpenSearchTestCase;

import java.nio.file.Path;

public class InvertedIndexSidecarWriterTests extends OpenSearchTestCase {

    public void testWriteTextAndFlush() throws Exception {
        Path tmpDir = createTempDir();
        try (InvertedIndexSidecarWriter writer = new InvertedIndexSidecarWriter(tmpDir, "content", new StandardAnalyzer(), 1)) {
            writer.write(0, "the quick brown fox jumps over the lazy dog");
            writer.write(5, "hello world of search engines");

            SidecarWriter.SidecarWriteResult result = writer.flush();
            assertNotNull(result);
            assertEquals(2, result.numDocs());
            assertTrue(result.files().size() > 0);
            assertTrue(result.bitmap().get(0));
            assertTrue(result.bitmap().get(5));
            assertFalse(result.bitmap().get(1));
        }
    }

    public void testPostingsExistAfterFlush() throws Exception {
        Path tmpDir = createTempDir();
        try (InvertedIndexSidecarWriter writer = new InvertedIndexSidecarWriter(tmpDir, "content", new StandardAnalyzer(), 1)) {
            writer.write(0, "hello world");
            writer.flush();
        }

        // Verify the postings exist in the sidecar segment
        try (DirectoryReader reader = DirectoryReader.open(FSDirectory.open(tmpDir))) {
            LeafReader leaf = reader.leaves().get(0).reader();
            Terms terms = leaf.terms("content");
            assertNotNull(terms);
            assertTrue(terms.size() > 0); // should have "hello" and "world" terms
        }
    }

    public void testCustomIndexOptions() throws Exception {
        Path tmpDir = createTempDir();
        try (
            InvertedIndexSidecarWriter writer = new InvertedIndexSidecarWriter(
                tmpDir,
                "title",
                new StandardAnalyzer(),
                1,
                IndexOptions.DOCS_AND_FREQS
            )
        ) {
            writer.write(0, "test document with multiple words");
            SidecarWriter.SidecarWriteResult result = writer.flush();
            assertNotNull(result);
            assertEquals(1, result.numDocs());
        }
    }

    public void testEmptyFlushReturnsNull() throws Exception {
        Path tmpDir = createTempDir();
        try (InvertedIndexSidecarWriter writer = new InvertedIndexSidecarWriter(tmpDir, "content", new StandardAnalyzer(), 1)) {
            assertNull(writer.flush());
        }
    }

    public void testNegativeDocIdThrows() throws Exception {
        Path tmpDir = createTempDir();
        try (InvertedIndexSidecarWriter writer = new InvertedIndexSidecarWriter(tmpDir, "content", new StandardAnalyzer(), 1)) {
            expectThrows(IllegalArgumentException.class, () -> writer.write(-1, "text"));
        }
    }

    public void testNullTextThrows() throws Exception {
        Path tmpDir = createTempDir();
        try (InvertedIndexSidecarWriter writer = new InvertedIndexSidecarWriter(tmpDir, "content", new StandardAnalyzer(), 1)) {
            expectThrows(IllegalArgumentException.class, () -> writer.write(0, null));
        }
    }

    public void testGenerationTracked() throws Exception {
        Path tmpDir = createTempDir();
        try (InvertedIndexSidecarWriter writer = new InvertedIndexSidecarWriter(tmpDir, "content", new StandardAnalyzer(), 42)) {
            assertEquals(42, writer.generation());
        }
    }

    public void testBitmapAutoGrows() throws Exception {
        Path tmpDir = createTempDir();
        try (InvertedIndexSidecarWriter writer = new InvertedIndexSidecarWriter(tmpDir, "content", new StandardAnalyzer(), 1)) {
            writer.write(100000, "large doc id test");
            assertTrue(writer.getVersionBitmap().get(100000));
        }
    }
}
