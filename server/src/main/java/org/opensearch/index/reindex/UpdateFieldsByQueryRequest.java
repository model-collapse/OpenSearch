/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.index.reindex;

import org.opensearch.action.search.SearchRequest;
import org.opensearch.core.common.io.stream.StreamInput;
import org.opensearch.core.common.io.stream.StreamOutput;
import org.opensearch.core.tasks.TaskId;

import java.io.IOException;
import java.util.List;

/**
 * Request for _update_fields_by_query — scrolls documents and updates
 * specific fields via sidecar writes (optionally running a pipeline).
 *
 * @opensearch.internal
 */
public class UpdateFieldsByQueryRequest extends AbstractBulkByScrollRequest<UpdateFieldsByQueryRequest> {

    private String field;
    private String pipeline;
    private List<String> sourceFields;

    public UpdateFieldsByQueryRequest() {
        this(new SearchRequest());
    }

    public UpdateFieldsByQueryRequest(SearchRequest searchRequest) {
        super(searchRequest, true);
    }

    public UpdateFieldsByQueryRequest(StreamInput in) throws IOException {
        super(in);
        this.field = in.readString();
        this.pipeline = in.readOptionalString();
        this.sourceFields = in.readOptionalStringList();
    }

    @Override
    public void writeTo(StreamOutput out) throws IOException {
        super.writeTo(out);
        out.writeString(field);
        out.writeOptionalString(pipeline);
        out.writeOptionalStringCollection(sourceFields);
    }

    @Override
    protected UpdateFieldsByQueryRequest self() {
        return this;
    }

    @Override
    public UpdateFieldsByQueryRequest forSlice(TaskId slicingTask, SearchRequest slice, int totalSlices) {
        UpdateFieldsByQueryRequest sliced = new UpdateFieldsByQueryRequest(slice);
        sliced.setField(field);
        sliced.setPipeline(pipeline);
        sliced.setSourceFields(sourceFields);
        return doForSlice(sliced, slicingTask, totalSlices);
    }

    @Override
    public String toString() {
        return "UpdateFieldsByQueryRequest{field=" + field + ", pipeline=" + pipeline + ", sourceFields=" + sourceFields + "}";
    }

    public String getField() {
        return field;
    }

    public UpdateFieldsByQueryRequest setField(String field) {
        this.field = field;
        return this;
    }

    public String getPipeline() {
        return pipeline;
    }

    public UpdateFieldsByQueryRequest setPipeline(String pipeline) {
        this.pipeline = pipeline;
        return this;
    }

    public List<String> getSourceFields() {
        return sourceFields;
    }

    public UpdateFieldsByQueryRequest setSourceFields(List<String> sourceFields) {
        this.sourceFields = sourceFields;
        return this;
    }
}
