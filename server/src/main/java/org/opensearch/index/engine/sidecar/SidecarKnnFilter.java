package org.opensearch.index.engine.sidecar;

import org.apache.lucene.index.LeafReaderContext;
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
import java.util.Objects;

/**
 * A filter query that EXCLUDES documents present in a SidecarVersionBitmap.
 * Used to prevent stale vectors in the base HNSW graph from being returned
 * when updated versions exist in a sidecar.
 */
public class SidecarKnnFilter extends Query {

    private final SidecarVersionBitmap bitmap;
    private final int maxDoc;

    public SidecarKnnFilter(SidecarVersionBitmap bitmap, int maxDoc) {
        this.bitmap = bitmap;
        this.maxDoc = maxDoc;
    }

    @Override
    public Weight createWeight(IndexSearcher searcher, ScoreMode scoreMode, float boost) {
        return new ConstantScoreWeight(this, boost) {
            @Override
            public ScorerSupplier scorerSupplier(LeafReaderContext context) throws IOException {
                int leafMaxDoc = context.reader().maxDoc();
                DocIdSetIterator iterator = new DocIdSetIterator() {
                    int doc = -1;

                    @Override
                    public int docID() {
                        return doc;
                    }

                    @Override
                    public int nextDoc() {
                        doc++;
                        while (doc < leafMaxDoc) {
                            // Include doc only if NOT in sidecar bitmap
                            if (!bitmap.get(doc + context.docBase)) {
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
                Scorer scorer = new ConstantScoreScorer(score(), scoreMode, iterator);
                return new DefaultScorerSupplier(scorer);
            }

            @Override
            public boolean isCacheable(LeafReaderContext ctx) {
                return false; // bitmap may change between refreshes
            }
        };
    }

    @Override
    public String toString(String field) {
        return "SidecarKnnFilter{cardinality=" + bitmap.cardinality() + "}";
    }

    @Override
    public void visit(QueryVisitor visitor) {
        visitor.visitLeaf(this);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof SidecarKnnFilter other)) return false;
        return Objects.equals(bitmap, other.bitmap) && maxDoc == other.maxDoc;
    }

    @Override
    public int hashCode() {
        return Objects.hash(bitmap, maxDoc);
    }
}
