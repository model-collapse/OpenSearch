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
import org.opensearch.common.inject.Inject;
import org.opensearch.core.action.ActionListener;
import org.opensearch.tasks.Task;
import org.opensearch.transport.TransportService;

/**
 * Transport action for the _update_fields endpoint.
 * <p>
 * This is currently a skeleton implementation that acknowledges the request
 * and returns a successful response with the count of updates. Full sidecar
 * engine wiring will be added in a subsequent task.
 *
 * @opensearch.internal
 */
public class TransportUpdateFieldsAction extends HandledTransportAction<UpdateFieldsRequest, UpdateFieldsResponse> {

    @Inject
    public TransportUpdateFieldsAction(TransportService transportService, ActionFilters actionFilters) {
        super(UpdateFieldsAction.NAME, transportService, actionFilters, UpdateFieldsRequest::new);
    }

    @Override
    protected void doExecute(Task task, UpdateFieldsRequest request, ActionListener<UpdateFieldsResponse> listener) {
        long startTime = System.currentTimeMillis();
        int updateCount = request.getUpdates().size();

        // Skeleton: acknowledge without writing sidecars. Full engine wiring in next task.
        long took = System.currentTimeMillis() - startTime;
        listener.onResponse(new UpdateFieldsResponse(took, updateCount, 0, 0));
    }
}
