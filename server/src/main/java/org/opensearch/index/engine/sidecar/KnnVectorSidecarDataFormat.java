/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.index.engine.sidecar;

import org.opensearch.common.annotation.ExperimentalApi;
import org.opensearch.index.engine.dataformat.DataFormat;
import org.opensearch.index.engine.dataformat.FieldTypeCapabilities;

import java.util.Set;

/**
 * A sidecar data format for knn_vector fields that declares VECTOR_SEARCH capability.
 * This enables the DataFormatAwareEngine to route vector-only updates to the sidecar writer.
 *
 * @opensearch.experimental
 */
@ExperimentalApi
public class KnnVectorSidecarDataFormat extends DataFormat {

    public static final String NAME = "knn_vector_sidecar";

    private static final Set<FieldTypeCapabilities> SUPPORTED_FIELDS = Set.of(
        new FieldTypeCapabilities("knn_vector", Set.of(FieldTypeCapabilities.Capability.VECTOR_SEARCH))
    );

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public long priority() {
        return 100;
    }

    @Override
    public Set<FieldTypeCapabilities> supportedFields() {
        return SUPPORTED_FIELDS;
    }
}
