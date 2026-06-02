/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.index.engine.sidecar;

import org.apache.lucene.index.PointValues;
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.util.IntsRef;
import org.opensearch.common.annotation.ExperimentalApi;

import java.io.IOException;

/**
 * Wraps {@link PointValues} to exclude documents that have sidecar updates.
 * Prevents BKD range queries from returning stale matches when the field
 * has been updated via sidecar (the base segment's BKD tree still contains
 * the old value).
 *
 * @opensearch.experimental
 */
@ExperimentalApi
public class FilteredPointValues extends PointValues {

    private final PointValues delegate;
    private final SidecarVersionBitmap dirtyBitmap;

    public FilteredPointValues(PointValues delegate, SidecarVersionBitmap dirtyBitmap) {
        this.delegate = delegate;
        this.dirtyBitmap = dirtyBitmap;
    }

    @Override
    public PointTree getPointTree() throws IOException {
        return new FilteredPointTree(delegate.getPointTree(), dirtyBitmap);
    }

    @Override
    public byte[] getMinPackedValue() throws IOException {
        return delegate.getMinPackedValue();
    }

    @Override
    public byte[] getMaxPackedValue() throws IOException {
        return delegate.getMaxPackedValue();
    }

    @Override
    public int getNumDimensions() throws IOException {
        return delegate.getNumDimensions();
    }

    @Override
    public int getNumIndexDimensions() throws IOException {
        return delegate.getNumIndexDimensions();
    }

    @Override
    public int getBytesPerDimension() throws IOException {
        return delegate.getBytesPerDimension();
    }

    @Override
    public long size() {
        // Approximate: subtract dirty docs (may overcount if some dirty docs had no point value)
        long base = delegate.size();
        long dirty = dirtyBitmap.cardinality();
        return Math.max(0, base - dirty);
    }

    @Override
    public int getDocCount() {
        // Approximate: subtract dirty docs
        int base = delegate.getDocCount();
        int dirty = dirtyBitmap.cardinality();
        return Math.max(0, base - dirty);
    }

    private static class FilteredPointTree implements PointValues.PointTree {
        private final PointValues.PointTree delegate;
        private final SidecarVersionBitmap dirtyBitmap;

        FilteredPointTree(PointValues.PointTree delegate, SidecarVersionBitmap dirtyBitmap) {
            this.delegate = delegate;
            this.dirtyBitmap = dirtyBitmap;
        }

        @Override
        public PointValues.PointTree clone() {
            return new FilteredPointTree(delegate.clone(), dirtyBitmap);
        }

        @Override
        public boolean moveToChild() throws IOException {
            return delegate.moveToChild();
        }

        @Override
        public boolean moveToSibling() throws IOException {
            return delegate.moveToSibling();
        }

        @Override
        public boolean moveToParent() throws IOException {
            return delegate.moveToParent();
        }

        @Override
        public byte[] getMinPackedValue() {
            return delegate.getMinPackedValue();
        }

        @Override
        public byte[] getMaxPackedValue() {
            return delegate.getMaxPackedValue();
        }

        @Override
        public long size() {
            return delegate.size();
        }

        @Override
        public void visitDocIDs(PointValues.IntersectVisitor visitor) throws IOException {
            delegate.visitDocIDs(new FilteringVisitor(visitor, dirtyBitmap));
        }

        @Override
        public void visitDocValues(PointValues.IntersectVisitor visitor) throws IOException {
            delegate.visitDocValues(new FilteringVisitor(visitor, dirtyBitmap));
        }
    }

    private static class FilteringVisitor implements PointValues.IntersectVisitor {
        private final PointValues.IntersectVisitor delegate;
        private final SidecarVersionBitmap dirtyBitmap;

        FilteringVisitor(PointValues.IntersectVisitor delegate, SidecarVersionBitmap dirtyBitmap) {
            this.delegate = delegate;
            this.dirtyBitmap = dirtyBitmap;
        }

        @Override
        public void visit(int docID) throws IOException {
            if (docID < dirtyBitmap.maxDoc() && dirtyBitmap.get(docID)) {
                return; // skip dirty doc
            }
            delegate.visit(docID);
        }

        @Override
        public void visit(DocIdSetIterator iterator) throws IOException {
            // Filter batch iteration by visiting one-by-one with dirty check
            for (int docID = iterator.nextDoc(); docID != DocIdSetIterator.NO_MORE_DOCS; docID = iterator.nextDoc()) {
                if (docID >= dirtyBitmap.maxDoc() || !dirtyBitmap.get(docID)) {
                    delegate.visit(docID);
                }
            }
        }

        @Override
        public void visit(IntsRef ref) throws IOException {
            // Filter batch iteration by visiting one-by-one with dirty check
            for (int i = ref.offset; i < ref.offset + ref.length; i++) {
                int docID = ref.ints[i];
                if (docID >= dirtyBitmap.maxDoc() || !dirtyBitmap.get(docID)) {
                    delegate.visit(docID);
                }
            }
        }

        @Override
        public void visit(int docID, byte[] packedValue) throws IOException {
            if (docID < dirtyBitmap.maxDoc() && dirtyBitmap.get(docID)) {
                return; // skip dirty doc
            }
            delegate.visit(docID, packedValue);
        }

        @Override
        public void visit(DocIdSetIterator iterator, byte[] packedValue) throws IOException {
            // Filter batch iteration by visiting one-by-one with dirty check
            for (int docID = iterator.nextDoc(); docID != DocIdSetIterator.NO_MORE_DOCS; docID = iterator.nextDoc()) {
                if (docID >= dirtyBitmap.maxDoc() || !dirtyBitmap.get(docID)) {
                    delegate.visit(docID, packedValue);
                }
            }
        }

        @Override
        public Relation compare(byte[] minPackedValue, byte[] maxPackedValue) {
            return delegate.compare(minPackedValue, maxPackedValue);
        }

        @Override
        public void grow(int count) {
            delegate.grow(count);
        }
    }
}
