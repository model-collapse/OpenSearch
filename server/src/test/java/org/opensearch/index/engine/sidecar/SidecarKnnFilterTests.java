package org.opensearch.index.engine.sidecar;

import org.apache.lucene.document.Document;
import org.apache.lucene.document.KnnFloatVectorField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.VectorSimilarityFunction;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.KnnFloatVectorQuery;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.opensearch.test.OpenSearchTestCase;

import java.util.List;

public class SidecarKnnFilterTests extends OpenSearchTestCase {

    public void testFilterExcludesDirtyDocs() throws Exception {
        ByteBuffersDirectory dir = new ByteBuffersDirectory();
        IndexWriter writer = new IndexWriter(dir, new IndexWriterConfig());

        // Add 5 docs with vectors
        for (int i = 0; i < 5; i++) {
            Document doc = new Document();
            float[] vec = new float[] { (float) i, (float) i, (float) i, (float) i };
            doc.add(new KnnFloatVectorField("vec", vec, VectorSimilarityFunction.DOT_PRODUCT));
            writer.addDocument(doc);
        }
        writer.commit();

        // Mark docs 1 and 3 as dirty (in sidecar)
        SidecarVersionBitmap bitmap = new SidecarVersionBitmap(5);
        bitmap.set(1);
        bitmap.set(3);

        DirectoryReader reader = DirectoryReader.open(dir);
        IndexSearcher searcher = new IndexSearcher(reader);

        // KNN search with filter excluding dirty docs
        float[] query = new float[] { 4.0f, 4.0f, 4.0f, 4.0f };
        SidecarKnnFilter filter = new SidecarKnnFilter(bitmap, 5);
        KnnFloatVectorQuery knnQuery = new KnnFloatVectorQuery("vec", query, 5, filter);
        TopDocs results = searcher.search(knnQuery, 5);

        // Should only get docs 0, 2, 4 (not 1, 3)
        assertEquals(3, results.scoreDocs.length);
        for (var scoreDoc : results.scoreDocs) {
            assertNotEquals(1, scoreDoc.doc);
            assertNotEquals(3, scoreDoc.doc);
        }

        reader.close();
        writer.close();
    }

    public void testMergerReturnsTopK() {
        List<SidecarKnnResultMerger.ScoredDoc> base = List.of(
            new SidecarKnnResultMerger.ScoredDoc(0, 0.9f, false),
            new SidecarKnnResultMerger.ScoredDoc(2, 0.7f, false),
            new SidecarKnnResultMerger.ScoredDoc(4, 0.5f, false)
        );
        List<SidecarKnnResultMerger.ScoredDoc> sidecar = List.of(
            new SidecarKnnResultMerger.ScoredDoc(1, 0.95f, true),
            new SidecarKnnResultMerger.ScoredDoc(3, 0.6f, true)
        );

        List<SidecarKnnResultMerger.ScoredDoc> merged = SidecarKnnResultMerger.merge(base, sidecar, 3);
        assertEquals(3, merged.size());
        assertEquals(1, merged.get(0).docId()); // 0.95 from sidecar
        assertEquals(0, merged.get(1).docId()); // 0.9 from base
        assertEquals(2, merged.get(2).docId()); // 0.7 from base
    }

    public void testMergerHandlesEmptySidecar() {
        List<SidecarKnnResultMerger.ScoredDoc> base = List.of(
            new SidecarKnnResultMerger.ScoredDoc(0, 0.9f, false),
            new SidecarKnnResultMerger.ScoredDoc(1, 0.8f, false)
        );
        List<SidecarKnnResultMerger.ScoredDoc> sidecar = List.of();

        List<SidecarKnnResultMerger.ScoredDoc> merged = SidecarKnnResultMerger.merge(base, sidecar, 5);
        assertEquals(2, merged.size());
    }

    public void testFilterToString() {
        SidecarVersionBitmap bitmap = new SidecarVersionBitmap(100);
        bitmap.set(5);
        bitmap.set(10);
        SidecarKnnFilter filter = new SidecarKnnFilter(bitmap, 100);
        assertTrue(filter.toString("").contains("cardinality=2"));
    }
}
