package org.opensearch.index.engine.sidecar;

import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.SegmentReader;
import org.apache.lucene.search.ConstantScoreScorer;
import org.apache.lucene.search.ConstantScoreWeight;
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.QueryVisitor;
import org.apache.lucene.search.ScoreMode;
import org.apache.lucene.search.Scorer;
import org.apache.lucene.search.ScorerSupplier;
import org.apache.lucene.search.Weight;

import java.io.IOException;
import java.util.Map;
import java.util.Objects;

/**
 * A filter query that EXCLUDES documents present in per-segment SidecarVersionBitmaps.
 * Used to prevent stale vectors in the base HNSW graph from being returned
 * when updated versions exist in a sidecar.
 *
 * The filter resolves the correct bitmap per leaf context using the segment name,
 * so document IDs are always segment-local (no docBase offset needed).
 */
public class SidecarKnnFilter extends Query {

    private final Map<String, SidecarVersionBitmap> segmentBitmaps;
    private final int maxDoc;

    /**
     * Creates a filter with per-segment bitmaps.
     *
     * @param segmentBitmaps map from segment name to bitmap of dirty doc IDs (segment-local)
     * @param maxDoc         the total maximum document count across all segments
     */
    public SidecarKnnFilter(Map<String, SidecarVersionBitmap> segmentBitmaps, int maxDoc) {
        this.segmentBitmaps = segmentBitmaps;
        this.maxDoc = maxDoc;
    }

    /**
     * Backward-compatible constructor for a single bitmap (used in per-segment exclusion filter).
     * The bitmap is associated with a synthetic segment key.
     */
    public SidecarKnnFilter(SidecarVersionBitmap bitmap, int maxDoc) {
        this(Map.of("__single__", bitmap), maxDoc);
    }

    @Override
    public Weight createWeight(IndexSearcher searcher, ScoreMode scoreMode, float boost) {
        return new ConstantScoreWeight(this, boost) {
            @Override
            public ScorerSupplier scorerSupplier(LeafReaderContext context) throws IOException {
                int leafMaxDoc = context.reader().maxDoc();

                // Resolve the bitmap for this leaf's segment
                SidecarVersionBitmap bitmap = resolveLeafBitmap(context);

                DocIdSetIterator iterator;
                if (bitmap == null || bitmap.cardinality() == 0) {
                    // No exclusion for this segment — include all docs
                    iterator = DocIdSetIterator.all(leafMaxDoc);
                } else {
                    iterator = new DocIdSetIterator() {
                        int doc = -1;

                        @Override
                        public int docID() {
                            return doc;
                        }

                        @Override
                        public int nextDoc() {
                            doc++;
                            while (doc < leafMaxDoc) {
                                // Use segment-local doc ID directly (no docBase offset)
                                if (doc >= bitmap.maxDoc() || !bitmap.get(doc)) {
                                    return doc;
                                }
                                doc++;
                            }
                            return doc = NO_MORE_DOCS;
                        }

                        @Override
                        public int advance(int target) {
                            doc = target - 1;
                            return nextDoc();
                        }

                        @Override
                        public long cost() {
                            return leafMaxDoc - bitmap.cardinality();
                        }
                    };
                }
                Scorer scorer = new ConstantScoreScorer(score(), scoreMode, iterator);
                return new DefaultScorerSupplier(scorer);
            }

            @Override
            public boolean isCacheable(LeafReaderContext ctx) {
                return false; // bitmap may change between refreshes
            }
        };
    }

    /**
     * Resolves the appropriate bitmap for the given leaf reader context.
     * First tries to match by segment name; falls back to "__single__" key
     * for backward-compatible single-bitmap usage.
     */
    private SidecarVersionBitmap resolveLeafBitmap(LeafReaderContext context) {
        // Try segment name lookup
        String segName = getSegmentName(context);
        if (segName != null) {
            SidecarVersionBitmap bitmap = segmentBitmaps.get(segName);
            if (bitmap != null) {
                return bitmap;
            }
        }

        // Fallback for single-bitmap constructor
        if (segmentBitmaps.size() == 1 && segmentBitmaps.containsKey("__single__")) {
            return segmentBitmaps.get("__single__");
        }

        return null;
    }

    /**
     * Extracts the segment name from a leaf reader context.
     */
    private static String getSegmentName(LeafReaderContext context) {
        if (context.reader() instanceof SegmentReader sr) {
            return sr.getSegmentName();
        }
        return "leaf_" + context.ord;
    }

    @Override
    public String toString(String field) {
        int totalCardinality = segmentBitmaps.values().stream().mapToInt(SidecarVersionBitmap::cardinality).sum();
        return "SidecarKnnFilter{cardinality=" + totalCardinality + "}";
    }

    @Override
    public void visit(QueryVisitor visitor) {
        visitor.visitLeaf(this);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof SidecarKnnFilter other)) return false;
        return Objects.equals(segmentBitmaps, other.segmentBitmaps) && maxDoc == other.maxDoc;
    }

    @Override
    public int hashCode() {
        return Objects.hash(segmentBitmaps, maxDoc);
    }
}
