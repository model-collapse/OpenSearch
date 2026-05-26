/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.index.engine;

import org.apache.lucene.index.IndexCommit;
import org.apache.lucene.store.Directory;
import org.opensearch.index.engine.exec.coord.CatalogSnapshot;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Map;

/**
 * Wraps a {@link CatalogSnapshot} as a Lucene {@link IndexCommit} so that standard
 * snapshot paths (peer recovery, remote store upload) can enumerate all files
 * including sidecar format files managed by DataFormatAwareEngine.
 *
 * @opensearch.internal
 */
public class CatalogSnapshotIndexCommit extends IndexCommit {

    private final CatalogSnapshot catalogSnapshot;
    private final Directory directory;
    private final long generation;

    public CatalogSnapshotIndexCommit(CatalogSnapshot catalogSnapshot, Directory directory, long generation) {
        this.catalogSnapshot = catalogSnapshot;
        this.directory = directory;
        this.generation = generation;
    }

    @Override
    public String getSegmentsFileName() {
        return "segments_" + Long.toString(generation, Character.MAX_RADIX);
    }

    @Override
    public Collection<String> getFileNames() throws IOException {
        return new ArrayList<>(catalogSnapshot.getFiles(true));
    }

    @Override
    public Directory getDirectory() {
        return directory;
    }

    @Override
    public void delete() {
        // no-op — lifecycle managed by GatedCloseable
    }

    @Override
    public boolean isDeleted() {
        return false;
    }

    @Override
    public int getSegmentCount() {
        return -1;
    }

    @Override
    public long getGeneration() {
        return generation;
    }

    @Override
    public Map<String, String> getUserData() throws IOException {
        return Map.of();
    }
}
