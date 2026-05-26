/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.action.update.fields;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.opensearch.action.support.ActionFilters;
import org.opensearch.action.support.replication.TransportWriteAction;
import org.opensearch.cluster.action.shard.ShardStateAction;
import org.opensearch.cluster.service.ClusterService;
import org.opensearch.common.inject.Inject;
import org.opensearch.common.settings.Settings;
import org.opensearch.core.action.ActionListener;
import org.opensearch.index.IndexingPressureService;
import org.opensearch.index.engine.sidecar.KnnVectorSidecarWriter;
import org.opensearch.index.shard.IndexShard;
import org.opensearch.indices.IndicesService;
import org.opensearch.indices.SystemIndices;
import org.opensearch.telemetry.tracing.Tracer;
import org.opensearch.threadpool.ThreadPool;
import org.opensearch.transport.TransportService;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Function;

/**
 * Shard-level transport action for _update_fields that extends TransportWriteAction.
 * Receives a per-shard request, creates a KnnVectorSidecarWriter, writes vectors,
 * and handles replication.
 *
 * @opensearch.internal
 */
public class TransportShardUpdateFieldsAction extends TransportWriteAction<
    ShardUpdateFieldsRequest, ShardUpdateFieldsRequest, ShardUpdateFieldsResponse> {

    private static final Logger logger = LogManager.getLogger(TransportShardUpdateFieldsAction.class);

    private static final Function<IndexShard, String> EXECUTOR_NAME_FUNCTION = shard -> ThreadPool.Names.WRITE;

    @Inject
    public TransportShardUpdateFieldsAction(
        Settings settings,
        TransportService transportService,
        ClusterService clusterService,
        IndicesService indicesService,
        ThreadPool threadPool,
        ShardStateAction shardStateAction,
        ActionFilters actionFilters,
        IndexingPressureService indexingPressureService,
        SystemIndices systemIndices,
        Tracer tracer
    ) {
        super(
            settings,
            ShardUpdateFieldsAction.NAME,
            transportService,
            clusterService,
            indicesService,
            threadPool,
            shardStateAction,
            actionFilters,
            ShardUpdateFieldsRequest::new,
            ShardUpdateFieldsRequest::new,
            EXECUTOR_NAME_FUNCTION,
            false,
            indexingPressureService,
            systemIndices,
            tracer
        );
    }

    @Override
    protected void dispatchedShardOperationOnPrimary(
        ShardUpdateFieldsRequest request,
        IndexShard primary,
        ActionListener<PrimaryResult<ShardUpdateFieldsRequest, ShardUpdateFieldsResponse>> listener
    ) {
        ActionListener.completeWith(listener, () -> {
            int updated = 0;
            int failed = 0;

            // Get sidecar directory under shard data path
            Path shardPath = primary.shardPath().getDataPath();
            Path sidecarDir = shardPath.resolve(".sidecars").resolve(request.field()).resolve(String.valueOf(System.nanoTime()));
            Files.createDirectories(sidecarDir);

            // Get field dimension from the first update's vector length
            int dimension = request.updates().isEmpty() ? 0 : request.updates().get(0).getValue().length;

            try (KnnVectorSidecarWriter writer = new KnnVectorSidecarWriter(sidecarDir, dimension, 1)) {
                for (UpdateFieldsRequest.FieldUpdate update : request.updates()) {
                    try {
                        // Resolve internal doc ID (placeholder — full UID-to-docId resolution in follow-up)
                        int docId = Math.abs(update.getId().hashCode() % 1_000_000);
                        writer.addVector(docId, update.getValue());
                        updated++;
                    } catch (Exception e) {
                        logger.warn("Failed to write vector for doc [{}]: {}", update.getId(), e.getMessage());
                        failed++;
                    }
                }

                if (updated > 0) {
                    writer.flush();
                }
            }

            ShardUpdateFieldsResponse response = new ShardUpdateFieldsResponse(updated, failed);
            return new WritePrimaryResult<>(request, response, null, null, primary, logger);
        });
    }

    @Override
    protected void dispatchedShardOperationOnReplica(
        ShardUpdateFieldsRequest request,
        IndexShard replica,
        ActionListener<ReplicaResult> listener
    ) {
        // Replicas receive sidecar files via segment replication, not ops replay
        ActionListener.completeWith(listener, ReplicaResult::new);
    }

    @Override
    protected ShardUpdateFieldsResponse newResponseInstance(org.opensearch.core.common.io.stream.StreamInput in) throws java.io.IOException {
        return new ShardUpdateFieldsResponse(in);
    }
}
