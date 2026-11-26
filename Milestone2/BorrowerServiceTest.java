import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Plain-Java test runner for BorrowerService (no JUnit, no GUI).
 *
 *   javac -cp ".;mysql-connector-j-26.10.0.jar" BorrowerService.java BorrowerServiceTest.java
 *   java  -cp ".;mysql-connector-j-26.10.0.jar" BorrowerServiceTest
 *
 * Connection settings come from LIBRARY_DB_URL / LIBRARY_DB_USER / LIBRARY_DB_PASSWORD
 * (see BorrowerService.connectFromEnvironment).
 *
 * All test borrowers are fictional and use SSNs starting with 900-99-, a range
 * that is never issued as a real SSN. Every row the tests insert is deleted at
 * the end, and the test checks that pre-existing borrowers were not changed.
 */
public class BorrowerServiceTest {

    // Fictional SSN prefix used only by these tests.
    private static final String TEST_SSN_PREFIX = "900-99-";

    private static int passed = 0;
    private static int failed = 0;
    private static int skipped = 0;

    public static void main(String[] args) {
        System.out.println("=== Input validation tests (no database needed) ===");
        runValidationTests();

        System.out.println();
        System.out.println("=== Connection failure tests ===");
        runConnectionFailureTests();

        System.out.println();
        System.out.println("=== Database tests ===");
        Connection conn = null;
        try {
            conn = BorrowerService.connectFromEnvironment();
        } catch (SQLException e) {
            System.out.println("Could not connect to MySQL: " + e.getMessage());
        }

        if (conn == null) {
            skip("Successful registration");
            skip("Duplicate SSN rejection");
            skip("Sequential, unique card IDs");
            skip("Concurrent registrations get unique card IDs");
            skip("Existing borrower records preserved");
        } else {
            try {
                runDatabaseTests(conn);
            } finally {
                try { conn.close(); } catch (SQLException ignored) { }
            }
        }

        System.out.println();
        System.out.println("Passed: " + passed + "  Failed: " + failed + "  Skipped (need MySQL): " + skipped);
        if (failed > 0) {
            System.exit(1);
        }
    }

    // ------------------------------------------------------------ validation

    private static void runValidationTests() {
        // A null connection is fine here: validation happens before any SQL runs.
        BorrowerService service = new BorrowerService(null);

        expectFailure("Missing name (null) rejected",
                service.registerBorrower(null, "900-99-0001", "1 Test Ln, Dallas, TX", null), "name");
        expectFailure("Missing name (blank) rejected",
                service.registerBorrower("   ", "900-99-0001", "1 Test Ln, Dallas, TX", null), "name");
        expectFailure("Missing SSN (null) rejected",
                service.registerBorrower("Test Person", null, "1 Test Ln, Dallas, TX", null), "SSN");
        expectFailure("Missing SSN (blank) rejected",
                service.registerBorrower("Test Person", "", "1 Test Ln, Dallas, TX", null), "SSN");
        expectFailure("Missing address (null) rejected",
                service.registerBorrower("Test Person", "900-99-0001", null, null), "Address");
        expectFailure("Missing address (blank) rejected",
                service.registerBorrower("Test Person", "900-99-0001", "\t ", null), "Address");
        expectFailure("Badly formatted SSN rejected",
                service.registerBorrower("Test Person", "12-345-678", "1 Test Ln, Dallas, TX", null), "invalid");
        expectFailure("Name longer than column size rejected",
                service.registerBorrower(repeat('A', 101), "900-99-0001", "1 Test Ln", null), "100");

        check("SSN without dashes is normalized",
                "900-99-0001".equals(BorrowerService.formatSsn("900990001")));
    }

    // ---------------------------------------------------- connection failure

    private static void runConnectionFailureTests() {
        // No connection at all.
        expectFailure("Null connection gives a clear error",
                new BorrowerService(null).registerBorrower("Test Person", "900-99-0001", "1 Test Ln", null),
                "no database connection");

        // A connection that has been closed: the service must return an error, not crash.
        Connection closed = null;
        try {
            closed = BorrowerService.connectFromEnvironment();
            closed.close();
        } catch (SQLException e) {
            closed = null;
        }
        if (closed == null) {
            // Still possible to test failure handling: try a server that does not exist.
            boolean threw = false;
            try {
                DriverManager.getConnection("jdbc:mysql://127.0.0.1:1/none?connectTimeout=2000", "x", "x").close();
            } catch (SQLException e) {
                threw = true;
            }
            check("Unreachable server raises SQLException (handled by caller)", threw);
            skip("Closed connection gives a database error");
        } else {
            expectFailure("Closed connection gives a database error",
                    new BorrowerService(closed).registerBorrower("Test Person", "900-99-0001", "1 Test Ln", null),
                    "Database error");
        }
    }

    // -------------------------------------------------------------- database

    private static void runDatabaseTests(Connection conn) {
        List<String> before;
        try {
            if (countTestRows(conn) > 0) {
                System.out.println("Leftover rows with SSN " + TEST_SSN_PREFIX + "* found; removing them first.");
                deleteTestRows(conn);
            }
            before = snapshotBorrowers(conn);
        } catch (SQLException e) {
            System.out.println("Could not read BORROWER table: " + e.getMessage());
            failed++;
            return;
        }

        try {
            BorrowerService service = new BorrowerService(conn);
            int oldMax = maxCardId(conn);

            // 1. Successful registration
            BorrowerService.Result r1 = service.registerBorrower(
                    "Alex Testperson", "900-99-0001", "100 Sample Rd, Richardson, TX", "972-555-0100");
            check("Successful registration", r1.isSuccess());
            System.out.println("      -> " + r1);
            check("New card ID is MAX(Card_id) + 1", r1.getCardId() == oldMax + 1);
            check("Inserted row has the right values", rowMatches(conn, r1.getCardId(),
                    "900-99-0001", "Alex Testperson", "100 Sample Rd, Richardson, TX", "972-555-0100"));

            // 2. Duplicate SSN rejection (same SSN, also typed without dashes)
            expectFailure("Duplicate SSN rejected",
                    service.registerBorrower("Someone Else", "900-99-0001", "200 Other St, Plano, TX", null),
                    "already exists");
            expectFailure("Duplicate SSN (no dashes) rejected",
                    service.registerBorrower("Someone Else", "900990001", "200 Other St, Plano, TX", null),
                    "already exists");

            // An existing record stored WITHOUT dashes must still count as a duplicate.
            try (Statement st = conn.createStatement()) {
                st.executeUpdate("INSERT INTO borrower (Card_id, Ssn, Bname, Address) "
                        + "VALUES (999999, '900990777', 'Legacy Testperson', '9 Old Rd, Dallas, TX')");
            }
            expectFailure("Duplicate SSN vs. existing record stored without dashes rejected",
                    service.registerBorrower("Someone Else", "900-99-0777", "200 Other St, Plano, TX", null),
                    "already exists");
            try (Statement st = conn.createStatement()) {
                st.executeUpdate("DELETE FROM borrower WHERE Card_id = 999999 AND Ssn = '900990777'");
            }

            // 3. Sequential, unique card IDs
            BorrowerService.Result r2 = service.registerBorrower(
                    "Blair Testperson", "900-99-0002", "101 Sample Rd, Richardson, TX", null);
            BorrowerService.Result r3 = service.registerBorrower(
                    "Casey Testperson", "900-99-0003", "102 Sample Rd, Richardson, TX", "");
            check("Sequential card IDs",
                    r2.isSuccess() && r3.isSuccess()
                    && r2.getCardId() == r1.getCardId() + 1
                    && r3.getCardId() == r2.getCardId() + 1);
            check("Blank phone stored as NULL", rowMatches(conn, r3.getCardId(),
                    "900-99-0003", "Casey Testperson", "102 Sample Rd, Richardson, TX", null));

            // 4. Concurrent registrations, each on its own connection
            check("Concurrent registrations get unique card IDs", runConcurrentTest(8));

        } catch (SQLException e) {
            System.out.println("Unexpected SQL error during tests: " + e.getMessage());
            failed++;
        } finally {
            // 5. Clean up and confirm existing data is unchanged
            try {
                deleteTestRows(conn);
                List<String> after = snapshotBorrowers(conn);
                check("Existing borrower records preserved (and test rows removed)", before.equals(after));
            } catch (SQLException e) {
                System.out.println("Cleanup failed: " + e.getMessage());
                failed++;
            }
        }
    }

    /** Registers several borrowers at the same time from different threads/connections. */
    private static boolean runConcurrentTest(int threads) throws SQLException {
        final List<Integer> ids = Collections.synchronizedList(new ArrayList<>());
        final List<String> errors = Collections.synchronizedList(new ArrayList<>());
        List<Thread> workers = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            final String ssn = TEST_SSN_PREFIX + String.format("%04d", 100 + i);
            Thread t = new Thread(() -> {
                try (Connection c = BorrowerService.connectFromEnvironment()) {
                    BorrowerService.Result r = new BorrowerService(c).registerBorrower(
                            "Parallel Testperson", ssn, "300 Thread Ave, Dallas, TX", null);
                    if (r.isSuccess()) {
                        ids.add(r.getCardId());
                    } else {
                        errors.add(r.getMessage());
                    }
                } catch (SQLException e) {
                    errors.add(e.getMessage());
                }
            });
            workers.add(t);
        }
        for (Thread t : workers) t.start();
        for (Thread t : workers) {
            try { t.join(); } catch (InterruptedException ignored) { }
        }

        for (String err : errors) System.out.println("      concurrent error: " + err);
        Set<Integer> unique = new HashSet<>(ids);
        System.out.println("      concurrent card IDs: " + ids);
        return errors.isEmpty() && ids.size() == threads && unique.size() == threads;
    }

    // ---------------------------------------------------------- SQL helpers

    private static int maxCardId(Connection conn) throws SQLException {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT COALESCE(MAX(Card_id), 0) FROM borrower")) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private static int countTestRows(Connection conn) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT COUNT(*) FROM borrower WHERE REPLACE(Ssn, '-', '') LIKE ?")) {
            ps.setString(1, TEST_SSN_PREFIX.replace("-", "") + "%");
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    /** Deletes only the fictional rows created by this test. */
    private static void deleteTestRows(Connection conn) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM borrower WHERE REPLACE(Ssn, '-', '') LIKE ?")) {
            ps.setString(1, TEST_SSN_PREFIX.replace("-", "") + "%");
            ps.executeUpdate();
        }
    }

    /** Every borrower row as text, ordered by card ID, so before/after can be compared. */
    private static List<String> snapshotBorrowers(Connection conn) throws SQLException {
        List<String> rows = new ArrayList<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT Card_id, Ssn, Bname, Address, Phone FROM borrower ORDER BY Card_id")) {
            while (rs.next()) {
                rows.add(rs.getInt(1) + "|" + rs.getString(2) + "|" + rs.getString(3)
                        + "|" + rs.getString(4) + "|" + rs.getString(5));
            }
        }
        return rows;
    }

    private static boolean rowMatches(Connection conn, int cardId, String ssn, String name,
                                      String address, String phone) throws SQLException {
        String sql = "SELECT Ssn, Bname, Address, Phone FROM borrower WHERE Card_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, cardId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return false;
                return ssn.equals(rs.getString(1)) && name.equals(rs.getString(2))
                        && address.equals(rs.getString(3))
                        && (phone == null ? rs.getString(4) == null : phone.equals(rs.getString(4)));
            }
        }
    }

    // ---------------------------------------------------------- tiny asserts

    private static void check(String testName, boolean ok) {
        if (ok) {
            passed++;
            System.out.println("PASS  " + testName);
        } else {
            failed++;
            System.out.println("FAIL  " + testName);
        }
    }

    private static void expectFailure(String testName, BorrowerService.Result r, String expectedText) {
        boolean ok = !r.isSuccess()
                && r.getMessage().toLowerCase().contains(expectedText.toLowerCase());
        check(testName, ok);
        System.out.println("      -> " + r);
    }

    private static void skip(String testName) {
        skipped++;
        System.out.println("SKIP  " + testName + " (requires a running MySQL database)");
    }

    private static String repeat(char c, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) sb.append(c);
        return sb.toString();
    }
}
