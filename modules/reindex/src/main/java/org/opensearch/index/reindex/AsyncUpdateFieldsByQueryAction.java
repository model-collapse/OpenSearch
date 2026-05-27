/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.index.reindex;

import org.apache.logging.log4j.Logger;
import org.opensearch.action.index.IndexRequest;
import org.opensearch.core.action.ActionListener;
import org.opensearch.script.ScriptService;
import org.opensearch.threadpool.ThreadPool;
import org.opensearch.transport.client.ParentTaskAssigningClient;

/**
 * Implementation of update-fields-by-query using scrolling and bulk.
 * Scrolls over documents matching a query and builds IndexRequests for sidecar field updates.
 *
 * Phase 3 skeleton: buildRequest() creates a standard IndexRequest from the scroll hit.
 * Full pipeline integration (reading source_fields, running pipeline, writing sidecar updates)
 * will be added in a follow-up.
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
        // Phase 3 skeleton: build an IndexRequest that will be sent as a sidecar update.
        // In the full implementation, this would:
        // 1. Read source_fields from doc.getSource()
        // 2. Run the pipeline to compute new field values
        // 3. Create a sidecar update request
        IndexRequest index = new IndexRequest();
        index.index(doc.getIndex());
        index.id(doc.getId());
        index.source(doc.getSource(), doc.getMediaType());
        index.setIfSeqNo(doc.getSeqNo());
        index.setIfPrimaryTerm(doc.getPrimaryTerm());
        if (doc.getRouting() != null) {
            index.routing(doc.getRouting());
        }
        return wrap(index);
    }
}
