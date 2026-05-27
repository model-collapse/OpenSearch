/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.index.engine.sidecar;

import org.opensearch.index.engine.dataformat.FieldTypeCapabilities;
import org.opensearch.test.OpenSearchTestCase;

import java.util.Set;

public class InvertedIndexSidecarDataFormatTests extends OpenSearchTestCase {

    public void testName() {
        InvertedIndexSidecarDataFormat format = new InvertedIndexSidecarDataFormat();
        assertEquals("inverted_index_sidecar", format.name());
    }

    public void testSupportsFullTextSearch() {
        InvertedIndexSidecarDataFormat format = new InvertedIndexSidecarDataFormat();
        Set<FieldTypeCapabilities> supported = format.supportedFields();
        assertEquals(1, supported.size());
        FieldTypeCapabilities ftc = supported.iterator().next();
        assertEquals("text", ftc.fieldType());
        assertTrue(ftc.capabilities().contains(FieldTypeCapabilities.Capability.FULL_TEXT_SEARCH));
    }

    public void testPriorityIsLowerThanKnn() {
        InvertedIndexSidecarDataFormat format = new InvertedIndexSidecarDataFormat();
        KnnVectorSidecarDataFormat knnFormat = new KnnVectorSidecarDataFormat();
        assertTrue(format.priority() > knnFormat.priority());
    }

    public void testEqualityByName() {
        InvertedIndexSidecarDataFormat format1 = new InvertedIndexSidecarDataFormat();
        InvertedIndexSidecarDataFormat format2 = new InvertedIndexSidecarDataFormat();
        assertEquals(format1, format2);
        assertEquals(format1.hashCode(), format2.hashCode());
    }
}
