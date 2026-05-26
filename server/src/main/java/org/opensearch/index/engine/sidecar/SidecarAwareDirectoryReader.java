/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.index.engine.sidecar;

import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.FilterDirectoryReader;
import org.apache.lucene.index.LeafReader;

import java.io.IOException;
import java.util.Map;
import java.util.function.BiFunction;

/**
 * A {@link FilterDirectoryReader} that wraps leaf readers with {@link SidecarAwareLeafReader}
 * to overlay sidecar field values into _source at read time.
 *
 * @opensearch.internal
 */
public class SidecarAwareDirectoryReader extends FilterDirectoryReader {

    private final BiFunction<LeafReader, Integer, Map<String, Object>> sidecarProvider;
    private final SubReaderWrapper wrapper;

    private SidecarAwareDirectoryReader(
        DirectoryReader in,
        SubReaderWrapper wrapper,
        BiFunction<LeafReader, Integer, Map<String, Object>> sidecarProvider
    ) throws IOException {
        super(in, wrapper);
        this.wrapper = wrapper;
        this.sidecarProvider = sidecarProvider;
    }

    @Override
    protected DirectoryReader doWrapDirectoryReader(DirectoryReader directoryReader) throws IOException {
        return new SidecarAwareDirectoryReader(directoryReader, wrapper, sidecarProvider);
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
        return new SidecarAwareDirectoryReader(in, new SubReaderWrapper() {
            @Override
            public LeafReader wrap(LeafReader reader) {
                return new SidecarAwareLeafReader(reader, docId -> sidecarProvider.apply(reader, docId));
            }
        }, sidecarProvider);
    }
}
