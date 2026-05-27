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
 * A sidecar data format for text fields that declares FULL_TEXT_SEARCH capability.
 * This enables the DataFormatAwareEngine to route text re-analysis updates to the sidecar writer.
 *
 * @opensearch.experimental
 */
@ExperimentalApi
public class InvertedIndexSidecarDataFormat extends DataFormat {

    public static final String NAME = "inverted_index_sidecar";

    private static final Set<FieldTypeCapabilities> SUPPORTED_FIELDS = Set.of(
        new FieldTypeCapabilities("text", Set.of(FieldTypeCapabilities.Capability.FULL_TEXT_SEARCH))
    );

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public long priority() {
        return 200; // lower precedence than KNN (100) and primary Lucene (0)
    }

    @Override
    public Set<FieldTypeCapabilities> supportedFields() {
        return SUPPORTED_FIELDS;
    }
}
