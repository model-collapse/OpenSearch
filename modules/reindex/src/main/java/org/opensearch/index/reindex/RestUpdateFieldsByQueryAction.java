/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.index.reindex;

import org.opensearch.core.common.io.stream.NamedWriteableRegistry;
import org.opensearch.core.xcontent.XContentParser;
import org.opensearch.index.query.AbstractQueryBuilder;
import org.opensearch.rest.RestRequest;
import org.opensearch.search.builder.SearchSourceBuilder;
import org.opensearch.transport.client.node.NodeClient;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static java.util.Collections.singletonList;
import static org.opensearch.rest.RestRequest.Method.POST;

/**
 * REST handler for {@code POST /{index}/_update_fields_by_query}.
 * <p>
 * Parses field, pipeline, source_fields, query, batch_size, and slices
 * from the request body. Extends {@link AbstractBaseReindexRestHandler}
 * for automatic requests_per_second, timeout, refresh, and scroll
 * parameter handling.
 */
public class RestUpdateFieldsByQueryAction extends AbstractBaseReindexRestHandler<UpdateFieldsByQueryRequest, UpdateFieldsByQueryAction> {

    public RestUpdateFieldsByQueryAction() {
        super(UpdateFieldsByQueryAction.INSTANCE);
    }

    @Override
    public String getName() {
        return "update_fields_by_query_action";
    }

    @Override
    public List<Route> routes() {
        return singletonList(new Route(POST, "/{index}/_update_fields_by_query"));
    }

    @Override
    public RestChannelConsumer prepareRequest(RestRequest request, NodeClient client) throws IOException {
        return doPrepareRequest(request, client, false, true);
    }

    @Override
    protected UpdateFieldsByQueryRequest buildRequest(RestRequest request, NamedWriteableRegistry namedWriteableRegistry)
        throws IOException {
        UpdateFieldsByQueryRequest updateRequest = new UpdateFieldsByQueryRequest();
        updateRequest.getSearchRequest().indices(request.param("index").split(","));

        if (request.hasContentOrSourceParam()) {
            try (XContentParser parser = request.contentOrSourceParamParser()) {
                parser.nextToken(); // START_OBJECT
                while (parser.nextToken() != XContentParser.Token.END_OBJECT) {
                    String fieldName = parser.currentName();
                    parser.nextToken();
                    switch (fieldName) {
                        case "field":
                            updateRequest.setField(parser.text());
                            break;
                        case "pipeline":
                            updateRequest.setPipeline(parser.text());
                            break;
                        case "source_fields":
                            List<String> sourceFields = new ArrayList<>();
                            while (parser.nextToken() != XContentParser.Token.END_ARRAY) {
                                sourceFields.add(parser.text());
                            }
                            updateRequest.setSourceFields(sourceFields);
                            break;
                        case "query":
                            updateRequest.getSearchRequest().source(
                                new SearchSourceBuilder().query(
                                    AbstractQueryBuilder.parseInnerQueryBuilder(parser)
                                )
                            );
                            break;
                        case "batch_size":
                            if (updateRequest.getSearchRequest().source() == null) {
                                updateRequest.getSearchRequest().source(new SearchSourceBuilder());
                            }
                            updateRequest.getSearchRequest().source().size(parser.intValue());
                            break;
                        case "slices":
                            String slicesStr = parser.text();
                            if (AbstractBulkByScrollRequest.AUTO_SLICES_VALUE.equals(slicesStr)) {
                                updateRequest.setSlices(AbstractBulkByScrollRequest.AUTO_SLICES);
                            } else {
                                updateRequest.setSlices(Integer.parseInt(slicesStr));
                            }
                            break;
                        default:
                            parser.skipChildren();
                            break;
                    }
                }
            }
        }

        // Apply scroll_size from query params
        String scrollSize = request.param("scroll_size");
        if (scrollSize != null) {
            if (updateRequest.getSearchRequest().source() == null) {
                updateRequest.getSearchRequest().source(new SearchSourceBuilder());
            }
            updateRequest.getSearchRequest().source().size(Integer.parseInt(scrollSize));
        }

        return updateRequest;
    }
}
