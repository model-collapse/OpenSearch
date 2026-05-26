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
 * Action type for the shard-level _update_fields operation.
 *
 * @opensearch.internal
 */
public class ShardUpdateFieldsAction extends ActionType<ShardUpdateFieldsResponse> {

    public static final ShardUpdateFieldsAction INSTANCE = new ShardUpdateFieldsAction();
    public static final String NAME = "indices:data/write/update/fields[s]";

    private ShardUpdateFieldsAction() {
        super(NAME, ShardUpdateFieldsResponse::new);
    }
}
