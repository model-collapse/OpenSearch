/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.index.engine.sidecar;

import org.apache.lucene.document.Document;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.FieldInfo;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.LeafReader;
import org.apache.lucene.index.StoredFieldVisitor;
import org.apache.lucene.index.StoredFields;
import org.apache.lucene.store.Directory;
import org.apache.lucene.tests.analysis.MockAnalyzer;
import org.opensearch.common.util.io.IOUtils;
import org.opensearch.common.xcontent.XContentFactory;
import org.opensearch.common.xcontent.XContentHelper;
import org.opensearch.common.xcontent.XContentType;
import org.opensearch.core.common.bytes.BytesArray;
import org.opensearch.core.common.bytes.BytesReference;
import org.opensearch.test.OpenSearchTestCase;
import org.junit.After;
import org.junit.Before;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public class SidecarAwareStoredFieldsTests extends OpenSearchTestCase {

    private Directory dir;
    private IndexWriter writer;
    private DirectoryReader dirReader;
    private static final String ORIGINAL_SOURCE = "{\"name\":\"alice\",\"age\":30}";
    private static final byte[] ORIGINAL_SOURCE_BYTES = ORIGINAL_SOURCE.getBytes(StandardCharsets.UTF_8);

    @Before
    public void setUp() throws Exception {
        super.setUp();
        dir = newDirectory();
        IndexWriterConfig config = newIndexWriterConfig(new MockAnalyzer(random()));
        writer = new IndexWriter(dir, config);

        Document doc = new Document();
        doc.add(new StoredField("_source", ORIGINAL_SOURCE_BYTES));
        writer.addDocument(doc);
        writer.commit();

        dirReader = DirectoryReader.open(writer);
    }

    @After
    public void tearDown() throws Exception {
        IOUtils.close(dirReader, writer, dir);
        super.tearDown();
    }

    public void testCleanDocPassedThroughUnchanged() throws IOException {
        // Sidecar provider returns null (clean doc)
        StoredFields storedFields = new SidecarAwareStoredFields(
            dirReader.leaves().get(0).reader().storedFields(),
            docId -> null
        );

        AtomicReference<byte[]> capturedSource = new AtomicReference<>();
        StoredFieldVisitor visitor = new StoredFieldVisitor() {
            @Override
            public Status needsField(FieldInfo fieldInfo) {
                return fieldInfo.name.equals("_source") ? Status.YES : Status.NO;
            }

            @Override
            public void binaryField(FieldInfo fieldInfo, byte[] value) {
                if ("_source".equals(fieldInfo.name)) {
                    capturedSource.set(value);
                }
            }
        };

        storedFields.document(0, visitor);
        assertNotNull("Source should have been read", capturedSource.get());
        // The source should be unchanged since the doc is clean
        Map<String, Object> resultMap = XContentHelper.convertToMap(new BytesArray(capturedSource.get()), false, XContentType.JSON).v2();
        assertEquals("alice", resultMap.get("name"));
        assertEquals(30, resultMap.get("age"));
    }

    public void testCleanDocWithEmptyMapPassedThroughUnchanged() throws IOException {
        // Sidecar provider returns empty map (clean doc)
        StoredFields storedFields = new SidecarAwareStoredFields(
            dirReader.leaves().get(0).reader().storedFields(),
            docId -> Collections.emptyMap()
        );

        AtomicReference<byte[]> capturedSource = new AtomicReference<>();
        StoredFieldVisitor visitor = new StoredFieldVisitor() {
            @Override
            public Status needsField(FieldInfo fieldInfo) {
                return fieldInfo.name.equals("_source") ? Status.YES : Status.NO;
            }

            @Override
            public void binaryField(FieldInfo fieldInfo, byte[] value) {
                if ("_source".equals(fieldInfo.name)) {
                    capturedSource.set(value);
                }
            }
        };

        storedFields.document(0, visitor);
        assertNotNull("Source should have been read", capturedSource.get());
        Map<String, Object> resultMap = XContentHelper.convertToMap(new BytesArray(capturedSource.get()), false, XContentType.JSON).v2();
        assertEquals("alice", resultMap.get("name"));
        assertEquals(30, resultMap.get("age"));
    }

    public void testDirtyDocHasSidecarValuesOverlaid() throws IOException {
        // Sidecar provider returns overlay values (dirty doc)
        Map<String, Object> sidecarValues = new HashMap<>();
        sidecarValues.put("name", "bob");
        sidecarValues.put("email", "bob@example.com");

        StoredFields storedFields = new SidecarAwareStoredFields(
            dirReader.leaves().get(0).reader().storedFields(),
            docId -> sidecarValues
        );

        AtomicReference<byte[]> capturedSource = new AtomicReference<>();
        StoredFieldVisitor visitor = new StoredFieldVisitor() {
            @Override
            public Status needsField(FieldInfo fieldInfo) {
                return fieldInfo.name.equals("_source") ? Status.YES : Status.NO;
            }

            @Override
            public void binaryField(FieldInfo fieldInfo, byte[] value) {
                if ("_source".equals(fieldInfo.name)) {
                    capturedSource.set(value);
                }
            }
        };

        storedFields.document(0, visitor);
        assertNotNull("Source should have been read", capturedSource.get());

        Map<String, Object> resultMap = XContentHelper.convertToMap(new BytesArray(capturedSource.get()), false, XContentType.JSON).v2();
        // "name" should be overwritten by sidecar value
        assertEquals("bob", resultMap.get("name"));
        // "age" should be preserved from original source
        assertEquals(30, resultMap.get("age"));
        // "email" should be added from sidecar values
        assertEquals("bob@example.com", resultMap.get("email"));
    }

    public void testSourceNotRequestedPassesThrough() throws IOException {
        // Even for a dirty doc, if _source is not requested, it should pass through
        Map<String, Object> sidecarValues = new HashMap<>();
        sidecarValues.put("name", "bob");

        AtomicBoolean binaryFieldCalled = new AtomicBoolean(false);

        StoredFields storedFields = new SidecarAwareStoredFields(
            dirReader.leaves().get(0).reader().storedFields(),
            docId -> sidecarValues
        );

        StoredFieldVisitor visitor = new StoredFieldVisitor() {
            @Override
            public Status needsField(FieldInfo fieldInfo) {
                // Don't request _source
                return Status.NO;
            }

            @Override
            public void binaryField(FieldInfo fieldInfo, byte[] value) {
                binaryFieldCalled.set(true);
            }
        };

        storedFields.document(0, visitor);
        assertFalse("binaryField should not have been called", binaryFieldCalled.get());
    }

    public void testDirectoryReaderWrap() throws IOException {
        Map<String, Object> sidecarValues = new HashMap<>();
        sidecarValues.put("city", "seattle");

        SidecarAwareDirectoryReader wrappedReader = SidecarAwareDirectoryReader.wrap(
            dirReader,
            (leafReader, docId) -> sidecarValues
        );

        LeafReader leafReader = wrappedReader.leaves().get(0).reader();
        assertTrue("Leaf reader should be SidecarAwareLeafReader", leafReader instanceof SidecarAwareLeafReader);

        AtomicReference<byte[]> capturedSource = new AtomicReference<>();
        StoredFieldVisitor visitor = new StoredFieldVisitor() {
            @Override
            public Status needsField(FieldInfo fieldInfo) {
                return fieldInfo.name.equals("_source") ? Status.YES : Status.NO;
            }

            @Override
            public void binaryField(FieldInfo fieldInfo, byte[] value) {
                if ("_source".equals(fieldInfo.name)) {
                    capturedSource.set(value);
                }
            }
        };

        leafReader.storedFields().document(0, visitor);
        assertNotNull("Source should have been captured", capturedSource.get());

        Map<String, Object> resultMap = XContentHelper.convertToMap(new BytesArray(capturedSource.get()), false, XContentType.JSON).v2();
        assertEquals("alice", resultMap.get("name"));
        assertEquals(30, resultMap.get("age"));
        assertEquals("seattle", resultMap.get("city"));

        wrappedReader.close();
    }

    public void testOverlaySourceUtility() throws IOException {
        byte[] source = "{\"a\":1,\"b\":2}".getBytes(StandardCharsets.UTF_8);
        Map<String, Object> overlay = new HashMap<>();
        overlay.put("b", 99);
        overlay.put("c", 3);

        byte[] result = SidecarAwareStoredFields.overlaySource(source, overlay);
        Map<String, Object> resultMap = XContentHelper.convertToMap(new BytesArray(result), false, XContentType.JSON).v2();

        assertEquals(1, resultMap.get("a"));
        assertEquals(99, resultMap.get("b"));
        assertEquals(3, resultMap.get("c"));
    }
}
