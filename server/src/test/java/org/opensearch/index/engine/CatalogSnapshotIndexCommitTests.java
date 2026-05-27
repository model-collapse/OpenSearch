/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.index.engine;

import org.apache.lucene.store.ByteBuffersDirectory;
import org.apache.lucene.store.Directory;
import org.opensearch.core.common.io.stream.StreamOutput;
import org.opensearch.index.engine.exec.Segment;
import org.opensearch.index.engine.exec.WriterFileSet;
import org.opensearch.index.engine.exec.coord.CatalogSnapshot;
import org.opensearch.test.OpenSearchTestCase;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class CatalogSnapshotIndexCommitTests extends OpenSearchTestCase {

    private CatalogSnapshot createStubCatalogSnapshot(Collection<String> files, long generation) {
        return new CatalogSnapshot("test_snapshot", generation, 1L) {
            @Override
            protected void closeInternal() {}

            @Override
            public Map<String, String> getUserData() {
                return Collections.emptyMap();
            }

            @Override
            public long getId() {
                return 1L;
            }

            @Override
            public List<Segment> getSegments() {
                return Collections.emptyList();
            }

            @Override
            public Collection<WriterFileSet> getSearchableFiles(String dataFormat) {
                return Collections.emptyList();
            }

            @Override
            public Set<String> getDataFormats() {
                return Collections.emptySet();
            }

            @Override
            public long getLastWriterGeneration() {
                return 0L;
            }

            @Override
            public String serializeToString() throws IOException {
                return "";
            }

            @Override
            public void setUserData(Map<String, String> userData, boolean commitData) {}

            @Override
            public CatalogSnapshot clone() {
                return this;
            }

            @Override
            public long getFormatVersionForFile(String file) {
                return 0L;
            }

            @Override
            public long getMinSegmentFormatVersion() {
                return 0L;
            }

            @Override
            public long getCommitDataFormatVersion() {
                return 0L;
            }

            @Override
            public long getNumDocs() {
                return 0L;
            }

            @Override
            public String getLastCommitFileName() {
                return null;
            }

            @Override
            public Collection<String> getFiles(boolean includeSegmentsFile) throws IOException {
                return new HashSet<>(files);
            }

            @Override
            public void writeTo(StreamOutput out) throws IOException {
                super.writeTo(out);
            }
        };
    }

    public void testGetFileNamesReturnsFilesFromCatalogSnapshot() throws IOException {
        Collection<String> expectedFiles = Arrays.asList("_0.cfe", "_0.cfs", "_0.si", "segments_1");
        CatalogSnapshot snapshot = createStubCatalogSnapshot(expectedFiles, 1L);
        Directory directory = new ByteBuffersDirectory();

        CatalogSnapshotIndexCommit commit = new CatalogSnapshotIndexCommit(snapshot, directory, 1L);

        Collection<String> actualFiles = commit.getFileNames();
        assertEquals(new HashSet<>(expectedFiles), new HashSet<>(actualFiles));
    }

    public void testGetGenerationReturnsCorrectGeneration() {
        CatalogSnapshot snapshot = createStubCatalogSnapshot(Collections.emptyList(), 42L);
        Directory directory = new ByteBuffersDirectory();

        CatalogSnapshotIndexCommit commit = new CatalogSnapshotIndexCommit(snapshot, directory, 42L);

        assertEquals(42L, commit.getGeneration());
    }

    public void testGetDirectoryReturnsProvidedDirectory() {
        CatalogSnapshot snapshot = createStubCatalogSnapshot(Collections.emptyList(), 1L);
        Directory directory = new ByteBuffersDirectory();

        CatalogSnapshotIndexCommit commit = new CatalogSnapshotIndexCommit(snapshot, directory, 1L);

        assertSame(directory, commit.getDirectory());
    }

    public void testIsDeletedReturnsFalse() {
        CatalogSnapshot snapshot = createStubCatalogSnapshot(Collections.emptyList(), 1L);
        Directory directory = new ByteBuffersDirectory();

        CatalogSnapshotIndexCommit commit = new CatalogSnapshotIndexCommit(snapshot, directory, 1L);

        assertFalse(commit.isDeleted());
    }

    public void testDeleteIsNoOp() {
        CatalogSnapshot snapshot = createStubCatalogSnapshot(Collections.emptyList(), 1L);
        Directory directory = new ByteBuffersDirectory();

        CatalogSnapshotIndexCommit commit = new CatalogSnapshotIndexCommit(snapshot, directory, 1L);

        // Should not throw any exception
        commit.delete();
        // After delete, isDeleted should still return false (no-op)
        assertFalse(commit.isDeleted());
    }

    public void testGetFileNamesIncludesSidecarFiles() throws IOException {
        Collection<String> baseFiles = Arrays.asList("_0.cfe", "_0.cfs", "_0.si", "segments_1");
        Set<String> sidecarFiles = new LinkedHashSet<>(Arrays.asList("_sidecar_embedding_0.vec", "_sidecar_embedding_0.vem"));
        CatalogSnapshot snapshot = createStubCatalogSnapshot(baseFiles, 1L);
        Directory directory = new ByteBuffersDirectory();

        CatalogSnapshotIndexCommit commit = new CatalogSnapshotIndexCommit(snapshot, directory, 1L, sidecarFiles);

        Collection<String> actualFiles = commit.getFileNames();
        Set<String> expectedFiles = new HashSet<>(baseFiles);
        expectedFiles.addAll(sidecarFiles);
        assertEquals(expectedFiles, new HashSet<>(actualFiles));
    }

    public void testGetFileNamesWithEmptySidecarFiles() throws IOException {
        Collection<String> baseFiles = Arrays.asList("_0.cfe", "_0.cfs", "segments_1");
        CatalogSnapshot snapshot = createStubCatalogSnapshot(baseFiles, 1L);
        Directory directory = new ByteBuffersDirectory();

        CatalogSnapshotIndexCommit commit = new CatalogSnapshotIndexCommit(snapshot, directory, 1L, Collections.emptySet());

        Collection<String> actualFiles = commit.getFileNames();
        assertEquals(new HashSet<>(baseFiles), new HashSet<>(actualFiles));
    }

    public void testGetFileNamesWithNullSidecarFiles() throws IOException {
        Collection<String> baseFiles = Arrays.asList("_0.cfe", "_0.cfs", "segments_1");
        CatalogSnapshot snapshot = createStubCatalogSnapshot(baseFiles, 1L);
        Directory directory = new ByteBuffersDirectory();

        CatalogSnapshotIndexCommit commit = new CatalogSnapshotIndexCommit(snapshot, directory, 1L, null);

        Collection<String> actualFiles = commit.getFileNames();
        assertEquals(new HashSet<>(baseFiles), new HashSet<>(actualFiles));
    }
}
