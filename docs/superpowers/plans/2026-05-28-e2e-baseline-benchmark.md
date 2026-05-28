# E2E Baseline Benchmark: Standard _update (No Sidecar)

> **Purpose:** Establish the performance baseline for field-level updates using the standard `_update` API (full document rewrite). This is the "before" measurement that sidecar fields are designed to improve.

## Setup

- **OpenSearch:** 2.18.0 (vanilla, no sidecar feature)
- **Dataset:** NFCorpus (BEIR), 3,633 documents, 323 test queries
- **Embeddings:** GTE-multilingual-base (768d) + BGE-M3 (1024d)
- **Cluster:** Single node, 1 shard, 0 replicas
- **Search workload:** 4 threads × 20 QPS = ~80 QPS sustained

## Scenario

1. Index 3,633 docs with GTE embeddings + mock fields (title, content, category, date, score)
2. Benchmark NDCG@10 on GTE
3. Start continuous search workload
4. Add BGE embedding field via standard `_update` (full document rewrite for every doc)
5. Observe search latency during update
6. Re-benchmark both fields

## Results

### NDCG@10

| Field | Baseline | After BGE Added | Change |
|-------|----------|-----------------|--------|
| GTE (768d) | 0.3539 | 0.3535 | -0.0004 (noise) |
| BGE (1024d) | N/A | 0.2895 | — |

### Search Latency During Update

| Phase | Count | Avg (ms) | P50 (ms) | P95 (ms) | P99 (ms) |
|-------|-------|----------|----------|----------|----------|
| Before update | 129 | 8.6 | 8.4 | 10.1 | 13.4 |
| **During update** | 384 | **17.3** | **13.9** | **45.4** | **63.8** |
| After update | 161 | 18.3 | 18.2 | 19.7 | 21.0 |

### Update Performance

| Metric | Value |
|--------|-------|
| Total docs updated | 3,633 |
| Duration | 8.7 seconds |
| Throughput | ~418 docs/sec |
| Avg batch latency | 730 ms/batch (500 docs/batch) |
| Search errors during update | **0** |

### Root Cause of Latency Spike

The P95 spike from 10ms → 45ms during update is caused by:
- **Full document rewrite** — every `_update` reads old doc, adds new field, re-indexes entire doc
- **Segment merge I/O** — new segments trigger background merges, competing for disk
- **Refresh contention** — 1s refresh interval creates new searcher while bulk writes flush
- **Write amplification** — each doc rewrite is ~8KB (old 768d GTE + new 1024d BGE + all fields)

Total write I/O: ~29 MB for 3,633 docs (full rewrite).

### What Sidecar Should Improve

| Metric | Standard _update (baseline) | Expected with Sidecar |
|--------|----------------------------|----------------------|
| Write I/O per doc | ~8 KB (full doc) | ~4 KB (BGE vector only) |
| Total write I/O | ~29 MB | ~15 MB |
| Segment merges triggered | Yes (new full segments) | No (sidecar files only) |
| Refresh impact | High (full segment swap) | Low (sidecar reference added) |
| P95 search latency during update | 45 ms | Expected: ~15-20 ms |
| Documents reprocessed | ALL fields re-indexed | Vector field only |

### Observations

1. ✅ **Search correctness preserved** — GTE NDCG unchanged after BGE added
2. ✅ **Zero search errors** — availability maintained throughout
3. ✅ **BGE field immediately searchable** — produces valid NDCG after update
4. ⚠️ **4.5x P95 latency spike** during update — the pain point sidecar fields solve
5. ⚠️ **Permanent 2x latency increase** after update — index is larger (more vectors per doc)

---

*Baseline captured: 2026-05-28. Sidecar comparison requires building and deploying the `feature/sidecar-fields` branch.*
