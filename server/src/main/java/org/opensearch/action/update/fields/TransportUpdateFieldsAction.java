/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.action.update.fields;

import org.opensearch.action.support.ActionFilters;
import org.opensearch.action.support.HandledTransportAction;
import org.opensearch.cluster.metadata.IndexMetadata;
import org.opensearch.cluster.routing.OperationRouting;
import org.opensearch.cluster.service.ClusterService;
import org.opensearch.common.inject.Inject;
import org.opensearch.core.action.ActionListener;
import org.opensearch.core.index.shard.ShardId;
import org.opensearch.index.IndexNotFoundException;
import org.opensearch.tasks.Task;
import org.opensearch.transport.TransportService;
import org.opensearch.transport.client.node.NodeClient;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Transport action for the _update_fields endpoint.
 * <p>
 * Coordinator-level action that resolves the concrete index, groups updates by shard,
 * dispatches per-shard requests via {@link TransportShardUpdateFieldsAction}, and
 * aggregates the responses.
 *
 * @opensearch.internal
 */
public class TransportUpdateFieldsAction extends HandledTransportAction<UpdateFieldsRequest, UpdateFieldsResponse> {

    private final ClusterService clusterService;
    private final NodeClient client;

    @Inject
    public TransportUpdateFieldsAction(
        TransportService transportService,
        ActionFilters actionFilters,
        ClusterService clusterService,
        NodeClient client
    ) {
        super(UpdateFieldsAction.NAME, transportService, actionFilters, UpdateFieldsRequest::new);
        this.clusterService = clusterService;
        this.client = client;
    }

    @Override
    protected void doExecute(Task task, UpdateFieldsRequest request, ActionListener<UpdateFieldsResponse> listener) {
        long startTime = System.currentTimeMillis();

        try {
            var state = clusterService.state();
            IndexMetadata indexMetadata = state.metadata().index(request.getIndex());
            if (indexMetadata == null) {
                listener.onFailure(new IndexNotFoundException(request.getIndex()));
                return;
            }

            // Group updates by shard
            Map<ShardId, List<UpdateFieldsRequest.FieldUpdate>> shardUpdates = new HashMap<>();
            OperationRouting routing = clusterService.operationRouting();

            for (UpdateFieldsRequest.FieldUpdate update : request.getUpdates()) {
                ShardId shardId = routing.shardId(state, indexMetadata.getIndex().getName(), update.getId(), null);
                shardUpdates.computeIfAbsent(shardId, k -> new ArrayList<>()).add(update);
            }

            // Dispatch per-shard requests and aggregate responses
            int totalShards = shardUpdates.size();
            AtomicInteger completedShards = new AtomicInteger(0);
            AtomicInteger totalUpdated = new AtomicInteger(0);
            AtomicInteger totalFailed = new AtomicInteger(0);

            for (Map.Entry<ShardId, List<UpdateFieldsRequest.FieldUpdate>> entry : shardUpdates.entrySet()) {
                ShardUpdateFieldsRequest shardRequest = new ShardUpdateFieldsRequest(
                    entry.getKey(),
                    request.getField(),
                    entry.getValue()
                );

                client.execute(
                    ShardUpdateFieldsAction.INSTANCE,
                    shardRequest,
                    new ActionListener<ShardUpdateFieldsResponse>() {
                        @Override
                        public void onResponse(ShardUpdateFieldsResponse response) {
                            totalUpdated.addAndGet(response.updated());
                            totalFailed.addAndGet(response.failed());
                            if (completedShards.incrementAndGet() == totalShards) {
                                long took = System.currentTimeMillis() - startTime;
                                listener.onResponse(new UpdateFieldsResponse(took, totalUpdated.get(), totalFailed.get(), totalShards));
                            }
                        }

                        @Override
                        public void onFailure(Exception e) {
                            totalFailed.addAndGet(entry.getValue().size());
                            if (completedShards.incrementAndGet() == totalShards) {
                                long took = System.currentTimeMillis() - startTime;
                                listener.onResponse(new UpdateFieldsResponse(took, totalUpdated.get(), totalFailed.get(), totalShards));
                            }
                        }
                    }
                );
            }
        } catch (Exception e) {
            listener.onFailure(e);
        }
    }
}
