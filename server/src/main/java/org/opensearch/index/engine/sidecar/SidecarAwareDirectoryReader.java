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
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.FilterDirectoryReader;
import org.apache.lucene.index.LeafReader;
import org.apache.lucene.index.SegmentReader;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.function.BiFunction;

/**
 * A {@link FilterDirectoryReader} that wraps leaf readers with {@link SidecarAwareLeafReader}
 * to overlay sidecar field values into _source at read time.
 *
 * @opensearch.internal
 */
public class SidecarAwareDirectoryReader extends FilterDirectoryReader {

    private static final Logger logger = LogManager.getLogger(SidecarAwareDirectoryReader.class);

    private final BiFunction<LeafReader, Integer, Map<String, Object>> sidecarProvider;
    private final SidecarRegistry registry;
    private final SubReaderWrapper wrapper;

    private SidecarAwareDirectoryReader(
        DirectoryReader in,
        SubReaderWrapper wrapper,
        BiFunction<LeafReader, Integer, Map<String, Object>> sidecarProvider,
        SidecarRegistry registry
    ) throws IOException {
        super(in, wrapper);
        this.wrapper = wrapper;
        this.sidecarProvider = sidecarProvider;
        this.registry = registry;
    }

    @Override
    protected DirectoryReader doWrapDirectoryReader(DirectoryReader directoryReader) throws IOException {
        return new SidecarAwareDirectoryReader(directoryReader, wrapper, sidecarProvider, registry);
    }

    @Override
    public CacheHelper getReaderCacheHelper() {
        return in.getReaderCacheHelper();
    }

    /**
     * Wraps the given DirectoryReader so that _source reads overlay sidecar field values for dirty documents.
     *
     * @param in the DirectoryReader to wrap
     * @param sidecarProvider a function that takes (leafReader, docId) and returns a Map of field-to-value
     *                        to overlay, or null/empty if the document is clean
     * @return a wrapped DirectoryReader
     * @throws IOException if an I/O error occurs
     */
    public static SidecarAwareDirectoryReader wrap(
        DirectoryReader in,
        BiFunction<LeafReader, Integer, Map<String, Object>> sidecarProvider
    ) throws IOException {
        return wrap(in, sidecarProvider, null);
    }

    /**
     * Wraps the given DirectoryReader so that _source reads overlay sidecar field values for dirty documents,
     * and text search queries on updatable fields use sidecar inverted index postings via RemappingTerms.
     *
     * @param in the DirectoryReader to wrap
     * @param sidecarProvider a function that takes (leafReader, docId) and returns a Map of field-to-value
     *                        to overlay, or null/empty if the document is clean
     * @param registry the SidecarRegistry used to detect inverted index sidecars and build RemappingTermsProviders;
     *                 may be null if no term remapping is needed
     * @return a wrapped DirectoryReader
     * @throws IOException if an I/O error occurs
     */
    public static SidecarAwareDirectoryReader wrap(
        DirectoryReader in,
        BiFunction<LeafReader, Integer, Map<String, Object>> sidecarProvider,
        SidecarRegistry registry
    ) throws IOException {
        return new SidecarAwareDirectoryReader(in, new SubReaderWrapper() {
            @Override
            public LeafReader wrap(LeafReader reader) {
                Map<String, SidecarAwareLeafReader.RemappingTermsProvider> termsProviders = buildTermsProviders(reader, registry);
                return new SidecarAwareLeafReader(reader, docId -> sidecarProvider.apply(reader, docId), Map.of(), termsProviders);
            }
        }, sidecarProvider, registry);
    }

    /**
     * Builds RemappingTermsProvider instances for each field that has an inverted index sidecar
     * for the given leaf reader's segment.
     */
    private static Map<String, SidecarAwareLeafReader.RemappingTermsProvider> buildTermsProviders(
        LeafReader reader,
        SidecarRegistry registry
    ) {
        if (registry == null) {
            return Map.of();
        }
        String segmentName = (reader instanceof SegmentReader sr) ? sr.getSegmentName() : null;
        if (segmentName == null) {
            return Map.of();
        }
        Map<String, SidecarAwareLeafReader.RemappingTermsProvider> providers = new HashMap<>();
        for (String field : registry.getUpdatableFields()) {
            if (registry.hasInvertedIndexSidecar(field, segmentName)) {
                try {
                    int[] mapping = registry.getDocIdMapping(field, segmentName);
                    Path sidecarPath = registry.getSidecarPath(field, segmentName);
                    if (mapping != null && sidecarPath != null) {
                        providers.put(field, new SidecarAwareLeafReader.RemappingTermsProvider(sidecarPath, field, mapping));
                    }
                } catch (IOException e) {
                    logger.warn(
                        "Failed to build RemappingTermsProvider for field [{}] segment [{}]: {}",
                        field,
                        segmentName,
                        e.getMessage()
                    );
                }
            }
        }
        return providers;
    }
}
