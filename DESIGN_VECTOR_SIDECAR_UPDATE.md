# Design: Editable Segments — Field-Level Updates Without Full Reindex

## Problem Statement

When users need to update a subset of fields across an index (e.g., text embeddings, metadata tags, scores), the current approach requires full document reindex via bulk ingest. This is extremely wasteful because:
- Only the target field(s) change; all other fields remain identical
- Full reindex rewrites inverted indexes, BKD trees, stored fields, AND rebuilds HNSW graphs
- For a 10KB document where only a 3KB vector changes, 70%+ of I/O is redundant
- Multiplied by millions of documents, the cost is enormous in CPU, I/O, and merge amplification

## Solution: Editable Sidecar Segments

Leverage OpenSearch's existing `DataFormatAwareEngine` infrastructure to write field updates as **sidecar segments** — independent file sets that contain only the updated field data, co-located with the base Lucene segment. The base segment remains untouched.

### Supported Sidecar Types

| Sidecar Type | Field Types | Capability | Use Case |
|--------------|-------------|------------|----------|
| `knn_vector` | `knn_vector`, `dense_vector` | `VECTOR_SEARCH` | Re-embed with new model, update embeddings |
| `doc_values` | `keyword`, `long`, `double`, `date`, `ip`, `boolean` | `COLUMNAR_STORAGE` | Update scores, tags, metadata, computed fields |
| `inverted` | `text`, `keyword` (with `index: true`) | `FULL_TEXT_SEARCH` | Reanalyze with new analyzer, add search synonyms |

Each type uses the appropriate Lucene codec infrastructure (per-field via `PerFieldKnnVectorsFormat`, `PerFieldDocValuesFormat`, or `PerFieldPostingsFormat`) and writes independent file sets.

---

## Mapping API: `updatable` Field Property

### Design Philosophy

- **Zero ceremony** — add one property to the field mapping, everything else is automatic
- **Opt-in per field** — only fields marked `updatable` get sidecar treatment
- **Transparent to search** — queries work identically whether data is in base or sidecar
- **Compatible with existing mappings** — add `updatable` to existing fields via `_mapping` update

### Field-Level Declaration

```json
PUT /my-index
{
  "settings": {
    "index.derived_source.enabled": true
  },
  "mappings": {
    "properties": {
      "title": {
        "type": "text"
      },
      "content": {
        "type": "text"
      },
      "embedding": {
        "type": "knn_vector",
        "dimension": 768,
        "method": { "name": "hnsw", "engine": "lucene" },
        "updatable": true
      },
      "category_tags": {
        "type": "keyword",
        "updatable": true
      },
      "relevance_score": {
        "type": "float",
        "updatable": true
      },
      "summary_embedding": {
        "type": "knn_vector",
        "dimension": 384,
        "method": { "name": "hnsw", "engine": "lucene" },
        "updatable": true
      }
    }
  }
}
```

That's it. The `"updatable": true` property declares that a field supports in-place sidecar updates. No other configuration required.

### Adding `updatable` to Existing Fields

```json
PUT /my-index/_mapping
{
  "properties": {
    "embedding": {
      "type": "knn_vector",
      "dimension": 768,
      "updatable": true
    }
  }
}
```

This is a non-breaking mapping change (additive property). Existing data continues to be served from the base segment. Only future updates use the sidecar path.

### Advanced: Sidecar Tuning (Optional)

For power users who want control over merge behavior:

```json
PUT /my-index
{
  "settings": {
    "index.updatable_fields.max_sidecar_generations": 5,
    "index.updatable_fields.merge_threshold_pct": 30,
    "index.updatable_fields.backpressure_enabled": true
  },
  "mappings": {
    "properties": {
      "embedding": {
        "type": "knn_vector",
        "dimension": 768,
        "updatable": true,
        "updatable_options": {
          "max_generations": 8,
          "merge_on_refresh": false
        }
      }
    }
  }
}
```

| Setting | Level | Default | Description |
|---------|-------|---------|-------------|
| `index.updatable_fields.max_sidecar_generations` | Index | `5` | Max sidecar generations per segment before backpressure |
| `index.updatable_fields.merge_threshold_pct` | Index | `30` | Auto-promote to full reindex when >N% of docs updated |
| `index.updatable_fields.backpressure_enabled` | Index | `true` | Block writes when sidecar limit reached (vs. force merge) |
| `updatable_options.max_generations` | Field | inherits index | Per-field override for generation limit |
| `updatable_options.merge_on_refresh` | Field | `false` | Eagerly merge sidecars on each refresh (low-latency search) |

---

## Update APIs

### Single Document Update (Existing API — Auto-Detected)

```json
POST /my-index/_update/doc1
{
  "doc": {
    "embedding": [0.1, 0.2, ..., 0.768]
  }
}
```

**Behavior change when `embedding` is `updatable: true`:**
- Engine detects: all changed fields are editable → routes to sidecar writer
- Only vector data is written (no inverted index, no stored fields touched)
- Response is the same as standard `_update`

If the update touches ANY non-editable field, it falls back to full reindex (standard behavior).

### Bulk Field Update (New API)

For the primary use case of re-embedding an entire index with a new model:

```json
POST /my-index/_update_fields
{
  "field": "embedding",
  "updates": [
    { "_id": "doc1", "value": [0.1, 0.2, ...] },
    { "_id": "doc2", "value": [0.3, 0.4, ...] },
    { "_id": "doc3", "value": [0.5, 0.6, ...] }
  ]
}
```

Response:
```json
{
  "took": 2341,
  "updated": 3,
  "failed": 0,
  "sidecars_created": 2,
  "_shards": { "total": 5, "successful": 5, "failed": 0 }
}
```

### Multi-Field Bulk Update

Update multiple editable fields in one call:

```json
POST /my-index/_update_fields
{
  "updates": [
    {
      "_id": "doc1",
      "fields": {
        "embedding": [0.1, 0.2, ...],
        "category_tags": ["science", "ai"],
        "relevance_score": 0.95
      }
    },
    {
      "_id": "doc2",
      "fields": {
        "embedding": [0.3, 0.4, ...],
        "relevance_score": 0.87
      }
    }
  ]
}
```

Fields with different sidecar types are grouped automatically:
- `embedding` → knn_vector sidecar
- `category_tags` → doc_values sidecar  
- `relevance_score` → doc_values sidecar

### Bulk Update via Scroll (for full-index re-embedding)

For updating every document (e.g., after switching to a new embedding model):

```json
POST /my-index/_update_fields_by_query
{
  "field": "embedding",
  "pipeline": "new-embedding-pipeline",
  "source_fields": ["title", "content"],
  "query": { "match_all": {} },
  "batch_size": 1000
}
```

This reads `source_fields` from each document, runs them through the ingest `pipeline` (which produces the new embedding), and writes only the vector sidecar. The base segment is never touched.

### Status & Monitoring

```json
GET /my-index/_updatable_fields/stats
```

Response:
```json
{
  "my-index": {
    "updatable_fields": ["embedding", "category_tags", "relevance_score"],
    "sidecars": {
      "total": 12,
      "by_field": {
        "embedding": { "generations": 3, "docs_updated": 150000, "size_bytes": 450000000 },
        "category_tags": { "generations": 1, "docs_updated": 50000, "size_bytes": 2000000 },
        "relevance_score": { "generations": 2, "docs_updated": 100000, "size_bytes": 800000 }
      },
      "pending_merges": 2
    },
    "efficiency": {
      "bytes_saved_vs_reindex": 4500000000,
      "fields_updated_without_reindex": 300000
    }
  }
}
```

### Force Merge Sidecars

Manually consolidate all sidecars into base segments:

```json
POST /my-index/_updatable_fields/merge
{
  "fields": ["embedding"],
  "max_concurrent_merges": 2
}
```

---

## Architecture (Generalized)

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                        Index Segment View                                     │
│                                                                              │
│  Segment _0 (generation 5)                                                   │
│  ┌─────────────────────────┐ ┌─────────────────────┐ ┌────────────────────┐ │
│  │   Primary (Lucene)      │ │ Sidecar: knn_vector │ │ Sidecar: doc_values│ │
│  │  .fdt .fdx (stored)     │ │ _0_knn_0.vec        │ │ _0_dv_0.dvd        │ │
│  │  .tim .tip (terms)      │ │ _0_knn_0.vex        │ │ _0_dv_0.dvm        │ │
│  │  .doc .pos (postings)   │ │ _0_knn_0.vem        │ │ _0_dv_0.bmp        │ │
│  │  .dvd .dvm (doc values) │ │ _0_knn_0.bmp        │ │                    │ │
│  │  .vec .vex (base vecs)  │ │                     │ │                    │ │
│  │  .fnm (field infos)     │ │                     │ │                    │ │
│  └─────────────────────────┘ └─────────────────────┘ └────────────────────┘ │
│                                                                              │
│  All tracked in CatalogSnapshot as WriterFileSet entries per DataFormat       │
└─────────────────────────────────────────────────────────────────────────────┘
```

### Per-Sidecar-Type Behavior

#### KNN Vector Sidecar (`VECTOR_SEARCH`)

- Writes: new HNSW graph + raw vectors for updated doc IDs
- Search: composite query across base graph (filtered) + sidecar graph(s)
- Merge: rebuild consolidated HNSW graph with `PackedRowIdMapping`
- Files: `.vec`, `.vex`, `.vem`, `.bmp`

#### Doc Values Sidecar (`COLUMNAR_STORAGE`)

- Writes: new doc_values column for updated doc IDs (numeric, binary, sorted set)
- Search: aggregations/sorts read from sidecar if doc ID is in version bitmap, else base
- Merge: straightforward column merge with row remapping
- Files: `.dvd`, `.dvm`, `.bmp`
- Note: Lucene already supports generational DV updates — sidecar approach is even simpler

#### Inverted Index Sidecar (`FULL_TEXT_SEARCH`)

- Writes: new term postings for updated doc IDs (re-analyzed text)
- Search: multi-reader query across base + sidecar postings, merged via standard Lucene multi-reader
- Merge: term dictionary merge (same as segment merge logic)
- Files: `.tim`, `.tip`, `.doc`, `.pos`, `.bmp`
- Constraint: most complex type; requires careful term frequency/position handling

---

## Request Flow (Generalized)

```
1. POST /{index}/_update/{id}  or  POST /{index}/_update_fields
       │
2. UpdateHelper / UpdateFieldsAction determines changed fields
       │
3. Check mapping: are ALL changed fields marked "editable"?
       │── No → full reindex (standard path)
       │── Yes ↓
       │
4. Group fields by sidecar type:
       │── knn_vector fields  → KnnVectorDataFormat writer
       │── doc_values fields  → DocValuesSidecarDataFormat writer
       │── inverted fields    → InvertedSidecarDataFormat writer
       │
5. Each writer builds its sidecar independently, keyed by segment + doc IDs
       │
6. On refresh: new WriterFileSet(s) registered in CatalogSnapshot
       │
7. Search: sidecar-aware readers compose base + sidecar data per field type
       │
8. Background merge: CompositeMergeExecutor consolidates all sidecars
```

---

## Key Design Decisions

### 1. Doc-ID Stability (Solved by existing infrastructure)

`PackedRowIdMapping` provides O(1) bidirectional translation between old and new row IDs after merge. `CompositeMergeExecutor` already merges primary format first, then passes the `RowIdMapping` to secondary format mergers. Each sidecar type registers as a secondary DataFormat — when base merges, sidecars merge with it.

### 2. Version Bitmap (Per-Sidecar Correctness)

Each sidecar carries a bitmap of doc IDs it covers. At search/agg time:
- For a doc ID present in sidecar bitmap → read field value from sidecar
- For a doc ID NOT in sidecar bitmap → read from base segment
- Multiple sidecar generations: latest generation wins (ordered by generation number)

### 3. Sidecar Count Limit (Quality Protection)

| Sidecar Type | Default Max | Rationale |
|--------------|-------------|-----------|
| `knn_vector` | 5 | Recall@10 drops below 0.90 with >5 sub-graphs |
| `doc_values` | 10 | Column reads are cheap; more generations acceptable |
| `inverted` | 3 | Multi-reader overhead is higher for term queries |

When limit reached: backpressure blocks new sidecar writes until merge consolidates.

### 4. Translog Durability

Field-only updates are encoded in the translog as standard INDEX operations carrying a **sparse source** (only the changed fields + a `_sidecar_fields` metadata marker). This preserves:
- Wire compatibility (no new operation type)
- Crash recovery (translog replay re-routes to sidecar writers)
- Replication (ops-based replication still works)

### 5. Delete Handling

Sidecar-aware live-docs: when a document is deleted, all sidecar readers for that doc ID filter it out. On merge, deleted docs are excluded from consolidated output.

### 6. Auto-Detection vs Explicit

The engine auto-detects whether an update can use the sidecar path:
- `_update` API: if all changed fields have `updatable: true` in mapping → sidecar path
- `_update_fields` API: always uses sidecar path (validates fields are editable)
- `_index` / `_bulk` with full documents: always full reindex (user intent is full replace)

---

## Cost Comparison

| Operation | Full Reindex (500K docs) | Sidecar Update (50K docs) |
|-----------|--------------------------|---------------------------|
| **Vector field** | 60-180s, 4GB mem, 5GB I/O | 2-5s, 200MB mem, 150MB I/O |
| **Keyword/numeric field** | 60-180s, 4GB mem, 5GB I/O | <1s, 50MB mem, 5MB I/O |
| **Text field (re-analyze)** | 60-180s, 4GB mem, 5GB I/O | 5-10s, 500MB mem, 200MB I/O |

**Break-even point**: ~30-40% of docs updated in a segment. Beyond that, full segment rebuild is more efficient and the engine auto-promotes.

---

## Identified Risks and Mitigations

| # | Risk | Severity | Mitigation |
|---|------|----------|------------|
| 1 | Translog has no sidecar-specific op type | BLOCKER (mitigated) | Encode as standard INDEX with sparse source + `_sidecar_fields` marker |
| 2 | Segment replication must discover sidecar files | HIGH | Register in CatalogSnapshot; FormatBlobRouter routes per-format |
| 3 | Delete + sidecar race (phantom results) | HIGH | Sidecar-aware live-docs bitmap at search time |
| 4 | Recall degradation (vectors) / query perf (inverted) | HIGH | Per-type sidecar count limits with backpressure |
| 5 | Snapshot/restore backwards compatibility | MEDIUM | Feature-gate via index setting; validate on restore |
| 6 | Inverted index sidecar complexity (positions, offsets) | MEDIUM | Phase inverted sidecars after vector + doc_values proven |

---

## Complete API Reference

### Index Settings

| Setting | Dynamic | Default | Description |
|---------|---------|---------|-------------|
| `index.updatable_fields.enabled` | No | `false` (auto-enabled when any field has `updatable: true`) | Master switch |
| `index.updatable_fields.max_sidecar_generations` | Yes | `5` | Global max across all sidecar types |
| `index.updatable_fields.merge_threshold_pct` | Yes | `30` | Auto-promote to full reindex threshold |
| `index.updatable_fields.backpressure_enabled` | Yes | `true` | Block writes when limit reached |
| `index.derived_source.enabled` | No | `false` | Recommended companion: eliminates _source |

### Mapping Properties

| Property | Applies To | Default | Description |
|----------|-----------|---------|-------------|
| `updatable` | Any supported field type | `false` | Enable sidecar updates for this field |
| `updatable_options.max_generations` | Any editable field | inherits index setting | Per-field generation limit |
| `updatable_options.merge_on_refresh` | Any editable field | `false` | Eager merge on refresh |

### REST Endpoints

| Method | Endpoint | Description |
|--------|----------|-------------|
| `POST` | `/{index}/_update/{id}` | Existing API — auto-detects sidecar path |
| `POST` | `/{index}/_update_fields` | Bulk field update (new) |
| `POST` | `/{index}/_update_fields_by_query` | Update field via pipeline + query (new) |
| `GET` | `/{index}/_updatable_fields/stats` | Sidecar statistics and efficiency metrics |
| `POST` | `/{index}/_updatable_fields/merge` | Force-merge sidecars into base segments |

---

## Example: Full Re-Embedding Workflow

```bash
# 1. Create index with editable embedding field
PUT /articles
{
  "settings": { "index.derived_source.enabled": true },
  "mappings": {
    "properties": {
      "title": { "type": "text" },
      "content": { "type": "text" },
      "embedding": {
        "type": "knn_vector",
        "dimension": 768,
        "method": { "name": "hnsw", "engine": "lucene" },
        "updatable": true
      }
    }
  }
}

# 2. Index 1M documents normally
POST /articles/_bulk
...

# 3. Later: switch to a new embedding model — update ONLY the vector field
POST /articles/_update_fields_by_query
{
  "field": "embedding",
  "pipeline": "new-model-v2",
  "source_fields": ["title", "content"],
  "query": { "match_all": {} },
  "batch_size": 5000,
  "slices": "auto"
}

# 4. Monitor progress
GET /articles/_updatable_fields/stats

# 5. Optional: force consolidate after bulk update completes
POST /articles/_updatable_fields/merge
{ "fields": ["embedding"] }
```

**Cost savings**: For 1M docs × 768-dim vectors, full reindex takes ~30 minutes and 50GB I/O. Sidecar update takes ~3 minutes and 3GB I/O — **10x faster, 16x less I/O**.

---

## Implementation Phases

### Phase 1: KNN Vector Sidecar (Core)
- `KnnVectorSidecarDataFormat` implementing `DataFormat` with `VECTOR_SEARCH`
- `updatable` mapping property on `knn_vector` fields
- `_update_fields` REST endpoint (vector only)
- Version bitmap + composite KNN search
- Sidecar count limit + backpressure

### Phase 2: Doc Values Sidecar
- `DocValuesSidecarDataFormat` with `COLUMNAR_STORAGE`
- `updatable` on `keyword`, `long`, `double`, `date`, `boolean`, `ip` fields
- Composite aggregation/sort reader
- Multi-field `_update_fields` support

### Phase 3: Inverted Index Sidecar
- `InvertedSidecarDataFormat` with `FULL_TEXT_SEARCH`
- `updatable` on `text` fields (re-analysis use case)
- Multi-reader term query composition
- `_update_fields_by_query` with pipeline support

### Phase 4: Operational Maturity
- `_updatable_fields/stats` and `_updatable_fields/merge` endpoints
- Segment replication + remote store integration
- Snapshot/restore support
- Auto-promote to full reindex above threshold

---

## Relationship to Derived Source

| Feature | Role | Optimization |
|---------|------|--------------|
| `derived_source` | Eliminates _source blob | Read-path: per-field reconstruction from doc_values |
| `updatable` fields | Eliminates full reindex | Write-path: per-field sidecar updates |

Together they achieve **full per-field data independence**: each field is stored independently (derived source) and can be updated independently (editable sidecars). This is the architectural foundation for treating OpenSearch indexes as mutable columnar stores while preserving Lucene's immutable segment guarantees.
