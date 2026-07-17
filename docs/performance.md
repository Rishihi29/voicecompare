# Performance Notes

This document records timing, memory, and size observations for the VoiceCompare pipeline.

> These are empirical observations from a single machine (Intel Core i7, 16 GB RAM, 100 Mbps connection). Results will vary.

---

## Java Pipeline

### Web Crawler

| Mode | Time (5 providers) | Notes |
|---|---|---|
| LIVE | 15–45 seconds | Network-bound; depends on provider response time |
| CACHED | < 1 second | Reads from disk, no HTTP |
| LOCAL | < 1 second | Reads from disk |

**Bottleneck:** Network I/O — specifically RingCentral and Twilio occasionally respond slowly (> 10 s). The 3-retry exponential back-off adds up to 12 s of wait time in the worst case.

**Optimisation opportunity:** Parallel fetching with `CompletableFuture` could reduce LIVE mode wall-clock time from ~45s to ~15s. Not implemented because sequential fetching is simpler and CACHED mode already handles development ergonomics.

### Data Structure Population

| Structure | Time | Memory |
|---|---|---|
| SpellChecker (vocabulary) | ~50 ms | ~2 MB (5 000–20 000 words × avg 8 bytes) |
| Trie (insert all words) | ~100 ms | ~15 MB (each node ≈ HashMap + boolean) |
| FrequencyCounter | ~30 ms | ~1 MB |
| InvertedIndex | ~80 ms | ~5 MB |
| PageRanker (rank per keyword) | ~200 ms | ~1 MB |

**Total pipeline time (CACHED mode):** < 2 seconds.

---

## JSON Index Size Analysis

`data/search_index.json` is approximately **550 KB**.

| Section | Size | Reason |
|---|---|---|
| `vocabulary` | ~60 KB | Sorted array of ~8 000 unique words |
| `invertedIndex` | ~200 KB | Every word maps to 1–5 URLs |
| `frequencyMap` | ~150 KB | Per-URL per-word counts |
| `trieMap` | ~100 KB | ~700 prefix buckets |
| `pageRanks` | ~1 KB | 5 URL → score entries |
| `searchLog` | ~1 KB | Seeded queries |

**Why 550 KB is acceptable:**  
The file is fetched once on page load and cached by the browser. On a 10 Mbps connection it loads in ~0.4 seconds. The investment is worthwhile because it enables all intelligence features (autocomplete, spell-check, freq count, inverted index) with zero server round-trips at query time.

**Optimisation opportunity:** The `frequencyMap` could be stripped of words that appear in all 5 pages (stop-word filtering), reducing it by ~30%. The `invertedIndex` could drop words with length < 3. These would bring the total to ~350 KB. Not implemented — current size is within acceptable range.

---

## Frontend Rendering

| Operation | Time | Notes |
|---|---|---|
| CSV parse (14 rows) | < 1 ms | Trivial |
| JSON parse (550 KB) | ~30 ms | Browser's native JSON.parse |
| Initial card render (14 cards) | ~5 ms | Simple DOM generation |
| Filter + re-render | < 3 ms | Linear scan of 14 plans |
| Trie lookup (autocomplete) | < 1 ms | O(1) prefix map + linear bucket scan |
| Levenshtein (JS) per word | < 1 ms | Short words (< 15 chars) |

**Total time to interactive:** ~300–500 ms (dominated by font loading from Google Fonts).

---

## Algorithm Complexity Summary

| Algorithm | Time | Space |
|---|---|---|
| Levenshtein(a, b) | O(m·n) | O(m·n) |
| SpellChecker.suggest() | O(\|V\|·m·n) | O(1) extra |
| Trie.insert(word) | O(k) | O(k) per word |
| Trie.allWithPrefix(prefix) | O(k + R) | O(R) |
| InvertedIndex.addText(url, text) | O(n log n) | O(n) |
| InvertedIndex.lookup(word) | O(1) | O(1) |
| FrequencyCounter.addText() | O(n) | O(n) |
| PageRanker.rank(keyword) | O(P·n) | O(P) |
| CSV parse (frontend) | O(n) | O(n) |

Where: m, n = word lengths; k = prefix length; R = result count; P = number of pages; n = text length; |V| = vocabulary size.
