/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.action.update.fields;

import org.opensearch.action.ActionType;

/**
 * Action type for the _update_fields endpoint, which performs batch
 * updates of sidecar field values (e.g. vectors) for documents in an index.
 *
 * @opensearch.internal
 */
public class UpdateFieldsAction extends ActionType<UpdateFieldsResponse> {

    public static final UpdateFieldsAction INSTANCE = new UpdateFieldsAction();
    public static final String NAME = "indices:data/write/update/fields";

    private UpdateFieldsAction() {
        super(NAME, UpdateFieldsResponse::new);
    }
}
