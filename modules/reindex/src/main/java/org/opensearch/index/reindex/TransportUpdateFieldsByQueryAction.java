/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.index.reindex;

import org.opensearch.action.support.ActionFilters;
import org.opensearch.action.support.HandledTransportAction;
import org.opensearch.cluster.service.ClusterService;
import org.opensearch.common.inject.Inject;
import org.opensearch.core.action.ActionListener;
import org.opensearch.core.common.io.stream.Writeable;
import org.opensearch.script.ScriptService;
import org.opensearch.tasks.Task;
import org.opensearch.threadpool.ThreadPool;
import org.opensearch.transport.TransportService;
import org.opensearch.transport.client.Client;
import org.opensearch.transport.client.ParentTaskAssigningClient;

/**
 * Transport action for update-fields-by-query. Scrolls over documents matching a query
 * and applies sidecar field updates using the bulk-by-scroll infrastructure.
 *
 * @opensearch.internal
 */
public class TransportUpdateFieldsByQueryAction extends HandledTransportAction<UpdateFieldsByQueryRequest, BulkByScrollResponse> {

    private final ThreadPool threadPool;
    private final Client client;
    private final ScriptService scriptService;
    private final ClusterService clusterService;

    @Inject
    public TransportUpdateFieldsByQueryAction(
        ThreadPool threadPool,
        ActionFilters actionFilters,
        Client client,
        TransportService transportService,
        ScriptService scriptService,
        ClusterService clusterService
    ) {
        super(
            UpdateFieldsByQueryAction.NAME,
            transportService,
            actionFilters,
            (Writeable.Reader<UpdateFieldsByQueryRequest>) UpdateFieldsByQueryRequest::new
        );
        this.threadPool = threadPool;
        this.client = client;
        this.scriptService = scriptService;
        this.clusterService = clusterService;
    }

    @Override
    protected void doExecute(Task task, UpdateFieldsByQueryRequest request, ActionListener<BulkByScrollResponse> listener) {
        BulkByScrollTask bulkByScrollTask = (BulkByScrollTask) task;
        BulkByScrollParallelizationHelper.startSlicedAction(
            clusterService.state().metadata(),
            request,
            bulkByScrollTask,
            UpdateFieldsByQueryAction.INSTANCE,
            listener,
            client,
            clusterService.localNode(),
            () -> {
                ParentTaskAssigningClient assigningClient = new ParentTaskAssigningClient(
                    client,
                    clusterService.localNode(),
                    bulkByScrollTask
                );
                new AsyncUpdateFieldsByQueryAction(bulkByScrollTask, logger, assigningClient, threadPool, request, scriptService, listener)
                    .start();
            }
        );
    }
}
