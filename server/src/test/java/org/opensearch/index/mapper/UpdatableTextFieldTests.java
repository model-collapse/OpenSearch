/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.index.mapper;

import static org.hamcrest.Matchers.containsString;

public class UpdatableTextFieldTests extends MapperServiceTestCase {

    public void testUpdatableOnTextField() throws Exception {
        MapperService ms = createMapperService(fieldMapping(b -> b.field("type", "text").field("updatable", true)));
        FieldMapper mapper = (FieldMapper) ms.documentMapper().mappers().getMapper("field");
        assertTrue(mapper.isUpdatable());
    }

    public void testUpdatableDefaultsFalseOnText() throws Exception {
        MapperService ms = createMapperService(fieldMapping(b -> b.field("type", "text")));
        FieldMapper mapper = (FieldMapper) ms.documentMapper().mappers().getMapper("field");
        assertFalse(mapper.isUpdatable());
    }

    public void testDroppedOnTextField() throws Exception {
        MapperService ms = createMapperService(fieldMapping(b -> b.field("type", "text").field("updatable", true).field("dropped", true)));
        FieldMapper mapper = (FieldMapper) ms.documentMapper().mappers().getMapper("field");
        assertTrue(mapper.isDropped());
    }

    public void testCannotRevertUpdatable() throws Exception {
        MapperService ms = createMapperService(fieldMapping(b -> b.field("type", "text").field("updatable", true)));
        IllegalArgumentException e = expectThrows(
            IllegalArgumentException.class,
            () -> merge(ms, fieldMapping(b -> b.field("type", "text").field("updatable", false)))
        );
        assertThat(e.getMessage(), containsString("updatable"));
    }

    public void testCannotRevertDropped() throws Exception {
        MapperService ms = createMapperService(
            fieldMapping(b -> b.field("type", "text").field("updatable", true).field("dropped", true))
        );
        IllegalArgumentException e = expectThrows(
            IllegalArgumentException.class,
            () -> merge(ms, fieldMapping(b -> b.field("type", "text").field("updatable", true).field("dropped", false)))
        );
        assertThat(e.getMessage(), containsString("dropped"));
    }
}
