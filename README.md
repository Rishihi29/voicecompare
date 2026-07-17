<div align="center">

# VoiceCompare

### Autonomous Data Pipeline · Virtual Phone Plan Intelligence

![Java](https://img.shields.io/badge/Java-17%2B-ED8B00?style=flat-square&logo=openjdk&logoColor=white)
![License](https://img.shields.io/badge/License-MIT-22c55e?style=flat-square)
![Status](https://img.shields.io/badge/Status-Active-00c9ff?style=flat-square)
![Algorithms](https://img.shields.io/badge/Algorithms-10%20implemented-a855f7?style=flat-square)
![Plans](https://img.shields.io/badge/Plans-14%20across%205%20providers-f97316?style=flat-square)

**A production-grade data engineering pipeline that autonomously crawls five virtual phone providers, normalises 44 data dimensions, and powers a real-time search interface backed by from-scratch implementations of Trie, Levenshtein, Inverted Index, and Page Ranking.**

[Live Demo](#running-locally) · [Architecture](#architecture) · [Algorithms](#algorithms) · [Docs](docs/)

</div>

---

## Problem

Comparing virtual phone plans across providers is painful. Pricing pages use inconsistent terminology, hide costs behind per-user vs. flat-rate distinctions, and make feature comparisons nearly impossible without manually visiting five different websites.

## Solution

VoiceCompare automates the entire data collection, normalisation, and comparison pipeline:

1. **Crawls** Grasshopper, Google Voice, RingCentral, Twilio, and eVoice with resilient HTTP fetching
2. **Parses** provider-specific HTML structures using regex-based pattern extraction
3. **Normalises** all pricing and feature data into a canonical 44-column schema
4. **Exports** a structured CSV and a rich JSON search index
5. **Visualises** everything in a dark-mode SPA with real-time search, spell correction, autocomplete, and side-by-side comparison

---

## Architecture

```
Live Provider Websites (5 URLs)
         │
         ▼
  ┌─────────────────┐
  │   Web Crawler   │  Retry · Cache · Fallback
  └────────┬────────┘
           │ raw HTML
           ▼
  ┌─────────────────┐
  │   HTML Parser   │  Tag stripping · Table extraction
  └────────┬────────┘
           │ plain text + fields
           ▼
  ┌─────────────────┐
  │  Normaliser &   │  normPrice() · normBool() · Regex
  │   Validator     │
  └────────┬────────┘
           │ VirtualPhonePlan × 14
           ▼
  ┌──────────────────────────────────────────────┐
  │            Data Structures                   │
  │  SpellChecker · Trie · FrequencyCounter      │
  │  SearchTracker · PageRanker · InvertedIndex  │
  └────────────┬─────────────────────────────────┘
               │
       ┌───────┴────────┐
       │                │
       ▼                ▼
  virtual_phone_    search_index.json
  plans.csv         (550 KB — all structures
  (14 × 44)          serialised for frontend)
       │                │
       └───────┬────────┘
               ▼
        Browser SPA (index.html)
        Real-time search · Autocomplete
        Spell correction · Comparison modal
        Freq count · Page ranks · Inverted index
```

See [docs/architecture.md](docs/architecture.md) for full Mermaid diagrams and sequence diagrams.

---

## Features

| Feature | Description |
|---|---|
| 🕷 **Resilient Web Crawler** | Exponential-backoff retry (3 attempts), 24-hour on-disk cache, stale-cache fallback to prevent data loss on provider downtime |
| 📋 **44-Dimension Data Model** | Pricing, billing options, calling features, messaging, platforms, integrations, and add-ons — normalised across all five providers |
| 🔍 **Real-Time Search** | Instant filtering across provider name, plan name, pricing model, and feature descriptions |
| ✏️ **Spell Correction** | Levenshtein edit-distance suggestions surfaced inline as the user types |
| ⚡ **Prefix Autocomplete** | Trie-backed word completion with O(1) prefix bucket lookup |
| 📊 **Word Frequency Analysis** | Per-provider term frequency displayed as a live bar chart |
| 🏆 **Keyword Page Ranking** | Providers ranked by how often query terms appear in their scraped content |
| 🔗 **Inverted Index Lookup** | O(1) "which providers mention this term?" without scanning all pages |
| ↕️ **Multi-Dimension Sorting** | Sort by price (asc/desc), feature score, annual savings, or page rank |
| ⚖️ **Side-by-Side Comparison** | Up to 4 plans in a scrollable comparison modal with highlighted lowest price |
| 💰 **Monthly/Annual Toggle** | Switch billing cycle with automatic savings badge |
| 🎯 **Feature Filters** | Quick-filter by SMS, mobile app, CRM, free trial, IVR, and call recording |

---

## Algorithms

All 10 algorithms are implemented from scratch. No NLP or search libraries used.

| # | Algorithm | Class / Method | Complexity | Purpose |
|---|---|---|---|---|
| 1 | **Web Crawling** | `fetchHtml()` | O(1) per URL | Resilient HTTP fetch with cache |
| 2 | **HTML Parsing** | `stripHtml()`, `scrape*()` | O(n) | Tag stripping, entity decoding, table extraction |
| 3 | **Levenshtein DP** | `SpellChecker` | O(m·n) | Spell-check suggestions |
| 4 | **Trie** | `Trie` | O(k) insert, O(k+R) lookup | Prefix autocomplete |
| 5 | **Frequency Count** | `FrequencyCounter` | O(n) | Per-page word → count map |
| 6 | **Search Tracking** | `SearchTracker` | O(1) amortised | Query hit-count log |
| 7 | **Page Ranking** | `PageRanker` | O(P·n) | Keyword frequency ranking |
| 8 | **Inverted Index** | `InvertedIndex` | O(1) lookup | Word → URL set |
| 9 | **Data Validation** | `normPrice()`, `normBool()` | O(1) | Canonical schema enforcement |
| 10 | **Pattern Finding** | `firstMatch()`, `allMatches()` | O(n) | Regex extraction utilities |

Full algorithm details, pseudocode, and design rationale: [docs/algorithms.md](docs/algorithms.md)

---

## Tech Stack

| Layer | Technology |
|---|---|
| Data pipeline | Java 17 (`HttpClient`, `Pattern`, `Stream`, records) |
| HTML parsing | Java regex (no DOM library) |
| JSON serialisation | Manual `StringBuilder` (no library) |
| CSV output | RFC-4180 compliant hand-rolled writer |
| Frontend | Vanilla HTML + CSS + JavaScript (no framework) |
| Styling | TailwindCSS (CDN) + custom CSS variables |
| Typography | Syne, JetBrains Mono, DM Sans (Google Fonts) |
| CI | GitHub Actions |
| Hosting | Static file server / GitHub Pages |

---

## Installation & Running

### Prerequisites
- **Java 17+** — `java --version` to verify
- **Node.js** (optional) — only for `npx serve` to run the frontend

### 1. Clone the repository

```bash
git clone https://github.com/yourusername/voicecompare.git
cd voicecompare
```

### 2. Compile

```bash
# Windows
scripts\build.bat

# macOS / Linux
mkdir -p out
javac -d out src/VirtualPhoneScraperSuite.java
```

### 3. Run the scraper

```bash
# Windows — use cached HTML (faster, offline-friendly)
scripts\run-scraper.bat CACHED --demo

# macOS / Linux
java -cp out VirtualPhoneScraperSuite CACHED --demo
```

**Fetch modes:**

| Flag | Description |
|---|---|
| `LIVE` | Fetch fresh data from provider websites (default) |
| `CACHED` | Use on-disk cache (< 24h old); falls back to LIVE |
| `LOCAL` | Read pre-downloaded HTML from `html_pages/` |
| `--demo` | Seed search log with sample queries for UI demo |

Output is written to `data/virtual_phone_plans.csv` and `data/search_index.json`.

### 4. Serve the frontend

```bash
# Windows
scripts\serve.bat

# macOS / Linux
npx serve . --listen 3000
```

Open **http://localhost:3000** in your browser.

> **Note:** `index.html` must be served via HTTP — opening it as a `file://` URL will fail due to `fetch()` CORS restrictions.

---

## Output

### `data/virtual_phone_plans.csv`

14 rows × 44 columns, one row per plan:

```
Provider,Plan_Name,Pricing_Model,Monthly_Price,Annual_Monthly_Price,Annual_Total_Cost,
Monthly_vs_Annual_Savings,Billing_Options,Free_Trial,Users_Included,Phone_Numbers_Included,
Extensions_Included,Calling_Minutes,Local_Numbers,TollFree_Numbers,International_Calling,
Call_Forwarding,Voicemail,Voicemail_Transcription,Call_Recording,Custom_Greetings,
Auto_Attendant_IVR,Call_Screening,Conference_Calling,Call_Analytics_Reporting,
SMS_Messaging,MMS_Messaging,Bulk_Messaging,Mobile_App,Desktop_App,Web_App,
VoIP_WiFi_Calling,HD_Voice,CRM_Integration,Other_Integrations,Security_Features,
Multi_Line_Support,Number_Porting,Additional_Numbers_Addon,Additional_Extensions_Addon,
Best_For,Source_URL,Scraped_At,Notes
```

### `data/search_index.json`

```json
{
  "vocabulary":    ["analytics", "billing", "calling", ...],
  "invertedIndex": { "voicemail": ["https://grasshopper.com/...", ...] },
  "frequencyMap":  { "https://grasshopper.com/...": { "voicemail": 12 } },
  "trieMap":       { "vo": ["voice", "voicemail", "voip", ...] },
  "pageRanks":     { "https://grasshopper.com/...": 847 },
  "searchLog":     { "voicemail": 3, "sms": 2 },
  "generatedAt":   "2025-07-17 04:00 UTC"
}
```

---

## Folder Structure

```
voicecompare/
├── index.html                           Frontend SPA
├── README.md
├── LICENSE
├── CHANGELOG.md
├── CONTRIBUTING.md
├── .gitignore
│
├── src/
│   └── VirtualPhoneScraperSuite.java    Java data pipeline (~1 200 lines)
│
├── data/
│   ├── virtual_phone_plans.csv          Scraper output — 14 × 44
│   └── search_index.json                Serialised data structures (~550 KB)
│
├── docs/
│   ├── architecture.md                  System diagrams + component overview
│   ├── algorithms.md                    Algorithm deep-dives + complexity
│   ├── design-decisions.md              Engineering rationale + tradeoffs
│   └── performance.md                   Timing + memory + size analysis
│
├── tests/
│   └── VoiceCompareSuiteTest.java       35 unit assertions, no JUnit needed
│
├── scripts/
│   ├── build.bat                        Compile + test (Windows)
│   ├── run-scraper.bat                  Run the pipeline (Windows)
│   └── serve.bat                        Start frontend server (Windows)
│
└── .github/
    ├── workflows/ci.yml                 Compile + test on every push
    ├── ISSUE_TEMPLATE/
    │   ├── bug_report.md
    │   └── feature_request.md
    └── PULL_REQUEST_TEMPLATE.md
```

---

## Engineering Decisions

| Decision | Rationale |
|---|---|
| Single Java file | Zero build tooling; all algorithms visible in one place |
| Single HTML file | No build step; GitHub Pages compatible; algorithms clearly labelled |
| FetchMode enum | Three-tier data sourcing: live / cached / local — standard scraper pattern |
| Dual output (CSV + JSON) | CSV for data analysis tools; JSON for frontend algorithm features |
| Trie flat-map export | O(1) frontend lookup without serialising the full tree |
| Manual JSON serialisation | Zero dependency; JSON schema is known and stable |
| Regex HTML parsing | Zero dependency; sufficient for stable commercial pricing pages |
| TreeMap/TreeSet for index | Deterministic JSON diff; automatic URL deduplication |

Full rationale: [docs/design-decisions.md](docs/design-decisions.md)

---

## Future Improvements

The following improvements would require substantial new development:

- **Concurrent fetching** — `CompletableFuture` parallel crawl to reduce LIVE mode time from ~45s to ~15s
- **Stop-word filtering** — Remove high-frequency noise words from the inverted index to reduce JSON size by ~30%
- **Delta updates** — Track field changes between scraper runs and emit a change log
- **More providers** — Vonage, Nextiva, Ooma, 8×8
- **BM25 scoring** — Replace simple frequency ranking with TF-IDF / BM25 for more relevant results
- **Persistent search log** — Server endpoint to accumulate search history across sessions
- **Export to BigQuery / Snowflake** — Add a data warehouse connector for the CSV output
- **Automated re-scrape** — GitHub Actions scheduled job to refresh data weekly

---

## Running Tests

```bash
# Windows
scripts\build.bat   # compiles and runs tests automatically

# macOS / Linux
javac -d out src/VirtualPhoneScraperSuite.java tests/VoiceCompareSuiteTest.java
java  -cp out VoiceCompareSuiteTest
```

Expected output:
```
╔══════════════════════════════════════════════════════════════╗
║           VoiceCompare — Unit Test Suite                     ║
╚══════════════════════════════════════════════════════════════╝

── SpellChecker ─────────────────────────────────────────────────
  ✓ known word 'voicemail' is in vocabulary
  ✓ ...

  Results: 35 passed, 0 failed
```

---

## License

MIT — see [LICENSE](LICENSE).

---

## Credits

Data sourced from public pricing pages of Grasshopper, Google Voice, RingCentral, Twilio, and eVoice. All trademarks are property of their respective owners. This project is not affiliated with any provider.
