/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.index.engine.sidecar;

import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.LeafReader;
import org.apache.lucene.index.SortedNumericDocValues;
import org.apache.lucene.index.SortedSetDocValues;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Path;

/**
 * Provides DocValues from sidecar segments for a given field.
 * Used by {@link SidecarAwareLeafReader} to serve updated values for dirty docs.
 *
 * <p>The sidecar segment is expected to contain a single segment with all updated
 * documents for the given field. This provider opens and reads from that segment.
 *
 * @opensearch.internal
 */
public class SidecarDocValuesProvider implements Closeable {

    private final Path sidecarPath;
    private final String fieldName;
    private final Directory dir;
    private final DirectoryReader reader;
    private final LeafReader leafReader;

    /**
     * Creates a new SidecarDocValuesProvider.
     *
     * @param sidecarPath path to the sidecar index directory
     * @param fieldName   the field name to read DocValues for
     * @throws IOException if an I/O error occurs opening the sidecar
     */
    public SidecarDocValuesProvider(Path sidecarPath, String fieldName) throws IOException {
        this.sidecarPath = sidecarPath;
        this.fieldName = fieldName;
        this.dir = FSDirectory.open(sidecarPath);
        this.reader = DirectoryReader.open(dir);
        // Sidecar has a single segment
        this.leafReader = reader.leaves().get(0).reader();
    }

    /**
     * Returns the SortedSetDocValues for the configured field from the sidecar,
     * or null if the field does not have SortedSetDocValues in the sidecar.
     */
    public SortedSetDocValues getSortedSetDocValues() throws IOException {
        return leafReader.getSortedSetDocValues(fieldName);
    }

    /**
     * Returns the SortedNumericDocValues for the configured field from the sidecar,
     * or null if the field does not have SortedNumericDocValues in the sidecar.
     */
    public SortedNumericDocValues getSortedNumericDocValues() throws IOException {
        return leafReader.getSortedNumericDocValues(fieldName);
    }

    /**
     * Returns the underlying LeafReader for advanced access to the sidecar segment.
     */
    public LeafReader getLeafReader() {
        return leafReader;
    }

    /**
     * Returns the path to the sidecar directory.
     */
    public Path getSidecarPath() {
        return sidecarPath;
    }

    /**
     * Returns the field name this provider reads DocValues for.
     */
    public String getFieldName() {
        return fieldName;
    }

    @Override
    public void close() throws IOException {
        reader.close();
        dir.close();
    }
}
