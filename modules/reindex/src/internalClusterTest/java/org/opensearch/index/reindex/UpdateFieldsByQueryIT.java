/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.index.reindex;

import org.opensearch.action.search.SearchRequest;
import org.opensearch.common.settings.Settings;
import org.opensearch.index.query.QueryBuilders;
import org.opensearch.plugins.Plugin;
import org.opensearch.test.OpenSearchIntegTestCase;

import java.util.Arrays;
import java.util.Collection;

import static org.opensearch.test.hamcrest.OpenSearchAssertions.assertAcked;

@OpenSearchIntegTestCase.ClusterScope(scope = OpenSearchIntegTestCase.Scope.SUITE, numDataNodes = 1)
public class UpdateFieldsByQueryIT extends OpenSearchIntegTestCase {

    @Override
    protected Collection<Class<? extends Plugin>> nodePlugins() {
        return Arrays.asList(ReindexModulePlugin.class);
    }

    @Override
    protected int numberOfReplicas() {
        return 0;
    }

    public void testUpdateFieldsByQueryBasic() throws Exception {
        String indexName = "test-basic";
        assertAcked(
            prepareCreate(indexName).setMapping("title", "type=text", "embedding", "type=keyword,updatable=true")
        );

        for (int i = 0; i < 5; i++) {
            client().prepareIndex(indexName)
                .setId("doc" + i)
                .setSource("title", "document " + i, "embedding", "old_value")
                .get();
        }
        client().admin().indices().prepareRefresh(indexName).get();

        UpdateFieldsByQueryRequest request = new UpdateFieldsByQueryRequest(new SearchRequest(indexName));
        request.setField("embedding");
        request.getSearchRequest().source().query(QueryBuilders.matchAllQuery());

        BulkByScrollResponse response = client().execute(UpdateFieldsByQueryAction.INSTANCE, request).actionGet();

        assertNotNull(response);
        assertTrue("Expected no bulk failures but got: " + response.getBulkFailures(), response.getBulkFailures().isEmpty());
        assertEquals("Expected all 5 docs to be processed", 5L, response.getTotal());
    }

    public void testUpdateFieldsByQueryWithQuery() throws Exception {
        String indexName = "test-query";
        assertAcked(
            prepareCreate(indexName).setMapping("title", "type=text", "status", "type=keyword,updatable=true")
                .setSettings(Settings.builder().put("index.number_of_shards", 1).put("index.number_of_replicas", 0))
        );

        for (int i = 0; i < 10; i++) {
            client().prepareIndex(indexName)
                .setId("doc" + i)
                .setSource("title", "document " + i, "status", i < 5 ? "active" : "inactive")
                .get();
        }
        client().admin().indices().prepareRefresh(indexName).get();

        // Only target active docs — query must be set after construction because
        // the AbstractBulkByScrollRequest constructor resets the SearchSourceBuilder.
        UpdateFieldsByQueryRequest request = new UpdateFieldsByQueryRequest(new SearchRequest(indexName));
        request.setField("status");
        request.getSearchRequest().source().query(QueryBuilders.termQuery("status", "active"));

        BulkByScrollResponse response = client().execute(UpdateFieldsByQueryAction.INSTANCE, request).actionGet();

        assertNotNull(response);
        assertEquals("Expected only active docs to be scrolled", 5L, response.getTotal());
    }

    public void testUpdateFieldsByQueryMultipleShards() throws Exception {
        String indexName = "test-multishard";
        assertAcked(
            prepareCreate(indexName).setMapping("title", "type=text", "score", "type=long,updatable=true")
                .setSettings(
                    Settings.builder().put("index.number_of_shards", 2).put("index.number_of_replicas", 0)
                )
        );

        for (int i = 0; i < 20; i++) {
            client().prepareIndex(indexName)
                .setId("doc" + i)
                .setSource("title", "doc " + i, "score", i)
                .get();
        }
        client().admin().indices().prepareRefresh(indexName).get();

        // Single-slice execution across multiple shards
        UpdateFieldsByQueryRequest request = new UpdateFieldsByQueryRequest(new SearchRequest(indexName));
        request.setField("score");
        request.getSearchRequest().source().query(QueryBuilders.matchAllQuery());

        BulkByScrollResponse response = client().execute(UpdateFieldsByQueryAction.INSTANCE, request).actionGet();

        assertNotNull(response);
        assertEquals("Expected all 20 docs to be processed", 20L, response.getTotal());
    }

    public void testUpdateFieldsByQueryNonExistentIndex() throws Exception {
        UpdateFieldsByQueryRequest request = new UpdateFieldsByQueryRequest(new SearchRequest("nonexistent"));
        request.setField("embedding");
        request.getSearchRequest().source().query(QueryBuilders.matchAllQuery());

        Exception e = expectThrows(
            Exception.class,
            () -> client().execute(UpdateFieldsByQueryAction.INSTANCE, request).actionGet()
        );
        assertTrue(
            "Expected index_not_found error but got: " + e.getMessage(),
            e.getMessage().contains("no such index") || e.getMessage().contains("index_not_found")
        );
    }
}
