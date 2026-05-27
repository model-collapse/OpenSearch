/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.index.engine.sidecar;

import org.opensearch.action.DocWriteResponse;
import org.opensearch.action.admin.indices.mapping.get.GetMappingsResponse;
import org.opensearch.action.get.GetResponse;
import org.opensearch.action.search.SearchResponse;
import org.opensearch.action.update.UpdateResponse;
import org.opensearch.common.settings.Settings;
import org.opensearch.index.query.QueryBuilders;
import org.opensearch.search.aggregations.AggregationBuilders;
import org.opensearch.search.aggregations.bucket.terms.Terms;
import org.opensearch.test.OpenSearchIntegTestCase;

import java.util.Map;

import static org.opensearch.test.hamcrest.OpenSearchAssertions.assertAcked;

@OpenSearchIntegTestCase.ClusterScope(scope = OpenSearchIntegTestCase.Scope.TEST, numDataNodes = 1)
public class SidecarFeaturesIT extends OpenSearchIntegTestCase {

    @Override
    protected int numberOfReplicas() {
        return 0;
    }

    /**
     * Gap 4: Test that standard _update on an index with updatable fields
     * still works correctly (even though auto-detect isn't fully wired yet,
     * the update should not break).
     */
    public void testStandardUpdateWorksWithUpdatableFields() throws Exception {
        assertAcked(prepareCreate("test-index").setMapping(
            "title", "type=text",
            "category", "type=keyword,updatable=true"
        ));
        ensureGreen("test-index");

        client().prepareIndex("test-index").setId("doc1")
            .setSource("title", "hello", "category", "science")
            .get();
        client().admin().indices().prepareRefresh("test-index").get();

        // Standard _update on a non-updatable field should still work via full reindex
        UpdateResponse updateResponse = client().prepareUpdate("test-index", "doc1")
            .setDoc("title", "updated title")
            .get();
        assertEquals(DocWriteResponse.Result.UPDATED, updateResponse.getResult());

        // Verify the update persisted
        client().admin().indices().prepareRefresh("test-index").get();
        GetResponse getResponse = client().prepareGet("test-index", "doc1").get();
        assertEquals("updated title", getResponse.getSourceAsMap().get("title"));
        // category should still be there
        assertEquals("science", getResponse.getSourceAsMap().get("category"));
    }

    /**
     * Gap 4: Test _update touching only updatable fields still works
     * (currently falls through to full reindex — auto-detect not wired in engine yet)
     */
    public void testUpdateOnlyUpdatableFieldStillWorks() throws Exception {
        assertAcked(prepareCreate("test-index").setMapping(
            "title", "type=text",
            "category", "type=keyword,updatable=true"
        ));
        ensureGreen("test-index");

        client().prepareIndex("test-index").setId("doc1")
            .setSource("title", "hello", "category", "old")
            .get();
        client().admin().indices().prepareRefresh("test-index").get();

        // Update only the updatable field — should still succeed (via standard path for now)
        UpdateResponse updateResponse = client().prepareUpdate("test-index", "doc1")
            .setDoc("category", "new_category")
            .get();
        assertEquals(DocWriteResponse.Result.UPDATED, updateResponse.getResult());

        client().admin().indices().prepareRefresh("test-index").get();
        GetResponse getResponse = client().prepareGet("test-index", "doc1").get();
        assertEquals("new_category", getResponse.getSourceAsMap().get("category"));
        assertEquals("hello", getResponse.getSourceAsMap().get("title"));
    }

    /**
     * Gap 5: Snapshot/restore — verifies that an index with updatable fields
     * can be snapshotted and restored. Note: This does NOT test DFA engine
     * (CatalogSnapshotIndexCommit) — that requires the composite-engine plugin.
     * This test verifies no regression on standard engine indices with updatable mapping.
     */
    @SuppressWarnings("unchecked")
    public void testSnapshotRestoreWithUpdatableFields() throws Exception {
        // Create index with updatable field
        assertAcked(prepareCreate("test-index").setMapping(
            "title", "type=text",
            "category", "type=keyword,updatable=true"
        ));
        ensureGreen("test-index");

        client().prepareIndex("test-index").setId("doc1")
            .setSource("title", "hello", "category", "science")
            .get();
        client().admin().indices().prepareRefresh("test-index").get();

        // Create snapshot repo
        assertAcked(client().admin().cluster().preparePutRepository("test-repo")
            .setType("fs")
            .setSettings(Settings.builder().put("location", randomRepoPath())));

        // Take snapshot
        client().admin().cluster().prepareCreateSnapshot("test-repo", "snap1")
            .setWaitForCompletion(true)
            .setIndices("test-index")
            .get();

        // Delete the index
        assertAcked(client().admin().indices().prepareDelete("test-index"));

        // Restore
        client().admin().cluster().prepareRestoreSnapshot("test-repo", "snap1")
            .setWaitForCompletion(true)
            .get();

        // Verify data + mapping
        ensureGreen("test-index");
        client().admin().indices().prepareRefresh("test-index").get();
        GetResponse getResponse = client().prepareGet("test-index", "doc1").get();
        assertTrue(getResponse.isExists());
        assertEquals("science", getResponse.getSourceAsMap().get("category"));

        // Verify updatable mapping survived
        GetMappingsResponse mappings = client().admin().indices().prepareGetMappings("test-index").get();
        Map<String, Object> props = (Map<String, Object>) mappings.getMappings()
            .get("test-index").getSourceAsMap().get("properties");
        Map<String, Object> catField = (Map<String, Object>) props.get("category");
        assertEquals(true, catField.get("updatable"));
    }

    /**
     * Gap 6: Search with updatable fields — verifies that term queries, range queries,
     * and aggregations still work on an index with updatable fields.
     * Note: Full KNN vector sidecar search (with SidecarKnnFilter) requires the
     * k-NN plugin which is not available in server module tests.
     * This test verifies no regression.
     */
    public void testSearchWorksWithMultipleUpdatableFields() throws Exception {
        assertAcked(prepareCreate("test-index").setMapping(
            "title", "type=text",
            "category", "type=keyword,updatable=true",
            "score", "type=long,updatable=true"
        ));
        ensureGreen("test-index");

        for (int i = 0; i < 10; i++) {
            client().prepareIndex("test-index").setId("doc" + i)
                .setSource("title", "document " + i, "category", "cat" + (i % 3), "score", i * 10)
                .get();
        }
        client().admin().indices().prepareRefresh("test-index").get();

        // Term query on updatable keyword
        long hits = client().prepareSearch("test-index")
            .setQuery(QueryBuilders.termQuery("category", "cat1"))
            .get().getHits().getTotalHits().value();
        assertTrue("Expected at least one hit for term query on updatable keyword field", hits > 0);

        // Range query on updatable long
        hits = client().prepareSearch("test-index")
            .setQuery(QueryBuilders.rangeQuery("score").gte(50))
            .get().getHits().getTotalHits().value();
        assertTrue("Expected at least one hit for range query on updatable long field", hits > 0);

        // Aggregation on updatable field
        SearchResponse aggResponse = client().prepareSearch("test-index")
            .addAggregation(AggregationBuilders.terms("cats").field("category"))
            .setSize(0)
            .get();
        Terms termsAgg = aggResponse.getAggregations().get("cats");
        assertNotNull("Terms aggregation on updatable keyword field should not be null", termsAgg);
        assertEquals("Expected 3 category buckets", 3, termsAgg.getBuckets().size());
    }
}
