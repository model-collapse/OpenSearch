/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.index.engine.sidecar;

import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.LeafReader;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.PostingsEnum;
import org.apache.lucene.index.SegmentReader;
import org.apache.lucene.index.Terms;
import org.apache.lucene.index.TermsEnum;
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.util.BytesRef;
import org.opensearch.index.mapper.IdFieldMapper;
import org.opensearch.index.mapper.Uid;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Resolves document _id strings to internal Lucene doc ordinals by looking up
 * the _id term in the terms index.
 *
 * @opensearch.internal
 */
public class DocIdResolver {

    /**
     * Holds the resolution result: leaf reader ordinal, segment-local doc ID, and segment name.
     */
    public record ResolvedDoc(int leafOrd, int docId, String segmentName) {}

    /**
     * Resolves a document _id to its (leafReaderContext ordinal, segment-local docId, segmentName).
     * Returns null if the document is not found or is deleted.
     */
    public static ResolvedDoc resolve(DirectoryReader reader, String id) throws IOException {
        BytesRef idBytes = Uid.encodeId(id);

        for (LeafReaderContext ctx : reader.leaves()) {
            LeafReader leaf = ctx.reader();
            Terms terms = leaf.terms(IdFieldMapper.NAME);
            if (terms == null) continue;

            TermsEnum termsEnum = terms.iterator();
            if (termsEnum.seekExact(idBytes)) {
                PostingsEnum postings = termsEnum.postings(null, PostingsEnum.NONE);
                int docId = postings.nextDoc();
                if (docId != DocIdSetIterator.NO_MORE_DOCS) {
                    // Check if doc is live (not deleted)
                    if (leaf.getLiveDocs() == null || leaf.getLiveDocs().get(docId)) {
                        String segmentName;
                        if (leaf instanceof SegmentReader sr) {
                            segmentName = sr.getSegmentName();
                        } else {
                            segmentName = "leaf_" + ctx.ord; // fallback for wrapped readers
                        }
                        return new ResolvedDoc(ctx.ord, docId, segmentName);
                    }
                }
            }
        }
        return null; // doc not found
    }

    /**
     * Batch resolve multiple IDs. Returns list of resolved docs (skips not-found).
     */
    public static List<ResolvedDoc> resolveBatch(DirectoryReader reader, List<String> ids) throws IOException {
        List<ResolvedDoc> results = new ArrayList<>(ids.size());
        for (String id : ids) {
            ResolvedDoc resolved = resolve(reader, id);
            if (resolved != null) {
                results.add(resolved);
            }
        }
        return results;
    }
}
