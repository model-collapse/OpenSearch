/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.index.reindex;

import java.util.HashMap;
import java.util.Map;

import org.apache.logging.log4j.Logger;
import org.opensearch.action.index.IndexRequest;
import org.opensearch.common.xcontent.XContentHelper;
import org.opensearch.core.action.ActionListener;
import org.opensearch.script.ScriptService;
import org.opensearch.threadpool.ThreadPool;
import org.opensearch.transport.client.ParentTaskAssigningClient;

/**
 * Implementation of update-fields-by-query using scrolling and bulk.
 * Scrolls over documents matching a query and builds IndexRequests for sidecar field updates.
 *
 * buildRequest() sets the ingest pipeline on the IndexRequest and filters the document source
 * to only include configured source_fields. The ingest pipeline produces the new field value
 * during indexing.
 *
 * @opensearch.internal
 */
public class AsyncUpdateFieldsByQueryAction
    extends AbstractAsyncBulkByScrollAction<UpdateFieldsByQueryRequest, TransportUpdateFieldsByQueryAction> {

    public AsyncUpdateFieldsByQueryAction(
        BulkByScrollTask task,
        Logger logger,
        ParentTaskAssigningClient client,
        ThreadPool threadPool,
        UpdateFieldsByQueryRequest request,
        ScriptService scriptService,
        ActionListener<BulkByScrollResponse> listener
    ) {
        super(task, false, true, logger, client, threadPool, request, listener, scriptService, null);
    }

    @Override
    protected RequestWrapper<IndexRequest> buildRequest(ScrollableHitSource.Hit doc) {
        UpdateFieldsByQueryRequest request = mainRequest;

        IndexRequest index = new IndexRequest();
        index.index(doc.getIndex());
        index.id(doc.getId());
        index.setIfSeqNo(doc.getSeqNo());
        index.setIfPrimaryTerm(doc.getPrimaryTerm());
        if (doc.getRouting() != null) {
            index.routing(doc.getRouting());
        }

        // If pipeline is specified, set it so ingest processes the doc
        if (request.getPipeline() != null) {
            index.setPipeline(request.getPipeline());
        }

        // If sourceFields specified, only include those fields in the source
        if (request.getSourceFields() != null && !request.getSourceFields().isEmpty()) {
            Map<String, Object> fullSource = XContentHelper.convertToMap(doc.getSource(), true, doc.getMediaType()).v2();
            Map<String, Object> filteredSource = new HashMap<>();
            for (String field : request.getSourceFields()) {
                if (fullSource.containsKey(field)) {
                    filteredSource.put(field, fullSource.get(field));
                }
            }
            index.source(filteredSource, doc.getMediaType());
        } else {
            index.source(doc.getSource(), doc.getMediaType());
        }

        return wrap(index);
    }
}
