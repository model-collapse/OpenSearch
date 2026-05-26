/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.cluster.metadata;

import org.opensearch.action.admin.indices.shrink.ResizeType;
import org.opensearch.common.settings.FeatureFlagSettings;
import org.opensearch.common.settings.Setting;
import org.opensearch.common.settings.Settings;
import org.opensearch.common.util.FeatureFlags;
import org.opensearch.index.engine.dataformat.stub.MockCommitterEnginePlugin;
import org.opensearch.index.engine.dataformat.stub.MockParquetDataFormatPlugin;
import org.opensearch.plugins.Plugin;
import org.opensearch.test.OpenSearchIntegTestCase;

import java.util.Collection;
import java.util.List;

import static org.opensearch.test.hamcrest.OpenSearchAssertions.assertAcked;

/**
 * Integration tests verifying that shrink/split/clone operations are rejected for
 * indices with pluggable data format (DFA) enabled.
 */
@OpenSearchIntegTestCase.ClusterScope(scope = OpenSearchIntegTestCase.Scope.TEST, numDataNodes = 1)
public class ResizeDFAValidationIT extends OpenSearchIntegTestCase {

    @Override
    protected Collection<Class<? extends Plugin>> nodePlugins() {
        return List.of(MockCommitterEnginePlugin.class, MockParquetDataFormatPlugin.class);
    }

    @Override
    protected Settings featureFlagSettings() {
        Settings.Builder builder = Settings.builder();
        for (Setting<?> builtInFlag : FeatureFlagSettings.BUILT_IN_FEATURE_FLAGS) {
            builder.put(builtInFlag.getKey(), builtInFlag.getDefaultRaw(Settings.EMPTY));
        }
        builder.put(FeatureFlags.PLUGGABLE_DATAFORMAT_EXPERIMENTAL_FLAG, true);
        return builder.build();
    }

    public void testShrinkRejectedForDFAIndex() throws Exception {
        Settings settings = Settings.builder()
            .put("index.number_of_shards", 2)
            .put("index.number_of_replicas", 0)
            .put("index.pluggable.dataformat.enabled", true)
            .put("index.pluggable.dataformat", "parquet")
            .build();

        assertAcked(prepareCreate("source-index").setSettings(settings));
        ensureGreen("source-index");

        // Block writes (required for shrink)
        assertAcked(client().admin().indices().prepareUpdateSettings("source-index")
            .setSettings(Settings.builder().put("index.blocks.write", true)));

        // Attempt shrink — should fail because DFA is enabled
        IllegalArgumentException e = expectThrows(IllegalArgumentException.class, () ->
            client().admin().indices().prepareResizeIndex("source-index", "target-shrink")
                .setResizeType(ResizeType.SHRINK)
                .setSettings(Settings.builder()
                    .put("index.number_of_shards", 1)
                    .put("index.number_of_replicas", 0)
                    .build())
                .get()
        );
        assertTrue("Expected error about pluggable data format, got: " + e.getMessage(),
            e.getMessage().contains("pluggable data format"));
    }

    public void testSplitRejectedForDFAIndex() throws Exception {
        Settings settings = Settings.builder()
            .put("index.number_of_shards", 1)
            .put("index.number_of_replicas", 0)
            .put("index.number_of_routing_shards", 2)
            .put("index.pluggable.dataformat.enabled", true)
            .put("index.pluggable.dataformat", "parquet")
            .build();

        assertAcked(prepareCreate("source-split").setSettings(settings));
        ensureGreen("source-split");

        // Block writes (required for split)
        assertAcked(client().admin().indices().prepareUpdateSettings("source-split")
            .setSettings(Settings.builder().put("index.blocks.write", true)));

        // Attempt split — should fail because DFA is enabled
        IllegalArgumentException e = expectThrows(IllegalArgumentException.class, () ->
            client().admin().indices().prepareResizeIndex("source-split", "target-split")
                .setResizeType(ResizeType.SPLIT)
                .setSettings(Settings.builder()
                    .put("index.number_of_shards", 2)
                    .put("index.number_of_replicas", 0)
                    .build())
                .get()
        );
        assertTrue("Expected error about pluggable data format, got: " + e.getMessage(),
            e.getMessage().contains("pluggable data format"));
    }

    public void testCloneRejectedForDFAIndex() throws Exception {
        Settings settings = Settings.builder()
            .put("index.number_of_shards", 1)
            .put("index.number_of_replicas", 0)
            .put("index.pluggable.dataformat.enabled", true)
            .put("index.pluggable.dataformat", "parquet")
            .build();

        assertAcked(prepareCreate("source-clone").setSettings(settings));
        ensureGreen("source-clone");

        // Block writes (required for clone)
        assertAcked(client().admin().indices().prepareUpdateSettings("source-clone")
            .setSettings(Settings.builder().put("index.blocks.write", true)));

        // Attempt clone — should fail because DFA is enabled
        IllegalArgumentException e = expectThrows(IllegalArgumentException.class, () ->
            client().admin().indices().prepareResizeIndex("source-clone", "target-clone")
                .setResizeType(ResizeType.CLONE)
                .setSettings(Settings.builder()
                    .put("index.number_of_replicas", 0)
                    .build())
                .get()
        );
        assertTrue("Expected error about pluggable data format, got: " + e.getMessage(),
            e.getMessage().contains("pluggable data format"));
    }
}
