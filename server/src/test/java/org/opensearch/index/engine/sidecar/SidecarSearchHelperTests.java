/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.index.engine.sidecar;

import org.apache.lucene.search.Query;
import org.opensearch.test.OpenSearchTestCase;

public class SidecarSearchHelperTests extends OpenSearchTestCase {

    public void testNoFilterWhenNoSidecars() {
        SidecarRegistry registry = new SidecarRegistry();
        SidecarSearchHelper helper = new SidecarSearchHelper(registry);
        assertNull(helper.getExclusionFilter("embedding", "_0", 1000));
    }

    public void testReturnsFilterWhenSidecarsActive() {
        SidecarRegistry registry = new SidecarRegistry();
        SidecarVersionBitmap bitmap = new SidecarVersionBitmap(1000);
        bitmap.set(5);
        bitmap.set(10);
        registry.register("embedding", "_0", bitmap);

        SidecarSearchHelper helper = new SidecarSearchHelper(registry);
        Query filter = helper.getExclusionFilter("embedding", "_0", 1000);
        assertNotNull(filter);
        assertTrue(filter instanceof SidecarKnnFilter);
    }

    public void testNoFilterWhenBitmapEmpty() {
        SidecarRegistry registry = new SidecarRegistry();
        SidecarVersionBitmap bitmap = new SidecarVersionBitmap(1000);
        // No bits set
        registry.register("embedding", "_0", bitmap);

        SidecarSearchHelper helper = new SidecarSearchHelper(registry);
        assertNull(helper.getExclusionFilter("embedding", "_0", 1000));
    }

    public void testNoFilterForUnregisteredField() {
        SidecarRegistry registry = new SidecarRegistry();
        SidecarVersionBitmap bitmap = new SidecarVersionBitmap(1000);
        bitmap.set(5);
        registry.register("embedding", "_0", bitmap);

        SidecarSearchHelper helper = new SidecarSearchHelper(registry);
        assertNull(helper.getExclusionFilter("other_field", "_0", 1000));
    }

    public void testNoFilterForUnregisteredSegment() {
        SidecarRegistry registry = new SidecarRegistry();
        SidecarVersionBitmap bitmap = new SidecarVersionBitmap(1000);
        bitmap.set(5);
        registry.register("embedding", "_0", bitmap);

        SidecarSearchHelper helper = new SidecarSearchHelper(registry);
        assertNull(helper.getExclusionFilter("embedding", "_1", 1000));
    }

    public void testHasActiveSidecars() {
        SidecarRegistry registry = new SidecarRegistry();
        SidecarSearchHelper helper = new SidecarSearchHelper(registry);
        assertFalse(helper.hasActiveSidecars("embedding"));

        registry.register("embedding", "_0", new SidecarVersionBitmap(100));
        assertTrue(helper.hasActiveSidecars("embedding"));
    }

    public void testHasActiveSidecarsReturnsFalseForOtherField() {
        SidecarRegistry registry = new SidecarRegistry();
        SidecarVersionBitmap bitmap = new SidecarVersionBitmap(100);
        bitmap.set(1);
        registry.register("embedding", "_0", bitmap);

        SidecarSearchHelper helper = new SidecarSearchHelper(registry);
        assertFalse(helper.hasActiveSidecars("other_field"));
    }
}
