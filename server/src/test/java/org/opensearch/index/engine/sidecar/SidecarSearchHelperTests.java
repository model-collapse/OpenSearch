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

    public void testGetGlobalExclusionFilterNoSidecars() {
        SidecarRegistry registry = new SidecarRegistry();
        SidecarSearchHelper helper = new SidecarSearchHelper(registry);
        assertNull(helper.getGlobalExclusionFilter("embedding", 1000));
    }

    public void testGetGlobalExclusionFilterSingleSegment() {
        SidecarRegistry registry = new SidecarRegistry();
        SidecarVersionBitmap bitmap = new SidecarVersionBitmap(500);
        bitmap.set(3);
        bitmap.set(7);
        bitmap.set(99);
        registry.register("embedding", "_0", bitmap);

        SidecarSearchHelper helper = new SidecarSearchHelper(registry);
        Query filter = helper.getGlobalExclusionFilter("embedding", 1000);
        assertNotNull(filter);
        assertTrue(filter instanceof SidecarKnnFilter);
    }

    public void testGetGlobalExclusionFilterMultipleSegments() {
        SidecarRegistry registry = new SidecarRegistry();

        SidecarVersionBitmap bitmap0 = new SidecarVersionBitmap(500);
        bitmap0.set(5);
        bitmap0.set(10);
        registry.register("embedding", "_0", bitmap0);

        SidecarVersionBitmap bitmap1 = new SidecarVersionBitmap(800);
        bitmap1.set(500);
        bitmap1.set(750);
        registry.register("embedding", "_1", bitmap1);

        SidecarSearchHelper helper = new SidecarSearchHelper(registry);
        Query filter = helper.getGlobalExclusionFilter("embedding", 1000);
        assertNotNull(filter);
        assertTrue(filter instanceof SidecarKnnFilter);
    }

    public void testGetGlobalExclusionFilterEmptyBitmaps() {
        SidecarRegistry registry = new SidecarRegistry();
        // Register bitmaps with no bits set
        registry.register("embedding", "_0", new SidecarVersionBitmap(500));
        registry.register("embedding", "_1", new SidecarVersionBitmap(800));

        SidecarSearchHelper helper = new SidecarSearchHelper(registry);
        assertNull(helper.getGlobalExclusionFilter("embedding", 1000));
    }

    public void testGetGlobalExclusionFilterUnregisteredField() {
        SidecarRegistry registry = new SidecarRegistry();
        SidecarVersionBitmap bitmap = new SidecarVersionBitmap(500);
        bitmap.set(5);
        registry.register("embedding", "_0", bitmap);

        SidecarSearchHelper helper = new SidecarSearchHelper(registry);
        assertNull(helper.getGlobalExclusionFilter("other_field", 1000));
    }

    public void testGetGlobalExclusionFilterRespectsMaxDoc() {
        SidecarRegistry registry = new SidecarRegistry();
        SidecarVersionBitmap bitmap = new SidecarVersionBitmap(500);
        bitmap.set(5);
        bitmap.set(499); // This is within maxDoc of 500 but we pass globalMaxDoc=200
        registry.register("embedding", "_0", bitmap);

        SidecarSearchHelper helper = new SidecarSearchHelper(registry);
        // globalMaxDoc=200, so doc 499 should not be included in the global bitmap
        // but doc 5 should still cause a non-null filter
        Query filter = helper.getGlobalExclusionFilter("embedding", 200);
        assertNotNull(filter);
        assertTrue(filter instanceof SidecarKnnFilter);
    }
}
