# Design Decisions

This document explains the key engineering choices made in VoiceCompare and the tradeoffs involved.

---

## 1. Single Java File vs. Multi-Module Maven Project

**Decision:** All Java code lives in one file (`src/VirtualPhoneScraperSuite.java`, ~1 200 lines).

**Rationale:**
- **Zero build tooling required.** The project compiles with a single `javac` command. No Gradle, Maven, or build scripts are needed to reproduce the pipeline.
- **All algorithms are visible in one place.** For a portfolio project, reviewers can see the full implementation without navigating a package tree.
- **Static inner classes** preserve encapsulation — `SpellChecker`, `Trie`, etc. are not accessible outside the suite without explicit import.

**Tradeoff:** As the project grows to more providers or more algorithms, a multi-class structure would be warranted. The current single-file approach is intentional for a project of this scope.

---

## 2. Single HTML File vs. React / Vue / Next.js

**Decision:** The frontend is a single `index.html` (~1 120 lines) with embedded CSS and JavaScript.

**Rationale:**
- **No build step.** The demo runs with `npx serve .` — no `npm install`, no `webpack`, no `node_modules/`.
- **GitHub Pages friendly.** A single HTML file deploys instantly to GitHub Pages with zero configuration.
- **Portfolio clarity.** The JavaScript is structured with clearly labelled sections (`// REQ 3 — SPELL CHECKER`, etc.), making the algorithm implementations immediately visible to a technical reviewer.

**Tradeoff:** At this scale (~750 lines of JS), a framework like Svelte or Vue would improve maintainability. If the project added 10+ providers or a server-side component, that refactor would be justified.

---

## 3. FetchMode Enum (LIVE / CACHED / LOCAL)

**Decision:** The scraper accepts a `FetchMode` CLI argument controlling data source.

| Mode | Behaviour | Use case |
|---|---|---|
| `LIVE` | Fetch from live URLs, save to cache | Production run to refresh data |
| `CACHED` | Use cache if < 24h old, else fetch | Development iteration |
| `LOCAL` | Read pre-downloaded HTML from `html_pages/` | Offline / CI environments |

**Rationale:** This three-tier strategy is a common pattern in production scrapers. It prevents hammering external servers during development, enables reproducible offline runs, and allows the pipeline to be tested in CI without network access.

---

## 4. Dual Output: CSV + JSON

**Decision:** The scraper writes two output files to `data/`:
- `virtual_phone_plans.csv` — structured plan data
- `search_index.json` — all algorithm data structures serialised

**Rationale:**
- **CSV** is the canonical data engineering output. It can be loaded into pandas, Excel, BigQuery, or any BI tool — demonstrating DE skills.
- **JSON** enables the frontend to load pre-computed algorithm results (trie map, inverted index, frequency map) without a server. This is the key that makes the SPA's search intelligence work offline.

**Why not a single JSON with all plan data?**  
CSV keeps the plan data tabular and independently usable. A data analyst can open it directly without understanding the JSON structure.

---

## 5. Trie Flat-Map Export Strategy

**Decision:** The Trie is exported as a flat `Map<prefix, List<word>>` for 1- and 2-character prefixes, rather than serialising the tree structure.

**Rationale:**  
Serialising a full Trie to JSON would require a recursive structure (`{ "v": { "o": { "i": ... } } }`) that is complex to parse in JavaScript and expensive to traverse. The flat-map approach trades a slightly larger JSON size for dramatically simpler and faster frontend lookup:
- **Lookup time:** O(1) map access to get the prefix bucket + O(B) linear scan (B < 100 typically)
- **JSON size:** ~100 KB for the trie map vs. comparable size for a recursive structure

---

## 6. Manual JSON Serialisation

**Decision:** `writeSearchIndex()` builds the JSON string manually using `StringBuilder`, rather than using a library like Gson or Jackson.

**Rationale:**
- **Zero external dependencies.** The project has no `pom.xml` or `build.gradle` — adding Gson would require introducing a build system.
- **The JSON structure is known and stable.** The output is a fixed schema with 6 top-level keys; there is no need for a general-purpose reflection-based serialiser.
- **Demonstrates understanding.** Hand-rolling JSON serialisation shows awareness of escaping rules and format structure.

**Tradeoff:** If the JSON schema changes significantly (e.g., adding nested arrays of objects), a library would be safer. The current approach is brittle if string values contain unescaped backslashes or control characters not covered by `jsonStr()`.

---

## 7. Regex HTML Parsing vs. DOM Parser

**Decision:** Use regex-based pattern matching to extract data, rather than Jsoup or a proper HTML parser.

**Rationale:**  
The five target pages have stable, known HTML structures (class names, table layouts). Regex is sufficient and keeps the dependency count at zero.

**Acknowledged limitation:** Regex-based HTML parsing breaks when providers change their page structure. This is the primary maintenance burden of the scraper. If any of the five providers redesigns their pricing page, the corresponding `scrape*()` method will need to be updated.

---

## 8. Inverted Index — TreeMap/TreeSet vs. HashMap/HashSet

**Decision:** Use `TreeMap<String, TreeSet<String>>` for the inverted index.

**Rationale:**
- **Deterministic JSON output:** `TreeMap` iterates in sorted key order, so `search_index.json` has a predictable, diff-friendly structure. This matters for git history and debugging.
- **Automatic deduplication:** `TreeSet` ensures each URL appears at most once per word, regardless of how many times the word appears on that page.

**Tradeoff:** `TreeMap` operations are O(log n) vs. O(1) for `HashMap`. For a vocabulary of ~20 000 words, the difference is negligible.

---

## 9. Why Java (not Python)?

A Python scraper with BeautifulSoup, pandas, and scikit-learn would be faster to write. Java was chosen deliberately:

- **Demonstrates strong-typing and OOP discipline** — classes with clear interfaces, not procedural scripts
- **Algorithm implementations are more rigorous** — the Levenshtein DP and Trie in Java with explicit types are more convincing portfolio evidence than a Python one-liner using `difflib`
- **Java 17 features** — records, switch expressions, text blocks, and `HttpClient` show familiarity with modern Java

---

## 10. Search Seeding — `--demo` Flag

**Decision:** Random search-log seeding (for UI demo purposes) was gated behind an explicit `--demo` CLI flag, rather than always running.

**Rationale:** Silently populating a "search history" panel with fake data would be misleading on a clean run. The `--demo` flag makes the intent explicit and allows the flag to be omitted in CI, keeping test outputs deterministic.
