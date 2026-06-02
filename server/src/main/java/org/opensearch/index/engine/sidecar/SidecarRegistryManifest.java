/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.index.engine.sidecar;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.opensearch.common.xcontent.XContentFactory;
import org.opensearch.common.xcontent.XContentHelper;
import org.opensearch.core.common.bytes.BytesArray;
import org.opensearch.core.xcontent.MediaTypeRegistry;
import org.opensearch.core.xcontent.XContentBuilder;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;

/**
 * Persists and restores {@link SidecarRegistry} state to/from a JSON manifest file.
 * The manifest is written alongside sidecar data within the shard's index directory
 * so that registry state survives node restarts.
 *
 * @opensearch.experimental
 */
public class SidecarRegistryManifest {

    private static final Logger logger = LogManager.getLogger(SidecarRegistryManifest.class);

    static final String MANIFEST_FILE = "_sidecar_registry.json";
    private static final String BITMAP_FILE = "_sidecar_bitmap.bin";

    /**
     * Persist the current registry state to the manifest file.
     */
    public static void save(SidecarRegistry registry, Path indexPath) throws IOException {
        XContentBuilder builder = XContentFactory.jsonBuilder();
        builder.startObject();
        builder.startArray("sidecars");

        for (String field : registry.getUpdatableFields()) {
            for (String segment : registry.getSegmentsForField(field)) {
                SidecarVersionBitmap bitmap = registry.getBitmap(field, segment);
                Path sidecarPath = registry.getSidecarPath(field, segment);

                builder.startObject();
                builder.field("field", field);
                builder.field("segment", segment);
                builder.field("max_doc", bitmap != null ? bitmap.maxDoc() : 0);
                builder.field("cardinality", bitmap != null ? bitmap.cardinality() : 0);
                if (sidecarPath != null) {
                    builder.field("path", sidecarPath.toString());
                }
                builder.endObject();
            }
        }

        builder.endArray();
        builder.endObject();

        Path manifestPath = indexPath.resolve(MANIFEST_FILE);
        Files.writeString(
            manifestPath,
            builder.toString(),
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING
        );
    }

    /**
     * Persist the bitmap for a single sidecar entry to its sidecar directory.
     * Called on each register to ensure the bitmap is recoverable after restart.
     */
    public static void persistBitmap(SidecarVersionBitmap bitmap, Path sidecarPath) throws IOException {
        if (bitmap == null || sidecarPath == null) {
            return;
        }
        Path bitmapFile = sidecarPath.resolve(BITMAP_FILE);
        try (OutputStream out = Files.newOutputStream(bitmapFile, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            SidecarVersionBitmapFormat.write(bitmap, out);
        }
    }

    /**
     * Restore registry state from the manifest file.
     * Returns true if manifest was found and loaded, false if no manifest exists.
     */
    @SuppressWarnings("unchecked")
    public static boolean restore(SidecarRegistry registry, Path indexPath) throws IOException {
        Path manifestPath = indexPath.resolve(MANIFEST_FILE);
        if (!Files.exists(manifestPath)) {
            return false;
        }

        String content = Files.readString(manifestPath);
        Map<String, Object> map = XContentHelper.convertToMap(
            new BytesArray(content),
            false,
            MediaTypeRegistry.JSON
        ).v2();

        List<Map<String, Object>> sidecars = (List<Map<String, Object>>) map.get("sidecars");
        if (sidecars == null) {
            return false;
        }

        boolean restoredAny = false;
        for (Map<String, Object> entry : sidecars) {
            String field = (String) entry.get("field");
            String segment = (String) entry.get("segment");
            int maxDoc = ((Number) entry.get("max_doc")).intValue();
            String pathStr = (String) entry.get("path");

            if (field == null || segment == null || maxDoc <= 0) {
                continue;
            }

            Path sidecarPath = pathStr != null ? Path.of(pathStr) : null;

            // Rebuild bitmap from the persisted sidecar bitmap file
            if (sidecarPath != null && Files.exists(sidecarPath)) {
                Path bitmapFile = sidecarPath.resolve(BITMAP_FILE);
                SidecarVersionBitmap bitmap;
                if (Files.exists(bitmapFile)) {
                    try (InputStream in = Files.newInputStream(bitmapFile)) {
                        bitmap = SidecarVersionBitmapFormat.read(in);
                    }
                } else {
                    // Bitmap file doesn't exist — create empty with known maxDoc
                    bitmap = new SidecarVersionBitmap(maxDoc);
                }
                registry.register(field, segment, bitmap, sidecarPath);
                restoredAny = true;
            }
        }

        return restoredAny;
    }

    /**
     * Delete the manifest file.
     */
    public static void delete(Path indexPath) throws IOException {
        Path manifestPath = indexPath.resolve(MANIFEST_FILE);
        Files.deleteIfExists(manifestPath);
    }
}
