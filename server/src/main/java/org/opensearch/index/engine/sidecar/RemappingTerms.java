/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.index.engine.sidecar;

import org.apache.lucene.index.ImpactsEnum;
import org.apache.lucene.index.SlowImpactsEnum;
import org.apache.lucene.index.PostingsEnum;
import org.apache.lucene.index.TermState;
import org.apache.lucene.index.Terms;
import org.apache.lucene.index.TermsEnum;
import org.apache.lucene.util.AttributeSource;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.IOBooleanSupplier;

import java.io.IOException;

/**
 * Wraps a sidecar segment's Terms and returns RemappingPostingsEnum
 * instances that translate doc-IDs to base-segment space.
 *
 * @opensearch.experimental
 */
public class RemappingTerms extends Terms {

    private final Terms delegate;
    private final int[] sidecarToBase;

    public RemappingTerms(Terms delegate, int[] sidecarToBase) {
        this.delegate = delegate;
        this.sidecarToBase = sidecarToBase;
    }

    @Override
    public TermsEnum iterator() throws IOException {
        return new RemappingTermsEnum(delegate.iterator(), sidecarToBase);
    }

    @Override
    public long size() throws IOException {
        return delegate.size();
    }

    @Override
    public long getSumTotalTermFreq() throws IOException {
        return delegate.getSumTotalTermFreq();
    }

    @Override
    public long getSumDocFreq() throws IOException {
        return delegate.getSumDocFreq();
    }

    @Override
    public int getDocCount() throws IOException {
        return delegate.getDocCount();
    }

    @Override
    public boolean hasFreqs() {
        return delegate.hasFreqs();
    }

    @Override
    public boolean hasOffsets() {
        return delegate.hasOffsets();
    }

    @Override
    public boolean hasPositions() {
        return delegate.hasPositions();
    }

    @Override
    public boolean hasPayloads() {
        return delegate.hasPayloads();
    }

    private static class RemappingTermsEnum extends TermsEnum {
        private final TermsEnum delegate;
        private final int[] sidecarToBase;

        RemappingTermsEnum(TermsEnum delegate, int[] sidecarToBase) {
            this.delegate = delegate;
            this.sidecarToBase = sidecarToBase;
        }

        @Override
        public AttributeSource attributes() {
            return delegate.attributes();
        }

        @Override
        public BytesRef next() throws IOException {
            return delegate.next();
        }

        @Override
        public boolean seekExact(BytesRef text) throws IOException {
            return delegate.seekExact(text);
        }

        @Override
        public IOBooleanSupplier prepareSeekExact(BytesRef text) throws IOException {
            return delegate.prepareSeekExact(text);
        }

        @Override
        public SeekStatus seekCeil(BytesRef text) throws IOException {
            return delegate.seekCeil(text);
        }

        @Override
        public void seekExact(long ord) throws IOException {
            delegate.seekExact(ord);
        }

        @Override
        public BytesRef term() throws IOException {
            return delegate.term();
        }

        @Override
        public long ord() throws IOException {
            return delegate.ord();
        }

        @Override
        public int docFreq() throws IOException {
            return delegate.docFreq();
        }

        @Override
        public long totalTermFreq() throws IOException {
            return delegate.totalTermFreq();
        }

        @Override
        public PostingsEnum postings(PostingsEnum reuse, int flags) throws IOException {
            PostingsEnum basePostings = delegate.postings(null, flags);
            return new RemappingPostingsEnum(basePostings, sidecarToBase);
        }

        @Override
        public ImpactsEnum impacts(int flags) throws IOException {
            // SlowImpactsEnum disables WAND block skipping for sidecar terms.
            // This is correct: sidecar posting lists are short (only dirty docs),
            // so WAND gain is negligible. Avoids the doc-ID space mismatch bug
            // that would occur if we returned the sidecar's raw ImpactsEnum.
            return new SlowImpactsEnum(postings(null, flags));
        }

        @Override
        public TermState termState() throws IOException {
            return delegate.termState();
        }

        @Override
        public void seekExact(BytesRef term, TermState state) throws IOException {
            delegate.seekExact(term, state);
        }
    }
}
