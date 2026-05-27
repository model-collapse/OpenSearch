/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.index.mapper;

public class UpdatableDocValuesFieldsTests extends MapperServiceTestCase {

    public void testUpdatableOnLongField() throws Exception {
        MapperService ms = createMapperService(fieldMapping(b -> b.field("type", "long").field("updatable", true)));
        FieldMapper mapper = (FieldMapper) ms.documentMapper().mappers().getMapper("field");
        assertTrue(mapper.isUpdatable());
    }

    public void testUpdatableOnDoubleField() throws Exception {
        MapperService ms = createMapperService(fieldMapping(b -> b.field("type", "double").field("updatable", true)));
        FieldMapper mapper = (FieldMapper) ms.documentMapper().mappers().getMapper("field");
        assertTrue(mapper.isUpdatable());
    }

    public void testUpdatableOnDateField() throws Exception {
        MapperService ms = createMapperService(fieldMapping(b -> b.field("type", "date").field("updatable", true)));
        FieldMapper mapper = (FieldMapper) ms.documentMapper().mappers().getMapper("field");
        assertTrue(mapper.isUpdatable());
    }

    public void testUpdatableOnBooleanField() throws Exception {
        MapperService ms = createMapperService(fieldMapping(b -> b.field("type", "boolean").field("updatable", true)));
        FieldMapper mapper = (FieldMapper) ms.documentMapper().mappers().getMapper("field");
        assertTrue(mapper.isUpdatable());
    }

    public void testUpdatableOnIpField() throws Exception {
        MapperService ms = createMapperService(fieldMapping(b -> b.field("type", "ip").field("updatable", true)));
        FieldMapper mapper = (FieldMapper) ms.documentMapper().mappers().getMapper("field");
        assertTrue(mapper.isUpdatable());
    }

    public void testUpdatableDefaultsFalseOnNumber() throws Exception {
        MapperService ms = createMapperService(fieldMapping(b -> b.field("type", "long")));
        FieldMapper mapper = (FieldMapper) ms.documentMapper().mappers().getMapper("field");
        assertFalse(mapper.isUpdatable());
    }
}
