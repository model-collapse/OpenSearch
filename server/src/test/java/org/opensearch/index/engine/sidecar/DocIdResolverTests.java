/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.index.engine.sidecar;

import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StringField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.Term;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.opensearch.index.mapper.IdFieldMapper;
import org.opensearch.index.mapper.Uid;
import org.opensearch.test.OpenSearchTestCase;

import java.util.List;

public class DocIdResolverTests extends OpenSearchTestCase {

    public void testResolveSingleDoc() throws Exception {
        ByteBuffersDirectory dir = new ByteBuffersDirectory();
        IndexWriter writer = new IndexWriter(dir, new IndexWriterConfig());

        Document doc = new Document();
        doc.add(new StringField(IdFieldMapper.NAME, Uid.encodeId("doc1"), Field.Store.YES));
        writer.addDocument(doc);
        writer.commit();

        DirectoryReader reader = DirectoryReader.open(dir);
        DocIdResolver.ResolvedDoc resolved = DocIdResolver.resolve(reader, "doc1");

        assertNotNull(resolved);
        assertEquals(0, resolved.docId());
        assertEquals(0, resolved.leafOrd());

        reader.close();
        writer.close();
    }

    public void testResolveNonExistentReturnsNull() throws Exception {
        ByteBuffersDirectory dir = new ByteBuffersDirectory();
        IndexWriter writer = new IndexWriter(dir, new IndexWriterConfig());

        Document doc = new Document();
        doc.add(new StringField(IdFieldMapper.NAME, Uid.encodeId("doc1"), Field.Store.YES));
        writer.addDocument(doc);
        writer.commit();

        DirectoryReader reader = DirectoryReader.open(dir);
        DocIdResolver.ResolvedDoc resolved = DocIdResolver.resolve(reader, "nonexistent");

        assertNull(resolved);
        reader.close();
        writer.close();
    }

    public void testResolveDeletedDocReturnsNull() throws Exception {
        ByteBuffersDirectory dir = new ByteBuffersDirectory();
        IndexWriter writer = new IndexWriter(dir, new IndexWriterConfig());

        Document doc = new Document();
        doc.add(new StringField(IdFieldMapper.NAME, Uid.encodeId("doc1"), Field.Store.YES));
        writer.addDocument(doc);
        writer.commit();

        // Delete the doc
        writer.deleteDocuments(new Term(IdFieldMapper.NAME, Uid.encodeId("doc1")));
        writer.commit();

        DirectoryReader reader = DirectoryReader.open(dir);
        DocIdResolver.ResolvedDoc resolved = DocIdResolver.resolve(reader, "doc1");

        assertNull(resolved);
        reader.close();
        writer.close();
    }

    public void testResolveBatch() throws Exception {
        ByteBuffersDirectory dir = new ByteBuffersDirectory();
        IndexWriter writer = new IndexWriter(dir, new IndexWriterConfig());

        for (int i = 0; i < 5; i++) {
            Document doc = new Document();
            doc.add(new StringField(IdFieldMapper.NAME, Uid.encodeId("doc" + i), Field.Store.YES));
            writer.addDocument(doc);
        }
        writer.commit();

        DirectoryReader reader = DirectoryReader.open(dir);
        List<DocIdResolver.ResolvedDoc> results = DocIdResolver.resolveBatch(reader, List.of("doc0", "doc2", "doc4", "missing"));

        assertEquals(3, results.size()); // missing is skipped
        reader.close();
        writer.close();
    }

    public void testResolveMultipleSegments() throws Exception {
        ByteBuffersDirectory dir = new ByteBuffersDirectory();
        IndexWriter writer = new IndexWriter(dir, new IndexWriterConfig());

        // Segment 1
        Document doc1 = new Document();
        doc1.add(new StringField(IdFieldMapper.NAME, Uid.encodeId("doc1"), Field.Store.YES));
        writer.addDocument(doc1);
        writer.commit();

        // Segment 2
        Document doc2 = new Document();
        doc2.add(new StringField(IdFieldMapper.NAME, Uid.encodeId("doc2"), Field.Store.YES));
        writer.addDocument(doc2);
        writer.commit();

        DirectoryReader reader = DirectoryReader.open(dir);

        DocIdResolver.ResolvedDoc r1 = DocIdResolver.resolve(reader, "doc1");
        DocIdResolver.ResolvedDoc r2 = DocIdResolver.resolve(reader, "doc2");

        assertNotNull(r1);
        assertNotNull(r2);
        // They should be in different leaf readers
        assertNotEquals(r1.leafOrd(), r2.leafOrd());

        reader.close();
        writer.close();
    }
}
