/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.index.engine.sidecar;

import org.apache.lucene.index.PointValues;
import org.opensearch.test.OpenSearchTestCase;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class FilteredPointValuesTests extends OpenSearchTestCase {

    public void testDirtyDocsExcludedFromVisitDocID() throws IOException {
        // Create a bitmap with docs 2, 5 marked as dirty
        SidecarVersionBitmap bitmap = new SidecarVersionBitmap(10);
        bitmap.set(2);
        bitmap.set(5);

        // Create a simple PointValues that visits known doc IDs
        PointValues base = createMockPointValues(new int[] { 0, 1, 2, 3, 4, 5, 6, 7 });
        FilteredPointValues filtered = new FilteredPointValues(base, bitmap);

        // Collect visited doc IDs via the PointTree
        List<Integer> visited = new ArrayList<>();
        PointValues.PointTree tree = filtered.getPointTree();
        tree.visitDocIDs(new CollectingVisitor(visited));

        // Docs 2 and 5 should be excluded
        assertEquals(List.of(0, 1, 3, 4, 6, 7), visited);
    }

    public void testDirtyDocsExcludedFromVisitDocValues() throws IOException {
        SidecarVersionBitmap bitmap = new SidecarVersionBitmap(10);
        bitmap.set(1);
        bitmap.set(3);

        PointValues base = createMockPointValues(new int[] { 0, 1, 2, 3, 4 });
        FilteredPointValues filtered = new FilteredPointValues(base, bitmap);

        List<Integer> visited = new ArrayList<>();
        PointValues.PointTree tree = filtered.getPointTree();
        tree.visitDocValues(new CollectingVisitor(visited));

        assertEquals(List.of(0, 2, 4), visited);
    }

    public void testNoDirtyDocsPassesAll() throws IOException {
        SidecarVersionBitmap bitmap = new SidecarVersionBitmap(10);
        // No bits set

        PointValues base = createMockPointValues(new int[] { 0, 1, 2, 3 });
        FilteredPointValues filtered = new FilteredPointValues(base, bitmap);

        List<Integer> visited = new ArrayList<>();
        PointValues.PointTree tree = filtered.getPointTree();
        tree.visitDocIDs(new CollectingVisitor(visited));

        assertEquals(List.of(0, 1, 2, 3), visited);
    }

    public void testAllDirtyExcludesAll() throws IOException {
        SidecarVersionBitmap bitmap = new SidecarVersionBitmap(4);
        bitmap.set(0);
        bitmap.set(1);
        bitmap.set(2);
        bitmap.set(3);

        PointValues base = createMockPointValues(new int[] { 0, 1, 2, 3 });
        FilteredPointValues filtered = new FilteredPointValues(base, bitmap);

        List<Integer> visited = new ArrayList<>();
        PointValues.PointTree tree = filtered.getPointTree();
        tree.visitDocIDs(new CollectingVisitor(visited));

        assertTrue(visited.isEmpty());
    }

    public void testDocBeyondBitmapMaxDocPassesThrough() throws IOException {
        // Bitmap only covers up to maxDoc=5, but base has doc 7
        SidecarVersionBitmap bitmap = new SidecarVersionBitmap(5);
        bitmap.set(2);

        PointValues base = createMockPointValues(new int[] { 2, 3, 7 });
        FilteredPointValues filtered = new FilteredPointValues(base, bitmap);

        List<Integer> visited = new ArrayList<>();
        PointValues.PointTree tree = filtered.getPointTree();
        tree.visitDocIDs(new CollectingVisitor(visited));

        // Doc 2 is dirty and excluded; 3 passes (not dirty); 7 beyond maxDoc, passes
        assertEquals(List.of(3, 7), visited);
    }

    public void testSizeAndDocCountReduced() {
        SidecarVersionBitmap bitmap = new SidecarVersionBitmap(10);
        bitmap.set(1);
        bitmap.set(3);
        bitmap.set(5);

        PointValues base = createMockPointValues(new int[] { 0, 1, 2, 3, 4, 5, 6, 7, 8, 9 });
        FilteredPointValues filtered = new FilteredPointValues(base, bitmap);

        assertEquals(7, filtered.size()); // 10 - 3
        assertEquals(7, filtered.getDocCount()); // 10 - 3
    }

    public void testComparePassesThrough() throws IOException {
        SidecarVersionBitmap bitmap = new SidecarVersionBitmap(10);
        bitmap.set(0);

        PointValues base = createMockPointValues(new int[] { 0, 1 });
        FilteredPointValues filtered = new FilteredPointValues(base, bitmap);

        PointValues.PointTree tree = filtered.getPointTree();
        // The collecting visitor's compare always returns CELL_CROSSES_QUERY
        List<Integer> visited = new ArrayList<>();
        tree.visitDocValues(new CollectingVisitor(visited));

        assertEquals(List.of(1), visited);
    }

    private PointValues createMockPointValues(int[] docIDs) {
        return new PointValues() {
            @Override
            public PointTree getPointTree() {
                return new PointTree() {
                    @Override
                    public PointTree clone() {
                        return this;
                    }

                    @Override
                    public boolean moveToChild() {
                        return false;
                    }

                    @Override
                    public boolean moveToSibling() {
                        return false;
                    }

                    @Override
                    public boolean moveToParent() {
                        return false;
                    }

                    @Override
                    public byte[] getMinPackedValue() {
                        return new byte[8];
                    }

                    @Override
                    public byte[] getMaxPackedValue() {
                        return new byte[8];
                    }

                    @Override
                    public long size() {
                        return docIDs.length;
                    }

                    @Override
                    public void visitDocIDs(IntersectVisitor visitor) throws IOException {
                        for (int docID : docIDs) {
                            visitor.visit(docID);
                        }
                    }

                    @Override
                    public void visitDocValues(IntersectVisitor visitor) throws IOException {
                        byte[] packedValue = new byte[8];
                        for (int docID : docIDs) {
                            visitor.visit(docID, packedValue);
                        }
                    }
                };
            }

            @Override
            public byte[] getMinPackedValue() {
                return new byte[8];
            }

            @Override
            public byte[] getMaxPackedValue() {
                return new byte[8];
            }

            @Override
            public int getNumDimensions() {
                return 1;
            }

            @Override
            public int getNumIndexDimensions() {
                return 1;
            }

            @Override
            public int getBytesPerDimension() {
                return 8;
            }

            @Override
            public long size() {
                return docIDs.length;
            }

            @Override
            public int getDocCount() {
                return docIDs.length;
            }
        };
    }

    private static class CollectingVisitor implements PointValues.IntersectVisitor {
        private final List<Integer> collected;

        CollectingVisitor(List<Integer> collected) {
            this.collected = collected;
        }

        @Override
        public void visit(int docID) {
            collected.add(docID);
        }

        @Override
        public void visit(int docID, byte[] packedValue) {
            collected.add(docID);
        }

        @Override
        public PointValues.Relation compare(byte[] minPackedValue, byte[] maxPackedValue) {
            return PointValues.Relation.CELL_CROSSES_QUERY;
        }
    }
}
