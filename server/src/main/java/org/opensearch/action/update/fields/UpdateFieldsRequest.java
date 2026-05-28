/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.action.update.fields;

import org.opensearch.action.ActionRequest;
import org.opensearch.action.ActionRequestValidationException;
import org.opensearch.core.common.io.stream.StreamInput;
import org.opensearch.core.common.io.stream.StreamOutput;
import org.opensearch.core.common.io.stream.Writeable;
import org.opensearch.core.xcontent.XContentParser;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.opensearch.action.ValidateActions.addValidationError;

/**
 * Request for updating sidecar field values for multiple documents in an index.
 * <p>
 * The request body has the form:
 * <pre>
 * {
 *   "field": "embedding",
 *   "updates": [
 *     { "_id": "doc1", "value": [0.1, 0.2, ...] },
 *     { "_id": "doc2", "value": [0.3, 0.4, ...] }
 *   ]
 * }
 * </pre>
 *
 * @opensearch.internal
 */
public class UpdateFieldsRequest extends ActionRequest {

    private String index;
    private String field;
    private List<FieldUpdate> updates;

    public UpdateFieldsRequest() {
        this.updates = new ArrayList<>();
    }

    public UpdateFieldsRequest(String index, String field, List<FieldUpdate> updates) {
        this.index = index;
        this.field = field;
        this.updates = updates != null ? new ArrayList<>(updates) : new ArrayList<>();
    }

    public UpdateFieldsRequest(StreamInput in) throws IOException {
        super(in);
        this.index = in.readString();
        this.field = in.readString();
        int size = in.readVInt();
        this.updates = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            updates.add(new FieldUpdate(in));
        }
    }

    @Override
    public void writeTo(StreamOutput out) throws IOException {
        super.writeTo(out);
        out.writeString(index);
        out.writeString(field);
        out.writeVInt(updates.size());
        for (FieldUpdate update : updates) {
            update.writeTo(out);
        }
    }

    @Override
    public ActionRequestValidationException validate() {
        ActionRequestValidationException validationException = null;
        if (index == null || index.isEmpty()) {
            validationException = addValidationError("index is missing", validationException);
        }
        if (field == null || field.isEmpty()) {
            validationException = addValidationError("field is missing", validationException);
        }
        if (field != null && (field.contains("/") || field.contains("\\") || field.contains("..") || field.contains("\0"))) {
            validationException = addValidationError("field name must not contain path separators or '..'", validationException);
        }
        if (updates == null || updates.isEmpty()) {
            validationException = addValidationError("updates are missing", validationException);
        }
        return validationException;
    }

    public String getIndex() {
        return index;
    }

    public void setIndex(String index) {
        this.index = index;
    }

    public String getField() {
        return field;
    }

    public void setField(String field) {
        this.field = field;
    }

    public List<FieldUpdate> getUpdates() {
        return Collections.unmodifiableList(updates);
    }

    public void setUpdates(List<FieldUpdate> updates) {
        this.updates = updates != null ? new ArrayList<>(updates) : new ArrayList<>();
    }

    /**
     * Parse the request body from XContent.
     */
    public void fromXContent(XContentParser parser) throws IOException {
        XContentParser.Token token = parser.nextToken();
        if (token != XContentParser.Token.START_OBJECT) {
            throw new IllegalArgumentException("Expected START_OBJECT but got " + token);
        }

        String currentFieldName = null;
        while ((token = parser.nextToken()) != XContentParser.Token.END_OBJECT) {
            if (token == XContentParser.Token.FIELD_NAME) {
                currentFieldName = parser.currentName();
            } else if ("field".equals(currentFieldName)) {
                if (token == XContentParser.Token.VALUE_STRING) {
                    this.field = parser.text();
                } else {
                    throw new IllegalArgumentException("Expected string value for 'field' but got " + token);
                }
            } else if ("updates".equals(currentFieldName)) {
                if (token == XContentParser.Token.START_ARRAY) {
                    parseUpdatesArray(parser);
                } else {
                    throw new IllegalArgumentException("Expected array for 'updates' but got " + token);
                }
            } else {
                throw new IllegalArgumentException("Unknown parameter [" + currentFieldName + "] in request body");
            }
        }
    }

    private void parseUpdatesArray(XContentParser parser) throws IOException {
        XContentParser.Token token;
        while ((token = parser.nextToken()) != XContentParser.Token.END_ARRAY) {
            if (token == XContentParser.Token.START_OBJECT) {
                String id = null;
                float[] value = null;
                Object scalarValue = null;
                String innerField = null;
                while ((token = parser.nextToken()) != XContentParser.Token.END_OBJECT) {
                    if (token == XContentParser.Token.FIELD_NAME) {
                        innerField = parser.currentName();
                    } else if ("_id".equals(innerField)) {
                        if (token == XContentParser.Token.VALUE_STRING) {
                            id = parser.text();
                        } else {
                            throw new IllegalArgumentException("Expected string for '_id' but got " + token);
                        }
                    } else if ("value".equals(innerField)) {
                        if (token == XContentParser.Token.START_ARRAY) {
                            List<Float> floats = new ArrayList<>();
                            while (parser.nextToken() != XContentParser.Token.END_ARRAY) {
                                floats.add(parser.floatValue());
                            }
                            value = new float[floats.size()];
                            for (int i = 0; i < floats.size(); i++) {
                                value[i] = floats.get(i);
                            }
                        } else if (token == XContentParser.Token.VALUE_STRING) {
                            scalarValue = parser.text();
                        } else if (token == XContentParser.Token.VALUE_NUMBER) {
                            scalarValue = parser.numberValue();
                        } else if (token == XContentParser.Token.VALUE_BOOLEAN) {
                            scalarValue = parser.booleanValue();
                        } else {
                            throw new IllegalArgumentException("Expected array, string, number, or boolean for 'value' but got " + token);
                        }
                    } else {
                        throw new IllegalArgumentException("Unknown parameter [" + innerField + "] in update entry");
                    }
                }
                if (id == null || id.isEmpty()) {
                    throw new IllegalArgumentException("_id is required in each update entry");
                }
                if (value != null) {
                    if (value.length == 0) {
                        throw new IllegalArgumentException("value is required in each update entry");
                    }
                    updates.add(new FieldUpdate(id, value));
                } else if (scalarValue != null) {
                    updates.add(new FieldUpdate(id, scalarValue));
                } else {
                    throw new IllegalArgumentException("value is required in each update entry");
                }
            } else {
                throw new IllegalArgumentException("Expected object in updates array but got " + token);
            }
        }
    }

    /**
     * A single field update for a document, containing the document ID and either a vector value
     * (float array) or a scalar value (String, Long, Double, Boolean) for doc values updates.
     *
     * @opensearch.internal
     */
    public static class FieldUpdate implements Writeable {
        private final String id;
        private final float[] value;
        private final Object scalarValue; // String, Long, Double, Boolean, or null

        /**
         * Constructor for vector updates.
         */
        public FieldUpdate(String id, float[] value) {
            this.id = id;
            this.value = value;
            this.scalarValue = null;
        }

        /**
         * Constructor for scalar (doc values) updates.
         * Supported types: String, Long, Double, Boolean.
         */
        public FieldUpdate(String id, Object scalarValue) {
            this.id = id;
            this.value = null;
            this.scalarValue = scalarValue;
        }

        public FieldUpdate(StreamInput in) throws IOException {
            this.id = in.readString();
            boolean hasVector = in.readBoolean();
            if (hasVector) {
                this.value = in.readFloatArray();
            } else {
                this.value = null;
            }
            boolean hasScalar = in.readBoolean();
            if (hasScalar) {
                this.scalarValue = in.readGenericValue();
            } else {
                this.scalarValue = null;
            }
        }

        @Override
        public void writeTo(StreamOutput out) throws IOException {
            out.writeString(id);
            out.writeBoolean(value != null);
            if (value != null) {
                out.writeFloatArray(value);
            }
            out.writeBoolean(scalarValue != null);
            if (scalarValue != null) {
                out.writeGenericValue(scalarValue);
            }
        }

        public String getId() {
            return id;
        }

        public float[] getValue() {
            return value;
        }

        public Object getScalarValue() {
            return scalarValue;
        }

        public boolean isVectorUpdate() {
            return value != null;
        }

        public boolean isScalarUpdate() {
            return scalarValue != null;
        }
    }
}
