/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.rest.action.document;

import org.opensearch.rest.RestRequest;
import org.opensearch.test.rest.RestActionTestCase;
import org.junit.Before;

import java.util.List;

/**
 * Tests for {@link RestUpdateFieldsAction} route registration and metadata.
 */
public class RestUpdateFieldsActionTests extends RestActionTestCase {

    private RestUpdateFieldsAction action;

    @Before
    public void setUpAction() {
        action = new RestUpdateFieldsAction();
        controller().registerHandler(action);
    }

    public void testGetName() {
        assertEquals("update_fields_action", action.getName());
    }

    public void testRoutes() {
        List<RestUpdateFieldsAction.Route> routes = action.routes();
        assertEquals(1, routes.size());
        assertEquals(RestRequest.Method.POST, routes.get(0).getMethod());
        assertEquals("/{index}/_update_fields", routes.get(0).getPath());
    }
}
