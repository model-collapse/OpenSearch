package org.opensearch.index.engine.sidecar;

import java.util.Arrays;

import org.apache.lucene.util.FixedBitSet;
import org.opensearch.common.annotation.ExperimentalApi;

/**
 * Tracks which document IDs have been updated via sidecar writes (dirty-bit bitmap).
 * Named "version" bitmap for historical reasons; functionally a dirty-bit tracker.
 * Backed by Lucene's {@link FixedBitSet} for O(1) get/set operations.
 * Thread-safety: instances are NOT thread-safe. Use external synchronization
 * or publish via volatile reference for cross-thread visibility.
 *
 * @opensearch.experimental
 */
@ExperimentalApi
public class SidecarVersionBitmap {

    private final FixedBitSet bits;
    private final int maxDoc;

    public SidecarVersionBitmap(int maxDoc) {
        this.maxDoc = maxDoc;
        this.bits = new FixedBitSet(maxDoc);
    }

    SidecarVersionBitmap(FixedBitSet bits, int maxDoc) {
        this.bits = bits;
        this.maxDoc = maxDoc;
    }

    public void set(int docId) {
        bits.set(docId);
    }

    public boolean get(int docId) {
        return bits.get(docId);
    }

    public int cardinality() {
        return bits.cardinality();
    }

    public int maxDoc() {
        return maxDoc;
    }

    public long[] getBits() {
        long[] internal = bits.getBits();
        return Arrays.copyOf(internal, internal.length);
    }

    public int nextSetBit(int from) {
        return bits.nextSetBit(from);
    }

    /**
     * Returns a new bitmap grown to at least {@code requiredSize} capacity, copying all set bits.
     * If the current capacity is already sufficient, returns {@code this}.
     */
    public SidecarVersionBitmap growTo(int requiredSize) {
        if (requiredSize <= maxDoc) return this;
        int newSize = (int) Math.min((long) maxDoc * 2, Integer.MAX_VALUE - 1);
        newSize = Math.max(newSize, requiredSize);
        SidecarVersionBitmap newBitmap = new SidecarVersionBitmap(newSize);
        for (int doc = bits.nextSetBit(0); doc != -1 && doc < maxDoc; doc = bits.nextSetBit(doc + 1)) {
            newBitmap.set(doc);
        }
        return newBitmap;
    }

    public SidecarVersionBitmap remap(int[] oldToNew, int newMaxDoc) {
        SidecarVersionBitmap remapped = new SidecarVersionBitmap(newMaxDoc);
        for (int oldDoc = bits.nextSetBit(0); oldDoc != -1 && oldDoc < maxDoc; oldDoc = oldDoc + 1 < maxDoc
            ? bits.nextSetBit(oldDoc + 1)
            : -1) {
            int newDoc = oldToNew[oldDoc];
            if (newDoc >= 0 && newDoc < newMaxDoc) {
                remapped.set(newDoc);
            }
        }
        return remapped;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof SidecarVersionBitmap other)) return false;
        if (maxDoc != other.maxDoc) return false;
        return Arrays.equals(bits.getBits(), other.bits.getBits());
    }

    @Override
    public int hashCode() {
        return 31 * maxDoc + Arrays.hashCode(bits.getBits());
    }
}
