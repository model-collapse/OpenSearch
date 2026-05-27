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
 * Transport action for force-merging sidecar generations into base segments.
 *
 * @opensearch.experimental
 */
public class UpdatableFieldsMergeAction extends ActionType<UpdatableFieldsMergeResponse> {
    public static final UpdatableFieldsMergeAction INSTANCE = new UpdatableFieldsMergeAction();
    public static final String NAME = "indices:admin/updatable_fields/merge";

    private UpdatableFieldsMergeAction() {
        super(NAME, UpdatableFieldsMergeResponse::new);
    }
}
