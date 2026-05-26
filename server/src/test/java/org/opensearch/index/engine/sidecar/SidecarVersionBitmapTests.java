package org.opensearch.index.engine.sidecar;

import org.opensearch.test.OpenSearchTestCase;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

public class SidecarVersionBitmapTests extends OpenSearchTestCase {

    public void testSetAndGet() {
        SidecarVersionBitmap bitmap = new SidecarVersionBitmap(1000);
        bitmap.set(0);
        bitmap.set(500);
        bitmap.set(999);

        assertTrue(bitmap.get(0));
        assertTrue(bitmap.get(500));
        assertTrue(bitmap.get(999));
        assertFalse(bitmap.get(1));
        assertFalse(bitmap.get(501));
    }

    public void testCardinality() {
        SidecarVersionBitmap bitmap = new SidecarVersionBitmap(100);
        bitmap.set(10);
        bitmap.set(20);
        bitmap.set(30);
        assertEquals(3, bitmap.cardinality());
    }

    public void testSerializationRoundTrip() throws IOException {
        SidecarVersionBitmap original = new SidecarVersionBitmap(500);
        original.set(0);
        original.set(100);
        original.set(499);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        SidecarVersionBitmapFormat.write(original, out);

        ByteArrayInputStream in = new ByteArrayInputStream(out.toByteArray());
        SidecarVersionBitmap restored = SidecarVersionBitmapFormat.read(in);

        assertEquals(original.maxDoc(), restored.maxDoc());
        assertTrue(restored.get(0));
        assertTrue(restored.get(100));
        assertTrue(restored.get(499));
        assertFalse(restored.get(1));
        assertEquals(original.cardinality(), restored.cardinality());
    }

    public void testEmptyBitmap() {
        SidecarVersionBitmap bitmap = new SidecarVersionBitmap(1000);
        assertEquals(0, bitmap.cardinality());
        assertFalse(bitmap.get(0));
        assertFalse(bitmap.get(999));
    }

    public void testRemapAfterMerge() {
        SidecarVersionBitmap original = new SidecarVersionBitmap(100);
        original.set(5);
        original.set(50);
        original.set(99);

        int[] oldToNew = new int[100];
        for (int i = 0; i < 100; i++) oldToNew[i] = -1; // default: not mapped
        oldToNew[5] = 2;
        oldToNew[50] = 25;
        oldToNew[99] = 49;

        SidecarVersionBitmap remapped = original.remap(oldToNew, 50);
        assertTrue(remapped.get(2));
        assertTrue(remapped.get(25));
        assertTrue(remapped.get(49));
        assertFalse(remapped.get(5));
        assertEquals(3, remapped.cardinality());
    }
}
