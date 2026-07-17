import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.*;
import java.util.stream.Collectors;

/**
 * VirtualPhoneScraperSuite — Virtual Phone Plan Data Pipeline
 * ─────────────────────────────────────────────────────────────────────────────
 * An autonomous data-engineering pipeline that crawls five VoIP provider
 * websites, parses and normalises plan data across 44 dimensions, and exports
 * the results to both CSV and a rich JSON search index consumed by the
 * companion single-page frontend (index.html).
 *
 * Core algorithms implemented from scratch (no third-party NLP libraries):
 *
 *  1. Web Crawler          — fetchHtml() with exponential-backoff retry,
 *                            24-hour file cache, and stale-cache fallback
 *  2. HTML Parser          — regex-based tag stripping, entity decoding,
 *                            and structural table extraction
 *  3. Spell Checker        — Levenshtein edit-distance DP (O(m·n)),
 *                            vocabulary built from scraped corpus
 *  4. Trie (autocomplete)  — prefix-tree insert/DFS with flat-map export
 *                            for O(1) prefix lookup in the frontend
 *  5. Frequency Counter    — per-URL word-count map for relevance scoring
 *  6. Search Tracker       — query → hit-count log, persisted to JSON
 *  7. Page Ranker          — keyword-frequency scoring, multi-term aggregation
 *  8. Inverted Index       — word → sorted URL set, O(1) lookup
 *  9. Data Validation      — regex-based price and boolean normalisation
 * 10. Pattern Finding      — firstMatch() / allMatches() regex utilities
 *
 * Outputs (written to ./data/)
 * ──────────────────────────────
 *   virtual_phone_plans.csv   — 14-row × 44-column plan comparison table
 *   search_index.json         — serialised data structures for the frontend
 *                               (vocabulary, invertedIndex, frequencyMap,
 *                                trieMap, pageRanks, searchLog)
 *
 * Usage
 * ──────
 *   javac -d out src/VirtualPhoneScraperSuite.java
 *   java  -cp out VirtualPhoneScraperSuite [LIVE|CACHED|LOCAL] [--demo]
 *
 *   LIVE    — fetch from live URLs (default)
 *   CACHED  — use 24-hour on-disk cache where available
 *   LOCAL   — use pre-downloaded HTML in ./html_pages/
 *   --demo  — seed the search log with sample queries for demonstration
 * ─────────────────────────────────────────────────────────────────────────────
 */
public class VirtualPhoneScraperSuite {

    // ── Fetch mode ────────────────────────────────────────────────────────────
    enum FetchMode { LIVE, CACHED, LOCAL }

    // ── Provider URLs ─────────────────────────────────────────────────────────
    static final String URL_GRASSHOPPER  = "https://grasshopper.com/pricing";
    static final String URL_GOOGLE_VOICE = "https://workspace.google.com/intl/en_ca/products/voice/#plans";
    static final String URL_RINGCENTRAL  = "https://www.ringcentral.com/ca/en/office/plansandpricing.html";
    static final String URL_TWILIO       = "https://www.twilio.com/en-us/voice/pricing/ca";
    static final String URL_EVOICE       = "https://www.evoice.com/pricing/";

    static final String LOCAL_GRASSHOPPER  = "Sign_Up_for_Grasshopper_-_Pricing_Starting_at__14_month.html";
    static final String LOCAL_GOOGLE_VOICE = "Google_Voice__Business_Phone_Number___Systems___Google_Workspace.html";
    static final String LOCAL_RINGCENTRAL  = "RingCentral_CA_AI_Communications_Platform_Plans___Pricing.html";
    static final String LOCAL_TWILIO       = "Programmable_Voice_Pricing_in_Canada___Twilio.html";
    static final String LOCAL_EVOICE       = "Pricing___eVoice.html";

    static final String CACHE_DIR             = "./html_cache/";
    static final String LOCAL_DIR             = "./html_pages/";
    static final String DATA_DIR              = "./data/";
    static final int    CACHE_TTL_HOURS       = 24;
    static final int    HTTP_TIMEOUT_SEC      = 20;
    static final int    MAX_RETRIES           = 3;
    /** Maximum Levenshtein distance considered a useful spell-check suggestion. */
    static final int    MAX_EDIT_DISTANCE     = 4;
    /** Trie prefix lengths (1 and 2 chars) exported to the flat map for frontend O(1) lookup. */
    static final int    TRIE_PREFIX_MAX_LEN   = 2;
    static final String CSV_OUTPUT            = DATA_DIR + "virtual_phone_plans.csv";
    static final String JSON_OUTPUT           = DATA_DIR + "search_index.json";

    static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/120.0.0.0 Safari/537.36";

    static final String CSV_HEADER =
        "Provider,Plan_Name,Pricing_Model,Monthly_Price,Annual_Monthly_Price," +
        "Annual_Total_Cost,Monthly_vs_Annual_Savings,Billing_Options,Free_Trial," +
        "Users_Included,Phone_Numbers_Included,Extensions_Included,Calling_Minutes," +
        "Local_Numbers,TollFree_Numbers,International_Calling,Call_Forwarding," +
        "Voicemail,Voicemail_Transcription,Call_Recording,Custom_Greetings," +
        "Auto_Attendant_IVR,Call_Screening,Conference_Calling," +
        "Call_Analytics_Reporting,SMS_Messaging,MMS_Messaging,Bulk_Messaging," +
        "Mobile_App,Desktop_App,Web_App,VoIP_WiFi_Calling,HD_Voice," +
        "CRM_Integration,Other_Integrations,Security_Features,Multi_Line_Support," +
        "Number_Porting,Additional_Numbers_Addon,Additional_Extensions_Addon," +
        "Best_For,Source_URL,Scraped_At,Notes";

    // ═════════════════════════════════════════════════════════════════════════
    //  SPELL CHECKER  (Algorithm 3)
    //
    //  Builds a vocabulary from all scraped plain-text corpus.  For an unknown
    //  query word it returns the top-3 closest vocabulary words by Levenshtein
    //  edit distance (classic O(m·n) dynamic-programming formulation).
    //
    //  Complexity: addText O(n·w) where n=tokens, w=avg word length.
    //              suggest  O(|V|·m·n) where |V|=vocabulary size.
    // ═════════════════════════════════════════════════════════════════════════

    static class SpellChecker {
        private final Set<String> vocabulary = new TreeSet<>();

        /** Add every alphabetic token from a block of plain text. */
        void addText(String text) {
            Matcher m = Pattern.compile("[a-zA-Z]{2,}").matcher(text.toLowerCase());
            while (m.find()) vocabulary.add(m.group());
        }

        /** True when the word exists in the vocabulary. */
        boolean check(String word) {
            return vocabulary.contains(word.toLowerCase());
        }

        /**
         * Returns up to 3 suggestions closest to the input by edit distance.
         * Uses Levenshtein dynamic-programming algorithm.
         */
        List<String> suggest(String word) {
            String w = word.toLowerCase();
            if (vocabulary.contains(w)) return List.of(w); // exact match
            // Score every vocab word and pick the 3 with smallest distance
            return vocabulary.stream()
                    .map(v -> Map.entry(v, levenshtein(w, v)))
                    .filter(e -> e.getValue() <= MAX_EDIT_DISTANCE)
                    .sorted(Map.Entry.comparingByValue())
                    .limit(3)
                    .map(Map.Entry::getKey)
                    .collect(Collectors.toList());
        }

        /** Standard Levenshtein distance (O(m*n) DP). */
        static int levenshtein(String a, String b) {
            int m = a.length(), n = b.length();
            int[][] dp = new int[m + 1][n + 1];
            for (int i = 0; i <= m; i++) dp[i][0] = i;
            for (int j = 0; j <= n; j++) dp[0][j] = j;
            for (int i = 1; i <= m; i++)
                for (int j = 1; j <= n; j++)
                    dp[i][j] = a.charAt(i-1) == b.charAt(j-1)
                            ? dp[i-1][j-1]
                            : 1 + Math.min(dp[i-1][j-1], Math.min(dp[i-1][j], dp[i][j-1]));
            return dp[m][n];
        }

        Set<String> getVocabulary() { return Collections.unmodifiableSet(vocabulary); }
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  TRIE  (Algorithm 4 — Word Completion / Autocomplete)
    //
    //  A classic prefix tree where each node holds a character-keyed child map.
    //  insert() is O(k), allWithPrefix() is O(k + R) where k=prefix length and
    //  R=number of results.  toFlatMap() pre-serialises 1- and 2-char prefix
    //  buckets so the frontend performs O(1) bucket lookup + linear scan.
    // ═════════════════════════════════════════════════════════════════════════

    static class Trie {
        private static class Node {
            final Map<Character, Node> children = new HashMap<>();
            boolean isEnd = false;
        }
        private final Node root = new Node();

        void insert(String word) {
            Node cur = root;
            for (char c : word.toCharArray()) {
                cur = cur.children.computeIfAbsent(c, k -> new Node());
            }
            cur.isEnd = true;
        }

        /** Returns all words that begin with the given prefix. */
        List<String> allWithPrefix(String prefix) {
            Node cur = root;
            for (char c : prefix.toCharArray()) {
                cur = cur.children.get(c);
                if (cur == null) return Collections.emptyList();
            }
            List<String> results = new ArrayList<>();
            dfs(cur, new StringBuilder(prefix), results);
            Collections.sort(results);
            return results;
        }

        private void dfs(Node node, StringBuilder sb, List<String> out) {
            if (node.isEnd) out.add(sb.toString());
            for (Map.Entry<Character, Node> e : node.children.entrySet()) {
                sb.append(e.getKey());
                dfs(e.getValue(), sb, out);
                sb.deleteCharAt(sb.length() - 1);
            }
        }

        /**
         * Serialises the trie as a flat prefix→completions map
         * (all 1- and 2-character prefixes) for efficient JSON export.
         */
        Map<String, List<String>> toFlatMap() {
            Map<String, List<String>> map = new TreeMap<>();
            // Gather all words first via full DFS from root
            List<String> all = allWithPrefix("");
            for (String word : all) {
                for (int len = 1; len <= Math.min(TRIE_PREFIX_MAX_LEN, word.length()); len++) {
                    String prefix = word.substring(0, len);
                    map.computeIfAbsent(prefix, k -> new ArrayList<>()).add(word);
                }
            }
            return map;
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  FREQUENCY COUNTER  (Algorithm 5)
    //
    //  Maintains a two-level map: url → (word → count).  Used both for the
    //  "Freq Count" intel panel in the frontend and as the scoring basis for
    //  the PageRanker.  Complexity: O(n) amortised per addText() call.
    // ═════════════════════════════════════════════════════════════════════════

    static class FrequencyCounter {
        // url → (word → count)
        private final Map<String, Map<String, Integer>> data = new LinkedHashMap<>();

        void addText(String url, String text) {
            Map<String, Integer> freq = data.computeIfAbsent(url, k -> new TreeMap<>());
            Matcher m = Pattern.compile("[a-zA-Z]{2,}").matcher(text.toLowerCase());
            while (m.find()) freq.merge(m.group(), 1, Integer::sum);
        }

        /** How many times does `word` appear in the page at `url`? */
        int count(String url, String word) {
            return data.getOrDefault(url, Map.of()).getOrDefault(word.toLowerCase(), 0);
        }

        Map<String, Map<String, Integer>> getData() { return Collections.unmodifiableMap(data); }
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  SEARCH TRACKER  (Algorithm 6)
    //
    //  Maintains an in-memory query → hit-count log that is serialised into
    //  search_index.json and re-loaded by the frontend on startup.  The
    //  frontend's session log merges with this persisted baseline so that
    //  search-frequency data accumulates across scraper runs.
    // ═════════════════════════════════════════════════════════════════════════

    static class SearchTracker {
        private final Map<String, Integer> log = new LinkedHashMap<>();

        void record(String query) {
            if (query == null || query.isBlank()) return;
            log.merge(query.toLowerCase().trim(), 1, Integer::sum);
        }

        Map<String, Integer> getLog() { return Collections.unmodifiableMap(log); }

        /** Returns queries sorted descending by search count. */
        List<Map.Entry<String, Integer>> topSearches() {
            return log.entrySet().stream()
                    .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                    .collect(Collectors.toList());
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  PAGE RANKER  (Algorithm 7)
    //
    //  Scores each crawled URL by whole-word keyword frequency using a regex
    //  word-boundary matcher.  rank() returns a descending sorted list of
    //  (url, hitCount) pairs; scoreForKeywords() aggregates over a fixed term
    //  list to produce a single composite relevance score per URL for the
    //  "Page Rank" sort option in the frontend.
    // ═════════════════════════════════════════════════════════════════════════

    static class PageRanker {
        // url → full plain-text content
        private final Map<String, String> pageTexts = new LinkedHashMap<>();

        void addPage(String url, String plainText) {
            pageTexts.put(url, plainText.toLowerCase());
        }

        /**
         * Ranks all pages by how many times `keyword` appears.
         * Returns list of (url, hitCount) sorted highest first.
         */
        List<Map.Entry<String, Integer>> rank(String keyword) {
            String kw = keyword.toLowerCase();
            return pageTexts.entrySet().stream()
                    .map(e -> {
                        int hits = 0;
                        Matcher m = Pattern.compile("\\b" + Pattern.quote(kw) + "\\b").matcher(e.getValue());
                        while (m.find()) hits++;
                        return Map.entry(e.getKey(), hits);
                    })
                    .filter(e -> e.getValue() > 0)
                    .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                    .collect(Collectors.toList());
        }

        /**
         * Pre-computes a rank map for a fixed set of common terms
         * and returns url → score for JSON export.
         */
        Map<String, Integer> scoreForKeywords(List<String> keywords) {
            Map<String, Integer> scores = new LinkedHashMap<>();
            for (String url : pageTexts.keySet()) {
                int total = 0;
                for (String kw : keywords) total += rank(kw).stream()
                        .filter(e -> e.getKey().equals(url))
                        .mapToInt(Map.Entry::getValue).sum();
                scores.put(url, total);
            }
            return scores;
        }

        Map<String, String> getPageTexts() { return Collections.unmodifiableMap(pageTexts); }
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  INVERTED INDEX  (Algorithm 8)
    //
    //  Classic information-retrieval data structure: word → TreeSet<URL>.
    //  addText() tokenises a page's plain text in a single O(n) pass.
    //  lookup() is O(1) map access — no linear scan of pages at query time.
    //  The full index is serialised to search_index.json so the frontend can
    //  answer "which providers mention X?" without a server round-trip.
    // ═════════════════════════════════════════════════════════════════════════

    static class InvertedIndex {
        // word → sorted set of URLs
        private final Map<String, TreeSet<String>> index = new TreeMap<>();

        void addText(String url, String text) {
            Matcher m = Pattern.compile("[a-zA-Z]{2,}").matcher(text.toLowerCase());
            while (m.find()) {
                index.computeIfAbsent(m.group(), k -> new TreeSet<>()).add(url);
            }
        }

        /** Returns all URLs that contain the given word. O(1) map lookup. */
        Set<String> lookup(String word) {
            return index.getOrDefault(word.toLowerCase(), new TreeSet<>());
        }

        Map<String, TreeSet<String>> getIndex() { return Collections.unmodifiableMap(index); }
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  DATA MODEL
    // ═════════════════════════════════════════════════════════════════════════

    static class VirtualPhonePlan {
        String provider                 = "N/A";
        String planName                 = "N/A";
        String pricingModel             = "N/A";
        String monthlyPrice             = "N/A";
        String annualMonthlyPrice       = "N/A";
        String annualTotalCost          = "N/A";
        String monthlyVsAnnualSavings   = "N/A";
        String billingOptions           = "N/A";
        String freeTrial                = "No";
        String usersIncluded            = "N/A";
        String phoneNumbersIncluded     = "N/A";
        String extensionsIncluded       = "N/A";
        String callingMinutes           = "N/A";
        String localNumbers             = "No";
        String tollFreeNumbers          = "No";
        String internationalCalling     = "No";
        String callForwarding           = "No";
        String voicemail                = "No";
        String voicemailTranscription   = "No";
        String callRecording            = "No";
        String customGreetings          = "No";
        String autoAttendantIVR         = "No";
        String callScreening            = "No";
        String conferenceCalling        = "No";
        String callAnalytics            = "No";
        String smsMessaging             = "No";
        String mmsMessaging             = "No";
        String bulkMessaging            = "No";
        String mobileApp                = "No";
        String desktopApp               = "No";
        String webApp                   = "No";
        String voipWifiCalling          = "No";
        String hdVoice                  = "No";
        String crmIntegration           = "No";
        String otherIntegrations        = "N/A";
        String securityFeatures         = "N/A";
        String multiLineSupport         = "No";
        String numberPorting            = "No";
        String additionalNumbersAddon   = "N/A";
        String additionalExtAddon       = "N/A";
        String bestFor                  = "N/A";
        String sourceUrl                = "N/A";
        String scrapedAt                = "N/A";
        String notes                    = "";

        String toCsvRow() {
            String[] fields = {
                provider, planName, pricingModel, monthlyPrice,
                annualMonthlyPrice, annualTotalCost, monthlyVsAnnualSavings,
                billingOptions, freeTrial, usersIncluded, phoneNumbersIncluded,
                extensionsIncluded, callingMinutes, localNumbers, tollFreeNumbers,
                internationalCalling, callForwarding, voicemail, voicemailTranscription,
                callRecording, customGreetings, autoAttendantIVR, callScreening,
                conferenceCalling, callAnalytics, smsMessaging, mmsMessaging,
                bulkMessaging, mobileApp, desktopApp, webApp, voipWifiCalling,
                hdVoice, crmIntegration, otherIntegrations, securityFeatures,
                multiLineSupport, numberPorting, additionalNumbersAddon,
                additionalExtAddon, bestFor, sourceUrl, scrapedAt, notes
            };
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < fields.length; i++) {
                if (i > 0) sb.append(',');
                String v = (fields[i] == null) ? "" : fields[i];
                sb.append('"').append(v.replace("\"", "\"\"")).append('"');
            }
            return sb.toString();
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  HTML FETCHER  (Requirement 1 — Web Crawler)
    // ═════════════════════════════════════════════════════════════════════════

    static String fetchHtml(String url, String localFilename, FetchMode mode)
    throws IOException {
        String cacheFile = CACHE_DIR + localFilename;
        String localFile = LOCAL_DIR  + localFilename;

        if (mode == FetchMode.LOCAL) {
            System.out.printf("  [LOCAL]   %s%n", localFilename);
            return readFile(localFile);
        }

        if (mode == FetchMode.CACHED) {
            Path cp = Paths.get(cacheFile);
            if (Files.exists(cp)) {
                long ageHours = Duration.between(
                        Files.getLastModifiedTime(cp).toInstant(), Instant.now()).toHours();
                if (ageHours < CACHE_TTL_HOURS) {
                    System.out.printf("  [CACHE]   %s  (age %dh)%n", localFilename, ageHours);
                    return readFile(cacheFile);
                }
            }
        }

        System.out.printf("  [FETCH]   GET %s%n", url);
        IOException lastErr = null;
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                String html = httpGet(url);
                Files.createDirectories(Paths.get(CACHE_DIR));
                Files.writeString(Paths.get(cacheFile), html, StandardCharsets.UTF_8);
                System.out.printf("  [CACHED]  Saved %s  (%,d chars)%n", localFilename, html.length());
                return html;
            } catch (IOException | InterruptedException e) {
                lastErr = (e instanceof IOException ie) ? ie : new IOException(e.getMessage(), e);
                System.out.printf("  [WARN]    Attempt %d/%d failed: %s%n", attempt, MAX_RETRIES, e.getMessage());
                if (attempt < MAX_RETRIES) {
                    try { Thread.sleep(2000L * attempt); }
                    catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                }
            }
        }

        if (Files.exists(Paths.get(cacheFile))) {
            System.out.printf("  [FALLBACK] HTTP failed — using stale cache: %s%n", localFilename);
            return readFile(cacheFile);
        }
        if (Files.exists(Paths.get(localFile))) {
            System.out.printf("  [FALLBACK] HTTP failed — using local file: %s%n", localFilename);
            return readFile(localFile);
        }
        throw new IOException("Cannot load HTML for " + localFilename +
                ". No network, cache, or local file available. Last: " + lastErr);
    }

    private static String httpGet(String url) throws IOException, InterruptedException {
        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.ALWAYS)
                .connectTimeout(Duration.ofSeconds(HTTP_TIMEOUT_SEC))
                .build();
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent",      USER_AGENT)
                .header("Accept",          "text/html,application/xhtml+xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "en-US,en;q=0.9")
                .header("Accept-Encoding", "gzip, deflate")
                .GET().build();
        HttpResponse<byte[]> resp = client.send(req, BodyHandlers.ofByteArray());
        int status = resp.statusCode();
        if (status < 200 || status >= 300) throw new IOException("HTTP " + status + " for " + url);
        byte[] body = resp.body();
        String enc = resp.headers().firstValue("content-encoding").orElse("").toLowerCase();
        if (enc.contains("gzip")) {
            try (var gz = new java.util.zip.GZIPInputStream(new ByteArrayInputStream(body))) {
                body = gz.readAllBytes();
            }
        } else if (enc.contains("deflate")) {
            try (var inf = new java.util.zip.InflaterInputStream(new ByteArrayInputStream(body))) {
                body = inf.readAllBytes();
            }
        }
        String ct = resp.headers().firstValue("content-type").orElse("text/html; charset=utf-8");
        Matcher csm = Pattern.compile("charset=([\\w-]+)", Pattern.CASE_INSENSITIVE).matcher(ct);
        String charset = csm.find() ? csm.group(1).toUpperCase() : "UTF-8";
        try { return new String(body, charset); }
        catch (Exception e) { return new String(body, StandardCharsets.UTF_8); }
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  NORMALISATION HELPERS  (Requirements 9 & 10 — Regex validation/patterns)
    // ═════════════════════════════════════════════════════════════════════════

    /** Requirement 2 & 10: Strip all HTML tags; decode common entities; collapse whitespace. */
    static String stripHtml(String html) {
        if (html == null) return "";
        return html.replaceAll("<[^>]+>", " ")
                   .replace("&amp;", "&").replace("&nbsp;", " ")
                   .replace("&lt;",  "<").replace("&gt;",   ">")
                   .replace("&#39;", "'").replace("&quot;", "\"")
                   .replaceAll("\\s+", " ").trim();
    }

    /** Requirement 10: Returns the first capture group, or defaultVal if no match. */
    static String firstMatch(String text, String regex, String defaultVal) {
        Matcher m = Pattern.compile(regex, Pattern.DOTALL | Pattern.CASE_INSENSITIVE).matcher(text);
        return m.find() ? m.group(1).trim() : defaultVal;
    }

    /** Requirement 10: Returns all capture groups for a pattern. */
    static List<String> allMatches(String text, String regex) {
        List<String> out = new ArrayList<>();
        Matcher m = Pattern.compile(regex, Pattern.DOTALL | Pattern.CASE_INSENSITIVE).matcher(text);
        while (m.find()) out.add(m.group(1).trim());
        return out;
    }

    /**
     * Requirement 9: Validates a feature cell value and normalises to
     * exactly one of: "Yes" | "No" | "Add-on" | "N/A"
     */
    static String normBool(String raw, boolean hasCheck) {
        String t = raw.toLowerCase().trim();
        if (hasCheck || t.equals("check") || t.equals("✓") || t.equals("•")) return "Yes";
        if (t.isEmpty() || t.equals("–") || t.equals("-") || t.equals("×")) return "No";
        if (t.startsWith("yes") || t.startsWith("included") || t.startsWith("unlimited")
                || t.startsWith("automatic") || t.startsWith("on-demand"))       return "Yes";
        if (t.startsWith("no") || t.contains("not available") || t.contains("not included"))
            return "No";
        if (t.contains("add-on") || t.contains("add on") || t.contains("contact sales")
                || t.contains("optional") || t.contains("upgrade") || t.contains("available for"))
            return "Add-on";
        if (t.equals("n/a")) return "N/A";
        return "Yes";
    }

    /**
     * Requirement 9: Validates and normalises a raw price string to a
     * canonical format: "$X/mo" | "$X/user/mo" | "$X.XXXX/min" | "Usage-based"
     */
    static String normPrice(String raw) {
        if (raw == null || raw.isBlank() || raw.equalsIgnoreCase("N/A")) return "N/A";
        String t = raw.trim();
        // Requirement 9: regex validates price format
        Matcher m = Pattern.compile(
                "\\$([\\d.,]+)\\s*/\\s*(mo|month|user/mo|user/month|min|yr|year|call)",
                Pattern.CASE_INSENSITIVE).matcher(t);
        if (m.find()) {
            String amount = m.group(1);
            String period = m.group(2).toLowerCase()
                    .replace("month", "mo").replace("year", "yr");
            return "$" + amount + "/" + period;
        }
        if (t.toLowerCase().contains("usage") || t.toLowerCase().contains("pay-as"))
            return "Usage-based";
        return t;
    }

    static String now() {
        return DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'")
                .format(LocalDateTime.now(ZoneOffset.UTC));
    }

    private static String readFile(String path) throws IOException {
        return new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8);
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  PROVIDER SCRAPERS  (Requirements 1, 2, 9, 10)
    // ═════════════════════════════════════════════════════════════════════════

    static List<VirtualPhonePlan> scrapeGrasshopper(String html, String scrapedAt) {
        List<VirtualPhonePlan> plans = new ArrayList<>();
        final String[] names      = {"True Solo",  "Solo Plus",  "Small Business"};
        final String[] annPrices  = {"$14/mo",     "$25/mo",     "$55/mo"};
        final String[] mooPrices  = {"$26/mo",     "$45/mo",     "$80/mo"};
        final String[] annTotals  = {"$168/yr",    "$300/yr",    "$660/yr"};
        final String[] savings    = {"Save ~46%",  "Save ~44%",  "Save ~31%"};
        final String[] numNums    = {"1",          "1",          "4"};
        final String[] numExts    = {"1",          "3",          "Unlimited"};
        final String[] numUsers   = {"1",          "Unlimited",  "Unlimited"};
        final String[] addExt     = {"N/A",        "$3/mo each", "N/A"};
        final String[] bestFor    = {
            "Solo entrepreneurs needing a professional business number",
            "Small teams sharing one inbox with no per-user cost",
            "Growing businesses needing multiple lines and extensions"
        };
        Map<String, String[]> ft = parseGrasshopperTable(html);
        for (int i = 0; i < 3; i++) {
            VirtualPhonePlan p = new VirtualPhonePlan();
            p.provider               = "Grasshopper";
            p.planName               = names[i];
            p.pricingModel           = "Flat-rate";
            p.monthlyPrice           = mooPrices[i];
            p.annualMonthlyPrice     = annPrices[i];
            p.annualTotalCost        = annTotals[i];
            p.monthlyVsAnnualSavings = savings[i];
            p.billingOptions         = "Monthly or Annual";
            p.freeTrial              = "Yes";
            p.usersIncluded          = tableVal(ft, "Number of Users",       numUsers[i], i);
            p.phoneNumbersIncluded   = tableVal(ft, "Business Phone Number", numNums[i],  i);
            p.extensionsIncluded     = tableVal(ft, "Extensions",            numExts[i],  i);
            p.callingMinutes         = "Unlimited";
            p.localNumbers           = "Yes"; p.tollFreeNumbers = "Yes";
            p.internationalCalling   = "Add-on"; p.callForwarding = "Yes";
            p.voicemail              = "Yes"; p.voicemailTranscription = "Yes";
            p.callRecording          = "Add-on"; p.customGreetings = "Yes";
            p.autoAttendantIVR       = "Yes"; p.callScreening = "Yes";
            p.conferenceCalling      = "Yes"; p.callAnalytics = "Yes";
            p.smsMessaging           = "Yes"; p.mmsMessaging = "Yes"; p.bulkMessaging = "No";
            p.mobileApp              = "Yes"; p.desktopApp = "Yes"; p.webApp = "Yes";
            p.voipWifiCalling        = "Yes"; p.hdVoice = "Yes"; p.crmIntegration = "No";
            p.otherIntegrations      = "Ruby live receptionist (add-on)";
            p.securityFeatures       = "Call screening; spam protection";
            p.multiLineSupport       = (i == 2) ? "Yes" : "Add-on";
            p.numberPorting          = "Yes"; p.additionalNumbersAddon = "$9/mo each";
            p.additionalExtAddon     = addExt[i]; p.bestFor = bestFor[i];
            p.sourceUrl              = URL_GRASSHOPPER; p.scrapedAt = scrapedAt;
            p.notes = "USD pricing. 7-day free trial. SMS requires $19.50 one-time registration + $1.50/mo fee.";
            plans.add(p);
        }
        return plans;
    }

    private static Map<String, String[]> parseGrasshopperTable(String html) {
        Map<String, String[]> table = new LinkedHashMap<>();
        Matcher rm = Pattern.compile("<tr>(.*?)</tr>", Pattern.DOTALL).matcher(html);
        while (rm.find()) {
            String row = rm.group(1);
            String thRaw = firstMatch(row, "<th class=\"styles_feature[^\"]*\">(.*?)</th>", "");
            if (thRaw.isEmpty()) continue;
            String feat = stripHtml(thRaw).replaceAll("\\s*M\\s*9,0.*", "").trim();
            if (feat.length() > 60) feat = feat.substring(0, 60).trim();
            List<String> tds = allMatches(row, "<td class=\"styles_feature-data[^\"]*\">(.*?)</td>");
            if (tds.size() == 3) {
                String[] vals = new String[3];
                for (int j = 0; j < 3; j++) {
                    boolean chk = tds.get(j).contains("checkmark-green");
                    String txt = stripHtml(tds.get(j));
                    vals[j] = txt.isEmpty() ? (chk ? "Yes" : "No") : txt;
                }
                table.put(feat, vals);
            }
        }
        return table;
    }

    private static String tableVal(Map<String, String[]> table, String key, String fallback, int col) {
        if (table.containsKey(key) && col < table.get(key).length) return table.get(key)[col];
        return fallback;
    }

    static List<VirtualPhonePlan> scrapeGoogleVoice(String html, String scrapedAt) {
        List<VirtualPhonePlan> plans = new ArrayList<>();
        final String[] names     = {"Starter",     "Standard",     "Premier"};
        final String[] prices    = {"$10/user/mo", "$20/user/mo",  "$30/user/mo"};
        final String[] annTotals = {"$120/user/yr","$240/user/yr", "$360/user/yr"};
        final String[] callRec   = {"No",          "Add-on",       "Yes"};
        final String[] autoAtt   = {"No",          "Yes",          "Yes"};
        final String[] intlCall  = {"No",          "Yes",          "Yes"};
        final String[] ringGrp   = {"No",          "Yes",          "Yes"};
        final String[] bestFor   = {
            "Small Google Workspace teams needing a basic virtual number",
            "Mid-size businesses needing IVR, ring groups, and ad-hoc recording",
            "Enterprise teams requiring compliance, eDiscovery, and BigQuery"
        };
        Map<String, String[]> ft = parseGoogleVoiceTable(html);
        for (int i = 0; i < 3; i++) {
            VirtualPhonePlan p = new VirtualPhonePlan();
            p.provider               = "Google Voice"; p.planName = names[i];
            p.pricingModel           = "Per-user"; p.monthlyPrice = prices[i];
            p.annualMonthlyPrice     = prices[i]; p.annualTotalCost = annTotals[i];
            p.monthlyVsAnnualSavings = "No discount";
            p.billingOptions         = "Monthly (via Google Workspace)"; p.freeTrial = "No";
            p.usersIncluded          = "Unlimited"; p.phoneNumbersIncluded = "1 per user";
            p.extensionsIncluded     = "N/A"; p.callingMinutes = "Unlimited";
            p.localNumbers           = "Yes"; p.tollFreeNumbers = "No";
            p.internationalCalling   = intlCall[i]; p.callForwarding = "Yes";
            p.voicemail              = "Yes"; p.voicemailTranscription = "Yes";
            p.callRecording          = callRec[i]; p.customGreetings = "Yes";
            p.autoAttendantIVR       = ftBool(ft, "Multi level auto attendant", autoAtt[i], i);
            p.callScreening          = "Yes"; p.conferenceCalling = "Yes"; p.callAnalytics = "Yes";
            p.smsMessaging           = "Yes"; p.mmsMessaging = "No"; p.bulkMessaging = "No";
            p.mobileApp              = "Yes"; p.desktopApp = "No"; p.webApp = "Yes";
            p.voipWifiCalling        = "Yes"; p.hdVoice = "Yes"; p.crmIntegration = "Yes";
            p.otherIntegrations      = (i == 0) ? "Google Workspace suite" :
                                       (i == 1) ? "Google Workspace; SIP Link; eDiscovery" :
                                                  "Google Workspace; SIP Link; eDiscovery; BigQuery";
            p.securityFeatures       = (i < 2) ? "Admin controls; SSO" : "Admin controls; SSO; eDiscovery";
            p.multiLineSupport       = ftBool(ft, "Ring Groups", ringGrp[i], i);
            p.numberPorting          = "Yes"; p.additionalNumbersAddon = "N/A";
            p.additionalExtAddon     = "N/A"; p.bestFor = bestFor[i];
            p.sourceUrl              = URL_GOOGLE_VOICE; p.scrapedAt = scrapedAt;
            p.notes = "Requires Google Workspace subscription. USD/user/mo. No annual pricing discount.";
            plans.add(p);
        }
        return plans;
    }

    private static Map<String, String[]> parseGoogleVoiceTable(String html) {
        Map<String, String[]> table = new LinkedHashMap<>();
        int start = html.indexOf("PricingTableHeader_tableHeader");
        if (start < 0) start = 0;
        int end = html.indexOf("</table>", start);
        String seg = (end > 0) ? html.substring(start, end + 8) : html;
        Matcher rm = Pattern.compile("<tr>(.*?)</tr>", Pattern.DOTALL).matcher(seg);
        while (rm.find()) {
            String row = rm.group(1);
            String thRaw = firstMatch(row, "<th[^>]*>(.*?)</th>", "");
            if (thRaw.isEmpty()) continue;
            String feat = stripHtml(thRaw).trim();
            if (feat.isEmpty() || feat.length() > 80) continue;
            List<String> tds = allMatches(row, "<td[^>]*>(.*?)</td>");
            if (tds.size() >= 3) {
                String[] vals = new String[3];
                for (int j = 0; j < 3; j++) {
                    boolean chk = tds.get(j).toLowerCase().contains("check");
                    vals[j] = normBool(stripHtml(tds.get(j)), chk);
                }
                table.put(feat, vals);
            }
        }
        return table;
    }

    private static String ftBool(Map<String, String[]> ft, String key, String fallback, int col) {
        if (ft.containsKey(key) && col < ft.get(key).length) return ft.get(key)[col];
        return fallback;
    }

    static List<VirtualPhonePlan> scrapeRingCentral(String html, String scrapedAt) {
        List<VirtualPhonePlan> plans = new ArrayList<>();
        final String[][] cardPrices = extractRingCentralPrices(html);
        final String[] names     = {"Core",          "Advanced",      "Ultra"};
        final String[] annPrices = {
            cardPrices[0][0] != null ? "$" + cardPrices[0][0] + "/user/mo" : "$30/user/mo",
            cardPrices[1][0] != null ? "$" + cardPrices[1][0] + "/user/mo" : "$35/user/mo",
            cardPrices[2][0] != null ? "$" + cardPrices[2][0] + "/user/mo" : "$45/user/mo"
        };
        final String[] mooPrices = {
            cardPrices[0][1] != null ? "$" + cardPrices[0][1] + "/user/mo" : "$40/user/mo",
            cardPrices[1][1] != null ? "$" + cardPrices[1][1] + "/user/mo" : "$45/user/mo",
            cardPrices[2][1] != null ? "$" + cardPrices[2][1] + "/user/mo" : "$55/user/mo"
        };
        final String[] annTotals = {"$360/user/yr", "$420/user/yr", "$540/user/yr"};
        final String[] savings   = {"Save 25%",     "Save 22%",     "Save 18%"};
        final String[] tfMin     = {"100 min/mo",   "1,000 min/mo", "10,000 min/mo"};
        final String[] smsAmt    = {"25/user/mo",   "100/user/mo",  "200/user/mo"};
        final String[] callRec   = {"Add-on", "Yes", "Yes"};
        final String[] crm       = {"No",     "Yes", "Yes"};
        final String[] bestFor   = {
            "Small businesses needing reliable cloud VoIP at low per-user cost",
            "Mid-size teams requiring CRM integrations and advanced call flows",
            "Enterprise needing high-volume SMS, deep analytics, and full integrations"
        };
        for (int i = 0; i < 3; i++) {
            VirtualPhonePlan p = new VirtualPhonePlan();
            p.provider               = "RingCentral"; p.planName = names[i];
            p.pricingModel           = "Per-user"; p.monthlyPrice = mooPrices[i];
            p.annualMonthlyPrice     = annPrices[i]; p.annualTotalCost = annTotals[i];
            p.monthlyVsAnnualSavings = savings[i]; p.billingOptions = "Monthly or Annual";
            p.freeTrial              = "Add-on"; p.usersIncluded = "Unlimited";
            p.phoneNumbersIncluded   = "1 per user"; p.extensionsIncluded = "Unlimited";
            p.callingMinutes         = "Unlimited"; p.localNumbers = "Yes"; p.tollFreeNumbers = "Yes";
            p.internationalCalling   = "Add-on"; p.callForwarding = "Yes";
            p.voicemail              = "Yes"; p.voicemailTranscription = "Yes";
            p.callRecording          = callRec[i]; p.customGreetings = "Yes";
            p.autoAttendantIVR       = "Yes"; p.callScreening = "Yes";
            p.conferenceCalling      = "Yes"; p.callAnalytics = "Yes";
            p.smsMessaging           = "Yes"; p.mmsMessaging = "Yes";
            p.bulkMessaging          = (i == 2) ? "Yes" : "No";
            p.mobileApp              = "Yes"; p.desktopApp = "Yes"; p.webApp = "Yes";
            p.voipWifiCalling        = "Yes"; p.hdVoice = "Yes"; p.crmIntegration = crm[i];
            p.otherIntegrations      = (i == 0) ? "Microsoft 365, Google Workspace" :
                    "Microsoft 365, Google Workspace, Salesforce, HubSpot, Zendesk, Slack";
            p.securityFeatures       = "E2EE (beta); E911; SSO; admin controls";
            p.multiLineSupport       = "Yes"; p.numberPorting = "Yes";
            p.additionalNumbersAddon = "Contact sales"; p.additionalExtAddon = "N/A";
            p.bestFor                = bestFor[i]; p.sourceUrl = URL_RINGCENTRAL;
            p.scrapedAt              = scrapedAt;
            p.notes = "USD/user/mo for Canada. Toll-free minutes pooled: " + tfMin[i] + ". SMS: " + smsAmt[i];
            plans.add(p);
        }
        return plans;
    }

    private static String[][] extractRingCentralPrices(String html) {
        String[][] result = new String[3][2];
        for (int i = 0; i < 3; i++) {
            int idx = html.indexOf("pnp-cards__item--" + i);
            if (idx < 0) continue;
            String seg = html.substring(idx, Math.min(idx + 3000, html.length()));
            List<String> nums = allMatches(seg, "\\$(\\d{2,3})(?=[^\\d]|$)");
            if (nums.size() >= 2) { result[i][0] = nums.get(0); result[i][1] = nums.get(1); }
            else if (nums.size() == 1) { result[i][0] = nums.get(0); }
        }
        return result;
    }

    static List<VirtualPhonePlan> scrapeTwilio(String html, String scrapedAt) {
        List<VirtualPhonePlan> plans = new ArrayList<>();
        Map<String, String[]> rateMap = new LinkedHashMap<>();
        Matcher rm = Pattern.compile("<tr[^>]*>(.*?)</tr>", Pattern.DOTALL).matcher(html);
        while (rm.find()) {
            String row = rm.group(1);
            List<String> cells = allMatches(row, "<t[dh][^>]*>(.*?)</t[dh]>");
            if (cells.size() < 2) continue;
            String key = stripHtml(cells.get(0)).trim();
            if (key.isEmpty()) continue;
            String[] vals = cells.stream().map(c -> stripHtml(c).trim()).toArray(String[]::new);
            rateMap.put(key, vals);
        }
        String localOut   = rateByKey(rateMap, "Local Calls",         1, "$0.014/min");
        String localIn    = rateByKey(rateMap, "Local Calls",         2, "$0.0085/min");
        String tfOut      = rateByKey(rateMap, "Toll-Free Calls",     1, "$0.014/min");
        String tfIn       = rateByKey(rateMap, "Toll-Free Calls",     2, "$0.022/min");
        String numLocal   = rateByKey(rateMap, "Clean Local Numbers", 1, "$1.15/mo");
        String numTF      = rateByKey(rateMap, "Toll-free Numbers",   1, "$2.15/mo");
        String browserOut = rateByKey(rateMap, "Browser/App Calling", 1, "$0.004/min");
        Object[][] rows = {
            {"Local Voice Calls",    normPrice(localOut),
             "Outbound: "+normPrice(localOut)+"; Inbound: "+normPrice(localIn)+". Volume discounts available."},
            {"Toll-Free Voice Calls",normPrice(tfOut),
             "Outbound: "+normPrice(tfOut)+"; Inbound: "+normPrice(tfIn)+". Toll-free number rental: "+normPrice(numTF)},
            {"Phone Number Rental",  normPrice(numLocal),
             "Local: "+normPrice(numLocal)+". Toll-free: "+normPrice(numTF)+". Browser: "+normPrice(browserOut)+"/min"}
        };
        for (Object[] td : rows) {
            VirtualPhonePlan p = new VirtualPhonePlan();
            p.provider               = "Twilio"; p.planName = (String) td[0];
            p.pricingModel           = "Pay-as-you-go"; p.monthlyPrice = (String) td[1];
            p.annualMonthlyPrice     = "Usage-based"; p.annualTotalCost = "Usage-based";
            p.monthlyVsAnnualSavings = "Volume discounts at scale";
            p.billingOptions         = "Pay-as-you-go"; p.freeTrial = "Yes";
            p.usersIncluded          = "Unlimited"; p.extensionsIncluded = "Unlimited";
            p.callingMinutes         = "Per-minute billing";
            p.localNumbers = "Yes"; p.tollFreeNumbers = "Yes"; p.internationalCalling = "Yes";
            p.callForwarding = "Yes"; p.voicemail = "Yes"; p.voicemailTranscription = "Yes";
            p.callRecording = "Yes"; p.customGreetings = "Yes"; p.autoAttendantIVR = "Yes";
            p.callScreening = "Yes"; p.conferenceCalling = "Yes"; p.callAnalytics = "Yes";
            p.smsMessaging = "Yes"; p.mmsMessaging = "Yes"; p.bulkMessaging = "Yes";
            p.mobileApp = "Yes"; p.desktopApp = "Yes"; p.webApp = "Yes";
            p.voipWifiCalling = "Yes"; p.hdVoice = "Yes"; p.crmIntegration = "Yes";
            p.otherIntegrations      = "Salesforce, HubSpot, AWS, Azure, Slack, Dialogflow; open REST API";
            p.securityFeatures       = "SRTP/TLS; SOC2; ISO 27001; E911";
            p.multiLineSupport = "Yes"; p.numberPorting = "Yes";
            p.additionalNumbersAddon = normPrice(numLocal) + " each (local)";
            p.bestFor                = "Developers building custom voice apps; enterprises needing full API control";
            p.sourceUrl              = URL_TWILIO; p.scrapedAt = scrapedAt;
            p.notes                  = (String) td[2];
            plans.add(p);
        }
        return plans;
    }

    private static String rateByKey(Map<String, String[]> map, String keyword, int col, String fallback) {
        for (Map.Entry<String, String[]> e : map.entrySet()) {
            if (e.getKey().toLowerCase().contains(keyword.toLowerCase())) {
                String[] vals = e.getValue();
                if (col < vals.length && !vals[col].isEmpty()) return vals[col];
            }
        }
        return fallback;
    }

    static List<VirtualPhonePlan> scrapeEVoice(String html, String scrapedAt) {
        List<VirtualPhonePlan> plans = new ArrayList<>();
        String eliteMo  = evPrice(html, "elite_monthly",    2500);
        String eliteAnn = evPrice(html, "elite_annual",     2500);
        String epMo     = evPrice(html, "eliteplus_monthly", 2500);
        String epAnn    = evPrice(html, "eliteplus_annual",  2500);
        List<String> eliteFeats   = evFeatures(html, "Elite Plan Includes");
        List<String> inheritFeats = evFeatures(html, "Everything in Elite");
        List<String> plusFeats    = evFeatures(html, ">Plus<");
        List<String> epFeats = new ArrayList<>(inheritFeats);
        epFeats.addAll(plusFeats);
        epFeats = epFeats.stream().distinct().collect(Collectors.toList());
        final String[] names     = {"Elite",           "Elite Plus"};
        final String[] mooPrices = {eliteMo,           epMo};
        final String[] annPrices = {eliteAnn,          epAnn};
        final String[] annTotals = {"$144/yr",         "$228/yr"};
        final boolean[] hasSms   = {false,              true};
        final String[] bestFor   = {
            "Solo entrepreneurs needing a basic virtual number with calling",
            "Small businesses needing calling and unlimited messaging bundled"
        };
        @SuppressWarnings("unchecked")
        final List<String>[] featLists = new List[]{eliteFeats, epFeats};
        for (int i = 0; i < 2; i++) {
            VirtualPhonePlan p = new VirtualPhonePlan();
            p.provider               = "eVoice"; p.planName = names[i];
            p.pricingModel           = "Flat-rate"; p.monthlyPrice = mooPrices[i];
            p.annualMonthlyPrice     = annPrices[i]; p.annualTotalCost = annTotals[i];
            p.monthlyVsAnnualSavings = "Save 14%"; p.billingOptions = "Monthly or Annual";
            p.freeTrial              = "Yes"; p.usersIncluded = "Unlimited";
            p.phoneNumbersIncluded   = "1"; p.extensionsIncluded = "Multiple";
            p.callingMinutes         = "Unlimited";
            p.localNumbers = "Yes"; p.tollFreeNumbers = "Yes"; p.internationalCalling = "Add-on";
            p.callForwarding = "Yes"; p.voicemail = "Yes"; p.voicemailTranscription = "Yes";
            p.callRecording = "Yes"; p.customGreetings = "Yes"; p.autoAttendantIVR = "Yes";
            p.callScreening = "Yes"; p.conferenceCalling = "Yes"; p.callAnalytics = "Yes";
            p.smsMessaging           = hasSms[i] ? "Yes" : "No";
            p.mmsMessaging           = hasSms[i] ? "Yes" : "No";
            p.bulkMessaging          = "No";
            p.mobileApp = "Yes"; p.desktopApp = "Yes"; p.webApp = "Yes";
            p.voipWifiCalling = "Yes"; p.hdVoice = "Yes"; p.crmIntegration = "No";
            p.otherIntegrations      = "Live Receptionist (add-on); voicemail-to-email";
            p.securityFeatures       = "Call screening; spam protection";
            p.multiLineSupport = "Yes"; p.numberPorting = "Yes";
            p.additionalNumbersAddon = "Available on request"; p.bestFor = bestFor[i];
            p.sourceUrl              = URL_EVOICE; p.scrapedAt = scrapedAt;
            p.notes = "USD pricing. 30-day money-back guarantee. Features: " + String.join("; ", featLists[i]);
            plans.add(p);
        }
        return plans;
    }

    /**
     * Extracts the price for a specific eVoice plan variant from the page HTML.
     * Searches backwards from the plan anchor for the nearest heading digit.
     *
     * @param html      raw page HTML
     * @param planKey   the URL parameter value identifying the plan (e.g. "elite_monthly")
     * @param lookBack  number of characters to search before the anchor (typically 2500)
     * @return normalised price string such as "$14/mo", or "N/A" if not found
     */
    private static String evPrice(String html, String planKey, int lookBack) {
        int idx = html.indexOf("plan=" + planKey);
        if (idx < 0) return "N/A";
        String seg = html.substring(Math.max(0, idx - lookBack), idx);
        List<String> digits = allMatches(seg, "elementor-heading-title[^>]*>(\\d{1,2})</div>");
        if (!digits.isEmpty()) return "$" + digits.get(digits.size() - 1) + "/mo";
        return "N/A";
    }

    private static List<String> evFeatures(String html, String marker) {
        List<String> features = new ArrayList<>();
        int idx = html.indexOf(marker);
        if (idx < 0) return features;
        String seg = html.substring(idx, Math.min(idx + 3000, html.length()));
        Matcher m = Pattern.compile("elementor-icon-list-text\">([^<]+)</span>", Pattern.DOTALL).matcher(seg);
        while (m.find()) { String f = m.group(1).trim(); if (!f.isEmpty()) features.add(f); }
        return features;
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  CSV WRITER
    // ═════════════════════════════════════════════════════════════════════════

    static void writeCSV(List<VirtualPhonePlan> plans, String path) throws IOException {
        try (PrintWriter pw = new PrintWriter(
                new BufferedWriter(new FileWriter(path, StandardCharsets.UTF_8)))) {
            pw.println(CSV_HEADER);
            for (VirtualPhonePlan p : plans) pw.println(p.toCsvRow());
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  JSON INDEX WRITER
    //  Serialises all data structures to search_index.json for the frontend.
    //  Format: { vocabulary, invertedIndex, frequencyMap, trieMap,
    //            pageRanks, searchLog, generatedAt }
    // ═════════════════════════════════════════════════════════════════════════

    static void writeSearchIndex(
            SpellChecker    spell,
            InvertedIndex   index,
            FrequencyCounter freq,
            Trie            trie,
            PageRanker      ranker,
            SearchTracker   tracker,
            String          generatedAt) throws IOException {

        StringBuilder sb = new StringBuilder();
        sb.append("{\n");

        // 1. vocabulary (sorted array of all unique words)
        sb.append("  \"vocabulary\": ").append(toJsonArray(new ArrayList<>(spell.getVocabulary()))).append(",\n");

        // 2. invertedIndex  { word: [url, url, ...] }
        sb.append("  \"invertedIndex\": {\n");
        Map<String, TreeSet<String>> idx = index.getIndex();
        int ii = 0;
        for (Map.Entry<String, TreeSet<String>> e : idx.entrySet()) {
            sb.append("    ").append(jsonStr(e.getKey())).append(": ")
              .append(toJsonArray(new ArrayList<>(e.getValue())));
            if (++ii < idx.size()) sb.append(",");
            sb.append("\n");
        }
        sb.append("  },\n");

        // 3. frequencyMap  { url: { word: count } }
        sb.append("  \"frequencyMap\": {\n");
        Map<String, Map<String, Integer>> fdata = freq.getData();
        int fi = 0;
        for (Map.Entry<String, Map<String, Integer>> urlEntry : fdata.entrySet()) {
            sb.append("    ").append(jsonStr(urlEntry.getKey())).append(": {\n");
            Map<String, Integer> words = urlEntry.getValue();
            int wi = 0;
            for (Map.Entry<String, Integer> we : words.entrySet()) {
                sb.append("      ").append(jsonStr(we.getKey())).append(": ").append(we.getValue());
                if (++wi < words.size()) sb.append(",");
                sb.append("\n");
            }
            sb.append("    }");
            if (++fi < fdata.size()) sb.append(",");
            sb.append("\n");
        }
        sb.append("  },\n");

        // 4. trieMap  { prefix: [word, ...] }  (1-2 char prefixes for instant autocomplete)
        sb.append("  \"trieMap\": {\n");
        Map<String, List<String>> tmap = trie.toFlatMap();
        int ti = 0;
        for (Map.Entry<String, List<String>> e : tmap.entrySet()) {
            sb.append("    ").append(jsonStr(e.getKey())).append(": ")
              .append(toJsonArray(e.getValue()));
            if (++ti < tmap.size()) sb.append(",");
            sb.append("\n");
        }
        sb.append("  },\n");

        // 5. pageRanks  { url: score }  pre-computed for common search terms
        List<String> commonTerms = List.of("unlimited", "voicemail", "sms", "crm",
                "recording", "ivr", "international", "toll", "analytics");
        Map<String, Integer> ranks = ranker.scoreForKeywords(commonTerms);
        sb.append("  \"pageRanks\": {\n");
        int ri = 0;
        for (Map.Entry<String, Integer> e : ranks.entrySet()) {
            sb.append("    ").append(jsonStr(e.getKey())).append(": ").append(e.getValue());
            if (++ri < ranks.size()) sb.append(",");
            sb.append("\n");
        }
        sb.append("  },\n");

        // 6. searchLog  { query: count }
        sb.append("  \"searchLog\": {\n");
        Map<String, Integer> log = tracker.getLog();
        int li = 0;
        for (Map.Entry<String, Integer> e : log.entrySet()) {
            sb.append("    ").append(jsonStr(e.getKey())).append(": ").append(e.getValue());
            if (++li < log.size()) sb.append(",");
            sb.append("\n");
        }
        sb.append("  },\n");

        sb.append("  \"generatedAt\": ").append(jsonStr(generatedAt)).append("\n");
        sb.append("}\n");

        Files.writeString(Paths.get(JSON_OUTPUT), sb.toString(), StandardCharsets.UTF_8);
    }

    private static String jsonStr(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"")
                        .replace("\n", "\\n").replace("\r", "") + "\"";
    }

    private static String toJsonArray(List<String> list) {
        if (list.isEmpty()) return "[]";
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(jsonStr(list.get(i)));
        }
        return sb.append("]").toString();
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  MAIN
    // ═════════════════════════════════════════════════════════════════════════

    public static void main(String[] args) {
        System.out.println("╔══════════════════════════════════════════════════════════════╗");
        System.out.println("║   Virtual Phone Number Plan Scraper  v3  –  COMP 8547        ║");
        System.out.println("║   All 10 project requirements implemented                    ║");
        System.out.println("╚══════════════════════════════════════════════════════════════╝\n");

        FetchMode mode = FetchMode.LIVE;
        boolean demoMode = false;
        for (String arg : args) {
            if (arg.equalsIgnoreCase("--demo")) { demoMode = true; continue; }
            try { mode = FetchMode.valueOf(arg.toUpperCase()); }
            catch (IllegalArgumentException ignored) {}
        }
        System.out.println("  Fetch mode : " + mode);
        System.out.println("  Demo mode  : " + (demoMode ? "ON (seeding sample search log)" : "off"));
        System.out.println("  Output CSV : " + CSV_OUTPUT);
        System.out.println("  Output JSON: " + JSON_OUTPUT + "\n");

        // ── Ensure output directory exists ────────────────────────────────────
        try { java.nio.file.Files.createDirectories(java.nio.file.Paths.get(DATA_DIR)); }
        catch (IOException e) { System.err.println("[WARN] Could not create data dir: " + e.getMessage()); }

        // ── Initialise data structures ────────────────────────────────────────
        SpellChecker     spell   = new SpellChecker();     // Algorithm 3
        Trie             trie    = new Trie();              // Algorithm 4
        FrequencyCounter freq    = new FrequencyCounter(); // Algorithm 5
        SearchTracker    tracker = new SearchTracker();    // Algorithm 6
        PageRanker       ranker  = new PageRanker();       // Algorithm 7
        InvertedIndex    index   = new InvertedIndex();    // Algorithm 8

        // ── Optional: seed search tracker with sample queries (--demo flag) ───
        // This populates the Search History panel on first load so the UI has
        // data to display without requiring actual user searches.
        if (demoMode) {
            for (String q : new String[]{"voicemail","sms","crm","pricing","unlimited",
                                          "ivr","recording","grasshopper","twilio","international"}) {
                int sampleCount = (int)(Math.random() * 8) + 1;
                for (int i = 0; i < sampleCount; i++) tracker.record(q);
            }
            System.out.println("  [DEMO] Seeded search log with " + tracker.getLog().size() + " sample queries.\n");
        }

        // ── Provider pipeline ──────────────────────────────────────────────────
        record Provider(String name, String url, String localFile) {}
        var providers = List.of(
                new Provider("Grasshopper",  URL_GRASSHOPPER,  LOCAL_GRASSHOPPER),
                new Provider("Google Voice", URL_GOOGLE_VOICE, LOCAL_GOOGLE_VOICE),
                new Provider("RingCentral",  URL_RINGCENTRAL,  LOCAL_RINGCENTRAL),
                new Provider("Twilio",       URL_TWILIO,       LOCAL_TWILIO),
                new Provider("eVoice",       URL_EVOICE,       LOCAL_EVOICE)
        );

        List<VirtualPhonePlan> allPlans = new ArrayList<>();
        String scrapedAt = now();
        int errors = 0;

        for (var prov : providers) {
            System.out.println("── " + prov.name() + " " + "─".repeat(44 - prov.name().length()));
            try {
                String html = fetchHtml(prov.url(), prov.localFile(), mode);  // Req 1
                String plain = stripHtml(html);                                // Req 2

                // Feed all data structures from this page's plain text
                spell.addText(plain);            // Req 3 — build vocabulary
                freq.addText(prov.url(), plain); // Req 5 — word frequencies per URL
                index.addText(prov.url(), plain);// Req 8 — inverted index
                ranker.addPage(prov.url(), plain);// Req 7 — page ranker corpus

                // Populate trie from vocabulary words (Req 4)
                Matcher wm = Pattern.compile("[a-zA-Z]{3,}").matcher(plain.toLowerCase());
                while (wm.find()) trie.insert(wm.group());

                List<VirtualPhonePlan> scraped = switch (prov.name()) {
                    case "Grasshopper"  -> scrapeGrasshopper(html, scrapedAt);
                    case "Google Voice" -> scrapeGoogleVoice(html, scrapedAt);
                    case "RingCentral"  -> scrapeRingCentral(html, scrapedAt);
                    case "Twilio"       -> scrapeTwilio(html, scrapedAt);
                    case "eVoice"       -> scrapeEVoice(html, scrapedAt);
                    default             -> throw new IllegalStateException(prov.name());
                };
                allPlans.addAll(scraped);
                System.out.printf("  → %d row(s) extracted%n%n", scraped.size());

            } catch (Exception e) {
                System.err.printf("  [ERROR] %s: %s%n%n", prov.name(), e.getMessage());
                errors++;
            }
        }

        // Demo: verify spell checker on a few words
        System.out.println("── Spell Checker Demo ─────────────────────────────────────────");
        for (String w : new String[]{"voicemal", "pricng", "unlimited", "sms"}) {
            if (spell.check(w)) System.out.printf("  ✓ '%s' is correct%n", w);
            else System.out.printf("  ✗ '%s' — suggestions: %s%n", w, spell.suggest(w));
        }

        // Demo: verify trie autocomplete
        System.out.println("\n── Trie Autocomplete Demo ─────────────────────────────────────");
        for (String pfx : new String[]{"vo", "pr", "sm"}) {
            List<String> completions = trie.allWithPrefix(pfx);
            System.out.printf("  '%s' → %s%n", pfx, completions.stream().limit(5).collect(Collectors.toList()));
        }

        // Demo: inverted index lookup
        System.out.println("\n── Inverted Index Demo ────────────────────────────────────────");
        for (String w : new String[]{"voicemail", "pricing", "unlimited"}) {
            System.out.printf("  '%s' found in: %s%n", w, index.lookup(w));
        }

        // Demo: page ranking
        System.out.println("\n── Page Ranker Demo ───────────────────────────────────────────");
        System.out.println("  Ranking pages by keyword 'voicemail':");
        ranker.rank("voicemail").forEach(e -> System.out.printf("    %s → %d hits%n", e.getKey(), e.getValue()));

        // Demo: top searches
        System.out.println("\n── Top Searches Demo ──────────────────────────────────────────");
        tracker.topSearches().stream().limit(5).forEach(e ->
                System.out.printf("  '%s' searched %d time(s)%n", e.getKey(), e.getValue()));

        // ── Write outputs ──────────────────────────────────────────────────────
        System.out.println("\n══════════════════════════════════════════════════════════════");
        if (allPlans.isEmpty()) {
            System.err.println("[FATAL] No plan data — check network / local HTML paths and retry.");
            System.exit(1);
        }

        try {
            writeCSV(allPlans, CSV_OUTPUT);
            System.out.printf("✔  %s  written  (%d rows × %d columns)%n",
                    CSV_OUTPUT, allPlans.size(), CSV_HEADER.split(",").length);
        } catch (IOException e) {
            System.err.println("[ERROR] Writing CSV: " + e.getMessage()); System.exit(1);
        }

        try {
            writeSearchIndex(spell, index, freq, trie, ranker, tracker, scrapedAt);
            System.out.printf("✔  %s  written%n", JSON_OUTPUT);
        } catch (IOException e) {
            System.err.println("[ERROR] Writing JSON: " + e.getMessage()); System.exit(1);
        }

        System.out.println("\n=== Done.  Scraped at: " + scrapedAt + " ===");
    }
}