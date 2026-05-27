/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.action.admin.indices.updatablefields;

import org.opensearch.action.ActionRequest;
import org.opensearch.action.ActionRequestValidationException;
import org.opensearch.core.common.io.stream.StreamInput;
import org.opensearch.core.common.io.stream.StreamOutput;

import java.io.IOException;
import java.util.List;

/**
 * Request to force-merge sidecar generations for updatable fields.
 *
 * @opensearch.experimental
 */
public class UpdatableFieldsMergeRequest extends ActionRequest {
    private String index;
    private List<String> fields; // null = all fields

    public UpdatableFieldsMergeRequest() {}

    public UpdatableFieldsMergeRequest(String index) {
        this.index = index;
    }

    public UpdatableFieldsMergeRequest(StreamInput in) throws IOException {
        super(in);
        this.index = in.readString();
        this.fields = in.readOptionalStringList();
    }

    @Override
    public void writeTo(StreamOutput out) throws IOException {
        super.writeTo(out);
        out.writeString(index);
        if (fields != null) {
            out.writeBoolean(true);
            out.writeStringCollection(fields);
        } else {
            out.writeBoolean(false);
        }
    }

    @Override
    public ActionRequestValidationException validate() {
        return null;
    }

    public String index() {
        return index;
    }

    public UpdatableFieldsMergeRequest index(String index) {
        this.index = index;
        return this;
    }

    public List<String> fields() {
        return fields;
    }

    public UpdatableFieldsMergeRequest fields(List<String> fields) {
        this.fields = fields;
        return this;
    }
}
