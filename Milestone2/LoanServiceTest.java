import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Plain-Java tests for LoanService (checkout and check-in) and FineCalculator.
 *
 *   javac -cp ".;mysql-connector-j-26.10.0.jar" *.java
 *   java  -cp ".;mysql-connector-j-26.10.0.jar" LoanServiceTest
 *
 * Uses LIBRARY_DB_URL / LIBRARY_DB_USER / LIBRARY_DB_PASSWORD. All data is
 * fictional (ISBN "TST...", card IDs 990001+), removed afterwards, and the test
 * checks that every pre-existing row is unchanged.
 */
public class LoanServiceTest {

    // "Today" is fixed for the whole run so date checks are exact.
    static final ZoneId ZONE = ZoneId.systemDefault();
    static final LocalDate T = LocalDate.now(ZONE);
    static final Clock CLOCK = Clock.fixed(T.atStartOfDay(ZONE).toInstant(), ZONE);

    static final int C0 = TestFixtures.FIRST_TEST_CARD;   // card IDs are C0 + n

    public static void main(String[] args) {
        System.out.println("=== FineCalculator (no database needed) ===");
        runFineCalculatorTests();

        System.out.println();
        System.out.println("=== Input validation (no database needed) ===");
        runValidationTests();

        System.out.println();
        System.out.println("=== Database tests ===");
        Connection conn;
        try {
            conn = BorrowerService.connectFromEnvironment();
        } catch (SQLException e) {
            System.out.println("Could not connect to MySQL: " + e.getMessage());
            TestFixtures.skip("All checkout and check-in database tests");
            TestFixtures.finish();
            return;
        }

        List<String> before = null;
        try {
            TestFixtures.cleanUp(conn);
            before = TestFixtures.snapshotAllTables(conn);
            createFixture(conn);
            LoanService service = new LoanService(conn, CLOCK);
            runCheckoutTests(service, conn);
            runCheckInTests(service, conn);
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

    // =================================================================== fines

    private static void runFineCalculatorTests() {
        LocalDate due = LocalDate.of(2026, 3, 10);
        check("Returned before due date: $0.00",
                FineCalculator.calculateFine(due, due.minusDays(3)).equals(new BigDecimal("0.00")));
        check("Returned on due date: $0.00",
                FineCalculator.calculateFine(due, due).equals(new BigDecimal("0.00")));
        check("One day late: $0.25",
                FineCalculator.calculateFine(due, due.plusDays(1)).equals(new BigDecimal("0.25")));
        check("Ten days late: $2.50 (exact BigDecimal, 2 decimals)",
                FineCalculator.calculateFine(due, due.plusDays(10)).equals(new BigDecimal("2.50")));
        check("Late across a month boundary counts calendar days (Mar 10 -> Apr 2 = 23 days = $5.75)",
                FineCalculator.calculateFine(due, LocalDate.of(2026, 4, 2)).equals(new BigDecimal("5.75")));
    }

    private static void runValidationTests() {
        LoanService offline = new LoanService(null, CLOCK);
        expectFailure("Checkout with blank ISBN rejected", offline.checkout("  ", C0), "ISBN is required");
        expectFailure("Checkout with invalid card ID rejected", offline.checkout("TSTL001", 0), "positive");
        expectFailure("Checkout without a connection gives a clear error",
                offline.checkout("TSTL001", C0), "no database connection");
        expectFailure("Check-in with no loans selected rejected",
                offline.checkIn(List.of()), "at least one");
        expectFailure("Check-in of more than 3 loans rejected",
                offline.checkIn(List.of(1, 2, 3, 4)), "at most 3");
        expectFailure("Check-in with the same loan twice rejected",
                offline.checkIn(List.of(5, 5)), "more than once");
        try {
            offline.findActiveLoans(null, null, "  ");
            check("Loan search with no filters rejected", false);
        } catch (IllegalArgumentException e) {
            check("Loan search with no filters rejected", true);
        } catch (SQLException e) {
            check("Loan search with no filters rejected", false);
        }
    }

    // ================================================================= fixture

    private static String isbn(int n) {
        return String.format("TSTL%03d", n);
    }

    private static void createFixture(Connection c) throws SQLException {
        int[] books = {1, 2, 3, 4, 5, 6, 10, 11, 12, 13, 20, 21, 22, 23, 24, 25, 26, 27,
                       30, 31, 32, 33, 34, 35, 36, 37, 38, 39, 40};
        for (int b : books) {
            TestFixtures.insertBook(c, isbn(b), "Loan Test Book " + b);
        }
        String[] names = {
                "Checkout Success", "Second Reader", "Three Loans", "Owes Money", "Overdue Nofine",
                "Overdue Stalefine", "Late Unrecorded", "Paid Up", "Racer One", "Racer Two",
                "Racer Three", "Racer Four", "Racer Five", "Racer Six", "Limit Racer",
                "Returna Testperson", "Multi Returner", "Next Borrower", "Rollback Case"
        };
        for (int i = 0; i < names.length; i++) {
            TestFixtures.insertBorrower(c, C0 + i, names[i]);
        }
    }

    // ================================================================ checkout

    private static void runCheckoutTests(LoanService s, Connection c) throws SQLException {
        // Successful checkout with exact dates
        LoanService.Result r = s.checkout(isbn(1), C0);
        check("Successful checkout", r.isSuccess());
        System.out.println("      -> " + r);
        check("Date_out is today, Due_date is today + 14, Date_in is NULL",
                TestFixtures.countRows(c, "SELECT COUNT(*) FROM book_loans WHERE Loan_id = ? AND Date_out = ? "
                        + "AND Due_date = ? AND Date_in IS NULL", r.getId(),
                        java.sql.Date.valueOf(T), java.sql.Date.valueOf(T.plusDays(14))) == 1);
        check("Due date is exactly 14 days after Date_out (DATEDIFF in SQL)",
                TestFixtures.countRows(c, "SELECT DATEDIFF(Due_date, Date_out) FROM book_loans WHERE Loan_id = ?",
                        r.getId()) == 14);

        // Nonexistent book / borrower
        expectFailure("Nonexistent book rejected", s.checkout("TSTNOPE", C0), "No book found");
        expectFailure("Nonexistent borrower rejected", s.checkout(isbn(2), TestFixtures.LAST_TEST_CARD),
                "No borrower found");

        // Already checked out (by someone else, and by the same borrower)
        expectFailure("Book already checked out by another borrower rejected",
                s.checkout(isbn(1), C0 + 1), "not available");
        expectFailure("Book already checked out by the same borrower rejected",
                s.checkout(isbn(1), C0), "not available");

        // Three-loan limit
        boolean firstThree = s.checkout(isbn(2), C0 + 2).isSuccess()
                && s.checkout(isbn(3), C0 + 2).isSuccess()
                && s.checkout(isbn(4), C0 + 2).isSuccess();
        check("Borrower can have 3 active loans", firstThree);
        expectFailure("Fourth active loan rejected", s.checkout(isbn(5), C0 + 2), "limit is 3");

        // Unpaid fine recorded in FINES
        int late = TestFixtures.insertLoan(c, isbn(10), C0 + 3, T.minusDays(30), T.minusDays(16), T.minusDays(10));
        TestFixtures.insertFine(c, late, "1.50", false);
        expectFailure("Borrower with unpaid fine rejected", s.checkout(isbn(5), C0 + 3), "unpaid fines of $1.50");

        // Overdue book still out, FINES has no row yet (stale fines table)
        TestFixtures.insertLoan(c, isbn(11), C0 + 4, T.minusDays(17), T.minusDays(3), null);
        expectFailure("Overdue loan with no FINES row rejected (fine computed live)",
                s.checkout(isbn(5), C0 + 4), "unpaid fines of $0.75");

        // Overdue book still out, FINES row exists but is stale ($0.00)
        int stale = TestFixtures.insertLoan(c, isbn(12), C0 + 5, T.minusDays(22), T.minusDays(8), null);
        TestFixtures.insertFine(c, stale, "0.00", false);
        expectFailure("Overdue loan with stale $0.00 FINES row rejected",
                s.checkout(isbn(5), C0 + 5), "unpaid fines of $2.00");

        // Returned late but fine never recorded
        TestFixtures.insertLoan(c, isbn(13), C0 + 6, T.minusDays(20), T.minusDays(6), T.minusDays(4));
        expectFailure("Late return with no FINES row rejected", s.checkout(isbn(5), C0 + 6),
                "unpaid fines of $0.50");

        // Paid fines do not block checkout
        int paid = TestFixtures.insertLoan(c, isbn(13), C0 + 7, T.minusDays(40), T.minusDays(26), T.minusDays(22));
        TestFixtures.insertFine(c, paid, "1.00", true);
        check("Borrower whose fine is paid can check out", s.checkout(isbn(6), C0 + 7).isSuccess());

        // Concurrency 1: six borrowers try to take the same book at once
        List<Integer> racers = List.of(C0 + 8, C0 + 9, C0 + 10, C0 + 11, C0 + 12, C0 + 13);
        int wins = runConcurrentCheckouts(Collections.nCopies(racers.size(), isbn(20)), racers);
        check("Concurrent checkouts of one book: exactly one succeeds", wins == 1
                && TestFixtures.countRows(c,
                        "SELECT COUNT(*) FROM book_loans WHERE Isbn = ? AND Date_in IS NULL", isbn(20)) == 1);

        // Concurrency 2: borrower with 2 loans tries 5 checkouts at once
        TestFixtures.insertLoan(c, isbn(21), C0 + 14, T, T.plusDays(14), null);
        TestFixtures.insertLoan(c, isbn(22), C0 + 14, T, T.plusDays(14), null);
        wins = runConcurrentCheckouts(List.of(isbn(23), isbn(24), isbn(25), isbn(26), isbn(27)),
                Collections.nCopies(5, C0 + 14));
        check("Concurrent checkouts cannot exceed the 3-loan limit", wins == 1
                && TestFixtures.countRows(c,
                        "SELECT COUNT(*) FROM book_loans WHERE Card_id = ? AND Date_in IS NULL", C0 + 14) == 3);
    }

    /** Runs checkout(isbns[i], cards[i]) in parallel threads, each with its own connection. */
    private static int runConcurrentCheckouts(List<String> isbns, List<Integer> cards) {
        List<String> messages = Collections.synchronizedList(new ArrayList<>());
        List<Thread> threads = new ArrayList<>();
        final int[] wins = {0};
        for (int i = 0; i < isbns.size(); i++) {
            final String isbn = isbns.get(i);
            final int card = cards.get(i);
            threads.add(new Thread(() -> {
                try (Connection own = BorrowerService.connectFromEnvironment()) {
                    LoanService.Result r = new LoanService(own, CLOCK).checkout(isbn, card);
                    if (r.isSuccess()) {
                        synchronized (wins) { wins[0]++; }
                    }
                    messages.add(r.toString());
                } catch (SQLException e) {
                    messages.add("connection error: " + e.getMessage());
                }
            }));
        }
        for (Thread t : threads) t.start();
        for (Thread t : threads) {
            try { t.join(); } catch (InterruptedException ignored) { }
        }
        for (String m : messages) {
            if (!m.contains("not available") && !m.contains("limit is 3")) {
                System.out.println("      concurrent: " + m);
            }
        }
        return wins[0];
    }

    // ================================================================ check-in

    private static void runCheckInTests(LoanService s, Connection c) throws SQLException {
        int returna = C0 + 15, multi = C0 + 16, next = C0 + 17, rollbackCard = C0 + 18;

        int onTime  = TestFixtures.insertLoan(c, isbn(30), returna, T.minusDays(9),  T.plusDays(5),  null);
        int oneDay  = TestFixtures.insertLoan(c, isbn(31), returna, T.minusDays(15), T.minusDays(1), null);
        int tenDays = TestFixtures.insertLoan(c, isbn(32), returna, T.minusDays(24), T.minusDays(10), null);
        int unpaid  = TestFixtures.insertLoan(c, isbn(33), returna, T.minusDays(18), T.minusDays(4), null);
        TestFixtures.insertFine(c, unpaid, "0.50", false);
        int paidFine = TestFixtures.insertLoan(c, isbn(34), returna, T.minusDays(20), T.minusDays(6), null);
        TestFixtures.insertFine(c, paidFine, "0.75", true);
        int m1 = TestFixtures.insertLoan(c, isbn(35), multi, T.minusDays(2), T.plusDays(12), null);
        int m2 = TestFixtures.insertLoan(c, isbn(36), multi, T.minusDays(2), T.plusDays(12), null);
        int m3 = TestFixtures.insertLoan(c, isbn(37), multi, T.minusDays(2), T.plusDays(12), null);
        int returned = TestFixtures.insertLoan(c, isbn(38), returna, T.minusDays(10), T.plusDays(4), T.minusDays(2));
        int stillOut = TestFixtures.insertLoan(c, isbn(39), returna, T.minusDays(1), T.plusDays(13), null);
        int failing = TestFixtures.insertLoan(c, isbn(40), rollbackCard, T.minusDays(17), T.minusDays(3), null);

        // ---- Finding active loans
        check("Find by ISBN", loanIds(s.findActiveLoans(isbn(31), null, null)).equals(List.of(oneDay)));
        check("Find by card ID", loanIds(s.findActiveLoans(null, multi, null)).equals(List.of(m1, m2, m3)));
        List<LoanService.LoanInfo> byName = s.findActiveLoans(null, null, "eturna tes");
        check("Find by partial borrower name (returned loan not listed)",
                byName.size() == 6 && !loanIds(byName).contains(returned));
        check("Find by name is case-insensitive",
                loanIds(s.findActiveLoans(null, null, "RETURNA")).equals(loanIds(byName)));
        check("Find by combination card ID + ISBN",
                loanIds(s.findActiveLoans(isbn(36), multi, null)).equals(List.of(m2)));
        check("Find by combination that does not match gives nothing",
                s.findActiveLoans(null, returna, "multi").isEmpty());
        LoanService.LoanInfo info = s.findActiveLoans(isbn(32), null, null).get(0);
        check("Loan listing shows title, borrower, dates and current fine",
                info.getTitle().equals("Loan Test Book 32") && info.getBorrowerName().equals("Returna Testperson")
                && info.getDueDate().equals(T.minusDays(10))
                && info.getFineIfReturnedToday().equals(new BigDecimal("2.50")));
        System.out.println("      -> " + info);

        // ---- Returning
        LoanService.Result r = s.checkIn(List.of(onTime));
        check("Successful return", r.isSuccess() && dateIn(c, onTime).equals(T.toString()));
        check("On-time return creates no fine", fineRows(c, onTime) == 0);

        r = s.checkIn(List.of(oneDay));
        check("One day overdue: $0.25 unpaid fine",
                r.isSuccess() && "0.25|0".equals(fine(c, oneDay)));
        System.out.println("      -> " + r);

        s.checkIn(List.of(tenDays));
        check("Ten days overdue: $2.50 fine", "2.50|0".equals(fine(c, tenDays)));

        s.checkIn(List.of(unpaid));
        check("Existing unpaid fine updated to $1.00 (no duplicate row)",
                "1.00|0".equals(fine(c, unpaid)) && fineRows(c, unpaid) == 1);

        s.checkIn(List.of(paidFine));
        check("Previously paid fine left unchanged", "0.75|1".equals(fine(c, paidFine))
                && dateIn(c, paidFine).equals(T.toString()));

        r = s.checkIn(List.of(m1, m2, m3));
        check("Returning three books at once", r.isSuccess() && r.getId() == 3
                && s.findActiveLoans(null, multi, null).isEmpty());
        System.out.println("      -> " + r.getMessage().replace("\n", "\n      "));

        expectFailure("Already returned loan rejected", s.checkIn(List.of(returned)), "already returned");
        expectFailure("Loan returned earlier in this test rejected", s.checkIn(List.of(onTime)),
                "already returned");
        expectFailure("Invalid loan ID rejected", s.checkIn(List.of(99999999)), "does not exist");
        expectFailure("Valid + invalid loan together: whole request rejected",
                s.checkIn(List.of(stillOut, 99999999)), "No books were checked in");
        check("...and the valid loan was NOT returned", dateIn(c, stillOut) == null);

        // ---- Book is available again after return
        check("Book shows OUT before return", status(c, isbn(39)).equals("OUT"));
        s.checkIn(List.of(stillOut));
        check("Book shows IN after return", status(c, isbn(39)).equals("IN"));
        check("Returned book can be checked out again", s.checkout(isbn(39), next).isSuccess());

        // ---- Rollback: make the FINES insert fail and check nothing was saved
        boolean triggerCreated = false;
        try (Statement st = c.createStatement()) {
            st.execute("CREATE TRIGGER test_fail_fine_insert BEFORE INSERT ON fines FOR EACH ROW "
                    + "BEGIN IF NEW.Loan_id = " + failing + " THEN "
                    + "SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Simulated failure'; END IF; END");
            triggerCreated = true;
        } catch (SQLException e) {
            System.out.println("      (could not create test trigger: " + e.getMessage() + ")");
        }
        if (triggerCreated) {
            try {
                r = s.checkIn(List.of(failing));
                check("Failed fine insert reports an error", !r.isSuccess()
                        && r.getMessage().contains("no changes were saved"));
                System.out.println("      -> " + r);
                check("...and Date_in was rolled back (book still out, no fine row)",
                        dateIn(c, failing) == null && fineRows(c, failing) == 0);
            } finally {
                try (Statement st = c.createStatement()) {
                    st.execute("DROP TRIGGER IF EXISTS test_fail_fine_insert");
                }
            }
            check("Same return succeeds once the failure is gone",
                    s.checkIn(List.of(failing)).isSuccess() && "0.75|0".equals(fine(c, failing)));
        } else {
            TestFixtures.skip("Transaction rollback when a database operation fails");
        }
    }

    // ================================================================ helpers

    private static List<Integer> loanIds(List<LoanService.LoanInfo> loans) {
        List<Integer> ids = new ArrayList<>();
        for (LoanService.LoanInfo l : loans) ids.add(l.getLoanId());
        Collections.sort(ids);
        return ids;
    }

    private static String dateIn(Connection c, int loanId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT Date_in FROM book_loans WHERE Loan_id = ?")) {
            ps.setInt(1, loanId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                java.sql.Date d = rs.getDate(1);
                return d == null ? null : d.toLocalDate().toString();
            }
        }
    }

    private static int fineRows(Connection c, int loanId) throws SQLException {
        return TestFixtures.countRows(c, "SELECT COUNT(*) FROM fines WHERE Loan_id = ?", loanId);
    }

    /** "amount|paid" for a loan's FINES row, or null if there is none. */
    private static String fine(Connection c, int loanId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT Fine_amt, Paid FROM fines WHERE Loan_id = ?")) {
            ps.setInt(1, loanId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getBigDecimal(1) + "|" + rs.getInt(2) : null;
            }
        }
    }

    /** IN/OUT as reported by the book search feature. */
    private static String status(Connection c, String isbn) throws SQLException {
        List<BookService.BookResult> r = new BookService(c).searchBooks(isbn);
        return r.size() == 1 ? r.get(0).getStatus() : "NOT FOUND";
    }

    private static void expectFailure(String name, LoanService.Result r, String expectedText) {
        check(name, !r.isSuccess() && r.getMessage().toLowerCase().contains(expectedText.toLowerCase()));
        System.out.println("      -> " + r);
    }

    private static void check(String name, boolean ok) {
        TestFixtures.check(name, ok);
    }
}
