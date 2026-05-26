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
import org.apache.lucene.index.StoredFields;

import java.io.IOException;
import java.util.Map;
import java.util.function.Function;

/**
 * A {@link FilterLeafReader} that overrides {@link #storedFields()} to return
 * {@link SidecarAwareStoredFields}, which overlays sidecar field values into _source.
 *
 * @opensearch.internal
 */
public class SidecarAwareLeafReader extends FilterLeafReader {

    private final Function<Integer, Map<String, Object>> sidecarProvider;

    /**
     * Creates a new SidecarAwareLeafReader.
     *
     * @param in the delegate LeafReader
     * @param sidecarProvider a function that takes a docId and returns the map of sidecar fields to overlay,
     *                        or null/empty if the doc is clean
     */
    public SidecarAwareLeafReader(LeafReader in, Function<Integer, Map<String, Object>> sidecarProvider) {
        super(in);
        this.sidecarProvider = sidecarProvider;
    }

    @Override
    public StoredFields storedFields() throws IOException {
        return new SidecarAwareStoredFields(in.storedFields(), sidecarProvider);
    }

    @Override
    public CacheHelper getCoreCacheHelper() {
        return in.getCoreCacheHelper();
    }

    @Override
    public CacheHelper getReaderCacheHelper() {
        return in.getReaderCacheHelper();
    }
}
