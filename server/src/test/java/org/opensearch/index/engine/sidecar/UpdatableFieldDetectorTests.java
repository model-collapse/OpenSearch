/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.index.engine.sidecar;

import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.opensearch.index.mapper.FieldMapper;
import org.opensearch.index.mapper.MappingLookup;
import org.opensearch.index.mapper.MockFieldMapper;
import org.opensearch.test.OpenSearchTestCase;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class UpdatableFieldDetectorTests extends OpenSearchTestCase {

    private MappingLookup buildLookup(List<FieldMapper> fieldMappers) {
        return new MappingLookup(fieldMappers, Collections.emptyList(), Collections.emptyList(), 0, new StandardAnalyzer());
    }

    public void testAllFieldsUpdatable() {
        FieldMapper embeddingMapper = new MockFieldMapper("embedding") {
            @Override
            public boolean isUpdatable() {
                return true;
            }
        };
        FieldMapper scoreMapper = new MockFieldMapper("score") {
            @Override
            public boolean isUpdatable() {
                return true;
            }
        };

        MappingLookup lookup = buildLookup(Arrays.asList(embeddingMapper, scoreMapper));
        assertTrue(UpdatableFieldDetector.allFieldsUpdatable(Set.of("embedding", "score"), lookup));
    }

    public void testMixedFieldsReturnsFalse() {
        FieldMapper embeddingMapper = new MockFieldMapper("embedding") {
            @Override
            public boolean isUpdatable() {
                return true;
            }
        };
        FieldMapper titleMapper = new MockFieldMapper("title") {
            @Override
            public boolean isUpdatable() {
                return false;
            }
        };

        MappingLookup lookup = buildLookup(Arrays.asList(embeddingMapper, titleMapper));
        assertFalse(UpdatableFieldDetector.allFieldsUpdatable(Set.of("embedding", "title"), lookup));
    }

    public void testUnmappedFieldReturnsFalse() {
        FieldMapper embeddingMapper = new MockFieldMapper("embedding") {
            @Override
            public boolean isUpdatable() {
                return true;
            }
        };

        MappingLookup lookup = buildLookup(Collections.singletonList(embeddingMapper));
        assertFalse(UpdatableFieldDetector.allFieldsUpdatable(Set.of("unknown"), lookup));
    }

    public void testEmptyChangedFieldsReturnsFalse() {
        MappingLookup lookup = buildLookup(Collections.emptyList());
        assertFalse(UpdatableFieldDetector.allFieldsUpdatable(Set.of(), lookup));
    }

    public void testNullChangedFieldsReturnsFalse() {
        MappingLookup lookup = buildLookup(Collections.emptyList());
        assertFalse(UpdatableFieldDetector.allFieldsUpdatable(null, lookup));
    }

    public void testExtractChangedFields() {
        Map<String, Object> existing = Map.of("title", "hello", "embedding", new float[] { 1.0f });
        Map<String, Object> partial = Map.of("embedding", new float[] { 2.0f }, "score", 0.5);

        Set<String> changed = UpdatableFieldDetector.extractChangedFields(existing, partial);
        assertEquals(Set.of("embedding", "score"), changed);
    }
}
