/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.index.reindex;

import org.opensearch.action.ActionType;

/**
 * Action type for update fields by query
 *
 * @opensearch.internal
 */
public class UpdateFieldsByQueryAction extends ActionType<BulkByScrollResponse> {

    public static final UpdateFieldsByQueryAction INSTANCE = new UpdateFieldsByQueryAction();
    public static final String NAME = "indices:data/write/update/fields/byquery";

    private UpdateFieldsByQueryAction() {
        super(NAME, BulkByScrollResponse::new);
    }
}
