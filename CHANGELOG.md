# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

---

## [3.0.0] — 2025-07-17

### Added
- Professional repository structure (`src/`, `data/`, `docs/`, `tests/`, `scripts/`, `.github/`)
- `docs/architecture.md` — full system architecture with Mermaid diagrams
- `docs/algorithms.md` — deep-dive on all 10 algorithms with complexity analysis
- `docs/design-decisions.md` — engineering rationale for key design choices
- `docs/performance.md` — profiling notes, bottleneck analysis, memory usage
- `tests/VoiceCompareSuiteTest.java` — unit tests for all 6 data structures + normalisation helpers
- `.github/workflows/ci.yml` — GitHub Actions CI: compile + test on every push
- `.github/ISSUE_TEMPLATE/` — structured bug and feature request templates
- `.github/PULL_REQUEST_TEMPLATE.md` — contribution checklist
- `scripts/build.bat` / `scripts/run-scraper.bat` / `scripts/serve.bat` — one-command workflows
- `--demo` CLI flag to gate random search-log seeding (previously always ran)
- `DATA_DIR` constant; scraper now writes output to `./data/` instead of project root
- `MAX_EDIT_DISTANCE` and `TRIE_PREFIX_MAX_LEN` named constants (removed magic numbers)
- OG / SEO meta tags in `index.html`
- Frontend now fetches data from `./data/` to match the new layout

### Changed
- Java header Javadoc rewritten to professional standard (removed course attribution from source)
- `evPrice()` `boolean unused` parameter renamed to meaningful `int lookBack` parameter
- All inner-class section headers now include complexity annotations
- Demo search seeding moved behind explicit `--demo` flag

### Fixed
- `*.class` files no longer tracked in git (`.gitignore` added)

---

## [2.0.0] — 2025-06-01

### Added
- Inverted index (Algorithm 8): O(1) word → URL lookup
- Page ranker (Algorithm 7): keyword-frequency composite scoring
- Search tracker (Algorithm 6): persistent query log serialised to JSON
- Frequency counter (Algorithm 5): per-URL word-count maps
- `search_index.json` export consumed by the frontend intel panel

### Changed
- Frontend upgraded with Search Intelligence panel (Freq Count, Search History, Page Ranks, Inverted Index tabs)
- Comparison tray supports up to 4 simultaneous plan comparisons

---

## [1.0.0] — 2025-05-01

### Added
- Java web scraper for Grasshopper, Google Voice, RingCentral, Twilio, eVoice
- HTML parser with entity decoding and whitespace normalisation
- Spell checker using Levenshtein edit-distance DP
- Trie for O(k) prefix-based autocomplete
- 44-column CSV export (`virtual_phone_plans.csv`)
- Dark-mode SPA with provider cards, filters, and comparison modal
