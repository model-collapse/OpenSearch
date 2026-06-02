/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.index.engine.sidecar;

import org.opensearch.test.OpenSearchTestCase;

import java.nio.file.Files;
import java.nio.file.Path;

public class SidecarRegistryManifestTests extends OpenSearchTestCase {

    public void testSaveAndRestore() throws Exception {
        Path tmpDir = createTempDir();
        Path sidecarDir = tmpDir.resolve("_sidecar_emb_123");
        Files.createDirectories(sidecarDir);

        // Create registry with some state
        SidecarRegistry original = new SidecarRegistry();
        SidecarVersionBitmap bitmap = new SidecarVersionBitmap(1000);
        bitmap.set(5);
        bitmap.set(100);
        bitmap.set(999);
        original.register("embedding", "_0", bitmap, sidecarDir);

        // Save manifest
        SidecarRegistryManifest.save(original, tmpDir);
        assertTrue(Files.exists(tmpDir.resolve(SidecarRegistryManifest.MANIFEST_FILE)));

        // Restore into new registry
        SidecarRegistry restored = new SidecarRegistry();
        boolean found = SidecarRegistryManifest.restore(restored, tmpDir);
        assertTrue(found);
        assertTrue(restored.hasAnySidecars());
        assertTrue(restored.isDirty("embedding", "_0", 5));
        assertTrue(restored.isDirty("embedding", "_0", 100));
        assertTrue(restored.isDirty("embedding", "_0", 999));
        assertFalse(restored.isDirty("embedding", "_0", 6));
    }

    public void testRestoreWithNoManifest() throws Exception {
        Path tmpDir = createTempDir();
        SidecarRegistry registry = new SidecarRegistry();
        boolean found = SidecarRegistryManifest.restore(registry, tmpDir);
        assertFalse(found);
        assertFalse(registry.hasAnySidecars());
    }

    public void testSaveEmptyRegistry() throws Exception {
        Path tmpDir = createTempDir();
        SidecarRegistry registry = new SidecarRegistry();
        SidecarRegistryManifest.save(registry, tmpDir);
        assertTrue(Files.exists(tmpDir.resolve(SidecarRegistryManifest.MANIFEST_FILE)));

        SidecarRegistry restored = new SidecarRegistry();
        boolean found = SidecarRegistryManifest.restore(restored, tmpDir);
        assertFalse(found);
        assertFalse(restored.hasAnySidecars());
    }

    public void testMultipleFieldsAndSegments() throws Exception {
        Path tmpDir = createTempDir();
        Path sidecarDir1 = tmpDir.resolve("_sidecar_emb_1");
        Path sidecarDir2 = tmpDir.resolve("_sidecar_cat_2");
        Files.createDirectories(sidecarDir1);
        Files.createDirectories(sidecarDir2);

        SidecarRegistry original = new SidecarRegistry();
        SidecarVersionBitmap bm1 = new SidecarVersionBitmap(500);
        bm1.set(10);
        bm1.set(20);
        original.register("embedding", "_0", bm1, sidecarDir1);

        SidecarVersionBitmap bm2 = new SidecarVersionBitmap(500);
        bm2.set(30);
        original.register("category", "_1", bm2, sidecarDir2);

        SidecarRegistryManifest.save(original, tmpDir);

        SidecarRegistry restored = new SidecarRegistry();
        boolean found = SidecarRegistryManifest.restore(restored, tmpDir);
        assertTrue(found);
        assertTrue(restored.isDirty("embedding", "_0", 10));
        assertTrue(restored.isDirty("embedding", "_0", 20));
        assertTrue(restored.isDirty("category", "_1", 30));
        assertFalse(restored.isDirty("category", "_1", 10));
        assertEquals(1, restored.getSegmentCount("embedding"));
        assertEquals(1, restored.getSegmentCount("category"));
    }

    public void testDeleteManifest() throws Exception {
        Path tmpDir = createTempDir();
        Path sidecarDir = tmpDir.resolve("_sidecar_emb_del");
        Files.createDirectories(sidecarDir);

        SidecarRegistry registry = new SidecarRegistry();
        SidecarVersionBitmap bitmap = new SidecarVersionBitmap(100);
        bitmap.set(1);
        registry.register("field", "_0", bitmap, sidecarDir);

        SidecarRegistryManifest.save(registry, tmpDir);
        assertTrue(Files.exists(tmpDir.resolve(SidecarRegistryManifest.MANIFEST_FILE)));

        SidecarRegistryManifest.delete(tmpDir);
        assertFalse(Files.exists(tmpDir.resolve(SidecarRegistryManifest.MANIFEST_FILE)));
    }

    public void testPersistBitmapAndReload() throws Exception {
        Path tmpDir = createTempDir();
        Path sidecarDir = tmpDir.resolve("_sidecar_persist");
        Files.createDirectories(sidecarDir);

        SidecarVersionBitmap bitmap = new SidecarVersionBitmap(2000);
        bitmap.set(0);
        bitmap.set(500);
        bitmap.set(1999);

        SidecarRegistryManifest.persistBitmap(bitmap, sidecarDir);
        assertTrue(Files.exists(sidecarDir.resolve("_sidecar_bitmap.bin")));

        // Read it back using the format directly
        try (var in = Files.newInputStream(sidecarDir.resolve("_sidecar_bitmap.bin"))) {
            SidecarVersionBitmap loaded = SidecarVersionBitmapFormat.read(in);
            assertEquals(2000, loaded.maxDoc());
            assertTrue(loaded.get(0));
            assertTrue(loaded.get(500));
            assertTrue(loaded.get(1999));
            assertFalse(loaded.get(1));
            assertEquals(3, loaded.cardinality());
        }
    }

    public void testRestoreWithMissingSidecarDirectory() throws Exception {
        // Manifest references a path that doesn't exist on disk — should skip gracefully
        Path tmpDir = createTempDir();
        Path sidecarDir = tmpDir.resolve("_sidecar_missing");
        // DO NOT create the directory

        SidecarRegistry original = new SidecarRegistry();
        SidecarVersionBitmap bitmap = new SidecarVersionBitmap(100);
        bitmap.set(5);
        // Register without path persistence (just to populate the manifest content)
        original.register("field", "_0", bitmap, sidecarDir);

        // Manually write manifest as if sidecarDir existed at write time
        SidecarRegistryManifest.save(original, tmpDir);

        // Now restore — sidecarDir doesn't exist, so entry should be skipped
        SidecarRegistry restored = new SidecarRegistry();
        boolean found = SidecarRegistryManifest.restore(restored, tmpDir);
        assertFalse(found);
        assertFalse(restored.hasAnySidecars());
    }
}
