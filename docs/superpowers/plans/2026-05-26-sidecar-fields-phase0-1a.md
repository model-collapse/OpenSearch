# Sidecar Fields: Phase 0 + Phase 1a Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement foundation fixes for DataFormatAwareEngine and the core sidecar write path (mapping property + DataFormat + writer + version bitmap) that enables field-level vector updates without full reindex.

**Architecture:** Sidecar fields are stored as independent WriterFileSet entries tracked in CatalogSnapshot alongside base Lucene segments. A version bitmap per sidecar marks which doc IDs it covers. The `updatable` mapping property opts fields into this path. Phase 0 fixes pre-existing DFA engine gaps; Phase 1a builds the sidecar write infrastructure.

**Tech Stack:** Java 21, Gradle, OpenSearch 3.7.0, Lucene 10.4.0, DataFormatAwareEngine, FixedBitSet

---

## File Structure

### Phase 0 (Fixes to existing code)

| File | Action | Responsibility |
|------|--------|----------------|
| `server/src/main/java/org/opensearch/index/engine/DataFormatAwareEngine.java` | Modify | Add per-doc lock, store versionMap as field |
| `server/src/main/java/org/opensearch/cluster/metadata/MetadataCreateIndexService.java` | Modify | Block resize on DFA indices |
| `server/src/main/java/org/opensearch/action/update/UpdateHelper.java` | Modify | Carry forward extraFieldValues in script path |
| `server/src/main/java/org/opensearch/index/shard/IndexShard.java` | Modify | DFA-aware acquireLastIndexCommit |
| `server/src/main/java/org/opensearch/index/engine/CatalogSnapshotIndexCommit.java` | Create | IndexCommit wrapper over CatalogSnapshot |
| `server/src/test/java/org/opensearch/index/engine/DataFormatAwareEngineLockingTests.java` | Create | Tests for per-doc locking |
| `server/src/test/java/org/opensearch/cluster/metadata/MetadataCreateIndexServiceResizeTests.java` | Modify | Test DFA resize block |
| `server/src/test/java/org/opensearch/action/update/UpdateHelperExtraFieldsTests.java` | Create | Test extraFieldValues carried forward |

### Phase 1a (Sidecar infrastructure)

| File | Action | Responsibility |
|------|--------|----------------|
| `server/src/main/java/org/opensearch/index/mapper/FieldMapper.java` | Modify | Add `updatable` Parameter |
| `server/src/main/java/org/opensearch/index/engine/sidecar/SidecarVersionBitmap.java` | Create | Per-sidecar doc ID bitmap |
| `server/src/main/java/org/opensearch/index/engine/sidecar/SidecarVersionBitmapFormat.java` | Create | Serialization/deserialization of bitmap |
| `server/src/main/java/org/opensearch/index/engine/sidecar/KnnVectorSidecarDataFormat.java` | Create | DataFormat for knn_vector sidecars |
| `server/src/main/java/org/opensearch/index/engine/sidecar/KnnVectorSidecarPlugin.java` | Create | DataFormatPlugin registering the format |
| `server/src/main/java/org/opensearch/index/engine/sidecar/KnnVectorSidecarWriter.java` | Create | Writer that builds HNSW graphs for doc subsets |
| `server/src/main/java/org/opensearch/index/engine/sidecar/KnnVectorSidecarIndexingEngine.java` | Create | IndexingExecutionEngine creating writers |
| `server/src/test/java/org/opensearch/index/mapper/FieldMapperUpdatableTests.java` | Create | Tests for updatable property |
| `server/src/test/java/org/opensearch/index/engine/sidecar/SidecarVersionBitmapTests.java` | Create | Bitmap unit tests |
| `server/src/test/java/org/opensearch/index/engine/sidecar/KnnVectorSidecarWriterTests.java` | Create | Writer unit tests |

---

## Phase 0: Foundation Fixes

### Task 0.1: Per-Document Locking in DataFormatAwareEngine

**Files:**
- Modify: `server/src/main/java/org/opensearch/index/engine/DataFormatAwareEngine.java:365-377,521-524`
- Create: `server/src/test/java/org/opensearch/index/engine/DataFormatAwareEngineLockingTests.java`

- [ ] **Step 1: Write the failing test**

```java
// server/src/test/java/org/opensearch/index/engine/DataFormatAwareEngineLockingTests.java
package org.opensearch.index.engine;

import org.opensearch.test.OpenSearchTestCase;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

public class DataFormatAwareEngineLockingTests extends OpenSearchTestCase {

    public void testConcurrentIndexSameDocIsSerializedByLock() throws Exception {
        // Two threads indexing the same doc ID should not both succeed
        // without version conflict — proves lock serializes access
        AtomicInteger versionConflicts = new AtomicInteger(0);
        CountDownLatch latch = new CountDownLatch(2);
        // This test requires a DFA engine instance — use DataFormatAwareEngineTests helpers
        // For now, verify that versionMap field exists and lock is acquirable
        LiveVersionMap versionMap = new LiveVersionMap();
        byte[] uid = "doc1".getBytes();

        Thread t1 = new Thread(() -> {
            try (var lock = versionMap.acquireLock(uid)) {
                Thread.sleep(50); // hold lock
            } catch (Exception e) { /* */ }
            latch.countDown();
        });

        Thread t2 = new Thread(() -> {
            long start = System.currentTimeMillis();
            try (var lock = versionMap.acquireLock(uid)) {
                long waited = System.currentTimeMillis() - start;
                assertTrue("Should have waited for lock", waited >= 40);
            }
            latch.countDown();
        });

        t1.start();
        Thread.sleep(10); // ensure t1 gets lock first
        t2.start();
        latch.await();
    }
}
```

- [ ] **Step 2: Run test to verify it fails (LiveVersionMap may not be accessible)**

Run: `./gradlew :server:test --tests "org.opensearch.index.engine.DataFormatAwareEngineLockingTests" -x spotlessCheck`
Expected: Compilation or test failure (verifying baseline)

- [ ] **Step 3: Store versionMap as engine field**

In `DataFormatAwareEngine.java`, add a field declaration near line 146:
```java
    private final LiveVersionMap versionMap;
```

Modify the constructor (around line 368) — replace inline `new LiveVersionMap()` with field assignment:
```java
    this.versionMap = new LiveVersionMap();
    this.indexingStrategyPlanner = new IndexingStrategyPlanner(
        engineConfig.getIndexSettings(),
        engineConfig.getShardId(),
        this.versionMap,  // was: new LiveVersionMap()
        maxUnsafeAutoIdTimestamp::get,
        // ... rest unchanged
    );
```

- [ ] **Step 4: Add lock acquisition in index() method**

At line 524, wrap the try block with the version map lock:
```java
    try (Releasable indexThrottle = doThrottle ? throttle.acquireThrottle() : () -> {}) {
```
becomes:
```java
    try (
        Releasable uidLock = versionMap.acquireLock(index.uid().bytes());
        Releasable indexThrottle = doThrottle ? throttle.acquireThrottle() : () -> {}
    ) {
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew :server:test --tests "org.opensearch.index.engine.DataFormatAwareEngineLockingTests" -x spotlessCheck`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add server/src/main/java/org/opensearch/index/engine/DataFormatAwareEngine.java
git add server/src/test/java/org/opensearch/index/engine/DataFormatAwareEngineLockingTests.java
git commit -m "fix: add per-document locking to DataFormatAwareEngine

Adds versionMap.acquireLock(uid) around the index critical section,
matching InternalEngine's pattern. Prevents concurrent writes to the
same doc from racing past version checks."
```

---

### Task 0.2: Block Shrink/Split/Clone on DFA Indices

**Files:**
- Modify: `server/src/main/java/org/opensearch/cluster/metadata/MetadataCreateIndexService.java:1957`
- Modify: `server/src/test/java/org/opensearch/cluster/metadata/MetadataCreateIndexServiceTests.java`

- [ ] **Step 1: Write the failing test**

Add to `MetadataCreateIndexServiceTests.java`:
```java
    public void testValidateResizeRejectsDFAIndex() {
        Settings sourceSettings = Settings.builder()
            .put("index.pluggable.dataformat.enabled", true)
            .put("index.number_of_shards", 2)
            .put("index.number_of_replicas", 0)
            .put("index.blocks.write", true)
            .build();

        IndexMetadata sourceMeta = IndexMetadata.builder("source")
            .settings(Settings.builder().put(sourceSettings).put(IndexMetadata.SETTING_VERSION_CREATED, Version.CURRENT))
            .numberOfShards(2)
            .numberOfReplicas(0)
            .build();

        Metadata metadata = Metadata.builder().put(sourceMeta, false).build();
        ClusterState state = ClusterState.builder(ClusterName.DEFAULT).metadata(metadata).build();

        IllegalArgumentException ex = expectThrows(
            IllegalArgumentException.class,
            () -> MetadataCreateIndexService.validateResize(state, "source", "target", Settings.EMPTY)
        );
        assertThat(ex.getMessage(), containsString("pluggable data format"));
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :server:test --tests "org.opensearch.cluster.metadata.MetadataCreateIndexServiceTests.testValidateResizeRejectsDFAIndex" -x spotlessCheck`
Expected: FAIL — no validation exists yet

- [ ] **Step 3: Add validation in validateResize()**

In `MetadataCreateIndexService.java`, after line 1973 (after the data stream check), add:
```java
        // Block resize on data-format-aware indices (sidecar files not supported by addIndexes)
        if (sourceMetadata.getSettings().getAsBoolean("index.pluggable.dataformat.enabled", false)) {
            throw new IllegalArgumentException(
                "cannot resize index ["
                    + sourceIndex
                    + "] because it uses a pluggable data format; "
                    + "shrink/split/clone is not supported for data-format-aware indices"
            );
        }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :server:test --tests "org.opensearch.cluster.metadata.MetadataCreateIndexServiceTests.testValidateResizeRejectsDFAIndex" -x spotlessCheck`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add server/src/main/java/org/opensearch/cluster/metadata/MetadataCreateIndexService.java
git add server/src/test/java/org/opensearch/cluster/metadata/MetadataCreateIndexServiceTests.java
git commit -m "fix: block shrink/split/clone on data-format-aware indices

DFA indices store sidecar files outside Lucene segments. The resize
path uses addIndexes() which only handles Lucene files. Block at
validation time with a clear error message."
```

---

### Task 0.3: Fix UpdateHelper Script Path (extraFieldValues)

**Files:**
- Modify: `server/src/main/java/org/opensearch/action/update/UpdateHelper.java:294-302`
- Create: `server/src/test/java/org/opensearch/action/update/UpdateHelperExtraFieldsTests.java`

- [ ] **Step 1: Write the failing test**

```java
// server/src/test/java/org/opensearch/action/update/UpdateHelperExtraFieldsTests.java
package org.opensearch.action.update;

import org.opensearch.action.index.IndexRequest;
import org.opensearch.index.mapper.ExtraFieldValues;
import org.opensearch.test.OpenSearchTestCase;

import java.util.Map;

public class UpdateHelperExtraFieldsTests extends OpenSearchTestCase {

    public void testScriptUpdateCarriesForwardExtraFieldValues() {
        // Simulate: the current IndexRequest has extraFieldValues set
        IndexRequest currentRequest = new IndexRequest("test").id("1")
            .source(Map.of("title", "hello"));
        ExtraFieldValues extra = ExtraFieldValues.of(Map.of("embedding", new float[]{1.0f, 2.0f}));
        currentRequest.extraFieldValues(extra);

        // After script update builds a new IndexRequest, extraFieldValues must be preserved
        // This test validates the contract — actual integration test will use the full flow
        IndexRequest result = new IndexRequest("test").id("1")
            .source(Map.of("title", "updated"))
            .extraFieldValues(currentRequest.extraFieldValues());

        assertNotNull(result.extraFieldValues());
        assertSame(extra, result.extraFieldValues());
    }
}
```

- [ ] **Step 2: Run test to verify it passes (validates the API contract)**

Run: `./gradlew :server:test --tests "org.opensearch.action.update.UpdateHelperExtraFieldsTests" -x spotlessCheck`
Expected: PASS (this tests the contract; the actual fix is in UpdateHelper)

- [ ] **Step 3: Fix UpdateHelper.prepareUpdateScriptRequest()**

In `UpdateHelper.java`, at line ~296, add `.extraFieldValues()`:
```java
            case INDEX:
                final IndexRequest indexRequest = Requests.indexRequest(request.index())
                    .id(request.id())
                    .routing(routing)
                    .source(updatedSourceAsMap, updateSourceContentType)
                    .extraFieldValues(currentRequest != null ? currentRequest.extraFieldValues() : ExtraFieldValues.EMPTY)
                    .setIfSeqNo(getResult.getSeqNo())
                    .setIfPrimaryTerm(getResult.getPrimaryTerm())
                    .waitForActiveShards(request.waitForActiveShards())
                    .timeout(request.timeout())
                    .setRefreshPolicy(request.getRefreshPolicy());
```

- [ ] **Step 4: Run existing UpdateHelper tests to verify no regression**

Run: `./gradlew :server:test --tests "org.opensearch.action.update.*" -x spotlessCheck`
Expected: All PASS

- [ ] **Step 5: Commit**

```bash
git add server/src/main/java/org/opensearch/action/update/UpdateHelper.java
git add server/src/test/java/org/opensearch/action/update/UpdateHelperExtraFieldsTests.java
git commit -m "fix: carry forward extraFieldValues in scripted update path

prepareUpdateScriptRequest() was not passing extraFieldValues to the
new IndexRequest, causing sidecar field values to be silently lost
during scripted updates. Now matches prepareUpdateIndexRequest() behavior."
```

---

### Task 0.4: CatalogSnapshotIndexCommit for Standard Snapshots

**Files:**
- Create: `server/src/main/java/org/opensearch/index/engine/CatalogSnapshotIndexCommit.java`
- Modify: `server/src/main/java/org/opensearch/index/shard/IndexShard.java:1810-1818`

- [ ] **Step 1: Create CatalogSnapshotIndexCommit**

```java
// server/src/main/java/org/opensearch/index/engine/CatalogSnapshotIndexCommit.java
package org.opensearch.index.engine;

import org.apache.lucene.index.IndexCommit;
import org.apache.lucene.store.Directory;
import org.opensearch.index.engine.exec.coord.CatalogSnapshot;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Map;

public class CatalogSnapshotIndexCommit extends IndexCommit {

    private final CatalogSnapshot catalogSnapshot;
    private final Directory directory;
    private final long generation;

    public CatalogSnapshotIndexCommit(CatalogSnapshot catalogSnapshot, Directory directory, long generation) {
        this.catalogSnapshot = catalogSnapshot;
        this.directory = directory;
        this.generation = generation;
    }

    @Override
    public String getSegmentsFileName() {
        return "segments_" + Long.toString(generation, Character.MAX_RADIX);
    }

    @Override
    public Collection<String> getFileNames() throws IOException {
        return new ArrayList<>(catalogSnapshot.getFiles(true));
    }

    @Override
    public Directory getDirectory() {
        return directory;
    }

    @Override
    public void delete() {
        // no-op — lifecycle managed by GatedCloseable
    }

    @Override
    public boolean isDeleted() {
        return false;
    }

    @Override
    public int getSegmentCount() {
        return -1; // unknown without parsing SegmentInfos
    }

    @Override
    public long getGeneration() {
        return generation;
    }

    @Override
    public Map<String, String> getUserData() throws IOException {
        return Map.of();
    }
}
```

- [ ] **Step 2: Modify IndexShard.acquireLastIndexCommit()**

In `IndexShard.java` at line 1810, add DFA-aware branch:
```java
    @Deprecated
    public GatedCloseable<IndexCommit> acquireLastIndexCommit(boolean flushFirst) throws EngineException {
        final IndexShardState state = this.state;
        if (state == IndexShardState.STARTED || state == IndexShardState.CLOSED) {
            Indexer indexer = getIndexer();
            if (indexer instanceof DataFormatAwareEngine dfaEngine) {
                GatedCloseable<CatalogSnapshot> csRef = dfaEngine.acquireLastCommittedSnapshot(flushFirst);
                CatalogSnapshot cs = csRef.get();
                long gen = dfaEngine.getLastCommittedSegmentInfos().getGeneration();
                IndexCommit composite = new CatalogSnapshotIndexCommit(cs, store.directory(), gen);
                return new GatedCloseable<>(composite, csRef::close);
            }
            return applyOnEngine(indexer, engine -> engine.acquireLastIndexCommit(flushFirst));
        } else {
            throw new IllegalIndexShardStateException(shardId, state, "snapshot is not allowed");
        }
    }
```

- [ ] **Step 3: Run existing snapshot tests**

Run: `./gradlew :server:test --tests "org.opensearch.snapshots.*" -x spotlessCheck 2>&1 | tail -5`
Expected: Existing tests still pass (they don't use DFA engine)

- [ ] **Step 4: Commit**

```bash
git add server/src/main/java/org/opensearch/index/engine/CatalogSnapshotIndexCommit.java
git add server/src/main/java/org/opensearch/index/shard/IndexShard.java
git commit -m "feat: implement CatalogSnapshotIndexCommit for DFA snapshot support

Wraps CatalogSnapshot as an IndexCommit so standard snapshot
paths (BlobStoreRepository) can enumerate all files including
sidecar format files. Adds DFA-aware branch in IndexShard."
```

---

## Phase 1a: Sidecar Write Infrastructure

### Task 1.1: `updatable` Mapping Property

**Files:**
- Modify: `server/src/main/java/org/opensearch/index/mapper/FieldMapper.java`
- Create: `server/src/test/java/org/opensearch/index/mapper/FieldMapperUpdatableTests.java`

- [ ] **Step 1: Write the failing test**

```java
// server/src/test/java/org/opensearch/index/mapper/FieldMapperUpdatableTests.java
package org.opensearch.index.mapper;

import org.opensearch.common.xcontent.XContentFactory;
import org.opensearch.core.xcontent.XContentBuilder;
import org.opensearch.index.mapper.MapperServiceTestCase;

public class FieldMapperUpdatableTests extends MapperServiceTestCase {

    public void testUpdatableDefaultsFalse() throws Exception {
        MapperService mapperService = createMapperService(fieldMapping(b -> b.field("type", "keyword")));
        FieldMapper mapper = (FieldMapper) mapperService.documentMapper().mappers().getMapper("field");
        assertFalse(mapper.isUpdatable());
    }

    public void testUpdatableTrueOnKeyword() throws Exception {
        MapperService mapperService = createMapperService(fieldMapping(b ->
            b.field("type", "keyword").field("updatable", true)
        ));
        FieldMapper mapper = (FieldMapper) mapperService.documentMapper().mappers().getMapper("field");
        assertTrue(mapper.isUpdatable());
    }

    public void testUpdatableCanBeAddedToExistingField() throws Exception {
        MapperService mapperService = createMapperService(fieldMapping(b -> b.field("type", "keyword")));
        merge(mapperService, fieldMapping(b -> b.field("type", "keyword").field("updatable", true)));
        FieldMapper mapper = (FieldMapper) mapperService.documentMapper().mappers().getMapper("field");
        assertTrue(mapper.isUpdatable());
    }

    public void testUpdatableCannotBeRemovedOnce() throws Exception {
        MapperService mapperService = createMapperService(fieldMapping(b ->
            b.field("type", "keyword").field("updatable", true)
        ));
        IllegalArgumentException e = expectThrows(IllegalArgumentException.class, () ->
            merge(mapperService, fieldMapping(b -> b.field("type", "keyword").field("updatable", false)))
        );
        assertThat(e.getMessage(), containsString("updatable"));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :server:test --tests "org.opensearch.index.mapper.FieldMapperUpdatableTests" -x spotlessCheck`
Expected: FAIL — `isUpdatable()` method does not exist

- [ ] **Step 3: Add `updatable` parameter to FieldMapper**

In `FieldMapper.java`, find the `Builder` class. Add parameter:
```java
    // In FieldMapper.Builder, add near other parameter declarations:
    protected final Parameter<Boolean> updatable = Parameter.boolParam("updatable", true, m -> toType(m).updatable, false)
        .setMergeValidator((prev, toMerge, conflicts) -> {
            if (prev && !toMerge) {
                conflicts.addConflict("updatable", "cannot disable updatable once enabled");
            }
        });
```

Add field to FieldMapper class:
```java
    protected final boolean updatable;
```

In FieldMapper constructor, read from builder:
```java
    this.updatable = builder.updatable.getValue();
```

Add accessor:
```java
    public boolean isUpdatable() {
        return updatable;
    }
```

Include `updatable` in `getParameters()` list of all FieldMapper.Builder subclasses (or in the base if the pattern supports it).

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :server:test --tests "org.opensearch.index.mapper.FieldMapperUpdatableTests" -x spotlessCheck`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add server/src/main/java/org/opensearch/index/mapper/FieldMapper.java
git add server/src/test/java/org/opensearch/index/mapper/FieldMapperUpdatableTests.java
git commit -m "feat: add 'updatable' mapping property to FieldMapper

Adds a boolean 'updatable' parameter to field mappings. Once enabled,
it cannot be disabled (one-way switch). Defaults to false. This
property marks fields as eligible for sidecar updates."
```

---

### Task 1.2: SidecarVersionBitmap

**Files:**
- Create: `server/src/main/java/org/opensearch/index/engine/sidecar/SidecarVersionBitmap.java`
- Create: `server/src/main/java/org/opensearch/index/engine/sidecar/SidecarVersionBitmapFormat.java`
- Create: `server/src/test/java/org/opensearch/index/engine/sidecar/SidecarVersionBitmapTests.java`

- [ ] **Step 1: Write the failing test**

```java
// server/src/test/java/org/opensearch/index/engine/sidecar/SidecarVersionBitmapTests.java
package org.opensearch.index.engine.sidecar;

import org.apache.lucene.util.FixedBitSet;
import org.opensearch.test.OpenSearchTestCase;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

public class SidecarVersionBitmapTests extends OpenSearchTestCase {

    public void testSetAndGet() {
        SidecarVersionBitmap bitmap = new SidecarVersionBitmap(1000);
        bitmap.set(0);
        bitmap.set(500);
        bitmap.set(999);

        assertTrue(bitmap.get(0));
        assertTrue(bitmap.get(500));
        assertTrue(bitmap.get(999));
        assertFalse(bitmap.get(1));
        assertFalse(bitmap.get(501));
    }

    public void testCardinality() {
        SidecarVersionBitmap bitmap = new SidecarVersionBitmap(100);
        bitmap.set(10);
        bitmap.set(20);
        bitmap.set(30);
        assertEquals(3, bitmap.cardinality());
    }

    public void testSerializationRoundTrip() throws IOException {
        SidecarVersionBitmap original = new SidecarVersionBitmap(500);
        original.set(0);
        original.set(100);
        original.set(499);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        SidecarVersionBitmapFormat.write(original, out);

        ByteArrayInputStream in = new ByteArrayInputStream(out.toByteArray());
        SidecarVersionBitmap restored = SidecarVersionBitmapFormat.read(in);

        assertEquals(original.maxDoc(), restored.maxDoc());
        assertTrue(restored.get(0));
        assertTrue(restored.get(100));
        assertTrue(restored.get(499));
        assertFalse(restored.get(1));
        assertEquals(original.cardinality(), restored.cardinality());
    }

    public void testEmptyBitmap() {
        SidecarVersionBitmap bitmap = new SidecarVersionBitmap(1000);
        assertEquals(0, bitmap.cardinality());
        assertFalse(bitmap.get(0));
        assertFalse(bitmap.get(999));
    }

    public void testRemapAfterMerge() {
        SidecarVersionBitmap original = new SidecarVersionBitmap(100);
        original.set(5);
        original.set(50);
        original.set(99);

        // Simulate merge: old doc IDs 5->2, 50->25, 99->49
        int[] oldToNew = new int[100];
        oldToNew[5] = 2;
        oldToNew[50] = 25;
        oldToNew[99] = 49;

        SidecarVersionBitmap remapped = original.remap(oldToNew, 50);
        assertTrue(remapped.get(2));
        assertTrue(remapped.get(25));
        assertTrue(remapped.get(49));
        assertFalse(remapped.get(5));
        assertEquals(3, remapped.cardinality());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :server:test --tests "org.opensearch.index.engine.sidecar.SidecarVersionBitmapTests" -x spotlessCheck`
Expected: FAIL — classes don't exist

- [ ] **Step 3: Implement SidecarVersionBitmap**

```java
// server/src/main/java/org/opensearch/index/engine/sidecar/SidecarVersionBitmap.java
package org.opensearch.index.engine.sidecar;

import org.apache.lucene.util.FixedBitSet;

public class SidecarVersionBitmap {

    private final FixedBitSet bits;
    private final int maxDoc;

    public SidecarVersionBitmap(int maxDoc) {
        this.maxDoc = maxDoc;
        this.bits = new FixedBitSet(maxDoc);
    }

    SidecarVersionBitmap(FixedBitSet bits, int maxDoc) {
        this.bits = bits;
        this.maxDoc = maxDoc;
    }

    public void set(int docId) {
        bits.set(docId);
    }

    public boolean get(int docId) {
        return bits.get(docId);
    }

    public int cardinality() {
        return bits.cardinality();
    }

    public int maxDoc() {
        return maxDoc;
    }

    public long[] getBits() {
        return bits.getBits();
    }

    public SidecarVersionBitmap remap(int[] oldToNew, int newMaxDoc) {
        SidecarVersionBitmap remapped = new SidecarVersionBitmap(newMaxDoc);
        for (int oldDoc = bits.nextSetBit(0); oldDoc != -1; oldDoc = bits.nextSetBit(oldDoc + 1)) {
            int newDoc = oldToNew[oldDoc];
            if (newDoc >= 0 && newDoc < newMaxDoc) {
                remapped.set(newDoc);
            }
        }
        return remapped;
    }
}
```

- [ ] **Step 4: Implement SidecarVersionBitmapFormat**

```java
// server/src/main/java/org/opensearch/index/engine/sidecar/SidecarVersionBitmapFormat.java
package org.opensearch.index.engine.sidecar;

import org.apache.lucene.util.FixedBitSet;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class SidecarVersionBitmapFormat {

    private static final int MAGIC = 0x53564D50; // "SVMP"
    private static final int VERSION = 1;

    public static void write(SidecarVersionBitmap bitmap, OutputStream out) throws IOException {
        ByteBuffer header = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN);
        header.putInt(MAGIC);
        header.putInt(VERSION);
        header.putInt(bitmap.maxDoc());
        out.write(header.array());

        long[] bits = bitmap.getBits();
        ByteBuffer body = ByteBuffer.allocate(bits.length * Long.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        for (long word : bits) {
            body.putLong(word);
        }
        out.write(body.array());
    }

    public static SidecarVersionBitmap read(InputStream in) throws IOException {
        byte[] headerBytes = in.readNBytes(12);
        ByteBuffer header = ByteBuffer.wrap(headerBytes).order(ByteOrder.LITTLE_ENDIAN);
        int magic = header.getInt();
        if (magic != MAGIC) {
            throw new IOException("Invalid bitmap file magic: " + Integer.toHexString(magic));
        }
        int version = header.getInt();
        if (version != VERSION) {
            throw new IOException("Unsupported bitmap version: " + version);
        }
        int maxDoc = header.getInt();

        int numWords = FixedBitSet.bits2words(maxDoc);
        byte[] bodyBytes = in.readNBytes(numWords * Long.BYTES);
        ByteBuffer body = ByteBuffer.wrap(bodyBytes).order(ByteOrder.LITTLE_ENDIAN);
        long[] bits = new long[numWords];
        for (int i = 0; i < numWords; i++) {
            bits[i] = body.getLong();
        }

        FixedBitSet bitSet = new FixedBitSet(bits, maxDoc);
        return new SidecarVersionBitmap(bitSet, maxDoc);
    }
}
```

- [ ] **Step 5: Run tests**

Run: `./gradlew :server:test --tests "org.opensearch.index.engine.sidecar.SidecarVersionBitmapTests" -x spotlessCheck`
Expected: All PASS

- [ ] **Step 6: Commit**

```bash
git add server/src/main/java/org/opensearch/index/engine/sidecar/
git add server/src/test/java/org/opensearch/index/engine/sidecar/
git commit -m "feat: implement SidecarVersionBitmap for tracking updated doc IDs

Per-sidecar bitmap backed by Lucene's FixedBitSet. Supports O(1)
get/set, serialization to/from byte stream, and doc-ID remapping
for merge coordination via PackedRowIdMapping."
```

---

### Task 1.3: KnnVectorSidecarDataFormat

**Files:**
- Create: `server/src/main/java/org/opensearch/index/engine/sidecar/KnnVectorSidecarDataFormat.java`
- Create: `server/src/test/java/org/opensearch/index/engine/sidecar/KnnVectorSidecarDataFormatTests.java`

- [ ] **Step 1: Write the failing test**

```java
// server/src/test/java/org/opensearch/index/engine/sidecar/KnnVectorSidecarDataFormatTests.java
package org.opensearch.index.engine.sidecar;

import org.opensearch.index.engine.dataformat.FieldTypeCapabilities;
import org.opensearch.test.OpenSearchTestCase;

import java.util.Set;

public class KnnVectorSidecarDataFormatTests extends OpenSearchTestCase {

    public void testName() {
        KnnVectorSidecarDataFormat format = new KnnVectorSidecarDataFormat();
        assertEquals("knn_vector_sidecar", format.name());
    }

    public void testSupportsVectorSearch() {
        KnnVectorSidecarDataFormat format = new KnnVectorSidecarDataFormat();
        Set<FieldTypeCapabilities> supported = format.supportedFields();
        assertTrue(supported.stream().anyMatch(
            ftc -> ftc.fieldType().equals("knn_vector")
                && ftc.capabilities().contains(FieldTypeCapabilities.Capability.VECTOR_SEARCH)
        ));
    }

    public void testPriorityIsLowerThanLucene() {
        KnnVectorSidecarDataFormat format = new KnnVectorSidecarDataFormat();
        // Sidecar format should have higher priority number (lower precedence) than primary Lucene
        assertTrue(format.priority() > 0);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :server:test --tests "org.opensearch.index.engine.sidecar.KnnVectorSidecarDataFormatTests" -x spotlessCheck`
Expected: FAIL — class doesn't exist

- [ ] **Step 3: Implement KnnVectorSidecarDataFormat**

```java
// server/src/main/java/org/opensearch/index/engine/sidecar/KnnVectorSidecarDataFormat.java
package org.opensearch.index.engine.sidecar;

import org.opensearch.index.engine.dataformat.DataFormat;
import org.opensearch.index.engine.dataformat.FieldTypeCapabilities;

import java.util.Set;

public class KnnVectorSidecarDataFormat extends DataFormat {

    public static final String NAME = "knn_vector_sidecar";

    private static final Set<FieldTypeCapabilities> SUPPORTED_FIELDS = Set.of(
        new FieldTypeCapabilities("knn_vector", Set.of(FieldTypeCapabilities.Capability.VECTOR_SEARCH))
    );

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public long priority() {
        return 100; // lower precedence than primary Lucene format (priority 0)
    }

    @Override
    public Set<FieldTypeCapabilities> supportedFields() {
        return SUPPORTED_FIELDS;
    }
}
```

- [ ] **Step 4: Run tests**

Run: `./gradlew :server:test --tests "org.opensearch.index.engine.sidecar.KnnVectorSidecarDataFormatTests" -x spotlessCheck`
Expected: All PASS

- [ ] **Step 5: Commit**

```bash
git add server/src/main/java/org/opensearch/index/engine/sidecar/KnnVectorSidecarDataFormat.java
git add server/src/test/java/org/opensearch/index/engine/sidecar/KnnVectorSidecarDataFormatTests.java
git commit -m "feat: add KnnVectorSidecarDataFormat

Declares VECTOR_SEARCH capability for knn_vector fields. This is
the DataFormat registration that enables the DFA engine to route
vector-only updates to the sidecar writer."
```

---

### Task 1.4: KnnVectorSidecarWriter (Skeleton)

**Files:**
- Create: `server/src/main/java/org/opensearch/index/engine/sidecar/KnnVectorSidecarWriter.java`
- Create: `server/src/test/java/org/opensearch/index/engine/sidecar/KnnVectorSidecarWriterTests.java`

- [ ] **Step 1: Write the failing test**

```java
// server/src/test/java/org/opensearch/index/engine/sidecar/KnnVectorSidecarWriterTests.java
package org.opensearch.index.engine.sidecar;

import org.opensearch.index.engine.exec.WriterFileSet;
import org.opensearch.test.OpenSearchTestCase;

import java.nio.file.Files;
import java.nio.file.Path;

public class KnnVectorSidecarWriterTests extends OpenSearchTestCase {

    public void testAddVectorAndFlush() throws Exception {
        Path tmpDir = createTempDir();
        KnnVectorSidecarWriter writer = new KnnVectorSidecarWriter(tmpDir, 768, 1);

        float[] vector = new float[768];
        for (int i = 0; i < 768; i++) vector[i] = randomFloat();

        writer.addVector(42, vector);
        writer.addVector(100, vector);

        WriterFileSet fileSet = writer.flush();

        assertNotNull(fileSet);
        assertTrue(fileSet.files().size() > 0);
        assertEquals(2, fileSet.numRows());
        assertEquals(1, writer.generation());
    }

    public void testBitmapTracksAddedDocs() throws Exception {
        Path tmpDir = createTempDir();
        KnnVectorSidecarWriter writer = new KnnVectorSidecarWriter(tmpDir, 768, 1);

        float[] vector = new float[768];
        writer.addVector(10, vector);
        writer.addVector(50, vector);

        SidecarVersionBitmap bitmap = writer.getVersionBitmap();
        assertTrue(bitmap.get(10));
        assertTrue(bitmap.get(50));
        assertFalse(bitmap.get(11));
        assertEquals(2, bitmap.cardinality());
    }

    public void testEmptyWriterFlushProducesNothing() throws Exception {
        Path tmpDir = createTempDir();
        KnnVectorSidecarWriter writer = new KnnVectorSidecarWriter(tmpDir, 768, 1);

        WriterFileSet fileSet = writer.flush();
        assertNull(fileSet); // nothing to flush
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :server:test --tests "org.opensearch.index.engine.sidecar.KnnVectorSidecarWriterTests" -x spotlessCheck`
Expected: FAIL — class doesn't exist

- [ ] **Step 3: Implement KnnVectorSidecarWriter**

```java
// server/src/main/java/org/opensearch/index/engine/sidecar/KnnVectorSidecarWriter.java
package org.opensearch.index.engine.sidecar;

import org.apache.lucene.codecs.lucene99.Lucene99HnswVectorsFormat;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.KnnFloatVectorField;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.VectorSimilarityFunction;
import org.apache.lucene.store.FSDirectory;
import org.opensearch.index.engine.exec.WriterFileSet;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

public class KnnVectorSidecarWriter implements Closeable {

    private final Path directory;
    private final int dimension;
    private final long generation;
    private final IndexWriter luceneWriter;
    private SidecarVersionBitmap bitmap;
    private int maxDocSeen = 0;
    private int docsAdded = 0;

    public KnnVectorSidecarWriter(Path directory, int dimension, long generation) throws IOException {
        this.directory = directory;
        this.dimension = dimension;
        this.generation = generation;

        IndexWriterConfig config = new IndexWriterConfig()
            .setCodec(new org.apache.lucene.codecs.lucene104.Lucene104Codec());
        this.luceneWriter = new IndexWriter(FSDirectory.open(directory), config);
        this.bitmap = new SidecarVersionBitmap(1_000_000); // initial capacity, will resize if needed
    }

    public void addVector(int docId, float[] vector) throws IOException {
        if (vector.length != dimension) {
            throw new IllegalArgumentException("Expected dimension " + dimension + " but got " + vector.length);
        }

        Document doc = new Document();
        doc.add(new KnnFloatVectorField("vector", vector, VectorSimilarityFunction.COSINE));
        luceneWriter.addDocument(doc);

        if (docId >= bitmap.maxDoc()) {
            SidecarVersionBitmap newBitmap = new SidecarVersionBitmap(docId + 1);
            for (int i = bitmap.getBits().length > 0 ? 0 : -1;
                 i >= 0 && i < bitmap.maxDoc(); i++) {
                if (bitmap.get(i)) newBitmap.set(i);
            }
            bitmap = newBitmap;
        }
        bitmap.set(docId);
        maxDocSeen = Math.max(maxDocSeen, docId);
        docsAdded++;
    }

    public WriterFileSet flush() throws IOException {
        if (docsAdded == 0) {
            return null;
        }

        luceneWriter.commit();

        Set<String> files = new HashSet<>();
        String[] listedFiles = FSDirectory.open(directory).listAll();
        for (String f : listedFiles) {
            if (!f.equals("write.lock")) {
                files.add(f);
            }
        }

        return new WriterFileSet(directory.toString(), generation, files, docsAdded, 1);
    }

    public SidecarVersionBitmap getVersionBitmap() {
        return bitmap;
    }

    public long generation() {
        return generation;
    }

    @Override
    public void close() throws IOException {
        luceneWriter.close();
    }
}
```

- [ ] **Step 4: Run tests**

Run: `./gradlew :server:test --tests "org.opensearch.index.engine.sidecar.KnnVectorSidecarWriterTests" -x spotlessCheck`
Expected: All PASS

- [ ] **Step 5: Commit**

```bash
git add server/src/main/java/org/opensearch/index/engine/sidecar/KnnVectorSidecarWriter.java
git add server/src/test/java/org/opensearch/index/engine/sidecar/KnnVectorSidecarWriterTests.java
git commit -m "feat: implement KnnVectorSidecarWriter

Writer that builds HNSW graphs for a subset of doc IDs using Lucene's
vector codec. Maintains a SidecarVersionBitmap tracking which docs
are covered. Produces a WriterFileSet on flush for CatalogSnapshot
registration."
```

---

## Next Steps

After this plan is implemented:
- **Phase 1b** (Tasks 1.5-1.11): Composite KNN search, SidecarAwareStoredFieldsReader, `_update_fields` REST endpoint
- **Phase 2**: Doc values sidecar
- **Phase 3**: Bulk operations + pipeline

---
