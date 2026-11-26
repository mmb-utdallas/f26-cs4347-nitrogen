import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Plain-Java tests for BookService (book search and availability).
 *
 *   javac -cp ".;mysql-connector-j-26.10.0.jar" *.java
 *   java  -cp ".;mysql-connector-j-26.10.0.jar" BookServiceTest
 *
 * Uses LIBRARY_DB_URL / LIBRARY_DB_USER / LIBRARY_DB_PASSWORD. Inserts fictional
 * books (ISBN "TST..."), removes them afterwards and checks that every other
 * row in the database is unchanged.
 */
public class BookServiceTest {

    // Fixture books. ISBN_B contains ISBN_A as a substring on purpose, to prove
    // a full-ISBN search still returns only one book.
    static final String ISBN_A = "TST0000001";     // "The Zorblax Chronicles" by Zorbington
    static final String ISBN_B = "TST00000010";    // "Zorblax Cooking" by Quuxley
    static final String ISBN_C = "TST0000003";     // "Advanced Quuxology" by Testwright AND Zorbington
    static final String ISBN_D = "TST0000004";     // "Odd Symbols 50% Off_Sale" (no authors), checked out
    static final int CARD = TestFixtures.FIRST_TEST_CARD;

    public static void main(String[] args) {
        System.out.println("=== Input validation (no database needed) ===");
        BookService offline = new BookService(null);
        check("Blank search rejected", throwsIllegalArgument(offline, "   "));
        check("Null search rejected", throwsIllegalArgument(offline, null));
        check("Too many search words rejected", throwsIllegalArgument(offline, "a b c d e f g h i j k"));

        System.out.println();
        System.out.println("=== Database tests ===");
        Connection conn;
        try {
            conn = BorrowerService.connectFromEnvironment();
        } catch (SQLException e) {
            System.out.println("Could not connect to MySQL: " + e.getMessage());
            TestFixtures.skip("All book search database tests");
            TestFixtures.finish();
            return;
        }

        List<String> before = null;
        try {
            TestFixtures.cleanUp(conn);            // remove leftovers from an interrupted run
            before = TestFixtures.snapshotAllTables(conn);
            createFixture(conn);
            runSearchTests(new BookService(conn), conn);
        } catch (SQLException e) {
            System.out.println("Unexpected SQL error: " + e.getMessage());
            TestFixtures.failed++;
        } finally {
            try {
                TestFixtures.cleanUp(conn);
                if (before != null) {
                    check("Existing records preserved (test rows removed)",
                            before.equals(TestFixtures.snapshotAllTables(conn)));
                }
                conn.close();
            } catch (SQLException e) {
                System.out.println("Cleanup failed: " + e.getMessage());
                TestFixtures.failed++;
            }
        }
        TestFixtures.finish();
    }

    private static void createFixture(Connection c) throws SQLException {
        TestFixtures.insertBook(c, ISBN_A, "The Zorblax Chronicles", "Wilhelmina Zorbington");
        TestFixtures.insertBook(c, ISBN_B, "Zorblax Cooking", "Quentin Quuxley");
        TestFixtures.insertBook(c, ISBN_C, "Advanced Quuxology", "Ada Testwright", "Wilhelmina Zorbington");
        TestFixtures.insertBook(c, ISBN_D, "Odd Symbols 50% Off_Sale");
        TestFixtures.insertBorrower(c, CARD, "Fixture Reader");
        LocalDate today = LocalDate.now();
        // ISBN_D is currently out; ISBN_A was borrowed before but returned (so IN).
        TestFixtures.insertLoan(c, ISBN_D, CARD, today, today.plusDays(14), null);
        TestFixtures.insertLoan(c, ISBN_A, CARD, today.minusDays(20), today.minusDays(6), today.minusDays(7));
    }

    private static void runSearchTests(BookService s, Connection c) throws SQLException {
        // Exact ISBN: one result even though ISBN_B contains ISBN_A.
        List<BookService.BookResult> r = s.searchBooks(ISBN_A);
        check("Exact ISBN returns exactly one book", r.size() == 1 && r.get(0).getIsbn().equals(ISBN_A));
        r = s.searchBooks(ISBN_A.toLowerCase());
        check("Exact ISBN is case-insensitive", r.size() == 1 && r.get(0).getIsbn().equals(ISBN_A));

        // Partial ISBN
        check("Partial ISBN matches all books containing it",
                isbns(s.searchBooks("TST000000")).equals(Set.of(ISBN_A, ISBN_B, ISBN_C, ISBN_D)));

        // Partial title
        check("Partial title", isbns(s.searchBooks("orbla")).equals(Set.of(ISBN_A, ISBN_B)));

        // Partial author
        check("Partial author", isbns(s.searchBooks("quuxl")).equals(Set.of(ISBN_B)));

        // Case-insensitive
        check("Case-insensitive (ZORBLAX = zorblax = ZoRbLaX)",
                isbns(s.searchBooks("ZORBLAX")).equals(isbns(s.searchBooks("zorblax")))
                && isbns(s.searchBooks("ZoRbLaX")).equals(Set.of(ISBN_A, ISBN_B)));

        // Combinations: every word must match ISBN, title or an author.
        check("Combined title + author", isbns(s.searchBooks("zorblax zorbington")).equals(Set.of(ISBN_A)));
        check("Combined ISBN + title", isbns(s.searchBooks("TST0000003 quux")).equals(Set.of(ISBN_C)));
        check("Combined words that match nothing together",
                s.searchBooks("cooking zorbington").isEmpty());

        // Multiple authors: matching ONE author still shows ALL authors.
        r = s.searchBooks("testwright");
        check("Multi-author book shows all authors",
                r.size() == 1 && r.get(0).getAuthors().equals("Ada Testwright, Wilhelmina Zorbington"));
        System.out.println("      -> " + r);

        // Availability: D is out, A was returned, C was never borrowed.
        check("Status OUT for a book with an active loan", statusOf(s, ISBN_D).equals("OUT"));
        check("Status IN for a returned book", statusOf(s, ISBN_A).equals("IN"));
        check("Status IN for a never-borrowed book", statusOf(s, ISBN_C).equals("IN"));

        // No results
        check("No matching results gives an empty list", s.searchBooks("qqqnothingmatchesqqq").isEmpty());

        // No duplicates: "zorb" hits title AND author of A, and two authors' rows of C.
        r = s.searchBooks("zorb");
        check("No duplicate books in results", r.size() == isbns(r).size() && r.size() == 3);

        // Book with no authors still appears (LEFT JOIN).
        r = s.searchBooks("odd symbols");
        check("Book without authors listed with empty author list",
                r.size() == 1 && r.get(0).getAuthors().isEmpty());

        // LIKE wildcards in the input are matched literally.
        check("'%' and '_' are literal characters, not wildcards",
                isbns(s.searchBooks("50%")).equals(Set.of(ISBN_D))
                && isbns(s.searchBooks("off_sale")).equals(Set.of(ISBN_D))
                && s.searchBooks("%%").isEmpty());

        // SQL injection attempts are treated as plain text.
        int booksBefore = TestFixtures.countRows(c, "SELECT COUNT(*) FROM book");
        check("SQL injection: ' OR '1'='1 returns nothing",
                s.searchBooks("' OR '1'='1").isEmpty());
        check("SQL injection: '; DROP TABLE book; -- returns nothing",
                s.searchBooks("x'); DROP TABLE book; --").isEmpty());
        check("SQL injection: book table still intact",
                TestFixtures.countRows(c, "SELECT COUNT(*) FROM book") == booksBefore);
    }

    // ------------------------------------------------------------ helpers

    private static Set<String> isbns(List<BookService.BookResult> results) {
        Set<String> set = new HashSet<>();
        for (BookService.BookResult r : results) set.add(r.getIsbn());
        return set;
    }

    private static String statusOf(BookService s, String isbn) throws SQLException {
        List<BookService.BookResult> r = s.searchBooks(isbn);
        return r.size() == 1 ? r.get(0).getStatus() : "NOT FOUND";
    }

    private static boolean throwsIllegalArgument(BookService s, String text) {
        try {
            s.searchBooks(text);
            return false;
        } catch (IllegalArgumentException e) {
            return true;
        } catch (SQLException e) {
            return false;
        }
    }

    private static void check(String name, boolean ok) {
        TestFixtures.check(name, ok);
    }
}
