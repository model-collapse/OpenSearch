/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.index.engine.sidecar;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.lucene.document.Document;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.FloatVectorValues;
import org.apache.lucene.index.IndexableField;
import org.apache.lucene.index.LeafReader;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.SortedNumericDocValues;
import org.apache.lucene.index.SortedSetDocValues;
import org.apache.lucene.index.StoredFields;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.opensearch.common.annotation.ExperimentalApi;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-shard registry that tracks active sidecar bitmaps per (field, segment) pair.
 * Provides the lookup function for {@link SidecarAwareDirectoryReader} to determine
 * which documents need _source patching at read time.
 *
 * @opensearch.experimental
 */
@ExperimentalApi
public class SidecarRegistry {

    private static final Logger logger = LogManager.getLogger(SidecarRegistry.class);

    // Map: fieldName -> Map<segmentName, SidecarVersionBitmap>
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, SidecarVersionBitmap>> activeBitmaps = new ConcurrentHashMap<>();

    // Map: fieldName -> Map<segmentName, Path> -- sidecar directory paths
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, Path>> sidecarPaths = new ConcurrentHashMap<>();

    // Map: segmentKey (field:segment) -> Set<filename> -- files produced by sidecar flushes
    private final ConcurrentHashMap<String, Set<String>> sidecarFiles = new ConcurrentHashMap<>();

    /**
     * Registers a sidecar bitmap for a given field and segment, along with the sidecar directory path.
     */
    public void register(String fieldName, String segmentName, SidecarVersionBitmap bitmap, Path sidecarPath) {
        activeBitmaps.computeIfAbsent(fieldName, k -> new ConcurrentHashMap<>()).put(segmentName, bitmap);
        if (sidecarPath != null) {
            sidecarPaths.computeIfAbsent(fieldName, k -> new ConcurrentHashMap<>()).put(segmentName, sidecarPath);
        }
    }

    /**
     * Registers a sidecar bitmap for a given field and segment (backward-compatible overload without path).
     */
    public void register(String fieldName, String segmentName, SidecarVersionBitmap bitmap) {
        register(fieldName, segmentName, bitmap, null);
    }

    /**
     * Unregisters a sidecar bitmap for a given field and segment.
     */
    public void unregister(String fieldName, String segmentName) {
        ConcurrentHashMap<String, SidecarVersionBitmap> segments = activeBitmaps.get(fieldName);
        if (segments != null) {
            segments.remove(segmentName);
            if (segments.isEmpty()) {
                activeBitmaps.remove(fieldName, segments);
            }
        }
    }

    /**
     * Returns true if the given document in the given segment has a dirty sidecar value for the field.
     */
    public boolean isDirty(String fieldName, String segmentName, int docId) {
        ConcurrentHashMap<String, SidecarVersionBitmap> segments = activeBitmaps.get(fieldName);
        if (segments == null) return false;
        SidecarVersionBitmap bitmap = segments.get(segmentName);
        if (bitmap == null) return false;
        return docId < bitmap.maxDoc() && bitmap.get(docId);
    }

    /**
     * Returns the sidecar directory path for a given field and segment.
     */
    public Path getSidecarPath(String fieldName, String segmentName) {
        ConcurrentHashMap<String, Path> segments = sidecarPaths.get(fieldName);
        return segments != null ? segments.get(segmentName) : null;
    }

    /**
     * Returns sidecar overlay values for a given document in a given segment.
     * Reads the actual updated value from the sidecar Lucene index and returns it
     * for _source patching.
     */
    public Map<String, Object> getSidecarValues(String fieldName, String segmentName, int docId) {
        if (!isDirty(fieldName, segmentName, docId)) {
            return Collections.emptyMap();
        }

        Path path = getSidecarPath(fieldName, segmentName);
        if (path == null) {
            return Collections.emptyMap();
        }

        try {
            return readSidecarValue(path, fieldName, docId);
        } catch (IOException e) {
            logger.warn(
                "Failed to read sidecar value for field [{}] segment [{}] doc [{}]: {}",
                fieldName,
                segmentName,
                docId,
                e.getMessage()
            );
            return Collections.emptyMap();
        }
    }

    private Map<String, Object> readSidecarValue(Path sidecarPath, String fieldName, int docId) throws IOException {
        try (Directory dir = FSDirectory.open(sidecarPath); DirectoryReader reader = DirectoryReader.open(dir)) {
            for (LeafReaderContext ctx : reader.leaves()) {
                LeafReader leaf = ctx.reader();
                StoredFields storedFields = leaf.storedFields();
                for (int i = 0; i < leaf.maxDoc(); i++) {
                    Document doc = storedFields.document(i);
                    IndexableField docIdField = doc.getField("original_doc_id");
                    if (docIdField != null && docIdField.numericValue().intValue() == docId) {
                        return extractFieldValue(leaf, i, fieldName);
                    }
                }
            }
        }
        return Collections.emptyMap();
    }

    private Map<String, Object> extractFieldValue(LeafReader leaf, int sidecarDocId, String fieldName) throws IOException {
        // Try vector field
        FloatVectorValues vectors = leaf.getFloatVectorValues(fieldName);
        if (vectors != null && vectors.advance(sidecarDocId) == sidecarDocId) {
            float[] vec = vectors.vectorValue();
            List<Double> vecList = new ArrayList<>(vec.length);
            for (float v : vec) {
                vecList.add((double) v);
            }
            return Map.of(fieldName, vecList);
        }

        // Try sorted set doc values (keyword)
        SortedSetDocValues ssdv = leaf.getSortedSetDocValues(fieldName);
        if (ssdv != null && ssdv.advanceExact(sidecarDocId)) {
            long ord = ssdv.nextOrd();
            if (ord != SortedSetDocValues.NO_MORE_ORDS) {
                return Map.of(fieldName, ssdv.lookupOrd(ord).utf8ToString());
            }
        }

        // Try sorted numeric doc values (long/double/date/boolean)
        SortedNumericDocValues sndv = leaf.getSortedNumericDocValues(fieldName);
        if (sndv != null && sndv.advanceExact(sidecarDocId)) {
            return Map.of(fieldName, sndv.nextValue());
        }

        return Collections.emptyMap();
    }

    /**
     * Returns the set of fields that have active sidecars.
     */
    public Set<String> getUpdatableFields() {
        return Collections.unmodifiableSet(activeBitmaps.keySet());
    }

    /**
     * Returns true if there are any active sidecars registered.
     */
    public boolean hasAnySidecars() {
        return !activeBitmaps.isEmpty();
    }

    /**
     * Returns the set of segment names that have sidecar bitmaps for a given field.
     */
    public Set<String> getSegmentsForField(String fieldName) {
        ConcurrentHashMap<String, SidecarVersionBitmap> segments = activeBitmaps.get(fieldName);
        return segments != null ? new HashSet<>(segments.keySet()) : Collections.emptySet();
    }

    /**
     * Returns the total number of dirty documents across all segments for a given field.
     */
    public long getDirtyDocCount(String fieldName) {
        ConcurrentHashMap<String, SidecarVersionBitmap> segments = activeBitmaps.get(fieldName);
        if (segments == null) return 0;
        return segments.values().stream().mapToLong(SidecarVersionBitmap::cardinality).sum();
    }

    /**
     * Returns the number of segments that have sidecar bitmaps for a given field.
     */
    public int getSegmentCount(String fieldName) {
        ConcurrentHashMap<String, SidecarVersionBitmap> segments = activeBitmaps.get(fieldName);
        return segments != null ? segments.size() : 0;
    }

    /**
     * Registers the set of files produced by a sidecar flush for a given segment key.
     * The segment key is typically "fieldName:segmentName".
     */
    public void registerFiles(String segmentKey, Set<String> files) {
        sidecarFiles.computeIfAbsent(segmentKey, k -> ConcurrentHashMap.newKeySet()).addAll(files);
    }

    /**
     * Returns all sidecar files across all registered segment keys.
     * Used by snapshot/replication to include sidecar files in the commit file listing.
     */
    public Set<String> getAllSidecarFiles() {
        Set<String> all = new HashSet<>();
        sidecarFiles.values().forEach(all::addAll);
        return all;
    }

    /**
     * Unregisters the sidecar files for a given segment key.
     */
    public void unregisterFiles(String segmentKey) {
        sidecarFiles.remove(segmentKey);
    }
}
