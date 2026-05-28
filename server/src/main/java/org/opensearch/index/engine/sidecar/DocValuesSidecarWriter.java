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
import org.apache.lucene.document.StoredField;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.NumericUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

/**
 * Writer that builds doc-values (keyword/ip as SortedSetDocValues, or
 * long/double/date/boolean as SortedNumericDocValues) for a subset of
 * document IDs. Maintains a {@link SidecarVersionBitmap} tracking which
 * docs are covered. Produces file set metadata on flush.
 *
 * @opensearch.experimental
 */
public class DocValuesSidecarWriter implements SidecarWriter<Object> {

    public enum DocValuesType {
        SORTED_SET,       // keyword, ip
        SORTED_NUMERIC    // long, int, double, float, date, boolean
    }

    private static final String DOC_ID_FIELD = "original_doc_id";

    private final Path directory;
    private final String fieldName;
    private final DocValuesType dvType;
    private final long generation;
    private final Directory luceneDir;
    private final IndexWriter writer;
    private SidecarVersionBitmap bitmap;
    private int docsWritten = 0;

    public DocValuesSidecarWriter(Path directory, String fieldName, DocValuesType dvType, long generation) throws IOException {
        this.directory = directory;
        this.fieldName = fieldName;
        this.dvType = dvType;
        this.generation = generation;
        Files.createDirectories(directory);
        this.luceneDir = FSDirectory.open(directory);
        this.bitmap = new SidecarVersionBitmap(65536);

        IndexWriterConfig config = new IndexWriterConfig();
        config.setUseCompoundFile(false);
        this.writer = new IndexWriter(luceneDir, config);
    }

    @Override
    public void write(int docId, Object value) throws IOException {
        if (docId < 0) {
            throw new IllegalArgumentException("docId must be non-negative, got: " + docId);
        }

        Document doc = new Document();
        doc.add(new StoredField(DOC_ID_FIELD, docId));

        switch (dvType) {
            case SORTED_SET -> {
                String strValue = convertToString(value);
                doc.add(new SortedSetDocValuesField(fieldName, new BytesRef(strValue)));
            }
            case SORTED_NUMERIC -> {
                long numValue = convertToLong(value);
                doc.add(new SortedNumericDocValuesField(fieldName, numValue));
            }
        }

        writer.addDocument(doc);
        bitmap = bitmap.growTo(docId + 1);
        bitmap.set(docId);
        docsWritten++;
    }

    @Override
    public SidecarWriteResult flush() throws IOException {
        if (docsWritten == 0) {
            return null;
        }
        writer.commit();

        Set<String> files = new HashSet<>();
        for (String f : luceneDir.listAll()) {
            if (!f.equals("write.lock") && !f.startsWith("segments")) {
                files.add(f);
            }
        }
        return new SidecarWriteResult(directory.toString(), generation, files, docsWritten, bitmap);
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

    public DocValuesType dvType() {
        return dvType;
    }

    private String convertToString(Object value) {
        if (value instanceof String s) return s;
        if (value instanceof BytesRef br) return br.utf8ToString();
        return value.toString();
    }

    private long convertToLong(Object value) {
        if (value instanceof Long l) return l;
        if (value instanceof Integer i) return i.longValue();
        if (value instanceof Double d) return NumericUtils.doubleToSortableLong(d);
        if (value instanceof Float f) return NumericUtils.floatToSortableInt(f);
        if (value instanceof Boolean b) return b ? 1L : 0L;
        if (value instanceof Number n) return n.longValue();
        throw new IllegalArgumentException("Cannot convert " + value.getClass().getSimpleName() + " to long for SortedNumericDocValues");
    }

}
