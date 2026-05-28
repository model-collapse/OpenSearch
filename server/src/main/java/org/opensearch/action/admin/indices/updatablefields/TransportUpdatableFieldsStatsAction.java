/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.action.admin.indices.updatablefields;

import org.opensearch.action.support.ActionFilters;
import org.opensearch.action.support.HandledTransportAction;
import org.opensearch.cluster.metadata.IndexMetadata;
import org.opensearch.cluster.routing.IndexRoutingTable;
import org.opensearch.cluster.routing.IndexShardRoutingTable;
import org.opensearch.cluster.routing.ShardRouting;
import org.opensearch.cluster.service.ClusterService;
import org.opensearch.common.inject.Inject;
import org.opensearch.core.action.ActionListener;
import org.opensearch.index.IndexNotFoundException;
import org.opensearch.index.IndexService;
import org.opensearch.index.engine.sidecar.SidecarRegistry;
import org.opensearch.index.shard.IndexShard;
import org.opensearch.indices.IndicesService;
import org.opensearch.tasks.Task;
import org.opensearch.transport.TransportService;

import java.util.HashMap;
import java.util.Map;

/**
 * Transport action that aggregates per-field sidecar statistics across all shards of an index.
 *
 * @opensearch.experimental
 */
public class TransportUpdatableFieldsStatsAction extends HandledTransportAction<UpdatableFieldsStatsRequest, UpdatableFieldsStatsResponse> {

    private final IndicesService indicesService;
    private final ClusterService clusterService;

    @Inject
    public TransportUpdatableFieldsStatsAction(
        TransportService transportService,
        ActionFilters actionFilters,
        IndicesService indicesService,
        ClusterService clusterService
    ) {
        super(UpdatableFieldsStatsAction.NAME, transportService, actionFilters, UpdatableFieldsStatsRequest::new);
        this.indicesService = indicesService;
        this.clusterService = clusterService;
    }

    @Override
    protected void doExecute(Task task, UpdatableFieldsStatsRequest request, ActionListener<UpdatableFieldsStatsResponse> listener) {
        try {
            String indexName = request.index();
            IndexMetadata indexMetadata = clusterService.state().metadata().index(indexName);
            if (indexMetadata == null) {
                listener.onFailure(new IndexNotFoundException(indexName));
                return;
            }

            // Determine total active shard copies from the routing table
            int totalShards = 0;
            IndexRoutingTable routingTable = clusterService.state().routingTable().index(indexName);
            if (routingTable != null) {
                for (Map.Entry<Integer, IndexShardRoutingTable> entry : routingTable.shards().entrySet()) {
                    IndexShardRoutingTable shardTable = entry.getValue();
                    for (ShardRouting routing : shardTable.shards()) {
                        if (routing.active()) {
                            totalShards++;
                        }
                    }
                }
            }

            IndexService indexService = indicesService.indexService(indexMetadata.getIndex());
            if (indexService == null) {
                listener.onResponse(new UpdatableFieldsStatsResponse(Map.of(), totalShards, 0));
                return;
            }

            // Aggregate stats across local shards only
            // field -> [generations, docsUpdated, segmentsWithSidecars]
            Map<String, long[]> aggregated = new HashMap<>();
            int localShardsQueried = 0;

            for (IndexShard shard : indexService) {
                localShardsQueried++;
                SidecarRegistry registry = shard.sidecarRegistry();
                if (registry == null || !registry.hasAnySidecars()) {
                    continue;
                }

                for (String field : registry.getUpdatableFields()) {
                    long[] stats = aggregated.computeIfAbsent(field, k -> new long[3]);
                    // stats[0] = max generations (reserved for future use)
                    // stats[1] = total dirty docs across all segments
                    // stats[2] = total segments with sidecars
                    stats[1] += registry.getDirtyDocCount(field);
                    stats[2] += registry.getSegmentCount(field);
                }
            }

            Map<String, UpdatableFieldsStatsResponse.FieldStats> result = new HashMap<>();
            for (Map.Entry<String, long[]> entry : aggregated.entrySet()) {
                long[] s = entry.getValue();
                result.put(entry.getKey(), new UpdatableFieldsStatsResponse.FieldStats((int) s[0], s[1], (int) s[2]));
            }

            listener.onResponse(new UpdatableFieldsStatsResponse(result, totalShards, localShardsQueried));
        } catch (Exception e) {
            listener.onFailure(e);
        }
    }
}
