package io.github.belingueres.dinuk.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class TestHelper {

    private TestHelper() {
        // no instances
    }

/** Normalise whitespace so assertion messages stay readable */
    private static String normalise(String sql) {
        return sql.trim()
                .replaceAll("\\s*=\\s*", "=")
                .replaceAll("\\s*\"([^\"]+?)\"", " $1")
                .replaceAll("\\s+", " ")
                .replaceAll("(?i)\\bOR\\b", "or")
                .replaceAll("(?i)\\bIS NULL\\b", "is null")
                .replaceAll("(?i)\\s+AS\\s+", " ")
                .replaceAll("(?i) from ", " from ")
                .replaceAll("(?i) where ", " where ")
                .replaceAll("(?i) and ", " and ")
                .replaceAll("(?i) union ", " union ")
                .replaceAll("(?i) intersect ", " intersect ")
                .replaceAll("(?i) except ", " except ")
                .replaceAll("(?i) join ", " join ")
                .replaceAll("(?i) on ", " on ");
    }

    public static void assertSqlContains(String rewritten, String fragment) {
        assertTrue(normalise(rewritten).contains(fragment),
                () -> "Expected [" + fragment + "] in:\n" + rewritten);
    }

    public static void assertSqlNotContains(String rewritten, String fragment) {
        assertFalse(normalise(rewritten).contains(fragment),
                () -> "Did NOT expect [" + fragment + "] in:\n" + rewritten);
    }

    public static void assertSqlEquals(String expected, String rewritten) {
        assertEquals(normalise(expected), normalise(rewritten),
                () -> "Expected SQL:\n" + expected + "\nBut got:\n"
                        + rewritten);
    }

    public static void assertSqlEquals(String expected, String rewritten, String message) {
        assertEquals(normalise(expected), normalise(rewritten),
                () -> message + "\nExpected SQL:\n" + expected + "\nBut got:\n" + rewritten);
    }

}
