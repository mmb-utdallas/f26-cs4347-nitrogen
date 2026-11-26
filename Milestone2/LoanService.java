import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/**
 * Book Loans (Milestone 2): checking books out and checking them back in.
 *
 * CHECKOUT  checkout(isbn, cardId)
 *   Fails with a clear message if the book or borrower does not exist, the book
 *   is already out, the borrower already has 3 active loans, or the borrower
 *   owes money. Otherwise inserts BOOK_LOANS(Date_out = today,
 *   Due_date = today + 14 days, Date_in = NULL).
 *
 * CHECK-IN  findActiveLoans(isbn, cardId, nameText)  then  checkIn(loanIds)
 *   Find unreturned loans by any combination of ISBN, card ID and part of the
 *   borrower's name, then return 1-3 of them. Date_in is set to today and a
 *   late return gets a FINES row ($0.25/day, see FineCalculator).
 *
 * Concurrency: each checkout locks the borrower row, then the book row
 * (SELECT ... FOR UPDATE), always in that order. Two checkouts of the same book,
 * or two checkouts by the same borrower, therefore run one after the other,
 * so the "book is available" and "max 3 loans" checks cannot both pass at once.
 *
 * No console printing happens here, so the GUI can reuse these methods.
 */
public class LoanService {

    public static final int MAX_ACTIVE_LOANS = 3;
    public static final int LOAN_PERIOD_DAYS = 14;
    public static final int MAX_CHECKIN_AT_ONCE = 3;

    private final Connection connection;
    private final Clock clock;   // where "today" comes from (tests can fix the date)

    public LoanService(Connection connection) {
        this(connection, Clock.systemDefaultZone());
    }

    /** Lets tests pretend today is a different date. */
    public LoanService(Connection connection, Clock clock) {
        this.connection = connection;
        this.clock = clock;
    }

    private LocalDate today() {
        return LocalDate.now(clock);
    }

    // =====================================================================
    // CHECKOUT
    // =====================================================================

    /**
     * Checks out a book to a borrower.
     *
     * @return Result whose id is the new Loan_id on success
     */
    public Result checkout(String isbn, int cardId) {
        String cleanIsbn = (isbn == null) ? "" : isbn.trim().replace("-", "");
        if (cleanIsbn.isEmpty()) {
            return Result.failure("ISBN is required.");
        }
        if (cardId <= 0) {
            return Result.failure("Card ID must be a positive number.");
        }
        if (connection == null) {
            return Result.failure("Database error: no database connection is available.");
        }

        try {
            return runInTransaction(() -> doCheckout(cleanIsbn, cardId));
        } catch (SQLException e) {
            return Result.failure("Database error during checkout: " + e.getMessage());
        }
    }

    private Result doCheckout(String isbn, int cardId) throws SQLException {
        // 1. Borrower must exist. FOR UPDATE locks this borrower's row so their
        //    other checkouts wait until we finish (protects the 3-loan limit).
        String borrowerName;
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT Bname FROM borrower WHERE Card_id = ? FOR UPDATE")) {
            ps.setInt(1, cardId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Result.failure("No borrower found with card ID " + cardId + ".");
                }
                borrowerName = rs.getString("Bname");
            }
        }

        // 2. Book must exist. FOR UPDATE locks the book row so another checkout
        //    of the same book waits here (protects "one active loan per book").
        String title;
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT Title FROM book WHERE isbn = ? FOR UPDATE")) {
            ps.setString(1, isbn);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Result.failure("No book found with ISBN " + isbn + ".");
                }
                title = rs.getString("Title");
            }
        }

        // 3. Book must not already be out (to anyone, including this borrower).
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT Card_id, Due_date FROM book_loans WHERE Isbn = ? AND Date_in IS NULL")) {
            ps.setString(1, isbn);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String who = (rs.getInt("Card_id") == cardId) ? "this borrower" : "another borrower";
                    return Result.failure("'" + title + "' is not available: it is already checked out by "
                            + who + " (due " + rs.getDate("Due_date").toLocalDate() + ").");
                }
            }
        }

        // 4. At most 3 active (unreturned) loans per borrower.
        int activeLoans;
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT COUNT(*) FROM book_loans WHERE Card_id = ? AND Date_in IS NULL")) {
            ps.setInt(1, cardId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                activeLoans = rs.getInt(1);
            }
        }
        if (activeLoans >= MAX_ACTIVE_LOANS) {
            return Result.failure(borrowerName + " (card " + cardId + ") already has " + activeLoans
                    + " books checked out. The limit is " + MAX_ACTIVE_LOANS + ".");
        }

        // 5. No unpaid fines (including fines still growing on overdue books).
        BigDecimal owed = getUnpaidFineTotal(cardId);
        if (owed.signum() > 0) {
            return Result.failure(borrowerName + " (card " + cardId + ") has unpaid fines of $" + owed
                    + ". Fines must be paid (and overdue books returned) before checking out.");
        }

        // 6. Create the loan. Loan_id is AUTO_INCREMENT, so MySQL assigns it.
        LocalDate dateOut = today();
        LocalDate dueDate = dateOut.plusDays(LOAN_PERIOD_DAYS);
        int loanId;
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO book_loans (Isbn, Card_id, Date_out, Due_date, Date_in) VALUES (?, ?, ?, ?, NULL)",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, isbn);
            ps.setInt(2, cardId);
            ps.setDate(3, Date.valueOf(dateOut));
            ps.setDate(4, Date.valueOf(dueDate));
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                keys.next();
                loanId = keys.getInt(1);
            }
        }
        return Result.success(loanId, "Checked out '" + title + "' to " + borrowerName
                + " (card " + cardId + "). Loan ID " + loanId + ", due " + dueDate + ".");
    }

    /**
     * Total money a borrower owes right now. Does not rely only on the FINES
     * table, which may be out of date until the daily refresh runs:
     *   - book still out and past due  -> fine accrued up to today (computed live)
     *   - book returned with an unpaid FINES row -> that Fine_amt
     *   - book returned late but no FINES row yet -> fine computed from Date_in
     *   - fine already paid -> nothing
     * Public so the Fines feature can reuse it.
     */
    public BigDecimal getUnpaidFineTotal(int cardId) throws SQLException {
        String sql = "SELECT bl.Due_date, bl.Date_in, f.Fine_amt, f.Paid "
                   + "FROM book_loans bl LEFT JOIN fines f ON f.Loan_id = bl.Loan_id "
                   + "WHERE bl.Card_id = ? "
                   + "  AND (f.Paid = 0 OR f.Paid IS NULL)";
        BigDecimal total = FineCalculator.ZERO;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setInt(1, cardId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    LocalDate due = rs.getDate("Due_date").toLocalDate();
                    Date dateIn = rs.getDate("Date_in");
                    BigDecimal recorded = rs.getBigDecimal("Fine_amt");   // null if no FINES row
                    BigDecimal owedForLoan;
                    if (dateIn == null) {
                        owedForLoan = FineCalculator.calculateFine(due, today());
                    } else if (recorded != null) {
                        owedForLoan = recorded;
                    } else {
                        owedForLoan = FineCalculator.calculateFine(due, dateIn.toLocalDate());
                    }
                    total = total.add(owedForLoan);
                }
            }
        }
        return total;
    }

    // =====================================================================
    // CHECK-IN
    // =====================================================================

    /**
     * Finds books that are currently checked out. Any combination of the three
     * filters may be given; blank/null filters are ignored, but at least one is
     * required. All given filters must match (AND).
     *
     * @param isbn     full or partial ISBN (case-insensitive), or null
     * @param cardId   exact borrower card ID, or null
     * @param nameText any part of the borrower's name (case-insensitive), or null
     */
    public List<LoanInfo> findActiveLoans(String isbn, Integer cardId, String nameText) throws SQLException {
        String cleanIsbn = (isbn == null) ? "" : isbn.trim().replace("-", "");
        String cleanName = (nameText == null) ? "" : nameText.trim();
        if (cleanIsbn.isEmpty() && cardId == null && cleanName.isEmpty()) {
            throw new IllegalArgumentException("Enter an ISBN, a card ID, and/or part of a borrower name.");
        }

        // Only the filters that were given are added, each with a ? placeholder.
        StringBuilder sql = new StringBuilder(
                  "SELECT bl.Loan_id, bl.Isbn, b.Title, bl.Card_id, br.Bname, bl.Date_out, bl.Due_date "
                + "FROM book_loans bl "
                + "JOIN book b ON b.isbn = bl.Isbn "
                + "JOIN borrower br ON br.Card_id = bl.Card_id "
                + "WHERE bl.Date_in IS NULL");
        List<Object> params = new ArrayList<>();
        if (!cleanIsbn.isEmpty()) {
            sql.append(" AND LOWER(bl.Isbn) LIKE ?");
            params.add("%" + escapeLike(cleanIsbn.toLowerCase()) + "%");
        }
        if (cardId != null) {
            sql.append(" AND bl.Card_id = ?");
            params.add(cardId);
        }
        if (!cleanName.isEmpty()) {
            sql.append(" AND LOWER(br.Bname) LIKE ?");
            params.add("%" + escapeLike(cleanName.toLowerCase()) + "%");
        }
        sql.append(" ORDER BY bl.Due_date, bl.Loan_id");

        List<LoanInfo> loans = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) {
                ps.setObject(i + 1, params.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    LocalDate due = rs.getDate("Due_date").toLocalDate();
                    loans.add(new LoanInfo(
                            rs.getInt("Loan_id"), rs.getString("Isbn"), rs.getString("Title"),
                            rs.getInt("Card_id"), rs.getString("Bname"),
                            rs.getDate("Date_out").toLocalDate(), due,
                            FineCalculator.calculateFine(due, today())));
                }
            }
        }
        return loans;
    }

    /**
     * Returns 1-3 loans. All-or-nothing: if any loan ID is invalid or already
     * returned, nothing is changed and the message explains why.
     *
     * @return Result with a message listing each returned book and any fine
     */
    public Result checkIn(List<Integer> loanIds) {
        if (loanIds == null || loanIds.isEmpty()) {
            return Result.failure("Select at least one loan to check in.");
        }
        if (loanIds.size() > MAX_CHECKIN_AT_ONCE) {
            return Result.failure("You can check in at most " + MAX_CHECKIN_AT_ONCE + " books at a time.");
        }
        for (Integer id : loanIds) {
            if (id == null) {
                return Result.failure("Loan IDs cannot be empty.");
            }
        }
        if (new HashSet<>(loanIds).size() != loanIds.size()) {
            return Result.failure("The same loan ID was selected more than once.");
        }
        if (connection == null) {
            return Result.failure("Database error: no database connection is available.");
        }

        try {
            return runInTransaction(() -> doCheckIn(loanIds));
        } catch (SQLException e) {
            return Result.failure("Database error during check-in (no changes were saved): " + e.getMessage());
        }
    }

    private Result doCheckIn(List<Integer> loanIds) throws SQLException {
        LocalDate returnDate = today();

        // 1. Validate every loan first, locking each row so it cannot be
        //    returned twice at the same time.
        List<LocalDate> dueDates = new ArrayList<>();
        List<String> isbns = new ArrayList<>();
        for (int loanId : loanIds) {
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT Isbn, Due_date, Date_in FROM book_loans WHERE Loan_id = ? FOR UPDATE")) {
                ps.setInt(1, loanId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return Result.failure("Loan ID " + loanId + " does not exist. No books were checked in.");
                    }
                    if (rs.getDate("Date_in") != null) {
                        return Result.failure("Loan ID " + loanId + " was already returned on "
                                + rs.getDate("Date_in").toLocalDate() + ". No books were checked in.");
                    }
                    isbns.add(rs.getString("Isbn"));
                    dueDates.add(rs.getDate("Due_date").toLocalDate());
                }
            }
        }

        // 2. Record the return and the fine for each loan.
        StringBuilder message = new StringBuilder("Checked in " + loanIds.size() + " book(s):");
        for (int i = 0; i < loanIds.size(); i++) {
            int loanId = loanIds.get(i);
            try (PreparedStatement ps = connection.prepareStatement(
                    "UPDATE book_loans SET Date_in = ? WHERE Loan_id = ?")) {
                ps.setDate(1, Date.valueOf(returnDate));
                ps.setInt(2, loanId);
                ps.executeUpdate();
            }

            BigDecimal fine = FineCalculator.calculateFine(dueDates.get(i), returnDate);
            saveFine(loanId, fine);

            message.append("\n  Loan ").append(loanId).append(" (ISBN ").append(isbns.get(i)).append(")");
            message.append(fine.signum() > 0
                    ? ": returned " + FineCalculator.daysLate(dueDates.get(i), returnDate)
                      + " day(s) late, fine $" + fine
                    : ": on time, no fine");
        }
        return Result.success(loanIds.size(), message.toString());
    }

    /**
     * Stores the final fine for a returned loan, following the FINES rules:
     *   - no row and fine > 0      -> INSERT (Paid = 0)
     *   - unpaid row exists        -> UPDATE Fine_amt (never a second row)
     *   - paid row exists          -> leave it alone
     *   - no row and fine is $0.00 -> nothing to record
     * Public so the Fines feature's refresh can reuse the same rules.
     */
    public void saveFine(int loanId, BigDecimal fine) throws SQLException {
        Boolean paid = null;   // null = no FINES row yet
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT Paid FROM fines WHERE Loan_id = ? FOR UPDATE")) {
            ps.setInt(1, loanId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    paid = rs.getBoolean("Paid");
                }
            }
        }

        if (paid == null) {
            if (fine.signum() > 0) {
                try (PreparedStatement ps = connection.prepareStatement(
                        "INSERT INTO fines (Loan_id, Fine_amt, Paid) VALUES (?, ?, 0)")) {
                    ps.setInt(1, loanId);
                    ps.setBigDecimal(2, fine);
                    ps.executeUpdate();
                }
            }
        } else if (!paid) {
            try (PreparedStatement ps = connection.prepareStatement(
                    "UPDATE fines SET Fine_amt = ? WHERE Loan_id = ? AND Paid = 0")) {
                ps.setBigDecimal(1, fine);
                ps.setInt(2, loanId);
                ps.executeUpdate();
            }
        }
        // paid == true: a paid fine is never changed.
    }

    // =====================================================================
    // Helpers
    // =====================================================================

    /** A unit of database work that returns a Result. */
    private interface TransactionWork {
        Result run() throws SQLException;
    }

    /**
     * Runs work in one transaction: commit if it succeeds, roll back if it
     * fails or returns a failure Result. READ COMMITTED makes every query see
     * the latest committed data after waiting on a lock.
     */
    private Result runInTransaction(TransactionWork work) throws SQLException {
        boolean oldAutoCommit = connection.getAutoCommit();
        int oldIsolation = connection.getTransactionIsolation();
        connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
        connection.setAutoCommit(false);
        try {
            Result result = work.run();
            if (result.isSuccess()) {
                connection.commit();
            } else {
                connection.rollback();
            }
            return result;
        } catch (SQLException | RuntimeException e) {
            try { connection.rollback(); } catch (SQLException ignored) { }
            throw e;
        } finally {
            try {
                connection.setAutoCommit(oldAutoCommit);
                connection.setTransactionIsolation(oldIsolation);
            } catch (SQLException ignored) {
                // Connection is probably broken; the caller already gets an error.
            }
        }
    }

    /** Makes %, _ and \ match literally inside LIKE. */
    private static String escapeLike(String text) {
        return text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    // =====================================================================
    // Result types
    // =====================================================================

    /** Outcome of a checkout or check-in. */
    public static class Result {
        private final boolean success;
        private final int id;
        private final String message;

        private Result(boolean success, int id, String message) {
            this.success = success;
            this.id = id;
            this.message = message;
        }

        static Result success(int id, String message) { return new Result(true, id, message); }
        static Result failure(String message) { return new Result(false, -1, message); }

        public boolean isSuccess() { return success; }
        /** Checkout: the new Loan_id. Check-in: number of books returned. -1 on failure. */
        public int getId() { return id; }
        public String getMessage() { return message; }

        @Override
        public String toString() { return (success ? "SUCCESS: " : "ERROR: ") + message; }
    }

    /** One active loan, as shown to the librarian during check-in. */
    public static class LoanInfo {
        private final int loanId;
        private final String isbn;
        private final String title;
        private final int cardId;
        private final String borrowerName;
        private final LocalDate dateOut;
        private final LocalDate dueDate;
        private final BigDecimal fineIfReturnedToday;

        public LoanInfo(int loanId, String isbn, String title, int cardId, String borrowerName,
                        LocalDate dateOut, LocalDate dueDate, BigDecimal fineIfReturnedToday) {
            this.loanId = loanId;
            this.isbn = isbn;
            this.title = title;
            this.cardId = cardId;
            this.borrowerName = borrowerName;
            this.dateOut = dateOut;
            this.dueDate = dueDate;
            this.fineIfReturnedToday = fineIfReturnedToday;
        }

        public int getLoanId() { return loanId; }
        public String getIsbn() { return isbn; }
        public String getTitle() { return title; }
        public int getCardId() { return cardId; }
        public String getBorrowerName() { return borrowerName; }
        public LocalDate getDateOut() { return dateOut; }
        public LocalDate getDueDate() { return dueDate; }
        /** Fine that would be charged if the book were returned today ($0.00 if not late). */
        public BigDecimal getFineIfReturnedToday() { return fineIfReturnedToday; }

        @Override
        public String toString() {
            return "Loan " + loanId + " | " + isbn + " | " + title + " | card " + cardId + " " + borrowerName
                    + " | out " + dateOut + " | due " + dueDate
                    + (fineIfReturnedToday.signum() > 0 ? " | OVERDUE, fine $" + fineIfReturnedToday : "");
        }
    }

    // =====================================================================
    // Command-line use (no GUI needed)
    // =====================================================================

    /**
     *   java -cp ".;mysql-connector-j-26.10.0.jar" LoanService checkout <isbn> <cardId>
     *   java -cp ".;mysql-connector-j-26.10.0.jar" LoanService find <isbn|-> <cardId|-> <name|->
     *   java -cp ".;mysql-connector-j-26.10.0.jar" LoanService checkin <loanId> [loanId] [loanId]
     */
    public static void main(String[] args) {
        if (args.length == 0) {
            System.out.println("Usage: LoanService checkout <isbn> <cardId>");
            System.out.println("       LoanService find <isbn|-> <cardId|-> <name|->   (use - to skip)");
            System.out.println("       LoanService checkin <loanId> [loanId] [loanId]");
            return;
        }
        try (Connection conn = BorrowerService.connectFromEnvironment()) {
            LoanService service = new LoanService(conn);
            switch (args[0]) {
                case "checkout":
                    System.out.println(service.checkout(args[1], Integer.parseInt(args[2])));
                    break;
                case "find":
                    String isbn = args[1].equals("-") ? null : args[1];
                    Integer card = args[2].equals("-") ? null : Integer.valueOf(args[2]);
                    String name = args[3].equals("-") ? null : args[3];
                    List<LoanInfo> loans = service.findActiveLoans(isbn, card, name);
                    if (loans.isEmpty()) System.out.println("No active loans found.");
                    for (LoanInfo loan : loans) System.out.println(loan);
                    break;
                case "checkin":
                    List<Integer> ids = new ArrayList<>();
                    for (int i = 1; i < args.length; i++) ids.add(Integer.valueOf(args[i]));
                    System.out.println(service.checkIn(ids));
                    break;
                default:
                    System.out.println("Unknown command: " + args[0]);
            }
        } catch (ArrayIndexOutOfBoundsException | NumberFormatException e) {
            System.out.println("ERROR: Missing or invalid arguments. Run with no arguments for usage.");
        } catch (IllegalArgumentException e) {
            System.out.println("ERROR: " + e.getMessage());
        } catch (SQLException e) {
            System.out.println("ERROR: Database problem: " + e.getMessage());
        }
    }
}
