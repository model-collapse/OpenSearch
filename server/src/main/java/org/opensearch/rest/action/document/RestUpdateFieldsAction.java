/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.rest.action.document;

import org.opensearch.action.update.fields.UpdateFieldsAction;
import org.opensearch.action.update.fields.UpdateFieldsRequest;
import org.opensearch.rest.BaseRestHandler;
import org.opensearch.rest.RestRequest;
import org.opensearch.rest.action.RestToXContentListener;
import org.opensearch.transport.client.node.NodeClient;

import java.io.IOException;
import java.util.List;

import static java.util.Collections.singletonList;
import static org.opensearch.rest.RestRequest.Method.POST;

/**
 * REST handler for the POST /{index}/_update_fields endpoint.
 * Parses the request body and delegates to {@link org.opensearch.action.update.fields.TransportUpdateFieldsAction}.
 *
 * @opensearch.internal
 */
public class RestUpdateFieldsAction extends BaseRestHandler {

    @Override
    public List<Route> routes() {
        return singletonList(new Route(POST, "/{index}/_update_fields"));
    }

    @Override
    public String getName() {
        return "update_fields_action";
    }

    @Override
    public RestChannelConsumer prepareRequest(final RestRequest request, final NodeClient client) throws IOException {
        String index = request.param("index");
        UpdateFieldsRequest updateFieldsRequest = new UpdateFieldsRequest();
        updateFieldsRequest.setIndex(index);

        request.applyContentParser(parser -> updateFieldsRequest.fromXContent(parser));

        return channel -> client.execute(UpdateFieldsAction.INSTANCE, updateFieldsRequest, new RestToXContentListener<>(channel));
    }
}
