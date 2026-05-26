/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.index.mapper;

import org.opensearch.common.xcontent.XContentFactory;
import org.opensearch.core.xcontent.ToXContent;
import org.opensearch.core.xcontent.XContentBuilder;

import java.io.IOException;

import static org.hamcrest.Matchers.containsString;

/**
 * Tests for the {@code updatable} mapping property on field mappers.
 */
public class UpdatableFieldPropertyTests extends MapperServiceTestCase {

    /**
     * Test that a keyword field without "updatable" defaults to false.
     */
    public void testUpdatableParameterDefaultsFalse() throws IOException {
        MapperService mapperService = createMapperService(fieldMapping(b -> b.field("type", "keyword")));
        KeywordFieldMapper mapper = (KeywordFieldMapper) mapperService.documentMapper().mappers().getMapper("field");
        assertFalse(mapper.isUpdatable());
    }

    /**
     * Test that a keyword field with "updatable": true is parsed correctly.
     */
    public void testUpdatableParameterSetTrue() throws IOException {
        MapperService mapperService = createMapperService(fieldMapping(b -> b.field("type", "keyword").field("updatable", true)));
        KeywordFieldMapper mapper = (KeywordFieldMapper) mapperService.documentMapper().mappers().getMapper("field");
        assertTrue(mapper.isUpdatable());
    }

    /**
     * Test that "updatable" can be enabled on an existing mapping (false -> true).
     */
    public void testUpdatableCanBeEnabled() throws IOException {
        MapperService mapperService = createMapperService(fieldMapping(b -> b.field("type", "keyword")));
        merge(mapperService, fieldMapping(b -> b.field("type", "keyword").field("updatable", true)));
        KeywordFieldMapper mapper = (KeywordFieldMapper) mapperService.documentMapper().mappers().getMapper("field");
        assertTrue(mapper.isUpdatable());
    }

    /**
     * Test that "updatable" cannot be disabled once enabled (true -> false is a conflict).
     */
    public void testUpdatableCannotBeDisabled() throws IOException {
        MapperService mapperService = createMapperService(fieldMapping(b -> b.field("type", "keyword").field("updatable", true)));
        IllegalArgumentException e = expectThrows(
            IllegalArgumentException.class,
            () -> merge(mapperService, fieldMapping(b -> b.field("type", "keyword").field("updatable", false)))
        );
        assertThat(e.getMessage(), containsString("updatable"));
    }

    /**
     * Test that "updatable" serializes to mapping JSON when set to true.
     */
    public void testUpdatableSerializesToJson() throws IOException {
        MapperService mapperService = createMapperService(fieldMapping(b -> b.field("type", "keyword").field("updatable", true)));
        XContentBuilder serialized = XContentFactory.jsonBuilder();
        mapperService.documentMapper().toXContent(serialized, ToXContent.EMPTY_PARAMS);
        String json = serialized.toString();
        assertThat(json, containsString("\"updatable\":true"));
    }

    /**
     * Test that "updatable" does not serialize when set to false (default).
     */
    public void testUpdatableDoesNotSerializeWhenDefault() throws IOException {
        MapperService mapperService = createMapperService(fieldMapping(b -> b.field("type", "keyword")));
        XContentBuilder serialized = XContentFactory.jsonBuilder();
        mapperService.documentMapper().toXContent(serialized, ToXContent.EMPTY_PARAMS);
        String json = serialized.toString();
        assertFalse(json.contains("\"updatable\""));
    }
}
