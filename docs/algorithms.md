# Algorithms Reference

Ten algorithms are implemented from scratch in `src/VirtualPhoneScraperSuite.java`. No third-party NLP or search libraries are used.

---

## 1 — Web Crawler (`fetchHtml`, `httpGet`)

**What it does:** Fetches raw HTML from live provider pricing pages.

**Why this approach:**  
The Java 11+ `HttpClient` provides a standards-compliant HTTP/1.1 client with redirect-following and configurable timeouts. Using it directly (rather than a library like OkHttp) keeps the project dependency-free and demonstrates knowledge of the standard library.

**Key engineering decisions:**
- **Exponential back-off retry** (`Thread.sleep(2000L * attempt)`): avoids hammering servers on transient failures
- **24-hour file cache** (`html_cache/`): prevents redundant network calls during development; makes the pipeline reproducible
- **Stale-cache fallback**: if all HTTP attempts fail, returns the cached HTML rather than crashing — resilient to temporary provider downtime
- **Gzip / deflate decompression**: manually decompresses the response body because `BodyHandlers.ofByteArray()` returns raw bytes

**Complexity:** O(1) per URL; bounded by network latency, not data size.

---

## 2 — HTML Parser (`stripHtml`, `firstMatch`, `allMatches`, `scrape*`)

**What it does:** Extracts structured plan data from raw HTML.

**Why regex instead of a DOM parser:**  
A full DOM parser (e.g., Jsoup) would add a dependency and is unnecessary for the relatively predictable structure of pricing pages. Pattern-matching against specific CSS class names (`styles_feature-data`, `elementor-heading-title`, etc.) is brittle in theory but sufficient in practice for these stable commercial pages.

**Key utilities:**
| Method | Purpose |
|---|---|
| `stripHtml(html)` | Remove all tags, decode 6 common HTML entities, collapse whitespace |
| `firstMatch(text, regex, default)` | Return first capture group or a default value |
| `allMatches(text, regex)` | Collect all capture groups into a list |

**Complexity:** O(n) where n = HTML character count.

---

## 3 — Spell Checker (Levenshtein Edit Distance)

**Location:** `SpellChecker` inner class

**What it does:** Given a query word not in the vocabulary, returns up to 3 closest words by edit distance.

**Algorithm — Levenshtein DP:**
```
dp[i][j] = min edit operations to transform a[0..i] → b[0..j]

Base cases:
  dp[i][0] = i   (delete i characters)
  dp[0][j] = j   (insert j characters)

Recurrence:
  if a[i-1] == b[j-1]:  dp[i][j] = dp[i-1][j-1]
  else:                  dp[i][j] = 1 + min(dp[i-1][j-1],   // substitute
                                            dp[i-1][j],      // delete
                                            dp[i][j-1])      // insert
```

**Why Levenshtein over soundex or n-gram:**
- Soundex is phonetic — it finds acoustically similar words, not textually similar ones. Useful for spoken names; wrong for typed search corrections.
- Levenshtein directly models what a user does: inserts, deletes, or substitutes characters. This is the standard for search spell-correction.

**Complexity:** `levenshtein(a, b)` is O(m·n) time and space where m, n are word lengths. `suggest(word)` is O(|V|·m·n) where |V| is vocabulary size (~5 000–20 000 words).

**Pruning:** Words with distance > `MAX_EDIT_DISTANCE` (4) are discarded early, keeping the suggestion list practical.

---

## 4 — Trie (Prefix Autocomplete)

**Location:** `Trie` inner class

**What it does:** Enables instant prefix-based word completion in the search input.

**Data structure:**
```
Each node: {
    children: HashMap<Character, Node>
    isEnd:    boolean
}
```

**Operations:**
- `insert(word)` — walk/create nodes for each character: **O(k)** where k = word length
- `allWithPrefix(prefix)` — traverse to prefix end, then DFS to collect all completions: **O(k + R)** where R = number of results
- `toFlatMap()` — pre-computes a `Map<String, List<String>>` for 1- and 2-character prefixes

**Why pre-compute a flat map for the frontend:**  
JavaScript doesn't hold the Trie in memory — it receives the serialised JSON. Rather than serialise the full tree (complex), we export prefix buckets (e.g., `"vo" → ["voice","voicemail","voip",…]`). The frontend does an O(1) map lookup by 2-char prefix, then linear-scans the bucket for full-prefix matches. Total query time: O(1 + B) where B = bucket size, typically < 100.

---

## 5 — Frequency Counter

**Location:** `FrequencyCounter` inner class

**What it does:** Counts how many times each word appears in each provider page's text.

**Data structure:** `Map<String, Map<String, Integer>>` (url → word → count)

**Usage in frontend:** Powers the "Freq Count" tab — shows a bar chart of how many times the search term appears on each provider's page.

**Complexity:** `addText()` is O(n) where n = number of tokens. `count()` is O(1) map lookup.

---

## 6 — Search Tracker

**Location:** `SearchTracker` inner class

**What it does:** Maintains a `query → hit-count` log that persists across scraper runs via the JSON index.

**Design:** The Java scraper serialises the log into `search_index.json`. The frontend merges this with in-session searches (`sessionSearchLog`). The combined log is displayed in the "Search History" tab, sorted by frequency.

**Demo mode:** Pass `--demo` to the scraper to pre-seed the log with sample queries, so the panel is populated on first load.

**Complexity:** `record()` is O(1) amortised (HashMap merge). `topSearches()` is O(n log n) sort.

---

## 7 — Page Ranker

**Location:** `PageRanker` inner class

**What it does:** Scores each provider page by keyword frequency and returns a ranked list.

**Algorithm:**
1. Store each page's plain text in a `Map<String, String>` (url → text)
2. For a query keyword, compile a word-boundary regex `\bkeyword\b` and count matches in each page's text
3. Sort results by hit count descending

**Composite scoring (`scoreForKeywords`):** Pre-computes a single score per URL by summing hit counts across a list of common terms (`unlimited`, `voicemail`, `sms`, `crm`, …). This composite score is stored in `search_index.json` as `pageRanks` and drives the "Page Rank" sort option in the frontend.

**Complexity:** `rank(keyword)` is O(P·n) where P = number of pages and n = average text length. `scoreForKeywords(terms)` is O(P·|terms|·n).

---

## 8 — Inverted Index

**Location:** `InvertedIndex` inner class

**What it does:** Maps every word to the sorted set of URLs that contain it, enabling O(1) lookup.

**Data structure:** `TreeMap<String, TreeSet<String>>` (word → sorted set of URLs)

**Why a TreeMap/TreeSet:**  
- `TreeMap` keeps words sorted lexicographically — useful for deterministic JSON serialisation
- `TreeSet` automatically deduplicates URLs (a word may appear many times on one page, but the URL should appear only once in the set)

**Complexity:**
- `addText()`: O(n log n) — each token is a `TreeMap.computeIfAbsent` call
- `lookup()`: **O(1)** — direct hash/tree lookup, no page scanning

---

## 9 — Data Validation (`normPrice`, `normBool`)

**What it does:** Validates and normalises scraped strings into canonical forms.

**`normPrice(raw)`** — Produces exactly one of:
- `"$X/mo"` `"$X/user/mo"` `"$X.XXXX/min"` `"Usage-based"` `"N/A"`

Uses a single regex: `\$[\d.,]+\s*/\s*(mo|month|user/mo|user/month|min|yr|year|call)`

**`normBool(raw, hasCheck)`** — Produces exactly one of:
- `"Yes"` `"No"` `"Add-on"` `"N/A"`

Maps checkmarks, "included", "unlimited", "yes" → `Yes`; "–", "×", "no", "not available" → `No`; "add-on", "optional", "contact sales" → `Add-on`.

**Why validation matters:** Raw scraped values are inconsistent across providers. Without normalisation, price comparison and feature filters would silently break.

---

## 10 — Pattern Finding (`firstMatch`, `allMatches`)

**What it does:** Wraps `Pattern.compile()` + `Matcher` in reusable utilities used throughout the scraper.

```java
firstMatch(text, regex, defaultVal)   → first capture group or default
allMatches(text, regex)               → List of all capture groups
```

Both use `DOTALL | CASE_INSENSITIVE` flags. `firstMatch` returns a safe default rather than throwing when no match is found, making scrapers resilient to layout changes.

**Usage count:** These two methods are called ~40 times across the five provider scrapers — they are the backbone of the parsing layer.
