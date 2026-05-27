/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.index.mapper;

import java.io.IOException;

import static org.hamcrest.Matchers.containsString;

/**
 * Tests for the {@code dropped} mapping property on field mappers.
 */
public class DroppedFieldPropertyTests extends MapperServiceTestCase {

    public void testDroppedDefaultsFalse() throws IOException {
        MapperService ms = createMapperService(fieldMapping(b -> b.field("type", "keyword").field("updatable", true)));
        FieldMapper mapper = (FieldMapper) ms.documentMapper().mappers().getMapper("field");
        assertFalse(mapper.isDropped());
    }

    public void testDroppedCanBeSet() throws IOException {
        MapperService ms = createMapperService(fieldMapping(b ->
            b.field("type", "keyword").field("updatable", true).field("dropped", true)
        ));
        FieldMapper mapper = (FieldMapper) ms.documentMapper().mappers().getMapper("field");
        assertTrue(mapper.isDropped());
    }

    public void testDroppedCannotBeReverted() throws IOException {
        MapperService ms = createMapperService(fieldMapping(b ->
            b.field("type", "keyword").field("updatable", true).field("dropped", true)
        ));
        IllegalArgumentException e = expectThrows(
            IllegalArgumentException.class,
            () -> merge(ms, fieldMapping(b -> b.field("type", "keyword").field("updatable", true).field("dropped", false)))
        );
        assertThat(e.getMessage(), containsString("dropped"));
    }

    public void testDroppedOnLongField() throws IOException {
        MapperService ms = createMapperService(fieldMapping(b ->
            b.field("type", "long").field("updatable", true).field("dropped", true)
        ));
        FieldMapper mapper = (FieldMapper) ms.documentMapper().mappers().getMapper("field");
        assertTrue(mapper.isDropped());
    }

    public void testDroppedOnDateField() throws IOException {
        MapperService ms = createMapperService(fieldMapping(b ->
            b.field("type", "date").field("updatable", true).field("dropped", true)
        ));
        FieldMapper mapper = (FieldMapper) ms.documentMapper().mappers().getMapper("field");
        assertTrue(mapper.isDropped());
    }

    public void testDroppedOnBooleanField() throws IOException {
        MapperService ms = createMapperService(fieldMapping(b ->
            b.field("type", "boolean").field("updatable", true).field("dropped", true)
        ));
        FieldMapper mapper = (FieldMapper) ms.documentMapper().mappers().getMapper("field");
        assertTrue(mapper.isDropped());
    }

    public void testDroppedOnIpField() throws IOException {
        MapperService ms = createMapperService(fieldMapping(b ->
            b.field("type", "ip").field("updatable", true).field("dropped", true)
        ));
        FieldMapper mapper = (FieldMapper) ms.documentMapper().mappers().getMapper("field");
        assertTrue(mapper.isDropped());
    }
}
