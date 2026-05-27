/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.action.admin.indices.updatablefields;

import org.opensearch.action.ActionType;

/**
 * Action for retrieving per-field sidecar statistics for an index.
 *
 * @opensearch.experimental
 */
public class UpdatableFieldsStatsAction extends ActionType<UpdatableFieldsStatsResponse> {
    public static final UpdatableFieldsStatsAction INSTANCE = new UpdatableFieldsStatsAction();
    public static final String NAME = "indices:monitor/updatable_fields/stats";

    private UpdatableFieldsStatsAction() {
        super(NAME, UpdatableFieldsStatsResponse::new);
    }
}
