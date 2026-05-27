/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.action.update.fields;

import org.opensearch.common.settings.Settings;
import org.opensearch.test.OpenSearchIntegTestCase;

import java.util.ArrayList;
import java.util.List;

import static org.opensearch.test.hamcrest.OpenSearchAssertions.assertAcked;

@OpenSearchIntegTestCase.ClusterScope(scope = OpenSearchIntegTestCase.Scope.TEST, numDataNodes = 1)
public class UpdateFieldsIT extends OpenSearchIntegTestCase {

    private void createTestIndex(String indexName) {
        assertAcked(prepareCreate(indexName).setMapping(
            "title", "type=text",
            "embedding", "type=keyword,updatable=true"
        ).setSettings(Settings.builder()
            .put("index.number_of_shards", 1)
            .put("index.number_of_replicas", 0)
        ));
        ensureGreen(indexName);
    }

    public void testUpdateFieldsReturnsValidResponse() throws Exception {
        createTestIndex("test-index");

        // Index a document
        client().prepareIndex("test-index").setId("doc1").setSource("title", "hello", "embedding", "old").get();
        client().admin().indices().prepareRefresh("test-index").get();

        // Call _update_fields via transport action
        UpdateFieldsRequest request = new UpdateFieldsRequest(
            "test-index",
            "embedding",
            List.of(new UpdateFieldsRequest.FieldUpdate("doc1", new float[]{0.1f, 0.2f, 0.3f}))
        );

        UpdateFieldsResponse response = client().execute(UpdateFieldsAction.INSTANCE, request).actionGet();

        assertNotNull(response);
        assertTrue("took should be non-negative", response.getTookInMillis() >= 0);
        assertEquals(1, response.getUpdated());
        assertEquals(0, response.getFailed());
        assertTrue("sidecars_created should be positive", response.getSidecarsCreated() > 0);
    }

    public void testUpdateFieldsNonExistentIndexReturns404() throws Exception {
        UpdateFieldsRequest request = new UpdateFieldsRequest(
            "nonexistent-index",
            "embedding",
            List.of(new UpdateFieldsRequest.FieldUpdate("doc1", new float[]{0.1f}))
        );

        Exception e = expectThrows(Exception.class,
            () -> client().execute(UpdateFieldsAction.INSTANCE, request).actionGet());
        // Should be IndexNotFoundException or similar
        assertTrue(
            "Expected index not found error but got: " + e.getMessage(),
            e.getMessage().contains("no such index") || e.getMessage().contains("IndexNotFoundException")
                || e.getMessage().contains("nonexistent")
        );
    }

    public void testUpdateFieldsMultipleDocuments() throws Exception {
        createTestIndex("test-index");

        // Index multiple documents
        for (int i = 0; i < 10; i++) {
            client().prepareIndex("test-index").setId("doc" + i)
                .setSource("title", "doc " + i, "embedding", "old")
                .get();
        }
        client().admin().indices().prepareRefresh("test-index").get();

        // Update all 10 with non-zero vectors to avoid COSINE similarity issues
        List<UpdateFieldsRequest.FieldUpdate> updates = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            updates.add(new UpdateFieldsRequest.FieldUpdate("doc" + i, new float[]{0.1f + 0.1f * i, 0.2f + 0.1f * i}));
        }

        UpdateFieldsRequest request = new UpdateFieldsRequest("test-index", "embedding", updates);

        UpdateFieldsResponse response = client().execute(UpdateFieldsAction.INSTANCE, request).actionGet();

        assertEquals(10, response.getUpdated());
        assertEquals(0, response.getFailed());
    }

    public void testUpdateFieldsEmptyUpdatesRejected() throws Exception {
        assertAcked(prepareCreate("test-index").setMapping("field1", "type=keyword,updatable=true")
            .setSettings(Settings.builder()
                .put("index.number_of_shards", 1)
                .put("index.number_of_replicas", 0)
            ));
        ensureGreen("test-index");

        UpdateFieldsRequest request = new UpdateFieldsRequest("test-index", "field1", List.of());

        Exception e = expectThrows(Exception.class,
            () -> client().execute(UpdateFieldsAction.INSTANCE, request).actionGet());
        assertTrue(
            "Expected validation error about empty updates but got: " + e.getMessage(),
            e.getMessage().contains("updates") || e.getMessage().contains("empty")
                || e.getMessage().contains("validation")
        );
    }
}
