/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.action.admin.indices.updatablefields;

import org.opensearch.action.support.ActionFilters;
import org.opensearch.action.support.broadcast.node.TransportBroadcastByNodeAction;
import org.opensearch.cluster.ClusterState;
import org.opensearch.cluster.block.ClusterBlockException;
import org.opensearch.cluster.block.ClusterBlockLevel;
import org.opensearch.cluster.metadata.IndexNameExpressionResolver;
import org.opensearch.cluster.routing.ShardRouting;
import org.opensearch.cluster.routing.ShardsIterator;
import org.opensearch.cluster.service.ClusterService;
import org.opensearch.common.inject.Inject;
import org.opensearch.core.action.support.DefaultShardOperationFailedException;
import org.opensearch.core.common.io.stream.StreamInput;
import org.opensearch.index.IndexService;
import org.opensearch.index.engine.sidecar.SidecarRegistry;
import org.opensearch.index.shard.IndexShard;
import org.opensearch.indices.IndicesService;
import org.opensearch.threadpool.ThreadPool;
import org.opensearch.transport.TransportService;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Transport action that aggregates per-field sidecar statistics across all shards of an index.
 * Uses TransportBroadcastByNodeAction to fan out to all nodes hosting shards for the index.
 *
 * @opensearch.experimental
 */
public class TransportUpdatableFieldsStatsAction extends TransportBroadcastByNodeAction<
    UpdatableFieldsStatsRequest,
    UpdatableFieldsStatsResponse,
    ShardUpdatableFieldsStats> {

    private final IndicesService indicesService;

    @Inject
    public TransportUpdatableFieldsStatsAction(
        ClusterService clusterService,
        TransportService transportService,
        IndicesService indicesService,
        ActionFilters actionFilters,
        IndexNameExpressionResolver indexNameExpressionResolver
    ) {
        super(
            UpdatableFieldsStatsAction.NAME,
            clusterService,
            transportService,
            actionFilters,
            indexNameExpressionResolver,
            UpdatableFieldsStatsRequest::new,
            ThreadPool.Names.MANAGEMENT
        );
        this.indicesService = indicesService;
    }

    @Override
    protected ShardsIterator shards(ClusterState clusterState, UpdatableFieldsStatsRequest request, String[] concreteIndices) {
        return clusterState.routingTable().allShards(concreteIndices);
    }

    @Override
    protected ClusterBlockException checkGlobalBlock(ClusterState state, UpdatableFieldsStatsRequest request) {
        return state.blocks().globalBlockedException(ClusterBlockLevel.METADATA_READ);
    }

    @Override
    protected ClusterBlockException checkRequestBlock(
        ClusterState state,
        UpdatableFieldsStatsRequest request,
        String[] concreteIndices
    ) {
        return state.blocks().indicesBlockedException(ClusterBlockLevel.METADATA_READ, concreteIndices);
    }

    @Override
    protected ShardUpdatableFieldsStats readShardResult(StreamInput in) throws IOException {
        return new ShardUpdatableFieldsStats(in);
    }

    @Override
    protected UpdatableFieldsStatsResponse newResponse(
        UpdatableFieldsStatsRequest request,
        int totalShards,
        int successfulShards,
        int failedShards,
        List<ShardUpdatableFieldsStats> shardResults,
        List<DefaultShardOperationFailedException> shardFailures,
        ClusterState clusterState
    ) {
        // Aggregate per-field stats across all shards
        Map<String, long[]> aggregated = new HashMap<>();
        for (ShardUpdatableFieldsStats shardStats : shardResults) {
            for (Map.Entry<String, long[]> entry : shardStats.fieldStats().entrySet()) {
                long[] agg = aggregated.computeIfAbsent(entry.getKey(), k -> new long[2]);
                agg[0] += entry.getValue()[0]; // dirtyDocCount
                agg[1] += entry.getValue()[1]; // segmentCount
            }
        }

        Map<String, UpdatableFieldsStatsResponse.FieldStats> result = new HashMap<>();
        for (Map.Entry<String, long[]> entry : aggregated.entrySet()) {
            long[] s = entry.getValue();
            result.put(entry.getKey(), new UpdatableFieldsStatsResponse.FieldStats(s[0], (int) s[1]));
        }

        return new UpdatableFieldsStatsResponse(result, totalShards, successfulShards, failedShards, shardFailures);
    }

    @Override
    protected UpdatableFieldsStatsRequest readRequestFrom(StreamInput in) throws IOException {
        return new UpdatableFieldsStatsRequest(in);
    }

    @Override
    protected ShardUpdatableFieldsStats shardOperation(UpdatableFieldsStatsRequest request, ShardRouting shardRouting) {
        IndexService indexService = indicesService.indexServiceSafe(shardRouting.shardId().getIndex());
        IndexShard indexShard = indexService.getShard(shardRouting.shardId().id());

        Map<String, long[]> fieldStats = new HashMap<>();
        SidecarRegistry registry = indexShard.sidecarRegistry();
        if (registry != null && registry.hasAnySidecars()) {
            for (String field : registry.getUpdatableFields()) {
                long dirtyDocs = registry.getDirtyDocCount(field);
                int segmentCount = registry.getSegmentCount(field);
                fieldStats.put(field, new long[] { dirtyDocs, segmentCount });
            }
        }

        return new ShardUpdatableFieldsStats(fieldStats);
    }
}
