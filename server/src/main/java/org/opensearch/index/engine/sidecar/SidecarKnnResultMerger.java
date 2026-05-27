package org.opensearch.index.engine.sidecar;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Merges KNN search results from base segment and sidecar segment,
 * returning the top-K by score.
 */
public class SidecarKnnResultMerger {

    public record ScoredDoc(int docId, float score, boolean fromSidecar) {}

    /**
     * Merge results from base and sidecar, return top-k by descending score.
     */
    public static List<ScoredDoc> merge(List<ScoredDoc> baseResults, List<ScoredDoc> sidecarResults, int k) {
        List<ScoredDoc> combined = new ArrayList<>(baseResults.size() + sidecarResults.size());
        combined.addAll(baseResults);
        combined.addAll(sidecarResults);

        combined.sort(Comparator.comparingDouble(ScoredDoc::score).reversed());

        return combined.subList(0, Math.min(k, combined.size()));
    }
}
