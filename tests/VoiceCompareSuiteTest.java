/**
 * VoiceCompareSuiteTest — Unit Test Runner
 * ─────────────────────────────────────────────────────────────────────────────
 * Self-contained test suite (no JUnit dependency) that exercises the six
 * core data structures and the normalisation helpers in VirtualPhoneScraperSuite.
 *
 * Run after compiling both files together:
 *   javac -d out src/VirtualPhoneScraperSuite.java tests/VoiceCompareSuiteTest.java
 *   java  -cp out VoiceCompareSuiteTest
 * ─────────────────────────────────────────────────────────────────────────────
 */
public class VoiceCompareSuiteTest {

    // ── Minimal assertion helpers ─────────────────────────────────────────────

    private static int passed = 0;
    private static int failed = 0;

    static void assertTrue(String name, boolean condition) {
        if (condition) {
            System.out.printf("  ✓ %s%n", name);
            passed++;
        } else {
            System.out.printf("  ✗ FAIL: %s%n", name);
            failed++;
        }
    }

    static void assertEquals(String name, Object expected, Object actual) {
        boolean ok = (expected == null && actual == null)
                  || (expected != null && expected.equals(actual));
        if (ok) {
            System.out.printf("  ✓ %s%n", name);
            passed++;
        } else {
            System.out.printf("  ✗ FAIL: %s — expected <%s> but got <%s>%n", name, expected, actual);
            failed++;
        }
    }

    // ── Test: SpellChecker ────────────────────────────────────────────────────

    static void testSpellChecker() {
        System.out.println("\n── SpellChecker ─────────────────────────────────────────────────");
        VirtualPhoneScraperSuite.SpellChecker sc = new VirtualPhoneScraperSuite.SpellChecker();
        sc.addText("voicemail pricing unlimited sms crm ivr recording");

        assertTrue("known word 'voicemail' is in vocabulary",
                sc.check("voicemail"));
        assertTrue("known word 'sms' is in vocabulary",
                sc.check("sms"));
        assertTrue("unknown word 'xyz123' not in vocabulary",
                !sc.check("xyz123"));

        // Misspelling should return at least one suggestion
        var suggestions = sc.suggest("voicemal");
        assertTrue("'voicemal' yields at least 1 suggestion",
                !suggestions.isEmpty());
        assertTrue("top suggestion for 'voicemal' is 'voicemail'",
                suggestions.get(0).equals("voicemail"));

        // Exact match returns itself
        var exact = sc.suggest("pricing");
        assertTrue("exact match 'pricing' returns itself",
                exact.size() == 1 && exact.get(0).equals("pricing"));
    }

    // ── Test: Levenshtein ─────────────────────────────────────────────────────

    static void testLevenshtein() {
        System.out.println("\n── Levenshtein Distance ─────────────────────────────────────────");
        assertEquals("identical strings → 0",
                0, VirtualPhoneScraperSuite.SpellChecker.levenshtein("abc", "abc"));
        assertEquals("empty vs abc → 3",
                3, VirtualPhoneScraperSuite.SpellChecker.levenshtein("", "abc"));
        assertEquals("kitten → sitting = 3",
                3, VirtualPhoneScraperSuite.SpellChecker.levenshtein("kitten", "sitting"));
        assertEquals("saturday → sunday = 3",
                3, VirtualPhoneScraperSuite.SpellChecker.levenshtein("saturday", "sunday"));
        assertEquals("single substitution = 1",
                1, VirtualPhoneScraperSuite.SpellChecker.levenshtein("cat", "bat"));
    }

    // ── Test: Trie ────────────────────────────────────────────────────────────

    static void testTrie() {
        System.out.println("\n── Trie (Autocomplete) ──────────────────────────────────────────");
        VirtualPhoneScraperSuite.Trie trie = new VirtualPhoneScraperSuite.Trie();
        trie.insert("voice");
        trie.insert("voicemail");
        trie.insert("voip");
        trie.insert("sms");
        trie.insert("smishing");

        var voResults = trie.allWithPrefix("vo");
        assertTrue("prefix 'vo' returns 3 words",
                voResults.size() == 3);
        assertTrue("prefix 'vo' includes 'voice'",
                voResults.contains("voice"));
        assertTrue("prefix 'vo' includes 'voicemail'",
                voResults.contains("voicemail"));
        assertTrue("prefix 'vo' includes 'voip'",
                voResults.contains("voip"));

        var smResults = trie.allWithPrefix("sm");
        assertTrue("prefix 'sm' returns 2 words",
                smResults.size() == 2);

        var noResults = trie.allWithPrefix("xyz");
        assertTrue("unknown prefix returns empty list",
                noResults.isEmpty());

        var flatMap = trie.toFlatMap();
        assertTrue("flat map contains 'vo' key",
                flatMap.containsKey("vo"));
        assertTrue("flat map contains 's' key",
                flatMap.containsKey("s"));
    }

    // ── Test: InvertedIndex ───────────────────────────────────────────────────

    static void testInvertedIndex() {
        System.out.println("\n── InvertedIndex ────────────────────────────────────────────────");
        VirtualPhoneScraperSuite.InvertedIndex idx = new VirtualPhoneScraperSuite.InvertedIndex();
        idx.addText("https://provider-a.com", "voicemail sms crm unlimited calling");
        idx.addText("https://provider-b.com", "voicemail recording ivr analytics");
        idx.addText("https://provider-c.com", "sms mms bulk messaging");

        var vUrls = idx.lookup("voicemail");
        assertTrue("'voicemail' found in 2 pages",
                vUrls.size() == 2);
        assertTrue("'voicemail' found in provider-a",
                vUrls.contains("https://provider-a.com"));
        assertTrue("'voicemail' found in provider-b",
                vUrls.contains("https://provider-b.com"));

        var smsUrls = idx.lookup("sms");
        assertTrue("'sms' found in 2 pages",
                smsUrls.size() == 2);

        var missingUrls = idx.lookup("quantum");
        assertTrue("unknown term returns empty set",
                missingUrls.isEmpty());
    }

    // ── Test: FrequencyCounter ────────────────────────────────────────────────

    static void testFrequencyCounter() {
        System.out.println("\n── FrequencyCounter ─────────────────────────────────────────────");
        VirtualPhoneScraperSuite.FrequencyCounter fc = new VirtualPhoneScraperSuite.FrequencyCounter();
        fc.addText("https://a.com", "voicemail voicemail voicemail sms sms");
        fc.addText("https://b.com", "pricing pricing");

        assertEquals("'voicemail' count on a.com = 3",
                3, fc.count("https://a.com", "voicemail"));
        assertEquals("'sms' count on a.com = 2",
                2, fc.count("https://a.com", "sms"));
        assertEquals("'pricing' count on b.com = 2",
                2, fc.count("https://b.com", "pricing"));
        assertEquals("'voicemail' count on b.com = 0",
                0, fc.count("https://b.com", "voicemail"));
    }

    // ── Test: PageRanker ──────────────────────────────────────────────────────

    static void testPageRanker() {
        System.out.println("\n── PageRanker ───────────────────────────────────────────────────");
        VirtualPhoneScraperSuite.PageRanker ranker = new VirtualPhoneScraperSuite.PageRanker();
        ranker.addPage("https://a.com", "voicemail voicemail voicemail sms");
        ranker.addPage("https://b.com", "voicemail sms sms sms");
        ranker.addPage("https://c.com", "crm analytics dashboard");

        var ranks = ranker.rank("voicemail");
        assertTrue("voicemail ranking returns 2 results",
                ranks.size() == 2);
        assertTrue("a.com ranks first for 'voicemail' (3 hits vs 1)",
                ranks.get(0).getKey().equals("https://a.com"));
        assertEquals("a.com hit count = 3",
                3, ranks.get(0).getValue());

        var noRanks = ranker.rank("quantum");
        assertTrue("unknown keyword returns empty ranking",
                noRanks.isEmpty());
    }

    // ── Test: SearchTracker ───────────────────────────────────────────────────

    static void testSearchTracker() {
        System.out.println("\n── SearchTracker ────────────────────────────────────────────────");
        VirtualPhoneScraperSuite.SearchTracker tracker = new VirtualPhoneScraperSuite.SearchTracker();
        tracker.record("voicemail");
        tracker.record("voicemail");
        tracker.record("sms");
        tracker.record(null);    // should be safely ignored
        tracker.record("  ");    // blank — should be ignored

        assertEquals("'voicemail' recorded 2 times",
                2, tracker.getLog().get("voicemail"));
        assertEquals("'sms' recorded 1 time",
                1, tracker.getLog().get("sms"));
        assertTrue("log size = 2 (nulls and blanks ignored)",
                tracker.getLog().size() == 2);

        var top = tracker.topSearches();
        assertTrue("top searches non-empty",
                !top.isEmpty());
        assertTrue("highest-count query is first",
                top.get(0).getKey().equals("voicemail"));
    }

    // ── Test: normPrice ───────────────────────────────────────────────────────

    static void testNormPrice() {
        System.out.println("\n── normPrice (Data Validation) ──────────────────────────────────");
        assertEquals("null → N/A",
                "N/A", VirtualPhoneScraperSuite.normPrice(null));
        assertEquals("blank → N/A",
                "N/A", VirtualPhoneScraperSuite.normPrice("   "));
        assertEquals("$10/month normalised",
                "$10/mo", VirtualPhoneScraperSuite.normPrice("$10/month"));
        assertEquals("$30/user/mo passes through",
                "$30/user/mo", VirtualPhoneScraperSuite.normPrice("$30/user/mo"));
        assertEquals("usage-based string recognised",
                "Usage-based", VirtualPhoneScraperSuite.normPrice("pay-as-you-go"));
    }

    // ── Test: normBool ────────────────────────────────────────────────────────

    static void testNormBool() {
        System.out.println("\n── normBool (Data Validation) ───────────────────────────────────");
        assertEquals("'yes' → Yes",   "Yes",    VirtualPhoneScraperSuite.normBool("yes", false));
        assertEquals("'no'  → No",    "No",     VirtualPhoneScraperSuite.normBool("no", false));
        assertEquals("'add-on' → Add-on", "Add-on", VirtualPhoneScraperSuite.normBool("add-on", false));
        assertEquals("'n/a' → N/A",   "N/A",    VirtualPhoneScraperSuite.normBool("n/a", false));
        assertEquals("checkmark flag → Yes", "Yes", VirtualPhoneScraperSuite.normBool("", true));
        assertEquals("'included' → Yes", "Yes", VirtualPhoneScraperSuite.normBool("included", false));
        assertEquals("'not available' → No", "No",
                VirtualPhoneScraperSuite.normBool("not available", false));
    }

    // ── Test: stripHtml ───────────────────────────────────────────────────────

    static void testStripHtml() {
        System.out.println("\n── stripHtml (HTML Parser) ───────────────────────────────────────");
        assertEquals("tags stripped",
                "Hello World",
                VirtualPhoneScraperSuite.stripHtml("<b>Hello</b> <i>World</i>"));
        assertEquals("entities decoded",
                "A & B < C > D",
                VirtualPhoneScraperSuite.stripHtml("A &amp; B &lt; C &gt; D"));
        assertEquals("nbsp decoded",
                "a b",
                VirtualPhoneScraperSuite.stripHtml("a&nbsp;b"));
        assertEquals("null returns empty string",
                "",
                VirtualPhoneScraperSuite.stripHtml(null));
    }

    // ── Main ──────────────────────────────────────────────────────────────────

    public static void main(String[] args) {
        System.out.println("╔══════════════════════════════════════════════════════════════╗");
        System.out.println("║           VoiceCompare — Unit Test Suite                     ║");
        System.out.println("╚══════════════════════════════════════════════════════════════╝");

        testSpellChecker();
        testLevenshtein();
        testTrie();
        testInvertedIndex();
        testFrequencyCounter();
        testPageRanker();
        testSearchTracker();
        testNormPrice();
        testNormBool();
        testStripHtml();

        System.out.println("\n══════════════════════════════════════════════════════════════");
        System.out.printf("  Results: %d passed, %d failed%n", passed, failed);
        System.out.println("══════════════════════════════════════════════════════════════");

        if (failed > 0) {
            System.exit(1);
        }
    }
}
