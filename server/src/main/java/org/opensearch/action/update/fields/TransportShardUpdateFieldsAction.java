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
import org.apache.lucene.index.DirectoryReader;
import org.opensearch.action.support.ActionFilters;
import org.opensearch.action.support.replication.TransportWriteAction;
import org.opensearch.cluster.action.shard.ShardStateAction;
import org.opensearch.cluster.service.ClusterService;
import org.opensearch.OpenSearchException;
import org.opensearch.common.inject.Inject;
import org.opensearch.common.settings.Settings;
import org.opensearch.core.action.ActionListener;
import org.opensearch.index.IndexingPressureService;
import org.opensearch.index.engine.Engine;
import org.opensearch.index.engine.sidecar.DocIdResolver;
import org.opensearch.index.engine.sidecar.DocValuesSidecarWriter;
import org.opensearch.index.engine.sidecar.KnnVectorSidecarWriter;
import org.opensearch.index.engine.sidecar.SidecarRegistry;
import org.opensearch.index.engine.sidecar.SidecarRegistryManifest;
import org.opensearch.index.engine.sidecar.SidecarWriter;
import org.opensearch.index.mapper.IpFieldMapper;
import org.opensearch.index.mapper.KeywordFieldMapper;
import org.opensearch.index.mapper.MappedFieldType;
import org.opensearch.index.shard.IndexShard;
import org.opensearch.indices.IndicesService;
import org.opensearch.indices.SystemIndices;
import org.opensearch.telemetry.tracing.Tracer;
import org.opensearch.threadpool.ThreadPool;
import org.opensearch.transport.TransportService;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
            // Sidecar writes rely on segment replication (or remote store) for cross-node
            // durability — sidecar files are copied directly to replicas. Without segment
            // replication, there is no translog entry to replay on crash recovery, creating
            // a durability gap. Reject early with a clear message.
            if (primary.indexSettings().isSegRepEnabledOrRemoteNode() == false) {
                throw new OpenSearchException(
                    "index ["
                        + primary.shardId().getIndexName()
                        + "] does not use segment replication; "
                        + "_update_fields requires index.replication.type=SEGMENT for durability"
                );
            }

            int updated = 0;
            int failed = 0;

            // Determine if this is a scalar or vector update
            boolean isScalar = !request.updates().isEmpty() && request.updates().get(0).isScalarUpdate();

            SidecarRegistry registry = primary.sidecarRegistry();

            // Acquire a searcher to resolve document _ids to Lucene doc ordinals
            try (Engine.Searcher searcher = primary.acquireSearcher("update_fields")) {
                DirectoryReader reader = searcher.getDirectoryReader();

                // Group updates by segment name using per-doc resolution
                Map<String, List<ResolvedUpdate>> bySegment = new HashMap<>();
                for (UpdateFieldsRequest.FieldUpdate update : request.updates()) {
                    try {
                        DocIdResolver.ResolvedDoc resolved = DocIdResolver.resolve(reader, update.getId());
                        if (resolved == null) {
                            failed++;
                            continue; // doc not found
                        }
                        bySegment.computeIfAbsent(resolved.segmentName(), k -> new ArrayList<>())
                            .add(new ResolvedUpdate(update, resolved.docId()));
                    } catch (Exception e) {
                        logger.warn("Failed to resolve doc [{}]: {}", update.getId(), e.getMessage());
                        failed++;
                    }
                }

                // Process each segment group with its own writer and bitmap
                for (Map.Entry<String, List<ResolvedUpdate>> entry : bySegment.entrySet()) {
                    String segmentName = entry.getKey();
                    List<ResolvedUpdate> segmentUpdates = entry.getValue();

                    // Store sidecar files within the Lucene index directory so they are co-located
                    // with segments. Full snapshot/replication integration is Phase 2.
                    Path sidecarDir = primary.shardPath().resolveIndex().resolve(
                        "_sidecar_" + request.field() + "_" + segmentName + "_" + System.nanoTime()
                    );
                    Files.createDirectories(sidecarDir);

                    if (isScalar) {
                        DocValuesSidecarWriter.DocValuesType dvType = resolveDocValuesType(primary, request.field());
                        try (DocValuesSidecarWriter writer = new DocValuesSidecarWriter(sidecarDir, request.field(), dvType, 1)) {
                            for (ResolvedUpdate ru : segmentUpdates) {
                                try {
                                    writer.write(ru.docId(), ru.update().getScalarValue());
                                    updated++;
                                } catch (Exception e) {
                                    logger.warn("Failed to write scalar for doc [{}]: {}", ru.update().getId(), e.getMessage());
                                    failed++;
                                }
                            }

                            SidecarWriter.SidecarWriteResult result = writer.flush();
                            if (result != null) {
                                registry.register(request.field(), segmentName, writer.getVersionBitmap(), sidecarDir);
                                String fileKey = request.field() + ":" + segmentName;
                                String sidecarDirName = sidecarDir.getFileName().toString();
                                registry.registerFiles(fileKey, result.files(), sidecarDirName);
                            }
                        }
                    } else {
                        int dimension = segmentUpdates.get(0).update().getValue().length;
                        try (KnnVectorSidecarWriter writer = new KnnVectorSidecarWriter(sidecarDir, dimension, 1)) {
                            for (ResolvedUpdate ru : segmentUpdates) {
                                try {
                                    writer.addVector(ru.docId(), ru.update().getValue());
                                    updated++;
                                } catch (Exception e) {
                                    logger.warn("Failed to write vector for doc [{}]: {}", ru.update().getId(), e.getMessage());
                                    failed++;
                                }
                            }

                            SidecarWriter.SidecarWriteResult result = writer.flush();
                            if (result != null) {
                                registry.register(request.field(), segmentName, writer.getVersionBitmap(), sidecarDir);
                                String fileKey = request.field() + ":" + segmentName;
                                String sidecarDirName = sidecarDir.getFileName().toString();
                                registry.registerFiles(fileKey, result.files(), sidecarDirName);
                            }
                        }
                    }
                }
            }

            // Persist registry state for crash recovery
            if (updated > 0) {
                try {
                    SidecarRegistryManifest.save(registry, primary.shardPath().resolveIndex());
                } catch (IOException e) {
                    logger.warn("Failed to persist sidecar registry manifest", e);
                }
            }

            // Fsync the translog to ensure durability before acknowledging.
            // Full translog-based recovery for sidecar ops is Phase 2.
            if (updated > 0) {
                primary.sync();
            }

            ShardUpdateFieldsResponse response = new ShardUpdateFieldsResponse(updated, failed);
            return new WritePrimaryResult<>(request, response, null, null, primary, logger);
        });
    }

    /**
     * Holds a resolved update: the original update paired with its segment-local doc ID.
     */
    private record ResolvedUpdate(UpdateFieldsRequest.FieldUpdate update, int docId) {}

    /**
     * Resolve the DocValuesType from the field's mapping type.
     */
    private DocValuesSidecarWriter.DocValuesType resolveDocValuesType(IndexShard shard, String fieldName) {
        MappedFieldType fieldType = shard.mapperService().fieldType(fieldName);
        if (fieldType == null) {
            // Default to SORTED_SET if we can't determine
            return DocValuesSidecarWriter.DocValuesType.SORTED_SET;
        }
        if (fieldType instanceof KeywordFieldMapper.KeywordFieldType || fieldType instanceof IpFieldMapper.IpFieldType) {
            return DocValuesSidecarWriter.DocValuesType.SORTED_SET;
        }
        // NumberFieldType, BooleanFieldType, DateFieldType -> SORTED_NUMERIC
        return DocValuesSidecarWriter.DocValuesType.SORTED_NUMERIC;
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
