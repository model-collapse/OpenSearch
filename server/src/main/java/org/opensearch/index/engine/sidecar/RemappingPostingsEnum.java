/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.index.engine.sidecar;

import org.apache.lucene.index.PostingsEnum;
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.util.BytesRef;

import java.io.IOException;
import java.util.Arrays;

/**
 * Wraps a sidecar's PostingsEnum and remaps doc-IDs from sidecar-local space
 * to base-segment space using a monotonically increasing mapping array.
 * Enables efficient conjunction (advance/nextDoc) with base segment postings.
 *
 * @opensearch.experimental
 */
public class RemappingPostingsEnum extends PostingsEnum {

    private final PostingsEnum delegate;
    private final int[] sidecarToBase; // monotonically increasing

    public RemappingPostingsEnum(PostingsEnum delegate, int[] sidecarToBase) {
        this.delegate = delegate;
        this.sidecarToBase = sidecarToBase;
    }

    @Override
    public int docID() {
        int d = delegate.docID();
        if (d == -1 || d == DocIdSetIterator.NO_MORE_DOCS) {
            return d;
        }
        return sidecarToBase[d];
    }

    @Override
    public int nextDoc() throws IOException {
        int d = delegate.nextDoc();
        return d == DocIdSetIterator.NO_MORE_DOCS ? DocIdSetIterator.NO_MORE_DOCS : sidecarToBase[d];
    }

    @Override
    public int advance(int baseTarget) throws IOException {
        // Binary search sidecarToBase for first entry >= baseTarget
        int idx = Arrays.binarySearch(sidecarToBase, baseTarget);
        int sidecarTarget = idx >= 0 ? idx : -(idx + 1);
        if (sidecarTarget >= sidecarToBase.length) {
            delegate.advance(DocIdSetIterator.NO_MORE_DOCS);
            return DocIdSetIterator.NO_MORE_DOCS;
        }
        int d = delegate.advance(sidecarTarget);
        return d == DocIdSetIterator.NO_MORE_DOCS ? DocIdSetIterator.NO_MORE_DOCS : sidecarToBase[d];
    }

    @Override
    public int freq() throws IOException {
        return delegate.freq();
    }

    @Override
    public int nextPosition() throws IOException {
        return delegate.nextPosition();
    }

    @Override
    public int startOffset() throws IOException {
        return delegate.startOffset();
    }

    @Override
    public int endOffset() throws IOException {
        return delegate.endOffset();
    }

    @Override
    public BytesRef getPayload() throws IOException {
        return delegate.getPayload();
    }

    @Override
    public long cost() {
        return delegate.cost();
    }
}
