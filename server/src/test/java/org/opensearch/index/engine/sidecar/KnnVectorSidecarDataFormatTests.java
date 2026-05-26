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

public class KnnVectorSidecarDataFormatTests extends OpenSearchTestCase {

    public void testName() {
        KnnVectorSidecarDataFormat format = new KnnVectorSidecarDataFormat();
        assertEquals("knn_vector_sidecar", format.name());
    }

    public void testSupportsVectorSearch() {
        KnnVectorSidecarDataFormat format = new KnnVectorSidecarDataFormat();
        Set<FieldTypeCapabilities> supported = format.supportedFields();
        assertEquals(1, supported.size());
        FieldTypeCapabilities ftc = supported.iterator().next();
        assertEquals("knn_vector", ftc.fieldType());
        assertTrue(ftc.capabilities().contains(FieldTypeCapabilities.Capability.VECTOR_SEARCH));
    }

    public void testPriorityIsSecondary() {
        KnnVectorSidecarDataFormat format = new KnnVectorSidecarDataFormat();
        assertTrue("Sidecar priority should be > 0 (secondary to primary Lucene format)", format.priority() > 0);
    }

    public void testEqualityByName() {
        KnnVectorSidecarDataFormat format1 = new KnnVectorSidecarDataFormat();
        KnnVectorSidecarDataFormat format2 = new KnnVectorSidecarDataFormat();
        assertEquals(format1, format2);
        assertEquals(format1.hashCode(), format2.hashCode());
    }
}
