# Contributing to VoiceCompare

Thank you for your interest in contributing! This document explains how to add a new provider, fix a bug, or improve the documentation.

---

## Project Structure

```
voicecompare/
├── src/VirtualPhoneScraperSuite.java   # Java data pipeline (single file, all algorithms)
├── index.html                          # Frontend SPA (HTML + CSS + JS)
├── data/
│   ├── virtual_phone_plans.csv         # Scraper output — 14 rows × 44 columns
│   └── search_index.json               # Serialised data structures for the frontend
├── docs/                               # Architecture, algorithm, and design docs
├── tests/VoiceCompareSuiteTest.java    # Unit tests
└── scripts/                            # Build and run helpers
```

---

## Development Setup

### Prerequisites
- Java 17+ (uses `record`, text blocks, switch expressions)
- Any static file server (`npx serve` works fine)

### Run the scraper
```bash
# Windows
scripts\build.bat
scripts\run-scraper.bat CACHED --demo

# macOS / Linux
javac -d out src/VirtualPhoneScraperSuite.java
java  -cp out VirtualPhoneScraperSuite CACHED --demo
```

### Serve the frontend
```bash
scripts\serve.bat          # Windows
npx serve .                # any platform
```

---

## Adding a New Provider

1. **Add constants** at the top of `VirtualPhoneScraperSuite.java`:
   ```java
   static final String URL_NEWPROVIDER   = "https://newprovider.com/pricing";
   static final String LOCAL_NEWPROVIDER = "NewProvider_Pricing.html";
   ```

2. **Write a scraper method** following the pattern of `scrapeGrasshopper()`:
   ```java
   static List<VirtualPhonePlan> scrapeNewProvider(String html, String scrapedAt) {
       // Parse HTML, build VirtualPhonePlan objects, return list
   }
   ```

3. **Add the provider to the pipeline** in `main()`:
   ```java
   new Provider("NewProvider", URL_NEWPROVIDER, LOCAL_NEWPROVIDER),
   ```

4. **Add the switch case**:
   ```java
   case "NewProvider" -> scrapeNewProvider(html, scrapedAt);
   ```

5. **Add provider colour** in `index.html` inside `PROVIDER_COLORS`:
   ```js
   'NewProvider': { hex:'#hex', bg:'rgba(...)', border:'rgba(...)', text:'#hex', icon:`<svg>…</svg>` }
   ```

6. **Run tests** to confirm nothing regressed:
   ```bash
   javac -d out src/VirtualPhoneScraperSuite.java tests/VoiceCompareSuiteTest.java
   java  -cp out VoiceCompareSuiteTest
   ```

---

## Code Style

- **Java**: Follow existing style — 4-space indent, descriptive variable names, Javadoc on public/package methods
- **JavaScript**: Keep functions small and clearly labelled with `// ─── SECTION ───` comments
- **No external libraries**: All algorithms are hand-rolled. Do not add Maven/Gradle or npm dependencies.

---

## Pull Request Checklist

- [ ] Tests pass (`VoiceCompareSuiteTest`)
- [ ] `CHANGELOG.md` updated under `[Unreleased]`
- [ ] No `.class` files committed
- [ ] Javadoc added to any new public methods
- [ ] `README.md` updated if the feature list or folder structure changed
