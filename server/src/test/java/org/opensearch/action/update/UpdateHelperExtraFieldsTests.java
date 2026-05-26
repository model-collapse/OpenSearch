/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.action.update;

import org.opensearch.action.DocWriteResponse;
import org.opensearch.action.index.IndexRequest;
import org.opensearch.common.settings.Settings;
import org.opensearch.core.common.bytes.BytesArray;
import org.opensearch.core.index.shard.ShardId;
import org.opensearch.env.Environment;
import org.opensearch.index.get.GetResult;
import org.opensearch.index.mapper.extrasource.BytesValue;
import org.opensearch.index.mapper.extrasource.ExtraFieldValues;
import org.opensearch.script.MockScriptEngine;
import org.opensearch.script.ScriptEngine;
import org.opensearch.script.ScriptModule;
import org.opensearch.script.ScriptService;
import org.opensearch.test.OpenSearchTestCase;
import org.junit.Before;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

import static org.opensearch.script.MockScriptEngine.mockInlineScript;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.instanceOf;

public class UpdateHelperExtraFieldsTests extends OpenSearchTestCase {

    private UpdateHelper updateHelper;

    @Override
    @Before
    public void setUp() throws Exception {
        super.setUp();
        final Settings baseSettings = Settings.builder().put(Environment.PATH_HOME_SETTING.getKey(), createTempDir().toString()).build();
        final Map<String, Function<Map<String, Object>, Object>> scripts = new HashMap<>();
        scripts.put("ctx._source.body = \"foo\"", vars -> {
            @SuppressWarnings("unchecked")
            final Map<String, Object> ctx = (Map<String, Object>) vars.get("ctx");
            @SuppressWarnings("unchecked")
            final Map<String, Object> source = (Map<String, Object>) ctx.get("_source");
            source.put("body", "foo");
            return null;
        });
        final MockScriptEngine engine = new MockScriptEngine("mock", scripts, Collections.emptyMap());
        Map<String, ScriptEngine> engines = Collections.singletonMap(engine.getType(), engine);
        ScriptService scriptService = new ScriptService(baseSettings, engines, ScriptModule.CORE_CONTEXTS);
        updateHelper = new UpdateHelper(scriptService);
    }

    public void testScriptedUpdateWithNullDocHasEmptyExtraFieldValues() {
        // When there is no doc on the update request (the common case for scripted updates),
        // the resulting IndexRequest should have ExtraFieldValues.EMPTY.
        ShardId shardId = new ShardId("test", "", 0);
        GetResult getResult = new GetResult("test", "1", 0, 1, 0, true, new BytesArray("{\"body\": \"bar\"}"), null, null);

        UpdateRequest request = new UpdateRequest("test", "1").script(mockInlineScript("ctx._source.body = \"foo\""));
        // request.doc() is null here

        UpdateHelper.Result result = updateHelper.prepareUpdateScriptRequest(
            shardId,
            request,
            getResult,
            OpenSearchTestCase::randomNonNegativeLong
        );

        assertThat(result.action(), instanceOf(IndexRequest.class));
        assertThat(result.getResponseResult(), equalTo(DocWriteResponse.Result.UPDATED));
        IndexRequest indexRequest = result.action();
        assertTrue("Expected empty ExtraFieldValues when doc is null", indexRequest.extraFieldValues().isEmpty());
    }

    public void testScriptedUpdateCarriesForwardExtraFieldValues() {
        // When there IS a doc on the update request with extraFieldValues set,
        // the resulting IndexRequest should carry them forward.
        // Note: validation currently rejects this combination at the transport layer,
        // but prepareUpdateScriptRequest itself should preserve the values for when
        // validation is relaxed.
        ShardId shardId = new ShardId("test", "", 0);
        GetResult getResult = new GetResult("test", "1", 0, 1, 0, true, new BytesArray("{\"body\": \"bar\"}"), null, null);

        UpdateRequest request = new UpdateRequest("test", "1").script(mockInlineScript("ctx._source.body = \"foo\""));
        // Directly set a doc with extraFieldValues (bypassing validation for unit test purposes)
        IndexRequest doc = new IndexRequest("test").id("1").source("{\"body\": \"bar\"}", org.opensearch.core.xcontent.MediaTypeRegistry.JSON);
        ExtraFieldValues extraValues = new ExtraFieldValues(
            Map.of("sidecar_field", new BytesValue(new BytesArray(new byte[] { 1, 2, 3 })))
        );
        doc.extraFieldValues(extraValues);
        request.doc(doc);

        UpdateHelper.Result result = updateHelper.prepareUpdateScriptRequest(
            shardId,
            request,
            getResult,
            OpenSearchTestCase::randomNonNegativeLong
        );

        assertThat(result.action(), instanceOf(IndexRequest.class));
        assertThat(result.getResponseResult(), equalTo(DocWriteResponse.Result.UPDATED));
        IndexRequest indexRequest = result.action();
        assertFalse("Expected non-empty ExtraFieldValues carried forward from doc", indexRequest.extraFieldValues().isEmpty());
        assertNotNull(indexRequest.extraFieldValues().get("sidecar_field"));
    }

    public void testExtraFieldValuesNullSafe() {
        // When currentRequest has no extraFieldValues set, should use EMPTY as fallback
        IndexRequest request = new IndexRequest("test").id("1")
            .source("{\"title\": \"hello\"}", org.opensearch.core.xcontent.MediaTypeRegistry.JSON);
        // Default extraFieldValues should be EMPTY
        IndexRequest result = new IndexRequest("test").id("1")
            .source("{\"title\": \"updated\"}", org.opensearch.core.xcontent.MediaTypeRegistry.JSON)
            .extraFieldValues(request.extraFieldValues() != null ? request.extraFieldValues() : ExtraFieldValues.EMPTY);
        assertNotNull(result);
        assertTrue("Expected empty ExtraFieldValues from null-safe pattern", result.extraFieldValues().isEmpty());
    }

    public void testExtraFieldValuesEmptyConstant() {
        // Verify ExtraFieldValues.EMPTY exists and is usable
        assertNotNull(ExtraFieldValues.EMPTY);
        assertTrue("EMPTY should report isEmpty() == true", ExtraFieldValues.EMPTY.isEmpty());
    }
}
