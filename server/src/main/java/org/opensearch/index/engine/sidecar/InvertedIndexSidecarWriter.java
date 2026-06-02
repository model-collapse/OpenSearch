/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.index.engine.sidecar;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.FieldType;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.index.IndexOptions;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Writer that builds inverted-index postings for a subset of document IDs
 * using re-analyzed text. Maintains a {@link SidecarVersionBitmap} tracking
 * which docs are covered. Produces file set metadata on flush.
 *
 * @opensearch.experimental
 */
public class InvertedIndexSidecarWriter implements SidecarWriter<String> {

    private static final String DOC_ID_FIELD = "original_doc_id";

    private final Path directory;
    private final String fieldName;
    private final long generation;
    private final Analyzer analyzer;
    private final FieldType fieldType;
    private final Directory luceneDir;
    private final IndexWriter writer;
    private final List<Map.Entry<Integer, String>> pending = new ArrayList<>();
    private SidecarVersionBitmap bitmap;
    private int docsWritten = 0;

    /**
     * Creates a writer with the default index options (DOCS_AND_FREQS_AND_POSITIONS).
     */
    public InvertedIndexSidecarWriter(Path directory, String fieldName, Analyzer analyzer, long generation) throws IOException {
        this(directory, fieldName, analyzer, generation, IndexOptions.DOCS_AND_FREQS_AND_POSITIONS);
    }

    /**
     * Creates a writer with the specified index options.
     */
    public InvertedIndexSidecarWriter(Path directory, String fieldName, Analyzer analyzer, long generation, IndexOptions indexOptions)
        throws IOException {
        this.directory = directory;
        this.fieldName = fieldName;
        this.analyzer = analyzer;
        this.generation = generation;
        Files.createDirectories(directory);
        this.luceneDir = FSDirectory.open(directory);
        this.bitmap = new SidecarVersionBitmap(65536);

        this.fieldType = new FieldType();
        this.fieldType.setTokenized(true);
        this.fieldType.setIndexOptions(indexOptions);
        this.fieldType.setStored(false);
        this.fieldType.setOmitNorms(false);
        this.fieldType.freeze();

        IndexWriterConfig config = new IndexWriterConfig(analyzer);
        config.setUseCompoundFile(false);
        this.writer = new IndexWriter(luceneDir, config);
    }

    @Override
    public void write(int docId, String text) throws IOException {
        if (docId < 0) {
            throw new IllegalArgumentException("docId must be non-negative, got: " + docId);
        }
        if (text == null) {
            throw new IllegalArgumentException("text must not be null");
        }

        pending.add(Map.entry(docId, text));
        bitmap = bitmap.growTo(docId + 1);
        bitmap.set(docId);
        docsWritten++;
    }

    @Override
    public SidecarWriteResult flush() throws IOException {
        if (docsWritten == 0) {
            return null;
        }

        // Sort by base doc-ID for monotonic mapping
        pending.sort(Comparator.comparingInt(Map.Entry::getKey));

        // Build sidecar-to-base mapping array and write documents in sorted order
        int[] sidecarToBase = new int[pending.size()];
        for (int i = 0; i < pending.size(); i++) {
            sidecarToBase[i] = pending.get(i).getKey();
            Document doc = new Document();
            doc.add(new Field(fieldName, pending.get(i).getValue(), fieldType));
            doc.add(new StoredField(DOC_ID_FIELD, pending.get(i).getKey()));
            writer.addDocument(doc);
        }
        writer.commit();

        // Persist the mapping array alongside the segment
        writeMappingArray(sidecarToBase);

        Set<String> files = new HashSet<>();
        for (String f : luceneDir.listAll()) {
            if (!f.equals("write.lock") && !f.startsWith("segments")) {
                files.add(f);
            }
        }
        return new SidecarWriteResult(directory.toString(), generation, files, docsWritten, bitmap);
    }

    private void writeMappingArray(int[] mapping) throws IOException {
        Path mappingFile = directory.resolve("_sidecar_to_base.map");
        try (var out = Files.newOutputStream(mappingFile)) {
            ByteBuffer buf = ByteBuffer.allocate(4 + mapping.length * 4).order(ByteOrder.LITTLE_ENDIAN);
            buf.putInt(mapping.length);
            for (int id : mapping) {
                buf.putInt(id);
            }
            out.write(buf.array());
        }
    }

    /**
     * Reads the sidecar-to-base doc-ID mapping array from a sidecar directory.
     *
     * @param sidecarDir the path to the sidecar directory
     * @return the mapping array, or null if no mapping file exists
     */
    public static int[] readMappingArray(Path sidecarDir) throws IOException {
        Path mappingFile = sidecarDir.resolve("_sidecar_to_base.map");
        if (!Files.exists(mappingFile)) {
            return null;
        }
        byte[] data = Files.readAllBytes(mappingFile);
        ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        int len = buf.getInt();
        int[] mapping = new int[len];
        for (int i = 0; i < len; i++) {
            mapping[i] = buf.getInt();
        }
        return mapping;
    }

    @Override
    public SidecarVersionBitmap getVersionBitmap() {
        return bitmap;
    }

    @Override
    public long generation() {
        return generation;
    }

    @Override
    public int docsWritten() {
        return docsWritten;
    }

    @Override
    public void close() throws IOException {
        writer.close();
        luceneDir.close();
    }

    public String fieldName() {
        return fieldName;
    }

    public Analyzer analyzer() {
        return analyzer;
    }

}
