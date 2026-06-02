/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.index.engine.sidecar;

import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.FilterLeafReader;
import org.apache.lucene.index.LeafReader;
import org.apache.lucene.index.SortedNumericDocValues;
import org.apache.lucene.index.SortedSetDocValues;
import org.apache.lucene.index.StoredFields;
import org.apache.lucene.index.Terms;
import org.apache.lucene.store.FSDirectory;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.Function;

/**
 * A {@link FilterLeafReader} that overrides {@link #storedFields()} to return
 * {@link SidecarAwareStoredFields}, which overlays sidecar field values into _source.
 * Also overrides DocValues access methods to serve updated values from sidecars.
 *
 * @opensearch.internal
 */
public class SidecarAwareLeafReader extends FilterLeafReader {

    private final Function<Integer, Map<String, Object>> sidecarProvider;
    private final Map<String, SidecarDocValuesProvider> dvProviders;
    private final Map<String, RemappingTermsProvider> termsProviders;

    /**
     * Creates a new SidecarAwareLeafReader with DocValues providers and remapping terms providers.
     *
     * @param in the delegate LeafReader
     * @param sidecarProvider a function that takes a docId and returns the map of sidecar fields to overlay,
     *                        or null/empty if the doc is clean
     * @param dvProviders a map of field name to {@link SidecarDocValuesProvider} for fields that have
     *                    sidecar DocValues
     * @param termsProviders a map of field name to {@link RemappingTermsProvider} for fields that have
     *                       sidecar inverted index postings
     */
    public SidecarAwareLeafReader(
        LeafReader in,
        Function<Integer, Map<String, Object>> sidecarProvider,
        Map<String, SidecarDocValuesProvider> dvProviders,
        Map<String, RemappingTermsProvider> termsProviders
    ) {
        super(in);
        this.sidecarProvider = sidecarProvider;
        this.dvProviders = dvProviders != null ? dvProviders : Map.of();
        this.termsProviders = termsProviders != null ? termsProviders : Map.of();
    }

    /**
     * Creates a new SidecarAwareLeafReader with DocValues providers.
     *
     * @param in the delegate LeafReader
     * @param sidecarProvider a function that takes a docId and returns the map of sidecar fields to overlay,
     *                        or null/empty if the doc is clean
     * @param dvProviders a map of field name to {@link SidecarDocValuesProvider} for fields that have
     *                    sidecar DocValues
     */
    public SidecarAwareLeafReader(
        LeafReader in,
        Function<Integer, Map<String, Object>> sidecarProvider,
        Map<String, SidecarDocValuesProvider> dvProviders
    ) {
        this(in, sidecarProvider, dvProviders, Map.of());
    }

    /**
     * Creates a new SidecarAwareLeafReader without DocValues providers.
     *
     * @param in the delegate LeafReader
     * @param sidecarProvider a function that takes a docId and returns the map of sidecar fields to overlay,
     *                        or null/empty if the doc is clean
     */
    public SidecarAwareLeafReader(LeafReader in, Function<Integer, Map<String, Object>> sidecarProvider) {
        this(in, sidecarProvider, Map.of(), Map.of());
    }

    @Override
    public StoredFields storedFields() throws IOException {
        return new SidecarAwareStoredFields(in.storedFields(), sidecarProvider);
    }

    @Override
    public SortedSetDocValues getSortedSetDocValues(String field) throws IOException {
        SidecarDocValuesProvider provider = dvProviders.get(field);
        if (provider != null) {
            SortedSetDocValues sidecarDV = provider.getSortedSetDocValues();
            if (sidecarDV != null) {
                return sidecarDV;
            }
        }
        return super.getSortedSetDocValues(field);
    }

    @Override
    public SortedNumericDocValues getSortedNumericDocValues(String field) throws IOException {
        SidecarDocValuesProvider provider = dvProviders.get(field);
        if (provider != null) {
            SortedNumericDocValues sidecarDV = provider.getSortedNumericDocValues();
            if (sidecarDV != null) {
                return sidecarDV;
            }
        }
        return super.getSortedNumericDocValues(field);
    }

    @Override
    public Terms terms(String field) throws IOException {
        RemappingTermsProvider provider = termsProviders.get(field);
        if (provider != null) {
            Terms sidecarTerms = provider.getTerms();
            if (sidecarTerms != null) {
                return new RemappingTerms(sidecarTerms, provider.getMapping());
            }
        }
        return super.terms(field);
    }

    @Override
    public CacheHelper getCoreCacheHelper() {
        return null; // Sidecar overlay changes visible data — disable caching
    }

    @Override
    public CacheHelper getReaderCacheHelper() {
        return null; // Sidecar overlay changes visible data — disable caching
    }

    /**
     * Provides access to a sidecar's inverted index terms with a doc-ID mapping
     * for translating sidecar-local IDs to base-segment IDs.
     *
     * @opensearch.experimental
     */
    public static class RemappingTermsProvider implements Closeable {
        private final String fieldName;
        private final int[] mapping;
        private final DirectoryReader reader;

        public RemappingTermsProvider(Path sidecarPath, String fieldName, int[] mapping) throws IOException {
            this.fieldName = fieldName;
            this.mapping = mapping;
            this.reader = DirectoryReader.open(FSDirectory.open(sidecarPath));
        }

        public Terms getTerms() throws IOException {
            return reader.leaves().get(0).reader().terms(fieldName);
        }

        public int[] getMapping() {
            return mapping;
        }

        @Override
        public void close() throws IOException {
            reader.close();
        }
    }
}
