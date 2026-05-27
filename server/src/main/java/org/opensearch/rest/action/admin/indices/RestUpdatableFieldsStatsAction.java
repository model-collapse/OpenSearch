/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.rest.action.admin.indices;

import org.opensearch.action.admin.indices.updatablefields.UpdatableFieldsStatsAction;
import org.opensearch.action.admin.indices.updatablefields.UpdatableFieldsStatsRequest;
import org.opensearch.transport.client.node.NodeClient;
import org.opensearch.rest.BaseRestHandler;
import org.opensearch.rest.RestRequest;
import org.opensearch.rest.action.RestToXContentListener;

import java.io.IOException;
import java.util.List;

import static org.opensearch.rest.RestRequest.Method.GET;

/**
 * REST handler for the updatable fields stats endpoint.
 *
 * @opensearch.experimental
 */
public class RestUpdatableFieldsStatsAction extends BaseRestHandler {

    @Override
    public String getName() {
        return "updatable_fields_stats_action";
    }

    @Override
    public List<Route> routes() {
        return List.of(new Route(GET, "/{index}/_updatable_fields/stats"));
    }

    @Override
    protected RestChannelConsumer prepareRequest(RestRequest request, NodeClient client) throws IOException {
        String index = request.param("index");
        UpdatableFieldsStatsRequest statsRequest = new UpdatableFieldsStatsRequest(index);
        return channel -> client.execute(UpdatableFieldsStatsAction.INSTANCE, statsRequest, new RestToXContentListener<>(channel));
    }
}
