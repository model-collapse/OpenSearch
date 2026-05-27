/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.rest.action.admin.indices;

import org.opensearch.action.admin.indices.updatablefields.UpdatableFieldsMergeAction;
import org.opensearch.action.admin.indices.updatablefields.UpdatableFieldsMergeRequest;
import org.opensearch.transport.client.node.NodeClient;
import org.opensearch.core.xcontent.XContentParser;
import org.opensearch.rest.BaseRestHandler;
import org.opensearch.rest.RestRequest;
import org.opensearch.rest.action.RestToXContentListener;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.opensearch.rest.RestRequest.Method.POST;

/**
 * REST handler for the POST /{index}/_updatable_fields/merge endpoint.
 *
 * @opensearch.experimental
 */
public class RestUpdatableFieldsMergeAction extends BaseRestHandler {

    @Override
    public String getName() {
        return "updatable_fields_merge_action";
    }

    @Override
    public List<Route> routes() {
        return List.of(new Route(POST, "/{index}/_updatable_fields/merge"));
    }

    @Override
    protected RestChannelConsumer prepareRequest(RestRequest request, NodeClient client) throws IOException {
        String index = request.param("index");
        UpdatableFieldsMergeRequest mergeRequest = new UpdatableFieldsMergeRequest(index);

        if (request.hasContent()) {
            try (XContentParser parser = request.contentParser()) {
                parser.nextToken();
                while (parser.nextToken() != XContentParser.Token.END_OBJECT) {
                    if ("fields".equals(parser.currentName())) {
                        parser.nextToken();
                        List<String> fields = new ArrayList<>();
                        while (parser.nextToken() != XContentParser.Token.END_ARRAY) {
                            fields.add(parser.text());
                        }
                        mergeRequest.fields(fields);
                    } else {
                        parser.skipChildren();
                    }
                }
            }
        }

        return channel -> client.execute(UpdatableFieldsMergeAction.INSTANCE, mergeRequest, new RestToXContentListener<>(channel));
    }
}
