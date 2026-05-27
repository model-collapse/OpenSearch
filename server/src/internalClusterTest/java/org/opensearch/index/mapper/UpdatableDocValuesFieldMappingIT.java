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
 * Integration tests for the {@code updatable} mapping parameter on doc-values-capable fields
 * (long, date, boolean, ip). Validates that the parameter persists through create/update mapping
 * and does not affect normal search behavior.
 */
@OpenSearchIntegTestCase.ClusterScope(scope = OpenSearchIntegTestCase.Scope.TEST, numDataNodes = 1)
public class UpdatableDocValuesFieldMappingIT extends OpenSearchIntegTestCase {

    @Override
    protected int numberOfReplicas() {
        return 0;
    }

    @SuppressWarnings("unchecked")
    public void testUpdatableOnLongField() throws Exception {
        assertAcked(prepareCreate("test-index").setMapping("score", "type=long,updatable=true"));
        GetMappingsResponse response = client().admin().indices().prepareGetMappings("test-index").get();
        Map<String, Object> mappings = response.getMappings().get("test-index").getSourceAsMap();
        Map<String, Object> properties = (Map<String, Object>) mappings.get("properties");
        Map<String, Object> field = (Map<String, Object>) properties.get("score");
        assertEquals(true, field.get("updatable"));
    }

    @SuppressWarnings("unchecked")
    public void testUpdatableOnDateField() throws Exception {
        assertAcked(prepareCreate("test-index").setMapping("timestamp", "type=date,updatable=true"));
        GetMappingsResponse response = client().admin().indices().prepareGetMappings("test-index").get();
        Map<String, Object> mappings = response.getMappings().get("test-index").getSourceAsMap();
        Map<String, Object> properties = (Map<String, Object>) mappings.get("properties");
        Map<String, Object> field = (Map<String, Object>) properties.get("timestamp");
        assertEquals(true, field.get("updatable"));
    }

    @SuppressWarnings("unchecked")
    public void testUpdatableOnBooleanField() throws Exception {
        assertAcked(prepareCreate("test-index").setMapping("active", "type=boolean,updatable=true"));
        GetMappingsResponse response = client().admin().indices().prepareGetMappings("test-index").get();
        Map<String, Object> mappings = response.getMappings().get("test-index").getSourceAsMap();
        Map<String, Object> properties = (Map<String, Object>) mappings.get("properties");
        Map<String, Object> field = (Map<String, Object>) properties.get("active");
        assertEquals(true, field.get("updatable"));
    }

    @SuppressWarnings("unchecked")
    public void testUpdatableOnIpField() throws Exception {
        assertAcked(prepareCreate("test-index").setMapping("ip_addr", "type=ip,updatable=true"));
        GetMappingsResponse response = client().admin().indices().prepareGetMappings("test-index").get();
        Map<String, Object> mappings = response.getMappings().get("test-index").getSourceAsMap();
        Map<String, Object> properties = (Map<String, Object>) mappings.get("properties");
        Map<String, Object> field = (Map<String, Object>) properties.get("ip_addr");
        assertEquals(true, field.get("updatable"));
    }

    public void testUpdatableDocValuesFieldDoesNotAffectSearch() throws Exception {
        assertAcked(prepareCreate("test-index").setMapping("score", "type=long,updatable=true"));
        client().prepareIndex("test-index").setId("1").setSource("score", 42).get();
        client().admin().indices().prepareRefresh("test-index").get();

        long hits = client().prepareSearch("test-index")
            .setQuery(QueryBuilders.rangeQuery("score").gte(40))
            .get().getHits().getTotalHits().value();
        assertEquals(1, hits);
    }

    @SuppressWarnings("unchecked")
    public void testAddUpdatableToExistingLongField() throws Exception {
        assertAcked(prepareCreate("test-index").setMapping("score", "type=long"));
        assertAcked(client().admin().indices().preparePutMapping("test-index")
            .setSource("{ \"properties\": { \"score\": { \"type\": \"long\", \"updatable\": true } } }",
                MediaTypeRegistry.JSON));

        GetMappingsResponse response = client().admin().indices().prepareGetMappings("test-index").get();
        Map<String, Object> mappings = response.getMappings().get("test-index").getSourceAsMap();
        Map<String, Object> properties = (Map<String, Object>) mappings.get("properties");
        Map<String, Object> field = (Map<String, Object>) properties.get("score");
        assertEquals(true, field.get("updatable"));
    }
}
