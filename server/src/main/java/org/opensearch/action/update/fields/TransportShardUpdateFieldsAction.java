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
import org.opensearch.common.inject.Inject;
import org.opensearch.common.settings.Settings;
import org.opensearch.core.action.ActionListener;
import org.opensearch.index.IndexingPressureService;
import org.opensearch.index.engine.Engine;
import org.opensearch.index.engine.sidecar.DocIdResolver;
import org.opensearch.index.engine.sidecar.DocValuesSidecarWriter;
import org.opensearch.index.engine.sidecar.KnnVectorSidecarWriter;
import org.opensearch.index.engine.sidecar.SidecarRegistry;
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

            // Determine if this is a scalar or vector update
            boolean isScalar = !request.updates().isEmpty() && request.updates().get(0).isScalarUpdate();

            // Store sidecar files within the Lucene index directory so they are co-located
            // with segments. Full snapshot/replication integration is Phase 2.
            Path sidecarDir = primary.shardPath().resolveIndex().resolve(
                "_sidecar_" + request.field() + "_" + System.nanoTime()
            );
            Files.createDirectories(sidecarDir);

            // Acquire a searcher to resolve document _ids to Lucene doc ordinals
            try (Engine.Searcher searcher = primary.acquireSearcher("update_fields")) {
                DirectoryReader reader = searcher.getDirectoryReader();

                if (isScalar) {
                    // Determine DocValuesType from field mapping
                    DocValuesSidecarWriter.DocValuesType dvType = resolveDocValuesType(primary, request.field());

                    try (DocValuesSidecarWriter writer = new DocValuesSidecarWriter(sidecarDir, request.field(), dvType, 1)) {
                        for (UpdateFieldsRequest.FieldUpdate update : request.updates()) {
                            try {
                                DocIdResolver.ResolvedDoc resolved = DocIdResolver.resolve(reader, update.getId());
                                if (resolved == null) {
                                    failed++;
                                    continue; // doc not found
                                }
                                int docId = resolved.docId();
                                writer.write(docId, update.getScalarValue());
                                updated++;
                            } catch (Exception e) {
                                logger.warn("Failed to write scalar for doc [{}]: {}", update.getId(), e.getMessage());
                                failed++;
                            }
                        }

                        if (updated > 0) {
                            SidecarWriter.SidecarWriteResult result = writer.flush();
                            // Register bitmap in SidecarRegistry so reads see dirty docs
                            if (result != null) {
                                SidecarRegistry registry = primary.sidecarRegistry();
                                registry.register(request.field(), resolveSegmentName(reader), writer.getVersionBitmap());
                            }
                        }
                    }
                } else {
                    // Vector update path
                    int dimension = request.updates().isEmpty() ? 0 : request.updates().get(0).getValue().length;

                    try (KnnVectorSidecarWriter writer = new KnnVectorSidecarWriter(sidecarDir, dimension, 1)) {
                        for (UpdateFieldsRequest.FieldUpdate update : request.updates()) {
                            try {
                                DocIdResolver.ResolvedDoc resolved = DocIdResolver.resolve(reader, update.getId());
                                if (resolved == null) {
                                    failed++;
                                    continue; // doc not found
                                }
                                int docId = resolved.docId();
                                writer.addVector(docId, update.getValue());
                                updated++;
                            } catch (Exception e) {
                                logger.warn("Failed to write vector for doc [{}]: {}", update.getId(), e.getMessage());
                                failed++;
                            }
                        }

                        if (updated > 0) {
                            SidecarWriter.SidecarWriteResult result = writer.flush();
                            // Register bitmap in SidecarRegistry so reads see dirty docs
                            if (result != null) {
                                SidecarRegistry registry = primary.sidecarRegistry();
                                registry.register(request.field(), resolveSegmentName(reader), writer.getVersionBitmap());
                            }
                        }
                    }
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
     * Resolve a stable segment name from the reader. Uses the first leaf's segment name
     * as the registration key. For multi-segment cases, the bitmap covers all segments
     * (Phase 1 simplification).
     */
    private String resolveSegmentName(DirectoryReader reader) {
        if (reader.leaves().isEmpty()) {
            return "unknown";
        }
        var leaf = reader.leaves().get(0).reader();
        if (leaf instanceof org.apache.lucene.index.SegmentReader sr) {
            return sr.getSegmentName();
        }
        return "leaf_" + reader.leaves().get(0).ord;
    }

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
