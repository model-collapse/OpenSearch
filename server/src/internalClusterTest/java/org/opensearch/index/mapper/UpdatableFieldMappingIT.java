/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.index.mapper;

import org.opensearch.action.admin.indices.mapping.get.GetMappingsResponse;
import org.opensearch.core.xcontent.MediaTypeRegistry;
import org.opensearch.index.query.QueryBuilders;
import org.opensearch.test.OpenSearchIntegTestCase;

import java.util.Map;

import static org.opensearch.test.hamcrest.OpenSearchAssertions.assertAcked;

/**
 * Integration tests for the {@code updatable} mapping parameter on keyword fields.
 * Validates that the parameter persists, can be added but not removed, and does not
 * affect normal search behavior.
 */
public class UpdatableFieldMappingIT extends OpenSearchIntegTestCase {

    @SuppressWarnings("unchecked")
    public void testCreateIndexWithUpdatableField() throws Exception {
        assertAcked(prepareCreate("test-index").setMapping(
            "field1", "type=keyword,updatable=true",
            "field2", "type=keyword"
        ));

        GetMappingsResponse response = client().admin().indices().prepareGetMappings("test-index").get();
        Map<String, Object> mappings = response.getMappings().get("test-index").getSourceAsMap();
        Map<String, Object> properties = (Map<String, Object>) mappings.get("properties");

        Map<String, Object> field1 = (Map<String, Object>) properties.get("field1");
        assertEquals(true, field1.get("updatable"));

        // field2 should not have updatable in its mapping (default false not serialized)
        Map<String, Object> field2 = (Map<String, Object>) properties.get("field2");
        assertNull(field2.get("updatable"));
    }

    @SuppressWarnings("unchecked")
    public void testAddUpdatableToExistingField() throws Exception {
        assertAcked(prepareCreate("test-index").setMapping("field1", "type=keyword"));

        // Add updatable to existing field via mapping update
        assertAcked(client().admin().indices().preparePutMapping("test-index")
            .setSource("{ \"properties\": { \"field1\": { \"type\": \"keyword\", \"updatable\": true } } }",
                MediaTypeRegistry.JSON));

        GetMappingsResponse response = client().admin().indices().prepareGetMappings("test-index").get();
        Map<String, Object> mappings = response.getMappings().get("test-index").getSourceAsMap();
        Map<String, Object> properties = (Map<String, Object>) mappings.get("properties");
        Map<String, Object> field1 = (Map<String, Object>) properties.get("field1");
        assertEquals(true, field1.get("updatable"));
    }

    public void testCannotDisableUpdatable() throws Exception {
        assertAcked(prepareCreate("test-index").setMapping("field1", "type=keyword,updatable=true"));

        // Try to disable updatable — should fail with a conflict
        Exception e = expectThrows(Exception.class, () ->
            client().admin().indices().preparePutMapping("test-index")
                .setSource("{ \"properties\": { \"field1\": { \"type\": \"keyword\", \"updatable\": false } } }",
                    MediaTypeRegistry.JSON)
                .get()
        );
        assertTrue("Expected error about updatable parameter, got: " + e.getMessage(),
            e.getMessage().contains("updatable"));
    }

    @SuppressWarnings("unchecked")
    public void testUpdatableFieldSurvivesRefresh() throws Exception {
        assertAcked(prepareCreate("test-index").setMapping("field1", "type=keyword,updatable=true"));

        // Index a document to ensure the index is active
        client().prepareIndex("test-index").setId("1").setSource("field1", "value1").get();
        client().admin().indices().prepareRefresh("test-index").get();

        // Verify after refresh that updatable is still present
        GetMappingsResponse response = client().admin().indices().prepareGetMappings("test-index").get();
        Map<String, Object> mappings = response.getMappings().get("test-index").getSourceAsMap();
        Map<String, Object> properties = (Map<String, Object>) mappings.get("properties");
        Map<String, Object> field1 = (Map<String, Object>) properties.get("field1");
        assertEquals(true, field1.get("updatable"));
    }

    public void testUpdatableFieldDoesNotAffectSearch() throws Exception {
        assertAcked(prepareCreate("test-index").setMapping("field1", "type=keyword,updatable=true"));

        // Index and search — updatable should not affect normal behavior
        client().prepareIndex("test-index").setId("1").setSource("field1", "hello").get();
        client().admin().indices().prepareRefresh("test-index").get();

        long hits = client().prepareSearch("test-index")
            .setQuery(QueryBuilders.termQuery("field1", "hello"))
            .get().getHits().getTotalHits().value();
        assertEquals(1, hits);
    }
}
