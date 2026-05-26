/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.index.engine.sidecar;

import java.io.Closeable;
import java.io.IOException;
import java.util.Set;

/**
 * Common interface for all sidecar writers (knn vectors, doc_values, inverted-index).
 * Each implementation writes auxiliary data alongside the main Lucene segments.
 *
 * @opensearch.experimental
 */
public interface SidecarWriter<T> extends Closeable {

    void write(int docId, T value) throws IOException;

    SidecarWriteResult flush() throws IOException;

    SidecarVersionBitmap getVersionBitmap();

    long generation();

    int docsWritten();

    record SidecarWriteResult(
        String directory,
        long generation,
        Set<String> files,
        int numDocs,
        SidecarVersionBitmap bitmap
    ) {}
}
