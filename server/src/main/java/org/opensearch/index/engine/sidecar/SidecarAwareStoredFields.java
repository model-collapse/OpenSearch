/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.index.engine.sidecar;

import org.apache.lucene.index.DocValuesSkipIndexType;
import org.apache.lucene.index.DocValuesType;
import org.apache.lucene.index.FieldInfo;
import org.apache.lucene.index.IndexOptions;
import org.apache.lucene.index.StoredFieldVisitor;
import org.apache.lucene.index.StoredFields;
import org.apache.lucene.index.VectorEncoding;
import org.apache.lucene.index.VectorSimilarityFunction;
import org.opensearch.common.xcontent.XContentFactory;
import org.opensearch.common.xcontent.XContentHelper;
import org.opensearch.common.xcontent.XContentType;
import org.opensearch.core.common.bytes.BytesArray;
import org.opensearch.core.common.bytes.BytesReference;
import org.opensearch.index.mapper.SourceFieldMapper;

import java.io.IOException;
import java.util.Collections;
import java.util.Map;
import java.util.function.Function;

/**
 * A {@link StoredFields} implementation that intercepts reads of the {@code _source} field
 * and overlays sidecar field values for dirty documents. Clean documents (where the sidecar
 * provider returns null or an empty map) pass through with zero overhead beyond a null check.
 *
 * @opensearch.internal
 */
public class SidecarAwareStoredFields extends StoredFields {

    static final FieldInfo FAKE_SOURCE_FIELD = new FieldInfo(
        SourceFieldMapper.NAME,
        1,
        false,
        false,
        false,
        IndexOptions.NONE,
        DocValuesType.NONE,
        DocValuesSkipIndexType.NONE,
        -1,
        Collections.emptyMap(),
        0,
        0,
        0,
        0,
        VectorEncoding.FLOAT32,
        VectorSimilarityFunction.EUCLIDEAN,
        false,
        false
    );

    private final StoredFields delegate;
    private final Function<Integer, Map<String, Object>> sidecarProvider;

    /**
     * Creates a new SidecarAwareStoredFields.
     *
     * @param delegate the delegate StoredFields
     * @param sidecarProvider function that returns the sidecar field overlay for a given docId,
     *                        or null/empty if the doc is clean
     */
    public SidecarAwareStoredFields(StoredFields delegate, Function<Integer, Map<String, Object>> sidecarProvider) {
        this.delegate = delegate;
        this.sidecarProvider = sidecarProvider;
    }

    @Override
    public void document(int docId, StoredFieldVisitor visitor) throws IOException {
        Map<String, Object> sidecarValues = sidecarProvider.apply(docId);
        if (sidecarValues == null || sidecarValues.isEmpty()) {
            // Clean doc — pass through unchanged with no overhead
            delegate.document(docId, visitor);
            return;
        }

        // Dirty doc — need to intercept _source and overlay sidecar values
        if (visitor.needsField(FAKE_SOURCE_FIELD) == StoredFieldVisitor.Status.YES) {
            // Use a capturing visitor to grab the raw _source bytes
            SourceCapturingVisitor capturingVisitor = new SourceCapturingVisitor(visitor);
            delegate.document(docId, capturingVisitor);

            if (capturingVisitor.sourceBytes != null) {
                // Deserialize _source, overlay sidecar values, re-serialize
                byte[] patchedBytes = overlaySource(capturingVisitor.sourceBytes, sidecarValues);
                visitor.binaryField(FAKE_SOURCE_FIELD, patchedBytes);
            }
        } else {
            // _source not requested — pass through unchanged
            delegate.document(docId, visitor);
        }
    }

    /**
     * Overlays sidecar field values into the given _source bytes.
     */
    static byte[] overlaySource(byte[] sourceBytes, Map<String, Object> sidecarValues) throws IOException {
        BytesReference bytesRef = new BytesArray(sourceBytes);
        Map<String, Object> sourceMap = XContentHelper.convertToMap(bytesRef, false, XContentType.JSON).v2();
        sourceMap.putAll(sidecarValues);
        BytesReference patched = BytesReference.bytes(XContentFactory.jsonBuilder().map(sourceMap));
        return BytesReference.toBytes(patched);
    }

    /**
     * A StoredFieldVisitor that wraps another visitor but captures _source bytes
     * instead of passing them through.
     */
    private static class SourceCapturingVisitor extends StoredFieldVisitor {
        private final StoredFieldVisitor delegate;
        byte[] sourceBytes;

        SourceCapturingVisitor(StoredFieldVisitor delegate) {
            this.delegate = delegate;
        }

        @Override
        public Status needsField(FieldInfo fieldInfo) throws IOException {
            if (SourceFieldMapper.NAME.equals(fieldInfo.name)) {
                // We capture _source ourselves
                return Status.YES;
            }
            return delegate.needsField(fieldInfo);
        }

        @Override
        public void binaryField(FieldInfo fieldInfo, byte[] value) throws IOException {
            if (SourceFieldMapper.NAME.equals(fieldInfo.name)) {
                sourceBytes = value;
            } else {
                delegate.binaryField(fieldInfo, value);
            }
        }

        @Override
        public void stringField(FieldInfo fieldInfo, String value) throws IOException {
            delegate.stringField(fieldInfo, value);
        }

        @Override
        public void intField(FieldInfo fieldInfo, int value) throws IOException {
            delegate.intField(fieldInfo, value);
        }

        @Override
        public void longField(FieldInfo fieldInfo, long value) throws IOException {
            delegate.longField(fieldInfo, value);
        }

        @Override
        public void floatField(FieldInfo fieldInfo, float value) throws IOException {
            delegate.floatField(fieldInfo, value);
        }

        @Override
        public void doubleField(FieldInfo fieldInfo, double value) throws IOException {
            delegate.doubleField(fieldInfo, value);
        }
    }
}
