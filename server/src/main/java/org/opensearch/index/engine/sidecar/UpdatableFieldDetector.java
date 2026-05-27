/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.index.engine.sidecar;

import org.opensearch.index.mapper.FieldMapper;
import org.opensearch.index.mapper.Mapper;
import org.opensearch.index.mapper.MappingLookup;

import java.util.Map;
import java.util.Set;

/**
 * Detects whether a partial document update touches only updatable fields,
 * enabling the sidecar fast path instead of full reindex.
 */
public class UpdatableFieldDetector {

    /**
     * Given the fields in a partial update, checks if ALL of them are marked updatable in the mapping.
     *
     * @param changedFields the field names that were modified in the partial update
     * @param mappingLookup the current index mapping
     * @return true if ALL changed fields are updatable, false if any is not
     */
    public static boolean allFieldsUpdatable(Set<String> changedFields, MappingLookup mappingLookup) {
        if (changedFields == null || changedFields.isEmpty()) {
            return false;
        }

        for (String fieldName : changedFields) {
            Mapper mapper = mappingLookup.getMapper(fieldName);
            if (mapper == null) {
                return false; // unmapped field - can't use sidecar
            }
            if (!(mapper instanceof FieldMapper)) {
                return false; // object mapper or alias - not a simple field
            }
            FieldMapper fieldMapper = (FieldMapper) mapper;
            if (!fieldMapper.isUpdatable()) {
                return false; // field exists but is not marked updatable
            }
        }
        return true;
    }

    /**
     * Extract the set of top-level field names from a partial document map.
     * The changed fields are the keys present in the partial doc
     * (partial doc represents what's being merged into existing).
     *
     * @param existingSource the current document source
     * @param partialDoc the partial document being applied
     * @return the set of field names in the partial document
     */
    public static Set<String> extractChangedFields(Map<String, Object> existingSource, Map<String, Object> partialDoc) {
        return partialDoc.keySet();
    }
}
