/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.index.engine.sidecar;

import org.apache.lucene.index.FilterLeafReader;
import org.apache.lucene.index.LeafReader;
import org.apache.lucene.index.SortedNumericDocValues;
import org.apache.lucene.index.SortedSetDocValues;
import org.apache.lucene.index.StoredFields;

import java.io.IOException;
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
        super(in);
        this.sidecarProvider = sidecarProvider;
        this.dvProviders = dvProviders != null ? dvProviders : Map.of();
    }

    /**
     * Creates a new SidecarAwareLeafReader without DocValues providers.
     *
     * @param in the delegate LeafReader
     * @param sidecarProvider a function that takes a docId and returns the map of sidecar fields to overlay,
     *                        or null/empty if the doc is clean
     */
    public SidecarAwareLeafReader(LeafReader in, Function<Integer, Map<String, Object>> sidecarProvider) {
        this(in, sidecarProvider, Map.of());
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
    public CacheHelper getCoreCacheHelper() {
        return null; // Sidecar overlay changes visible data — disable caching
    }

    @Override
    public CacheHelper getReaderCacheHelper() {
        return null; // Sidecar overlay changes visible data — disable caching
    }
}
