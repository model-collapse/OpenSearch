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
import org.apache.lucene.document.StoredField;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.VectorSimilarityFunction;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

/**
 * Writer that builds HNSW graphs for a subset of document IDs using Lucene's
 * vector codec. Maintains a {@link SidecarVersionBitmap} tracking which docs
 * are covered. Produces file set metadata on flush.
 *
 * @opensearch.experimental
 */
public class KnnVectorSidecarWriter implements Closeable {

    private static final String VECTOR_FIELD = "vector";
    private static final String DOC_ID_FIELD = "original_doc_id";

    private final Path directory;
    private final int dimension;
    private final long generation;
    private final Directory luceneDir;
    private final IndexWriter writer;
    private SidecarVersionBitmap bitmap;
    private int docsAdded = 0;

    public KnnVectorSidecarWriter(Path directory, int dimension, long generation) throws IOException {
        this.directory = directory;
        this.dimension = dimension;
        this.generation = generation;
        this.luceneDir = FSDirectory.open(directory);
        this.bitmap = new SidecarVersionBitmap(65536); // initial size, grows as needed

        IndexWriterConfig config = new IndexWriterConfig();
        config.setUseCompoundFile(false); // keep files separate for easier management
        this.writer = new IndexWriter(luceneDir, config);
    }

    /**
     * Add a vector for the given document ID. The vector must match the configured dimension.
     */
    public void addVector(int docId, float[] vector) throws IOException {
        if (vector.length != dimension) {
            throw new IllegalArgumentException("Expected dimension " + dimension + " but got " + vector.length);
        }

        Document doc = new Document();
        doc.add(new KnnFloatVectorField(VECTOR_FIELD, vector, VectorSimilarityFunction.COSINE));
        doc.add(new StoredField(DOC_ID_FIELD, docId));
        writer.addDocument(doc);

        ensureBitmapCapacity(docId + 1);
        bitmap.set(docId);
        docsAdded++;
    }

    /**
     * Commits the written vectors and returns metadata about the produced files.
     * Returns null if no documents have been added.
     */
    public SidecarWriteResult flush() throws IOException {
        if (docsAdded == 0) {
            return null;
        }

        writer.commit();

        Set<String> files = new HashSet<>();
        for (String f : luceneDir.listAll()) {
            if (!f.equals("write.lock") && !f.startsWith("segments")) {
                files.add(f);
            }
        }

        return new SidecarWriteResult(directory.toString(), generation, files, docsAdded, bitmap);
    }

    public SidecarVersionBitmap getVersionBitmap() {
        return bitmap;
    }

    public long generation() {
        return generation;
    }

    public int docsAdded() {
        return docsAdded;
    }

    private void ensureBitmapCapacity(int requiredSize) {
        if (requiredSize > bitmap.maxDoc()) {
            int newSize = Math.max(requiredSize, bitmap.maxDoc() * 2);
            SidecarVersionBitmap newBitmap = new SidecarVersionBitmap(newSize);
            // Copy existing set bits from old bitmap to new bitmap
            long[] oldBits = bitmap.getBits();
            long[] newBits = newBitmap.getBits();
            System.arraycopy(oldBits, 0, newBits, 0, oldBits.length);
            bitmap = newBitmap;
        }
    }

    @Override
    public void close() throws IOException {
        writer.close();
        luceneDir.close();
    }

    /**
     * Result of a sidecar flush, containing metadata about the produced files.
     */
    public record SidecarWriteResult(
        String directory,
        long generation,
        Set<String> files,
        int numDocs,
        SidecarVersionBitmap bitmap
    ) {}
}
