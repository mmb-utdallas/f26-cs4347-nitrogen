import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Shared helpers for BookServiceTest and LoanServiceTest: tiny pass/fail
 * counters plus methods that insert and remove FICTIONAL test data.
 *
 * Every test row is easy to recognise and is removed by cleanUp():
 *   books      Isbn starts with "TST"
 *   borrowers  Card_id 990001 - 990999 (SSN 900-98-xxxx, a never-issued range)
 *   authors    names listed in TEST_AUTHORS
 *   loans/fines belonging to those books or borrowers
 */
public class TestFixtures {

    static final String TEST_ISBN_PREFIX = "TST";
    static final int FIRST_TEST_CARD = 990001;
    static final int LAST_TEST_CARD = 990999;
    static final String[] TEST_AUTHORS = {
            "Wilhelmina Zorbington", "Quentin Quuxley", "Ada Testwright"
    };

    static int passed = 0;
    static int failed = 0;
    static int skipped = 0;

    // ------------------------------------------------------------ asserts

    static void check(String testName, boolean ok) {
        if (ok) {
            passed++;
            System.out.println("PASS  " + testName);
        } else {
            failed++;
            System.out.println("FAIL  " + testName);
        }
    }

    static void skip(String testName) {
        skipped++;
        System.out.println("SKIP  " + testName + " (requires a running MySQL database)");
    }

    /** Prints totals and exits with code 1 if anything failed. */
    static void finish() {
        System.out.println();
        System.out.println("Passed: " + passed + "  Failed: " + failed + "  Skipped (need MySQL): " + skipped);
        if (failed > 0) {
            System.exit(1);
        }
    }

    // ------------------------------------------------------------ inserts

    static void insertBook(Connection c, String isbn, String title, String... authorNames) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO book (isbn, Title) VALUES (?, ?)")) {
            ps.setString(1, isbn);
            ps.setString(2, title);
            ps.executeUpdate();
        }
        for (String name : authorNames) {
            int authorId = findOrCreateAuthor(c, name);
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO book_authors (Author_id, Isbn) VALUES (?, ?)")) {
                ps.setInt(1, authorId);
                ps.setString(2, isbn);
                ps.executeUpdate();
            }
        }
    }

    private static int findOrCreateAuthor(Connection c, String name) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT Author_id FROM authors WHERE Name = ?")) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getInt(1);
            }
        }
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO authors (Name) VALUES (?)", Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, name);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                keys.next();
                return keys.getInt(1);
            }
        }
    }

    static void insertBorrower(Connection c, int cardId, String name) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO borrower (Card_id, Ssn, Bname, Address) VALUES (?, ?, ?, ?)")) {
            ps.setInt(1, cardId);
            ps.setString(2, String.format("900-98-%04d", cardId - FIRST_TEST_CARD + 1));
            ps.setString(3, name);
            ps.setString(4, "1 Fixture Lane, Dallas, TX");
            ps.executeUpdate();
        }
    }

    /** Inserts a loan directly (dateIn may be null) and returns its Loan_id. */
    static int insertLoan(Connection c, String isbn, int cardId, LocalDate dateOut, LocalDate dueDate,
                          LocalDate dateIn) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO book_loans (Isbn, Card_id, Date_out, Due_date, Date_in) VALUES (?, ?, ?, ?, ?)",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, isbn);
            ps.setInt(2, cardId);
            ps.setDate(3, Date.valueOf(dateOut));
            ps.setDate(4, Date.valueOf(dueDate));
            ps.setDate(5, dateIn == null ? null : Date.valueOf(dateIn));
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                keys.next();
                return keys.getInt(1);
            }
        }
    }

    static void insertFine(Connection c, int loanId, String amount, boolean paid) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO fines (Loan_id, Fine_amt, Paid) VALUES (?, ?, ?)")) {
            ps.setInt(1, loanId);
            ps.setBigDecimal(2, new BigDecimal(amount));
            ps.setBoolean(3, paid);
            ps.executeUpdate();
        }
    }

    // ------------------------------------------------------------ queries

    static int countRows(Connection c, String sql, Object... params) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) ps.setObject(i + 1, params[i]);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    /** Every row of every library table as text, to prove real data was not changed. */
    static List<String> snapshotAllTables(Connection c) throws SQLException {
        String[] queries = {
                "SELECT * FROM book ORDER BY isbn",
                "SELECT * FROM authors ORDER BY Author_id",
                "SELECT * FROM book_authors ORDER BY Author_id, Isbn",
                "SELECT * FROM borrower ORDER BY Card_id",
                "SELECT * FROM book_loans ORDER BY Loan_id",
                "SELECT * FROM fines ORDER BY Loan_id"
        };
        List<String> rows = new ArrayList<>();
        try (Statement st = c.createStatement()) {
            for (String q : queries) {
                try (ResultSet rs = st.executeQuery(q)) {
                    int cols = rs.getMetaData().getColumnCount();
                    while (rs.next()) {
                        StringBuilder sb = new StringBuilder(q);   // which table the row came from
                        for (int i = 1; i <= cols; i++) sb.append('|').append(rs.getString(i));
                        rows.add(sb.toString());
                    }
                }
            }
        }
        return rows;
    }

    // ------------------------------------------------------------ cleanup

    /** Deletes only fixture rows, children first so foreign keys are satisfied. */
    static void cleanUp(Connection c) throws SQLException {
        try (Statement st = c.createStatement()) {
            st.executeUpdate("DELETE f FROM fines f JOIN book_loans bl ON bl.Loan_id = f.Loan_id "
                    + "WHERE bl.Isbn LIKE 'TST%' "
                    + "OR bl.Card_id BETWEEN " + FIRST_TEST_CARD + " AND " + LAST_TEST_CARD);
            st.executeUpdate("DELETE FROM book_loans WHERE Isbn LIKE 'TST%' "
                    + "OR Card_id BETWEEN " + FIRST_TEST_CARD + " AND " + LAST_TEST_CARD);
            st.executeUpdate("DELETE FROM book_authors WHERE Isbn LIKE 'TST%'");
            st.executeUpdate("DELETE FROM book WHERE isbn LIKE 'TST%'");
            st.executeUpdate("DELETE FROM borrower WHERE Card_id BETWEEN "
                    + FIRST_TEST_CARD + " AND " + LAST_TEST_CARD);
        }
        try (PreparedStatement ps = c.prepareStatement(
                "DELETE FROM authors WHERE Name = ? "
              + "AND NOT EXISTS (SELECT 1 FROM book_authors ba WHERE ba.Author_id = authors.Author_id)")) {
            for (String name : TEST_AUTHORS) {
                ps.setString(1, name);
                ps.executeUpdate();
            }
        }
    }
}
