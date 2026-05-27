/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.action.admin.indices.updatablefields;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.opensearch.action.admin.indices.forcemerge.ForceMergeRequest;
import org.opensearch.action.support.ActionFilters;
import org.opensearch.action.support.HandledTransportAction;
import org.opensearch.cluster.metadata.IndexMetadata;
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

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

/**
 * Transport action that consolidates sidecar generations into base segments.
 * Triggers a Lucene force-merge on each shard and clears sidecar registry entries
 * for the merged fields afterward.
 *
 * @opensearch.experimental
 */
public class TransportUpdatableFieldsMergeAction extends HandledTransportAction<UpdatableFieldsMergeRequest, UpdatableFieldsMergeResponse> {

    private static final Logger logger = LogManager.getLogger(TransportUpdatableFieldsMergeAction.class);

    private final IndicesService indicesService;
    private final ClusterService clusterService;

    @Inject
    public TransportUpdatableFieldsMergeAction(
        TransportService transportService,
        ActionFilters actionFilters,
        IndicesService indicesService,
        ClusterService clusterService
    ) {
        super(UpdatableFieldsMergeAction.NAME, transportService, actionFilters, UpdatableFieldsMergeRequest::new);
        this.indicesService = indicesService;
        this.clusterService = clusterService;
    }

    @Override
    protected void doExecute(Task task, UpdatableFieldsMergeRequest request, ActionListener<UpdatableFieldsMergeResponse> listener) {
        long startTime = System.currentTimeMillis();
        try {
            String indexName = request.index();
            IndexMetadata indexMetadata = clusterService.state().metadata().index(indexName);
            if (indexMetadata == null) {
                listener.onFailure(new IndexNotFoundException(indexName));
                return;
            }

            IndexService indexService = indicesService.indexService(indexMetadata.getIndex());
            if (indexService == null) {
                listener.onResponse(new UpdatableFieldsMergeResponse(0, 0, true));
                return;
            }

            int mergedCount = 0;
            for (IndexShard shard : indexService) {
                SidecarRegistry registry = shard.sidecarRegistry();
                if (registry == null || !registry.hasAnySidecars()) continue;

                // Get fields to merge
                Set<String> fieldsToMerge = new HashSet<>(registry.getUpdatableFields());
                if (request.fields() != null) {
                    fieldsToMerge.retainAll(new HashSet<>(request.fields()));
                }

                if (fieldsToMerge.isEmpty()) continue;

                // Trigger force merge on the shard to consolidate sidecars into base segments
                try {
                    shard.forceMerge(new ForceMergeRequest().maxNumSegments(1));
                } catch (IOException e) {
                    logger.warn("Force merge failed for shard {}", shard.shardId(), e);
                    continue;
                }

                // After merge: clear sidecar state for merged fields
                for (String field : fieldsToMerge) {
                    for (String segment : new ArrayList<>(registry.getSegmentsForField(field))) {
                        registry.unregister(field, segment);
                    }
                    mergedCount++;
                }
            }

            long took = System.currentTimeMillis() - startTime;
            listener.onResponse(new UpdatableFieldsMergeResponse(mergedCount, took, true));
        } catch (Exception e) {
            listener.onFailure(e);
        }
    }
}
