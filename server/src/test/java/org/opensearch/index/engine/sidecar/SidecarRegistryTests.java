/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.index.engine.sidecar;

import org.opensearch.test.OpenSearchTestCase;

import java.util.Map;

public class SidecarRegistryTests extends OpenSearchTestCase {

    public void testRegisterAndIsDirty() {
        SidecarRegistry registry = new SidecarRegistry();
        SidecarVersionBitmap bitmap = new SidecarVersionBitmap(100);
        bitmap.set(5);
        bitmap.set(42);

        registry.register("embedding", "_0", bitmap);

        assertTrue(registry.isDirty("embedding", "_0", 5));
        assertTrue(registry.isDirty("embedding", "_0", 42));
        assertFalse(registry.isDirty("embedding", "_0", 10));
    }

    public void testNotDirtyWhenEmpty() {
        SidecarRegistry registry = new SidecarRegistry();

        assertFalse(registry.isDirty("embedding", "_0", 0));
        assertFalse(registry.isDirty("embedding", "_0", 5));
        assertFalse(registry.hasAnySidecars());
    }

    public void testUnregister() {
        SidecarRegistry registry = new SidecarRegistry();
        SidecarVersionBitmap bitmap = new SidecarVersionBitmap(100);
        bitmap.set(7);

        registry.register("embedding", "_0", bitmap);
        assertTrue(registry.isDirty("embedding", "_0", 7));
        assertTrue(registry.hasAnySidecars());

        registry.unregister("embedding", "_0");
        assertFalse(registry.isDirty("embedding", "_0", 7));
        assertFalse(registry.hasAnySidecars());
    }

    public void testMultipleFieldsIndependent() {
        SidecarRegistry registry = new SidecarRegistry();

        SidecarVersionBitmap embeddingBitmap = new SidecarVersionBitmap(100);
        embeddingBitmap.set(3);

        SidecarVersionBitmap tagsBitmap = new SidecarVersionBitmap(100);
        tagsBitmap.set(7);

        registry.register("embedding", "_0", embeddingBitmap);
        registry.register("tags", "_0", tagsBitmap);

        assertTrue(registry.isDirty("embedding", "_0", 3));
        assertFalse(registry.isDirty("embedding", "_0", 7));

        assertTrue(registry.isDirty("tags", "_0", 7));
        assertFalse(registry.isDirty("tags", "_0", 3));
    }

    public void testMultipleSegmentsIndependent() {
        SidecarRegistry registry = new SidecarRegistry();

        SidecarVersionBitmap bitmap0 = new SidecarVersionBitmap(100);
        bitmap0.set(5);

        SidecarVersionBitmap bitmap1 = new SidecarVersionBitmap(200);
        bitmap1.set(150);

        registry.register("embedding", "_0", bitmap0);
        registry.register("embedding", "_1", bitmap1);

        assertTrue(registry.isDirty("embedding", "_0", 5));
        assertFalse(registry.isDirty("embedding", "_0", 150));

        assertTrue(registry.isDirty("embedding", "_1", 150));
        assertFalse(registry.isDirty("embedding", "_1", 5));
    }

    public void testGetSidecarValuesWhenDirty() {
        SidecarRegistry registry = new SidecarRegistry();
        SidecarVersionBitmap bitmap = new SidecarVersionBitmap(100);
        bitmap.set(10);

        registry.register("embedding", "_0", bitmap);

        // Phase 1: getSidecarValues always returns empty map (no _source overlay).
        // Actual sidecar values are read via DocValues/vectors at search time.
        Map<String, Object> values = registry.getSidecarValues("embedding", "_0", 10);
        assertTrue(values.isEmpty());
    }

    public void testGetSidecarValuesWhenClean() {
        SidecarRegistry registry = new SidecarRegistry();
        SidecarVersionBitmap bitmap = new SidecarVersionBitmap(100);
        bitmap.set(10);

        registry.register("embedding", "_0", bitmap);

        Map<String, Object> values = registry.getSidecarValues("embedding", "_0", 20);
        assertTrue(values.isEmpty());
    }

    public void testGetUpdatableFields() {
        SidecarRegistry registry = new SidecarRegistry();

        assertTrue(registry.getUpdatableFields().isEmpty());

        SidecarVersionBitmap bitmap = new SidecarVersionBitmap(100);
        bitmap.set(1);
        registry.register("embedding", "_0", bitmap);
        registry.register("tags", "_1", bitmap);

        assertEquals(2, registry.getUpdatableFields().size());
        assertTrue(registry.getUpdatableFields().contains("embedding"));
        assertTrue(registry.getUpdatableFields().contains("tags"));
    }

    public void testDocIdBeyondMaxDocIsNotDirty() {
        SidecarRegistry registry = new SidecarRegistry();
        SidecarVersionBitmap bitmap = new SidecarVersionBitmap(10);
        bitmap.set(5);

        registry.register("embedding", "_0", bitmap);

        // docId beyond maxDoc should not be dirty
        assertFalse(registry.isDirty("embedding", "_0", 50));
    }

    public void testUnregisterNonExistentFieldIsNoOp() {
        SidecarRegistry registry = new SidecarRegistry();
        // Should not throw
        registry.unregister("nonexistent", "_0");
        assertFalse(registry.hasAnySidecars());
    }

    public void testRegisterOverwritesBitmap() {
        SidecarRegistry registry = new SidecarRegistry();

        SidecarVersionBitmap bitmap1 = new SidecarVersionBitmap(100);
        bitmap1.set(5);

        SidecarVersionBitmap bitmap2 = new SidecarVersionBitmap(100);
        bitmap2.set(10);

        registry.register("embedding", "_0", bitmap1);
        assertTrue(registry.isDirty("embedding", "_0", 5));
        assertFalse(registry.isDirty("embedding", "_0", 10));

        registry.register("embedding", "_0", bitmap2);
        assertFalse(registry.isDirty("embedding", "_0", 5));
        assertTrue(registry.isDirty("embedding", "_0", 10));
    }
}
