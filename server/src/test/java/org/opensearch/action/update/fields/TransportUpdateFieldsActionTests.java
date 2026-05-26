/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.action.update.fields;

import org.opensearch.Version;
import org.opensearch.action.support.ActionFilters;
import org.opensearch.cluster.ClusterState;
import org.opensearch.cluster.metadata.Metadata;
import org.opensearch.cluster.service.ClusterService;
import org.opensearch.common.settings.Settings;
import org.opensearch.core.action.ActionListener;
import org.opensearch.index.IndexNotFoundException;
import org.opensearch.tasks.Task;
import org.opensearch.telemetry.tracing.noop.NoopTracer;
import org.opensearch.test.OpenSearchTestCase;
import org.opensearch.test.transport.MockTransportService;
import org.opensearch.threadpool.TestThreadPool;
import org.opensearch.threadpool.ThreadPool;
import org.opensearch.transport.TransportService;
import org.opensearch.transport.client.node.NodeClient;

import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link TransportUpdateFieldsAction} basic behavior.
 */
public class TransportUpdateFieldsActionTests extends OpenSearchTestCase {

    private TestThreadPool threadPool;
    private TransportService transportService;
    private ClusterService clusterService;
    private TransportUpdateFieldsAction action;

    @Override
    public void setUp() throws Exception {
        super.setUp();
        threadPool = new TestThreadPool(getClass().getName());
        transportService = MockTransportService.createNewService(Settings.EMPTY, Version.CURRENT, threadPool, NoopTracer.INSTANCE);
        clusterService = mock(ClusterService.class);
        ActionFilters actionFilters = mock(ActionFilters.class);
        NodeClient client = mock(NodeClient.class);

        action = new TransportUpdateFieldsAction(transportService, actionFilters, clusterService, client);
    }

    @Override
    public void tearDown() throws Exception {
        super.tearDown();
        ThreadPool.terminate(threadPool, 30, TimeUnit.SECONDS);
    }

    public void testActionName() {
        assertEquals("indices:data/write/update/fields", UpdateFieldsAction.NAME);
    }

    public void testIndexNotFoundReturnsFailure() throws Exception {
        ClusterState state = mock(ClusterState.class);
        Metadata metadata = mock(Metadata.class);
        when(clusterService.state()).thenReturn(state);
        when(state.metadata()).thenReturn(metadata);
        when(metadata.index("nonexistent-index")).thenReturn(null);

        UpdateFieldsRequest request = new UpdateFieldsRequest(
            "nonexistent-index",
            "embedding",
            Arrays.asList(new UpdateFieldsRequest.FieldUpdate("doc1", new float[] { 1.0f }))
        );

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Exception> failure = new AtomicReference<>();

        action.doExecute(mock(Task.class), request, new ActionListener<UpdateFieldsResponse>() {
            @Override
            public void onResponse(UpdateFieldsResponse response) {
                latch.countDown();
            }

            @Override
            public void onFailure(Exception e) {
                failure.set(e);
                latch.countDown();
            }
        });

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertNotNull(failure.get());
        assertTrue(failure.get() instanceof IndexNotFoundException);
    }

    public void testEmptyUpdatesFailsValidation() {
        UpdateFieldsRequest request = new UpdateFieldsRequest("my-index", "embedding", Collections.emptyList());
        assertNotNull(request.validate());
        assertTrue(request.validate().getMessage().contains("updates are missing"));
    }
}
