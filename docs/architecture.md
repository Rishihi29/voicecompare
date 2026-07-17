# System Architecture

## Overview

VoiceCompare is a two-stage system: a **Java data pipeline** that crawls, parses, and exports provider plan data; and a **browser SPA** that consumes the exported data to deliver real-time search, filtering, and comparison with no backend server.

```
┌─────────────────────────────────────────────────────────────────┐
│                     Java Data Pipeline                          │
│                 (src/VirtualPhoneScraperSuite.java)             │
└─────────────────────────────────────────────────────────────────┘
            │
            ▼
┌───────────────────────┐
│   1. Web Crawler      │  HTTP/1.1 client, exponential-backoff retry,
│   fetchHtml()         │  24h TTL file cache, stale-cache fallback
└──────────┬────────────┘
           │  raw HTML bytes
           ▼
┌───────────────────────┐
│   2. HTML Parser      │  Regex tag-stripping, entity decoding,
│   stripHtml()         │  structural table extraction per provider
│   scrape*()           │
└──────────┬────────────┘
           │  plain text + structured fields
           ▼
┌───────────────────────┐
│   3. Normalisation    │  normPrice() — canonical "$X/unit" format
│      & Validation     │  normBool()  — Yes | No | Add-on | N/A
│   (Algorithm 9 & 10)  │
└──────────┬────────────┘
           │  validated VirtualPhonePlan objects
           ▼
┌──────────────────────────────────────────────────────────────┐
│                  Data Structure Population                   │
│                                                              │
│   SpellChecker   ← vocabulary from corpus (Req 3)           │
│   Trie           ← all words for autocomplete (Req 4)        │
│   FrequencyCounter← per-URL word counts (Req 5)             │
│   SearchTracker  ← query hit-count log (Req 6)              │
│   PageRanker     ← keyword → URL ranking (Req 7)            │
│   InvertedIndex  ← word → URL set (Req 8)                   │
└──────────────────────────────────────────────────────────────┘
           │
    ┌──────┴──────┐
    │             │
    ▼             ▼
┌────────┐   ┌────────────────────┐
│  CSV   │   │   JSON Index       │
│  14×44 │   │   (550 KB)         │
└────────┘   └────────────────────┘
    │                 │
    └────────┬────────┘
             ▼
┌─────────────────────────────────────────────────────────────────┐
│               Browser SPA  (index.html)                         │
│                                                                 │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────────────┐  │
│  │ CSV Parser   │  │ Trie Lookup  │  │  Levenshtein (JS)    │  │
│  │ (RFC-4180)   │  │ Autocomplete │  │  Spell suggestions   │  │
│  └──────────────┘  └──────────────┘  └──────────────────────┘  │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────────────┐  │
│  │ InvIndex     │  │ FreqCounter  │  │  PageRanker          │  │
│  │ URL lookup   │  │ Word counts  │  │  Sort by rank        │  │
│  └──────────────┘  └──────────────┘  └──────────────────────┘  │
└─────────────────────────────────────────────────────────────────┘
```

---

## Mermaid Diagram

```mermaid
graph TD
    A["Live Provider Websites\n(5 URLs)"] --> B["Web Crawler\nfetchHtml() — retry + cache"]
    B --> C["HTML Parser\nstripHtml() + scrape*()"]
    C --> D["Normaliser & Validator\nnormPrice() / normBool()"]
    D --> E["Structured Plan Objects\nVirtualPhonePlan × 14"]

    E --> F["SpellChecker\nLevenshtein DP corpus"]
    E --> G["Trie\nPrefix autocomplete"]
    E --> H["FrequencyCounter\nword → count per URL"]
    E --> I["SearchTracker\nquery → hit count"]
    E --> J["PageRanker\nkeyword score per URL"]
    E --> K["InvertedIndex\nword → URL set"]

    F & G & H & I & J & K --> L["JSON Export\nsearch_index.json"]
    E --> M["CSV Export\nvirtual_phone_plans.csv"]

    L & M --> N["Browser SPA\nindex.html"]

    N --> O["Real-time Search\n+ Spell Correction"]
    N --> P["Autocomplete\n(Trie prefix lookup)"]
    N --> Q["Intel Panel\nFreq · Ranks · Index · History"]
    N --> R["Comparison Modal\nUp to 4 plans side-by-side"]
```

---

## Component Descriptions

### Java Pipeline (`src/VirtualPhoneScraperSuite.java`)

Single-file architecture for zero-dependency compilation. All components are `static` inner classes of the top-level `VirtualPhoneScraperSuite` class.

| Component | Class | Responsibility |
|---|---|---|
| Web Crawler | `fetchHtml()` | HTTP fetch with retry, disk cache, fallback |
| HTML Parser | `stripHtml()`, `scrape*()` | Tag stripping, entity decode, table extraction |
| Spell Checker | `SpellChecker` | Levenshtein DP, vocabulary from corpus |
| Autocomplete | `Trie` | Prefix tree, DFS traversal, flat-map export |
| Frequency Counter | `FrequencyCounter` | Per-URL word → count map |
| Search Tracker | `SearchTracker` | Query → hit count, serialised to JSON |
| Page Ranker | `PageRanker` | Keyword frequency scoring across pages |
| Inverted Index | `InvertedIndex` | word → sorted URL set, O(1) lookup |
| Data Model | `VirtualPhonePlan` | 44-field POJO, CSV serialisation |
| CSV Writer | `writeCSV()` | RFC-4180 compliant CSV output |
| JSON Writer | `writeSearchIndex()` | Manual JSON serialisation (no library) |

### Frontend (`index.html`)

Single-file SPA. Loads two files via `fetch()`:
- `data/virtual_phone_plans.csv` — plan cards
- `data/search_index.json` — all algorithm outputs for the intel panel

No build step, no framework, no transpilation. Runs from any static file server.

---

## Data Flow: Search Query

```mermaid
sequenceDiagram
    participant User
    participant UI as Browser SPA
    participant Trie as Trie Map (JSON)
    participant Index as Inverted Index (JSON)
    participant Freq as Frequency Map (JSON)
    participant Vocab as Vocabulary (JSON)

    User->>UI: Types "voicem" in search box
    UI->>Trie: getCompletions("vo") → prefix bucket
    Trie-->>UI: ["voice","voicemail","voip",…]
    UI->>UI: Filter to words starting with "voicem"
    UI-->>User: Autocomplete dropdown: "voicemail"

    User->>UI: Selects "voicemail" (full search)
    UI->>Vocab: Is "voicemail" in vocabulary?
    Vocab-->>UI: Yes → no spell-check hint shown
    UI->>Index: lookup("voicemail") → [url1, url2, …]
    Index-->>UI: Inverted Index tab updated
    UI->>Freq: frequencyMap[url]["voicemail"] for each URL
    Freq-->>UI: Freq Count tab + Page Ranks tab updated
    UI->>UI: Filter plan cards by text match
    UI-->>User: Filtered cards + intel panel updated
```

---

## File Layout

```
voicecompare/
├── src/VirtualPhoneScraperSuite.java   Java pipeline (~1 200 lines)
├── index.html                          Frontend SPA (~1 120 lines)
├── data/
│   ├── virtual_phone_plans.csv         14 rows × 44 columns (~10 KB)
│   └── search_index.json               all data structures (~550 KB)
├── docs/
│   ├── architecture.md                 ← you are here
│   ├── algorithms.md
│   ├── design-decisions.md
│   └── performance.md
├── tests/VoiceCompareSuiteTest.java    35 assertions, no JUnit
├── scripts/                            build.bat, run-scraper.bat, serve.bat
└── .github/
    ├── workflows/ci.yml                compile + test on push
    └── ISSUE_TEMPLATE/
```
