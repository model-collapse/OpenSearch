package org.opensearch.index.engine.sidecar;

import org.apache.lucene.util.FixedBitSet;

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
        return bits.getBits();
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
}
