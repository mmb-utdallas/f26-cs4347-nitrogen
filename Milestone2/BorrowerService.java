import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Borrower Management (Milestone 2).
 *
 * Lets a librarian register a new borrower in the BORROWER table:
 *   BORROWER(Card_id INT PK, Ssn VARCHAR(11) UNIQUE, Bname VARCHAR(100),
 *            Address VARCHAR(255), Phone VARCHAR(20) NULL)
 *
 * - Name, SSN and address are required (not null, not blank).
 * - SSN must look like ###-##-#### (9 digits without dashes are also accepted).
 * - A borrower with the same SSN is rejected.
 * - Card_id is generated automatically as MAX(Card_id) + 1, matching the
 *   existing integer card IDs (e.g. 1001, 1002, ...).
 *
 * Usage:
 *   BorrowerService service = new BorrowerService(connection);
 *   BorrowerService.Result r = service.registerBorrower(name, ssn, address, phone);
 *   if (r.isSuccess()) { ... r.getCardId() ... } else { ... r.getMessage() ... }
 */
public class BorrowerService {

    // Column size limits taken from the BORROWER table definition.
    private static final int MAX_NAME_LENGTH = 100;
    private static final int MAX_ADDRESS_LENGTH = 255;
    private static final int MAX_PHONE_LENGTH = 20;

    // Card ID used for the very first borrower if the table is empty.
    private static final int FIRST_CARD_ID = 1;

    // MySQL error code for a duplicate key (UNIQUE or PRIMARY KEY violation).
    private static final int MYSQL_DUPLICATE_KEY = 1062;

    // How many times to retry if another registration grabbed the same card ID.
    private static final int MAX_ATTEMPTS = 3;

    // Name of the MySQL named lock that serializes card ID generation,
    // and how long (seconds) to wait for it before giving up.
    private static final String CARD_ID_LOCK = "library_borrower_card_id";
    private static final int LOCK_TIMEOUT_SECONDS = 10;

    private final Connection connection;

    /** Uses an already-open JDBC connection to the library database. */
    public BorrowerService(Connection connection) {
        this.connection = connection;
    }

    /**
     * Opens a connection using environment variables so that no password is
     * stored in source code:
     *   LIBRARY_DB_URL      (default jdbc:mysql://127.0.0.1:3306/Library_Milestone2)
     *   LIBRARY_DB_USER     (default root)
     *   LIBRARY_DB_PASSWORD (default empty)
     */
    public static Connection connectFromEnvironment() throws SQLException {
        String url = getEnv("LIBRARY_DB_URL", "jdbc:mysql://127.0.0.1:3306/Library_Milestone2");
        String user = getEnv("LIBRARY_DB_USER", "root");
        String password = getEnv("LIBRARY_DB_PASSWORD", "");
        return DriverManager.getConnection(url, user, password);
    }

    private static String getEnv(String name, String defaultValue) {
        String value = System.getenv(name);
        return (value == null || value.isEmpty()) ? defaultValue : value;
    }

    /**
     * Registers a new borrower.
     *
     * @param name    borrower's full name (required)
     * @param ssn     SSN as ###-##-#### or 9 digits (required)
     * @param address full address (required)
     * @param phone   phone number (optional, may be null or blank)
     * @return a Result that says whether it worked, the new card ID, and a message
     */
    public Result registerBorrower(String name, String ssn, String address, String phone) {
        // ---- 1. Validate input before touching the database ----
        String cleanName = trimToNull(name);
        String cleanSsn = trimToNull(ssn);
        String cleanAddress = trimToNull(address);
        String cleanPhone = trimToNull(phone);

        if (cleanName == null) {
            return Result.failure("Borrower name is required and cannot be blank.");
        }
        if (cleanSsn == null) {
            return Result.failure("SSN is required and cannot be blank.");
        }
        if (cleanAddress == null) {
            return Result.failure("Address is required and cannot be blank.");
        }

        String formattedSsn = formatSsn(cleanSsn);
        if (formattedSsn == null) {
            return Result.failure("SSN '" + cleanSsn + "' is invalid. Use the format ###-##-####.");
        }
        if (cleanName.length() > MAX_NAME_LENGTH) {
            return Result.failure("Borrower name cannot be longer than " + MAX_NAME_LENGTH + " characters.");
        }
        if (cleanAddress.length() > MAX_ADDRESS_LENGTH) {
            return Result.failure("Address cannot be longer than " + MAX_ADDRESS_LENGTH + " characters.");
        }
        if (cleanPhone != null && cleanPhone.length() > MAX_PHONE_LENGTH) {
            return Result.failure("Phone number cannot be longer than " + MAX_PHONE_LENGTH + " characters.");
        }

        if (connection == null) {
            return Result.failure("Database error: no database connection is available.");
        }

        // ---- 2. Insert inside a transaction, retrying on a card ID collision ----
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            boolean locked = false;
            try {
                // Only one registration at a time may compute "MAX(Card_id) + 1".
                // GET_LOCK is a MySQL named lock shared by every connection to
                // the server, so it also works across different programs/computers.
                locked = acquireCardIdLock();
                if (!locked) {
                    return Result.failure("The system is busy registering another borrower. Please try again.");
                }
                return insertBorrower(cleanName, formattedSsn, cleanAddress, cleanPhone);
            } catch (SQLException e) {
                if (e.getErrorCode() == MYSQL_DUPLICATE_KEY) {
                    // The UNIQUE constraint on Ssn is the final safety net if two
                    // librarians register the same person at the same moment.
                    if (isSsnConstraint(e)) {
                        return duplicateSsnFailure(formattedSsn);
                    }
                    // Otherwise another registration took the same Card_id; try again.
                    continue;
                }
                return Result.failure("Database error while registering borrower: " + e.getMessage());
            } finally {
                if (locked) {
                    releaseCardIdLock();
                }
            }
        }
        return Result.failure("Could not generate a unique card ID. Please try again.");
    }

    /** Waits up to LOCK_TIMEOUT_SECONDS for the named lock. Returns true if we got it. */
    private boolean acquireCardIdLock() throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("SELECT GET_LOCK(?, ?)")) {
            ps.setString(1, CARD_ID_LOCK);
            ps.setInt(2, LOCK_TIMEOUT_SECONDS);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getInt(1) == 1;   // 1 = acquired, 0 = timed out
            }
        }
    }

    private void releaseCardIdLock() {
        try (PreparedStatement ps = connection.prepareStatement("SELECT RELEASE_LOCK(?)")) {
            ps.setString(1, CARD_ID_LOCK);
            ps.executeQuery().close();
        } catch (SQLException ignored) {
            // If the connection is broken, MySQL releases the lock automatically.
        }
    }

    /**
     * Does the actual database work in one transaction:
     * check SSN -> compute next Card_id -> INSERT -> commit.
     * Any SQLException causes a rollback and is passed to the caller.
     */
    private Result insertBorrower(String name, String ssn, String address, String phone)
            throws SQLException {
        boolean oldAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            // Reject a duplicate SSN with a friendly message.
            // Dashes are removed on both sides, so "123-45-6789" also matches an
            // existing record that was stored as "123456789" (e.g. from an import).
            String checkSsnSql = "SELECT Card_id FROM BORROWER WHERE REPLACE(Ssn, '-', '') = ?";
            try (PreparedStatement ps = connection.prepareStatement(checkSsnSql)) {
                ps.setString(1, ssn.replace("-", ""));
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        connection.rollback();
                        return duplicateSsnFailure(ssn);
                    }
                }
            }

            // Find the next card ID: highest existing Card_id + 1.
            // We hold the CARD_ID_LOCK (see registerBorrower), so no other
            // registration can read the same MAX value before we commit.
            int newCardId = FIRST_CARD_ID;
            String maxIdSql = "SELECT MAX(Card_id) FROM BORROWER";
            try (PreparedStatement ps = connection.prepareStatement(maxIdSql);
                 ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    int currentMax = rs.getInt(1);
                    if (!rs.wasNull()) {   // NULL means the table is empty
                        newCardId = currentMax + 1;
                    }
                }
            }

            // Insert the new borrower using a parameterized statement.
            String insertSql = "INSERT INTO BORROWER (Card_id, Ssn, Bname, Address, Phone) "
                             + "VALUES (?, ?, ?, ?, ?)";
            try (PreparedStatement ps = connection.prepareStatement(insertSql)) {
                ps.setInt(1, newCardId);
                ps.setString(2, ssn);
                ps.setString(3, name);
                ps.setString(4, address);
                if (phone == null) {
                    ps.setNull(5, java.sql.Types.VARCHAR);
                } else {
                    ps.setString(5, phone);
                }
                ps.executeUpdate();
            }

            connection.commit();
            return Result.success(newCardId,
                    "Borrower '" + name + "' registered successfully with card ID " + newCardId + ".");
        } catch (SQLException e) {
            safeRollback();
            throw e;
        } finally {
            try {
                connection.setAutoCommit(oldAutoCommit);
            } catch (SQLException ignored) {
                // Connection is probably broken; the caller already gets an error.
            }
        }
    }

    /**
     * Command-line way to register one borrower (no GUI needed):
     *   java -cp ".;mysql-connector-j-26.10.0.jar" BorrowerService "Name" "SSN" "Address" ["Phone"]
     */
    public static void main(String[] args) {
        if (args.length < 3 || args.length > 4) {
            System.out.println("Usage: java BorrowerService \"Name\" \"SSN\" \"Address\" [\"Phone\"]");
            return;
        }
        String phone = (args.length == 4) ? args[3] : null;
        try (Connection conn = connectFromEnvironment()) {
            Result result = new BorrowerService(conn).registerBorrower(args[0], args[1], args[2], phone);
            System.out.println(result);
        } catch (SQLException e) {
            System.out.println("ERROR: Could not connect to the database: " + e.getMessage());
        }
    }

    // ---------------------------------------------------------------- helpers

    private Result duplicateSsnFailure(String ssn) {
        return Result.failure("A borrower with SSN " + ssn + " already exists. "
                + "Each person may only have one library card.");
    }

    /** True if a duplicate-key error came from the Ssn UNIQUE key (not the Card_id primary key). */
    private static boolean isSsnConstraint(SQLException e) {
        String msg = e.getMessage();
        return msg != null && msg.toLowerCase().contains("ssn");
    }

    private void safeRollback() {
        try {
            connection.rollback();
        } catch (SQLException ignored) {
            // Nothing more we can do if rollback itself fails.
        }
    }

    /** Returns the trimmed string, or null if it is null or only whitespace. */
    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * Converts an SSN to ###-##-####. Accepts "123-45-6789" or "123456789".
     * Returns null if the value is not a valid 9-digit SSN.
     */
    static String formatSsn(String ssn) {
        if (ssn.matches("\\d{3}-\\d{2}-\\d{4}")) {
            return ssn;
        }
        if (ssn.matches("\\d{9}")) {
            return ssn.substring(0, 3) + "-" + ssn.substring(3, 5) + "-" + ssn.substring(5);
        }
        return null;
    }

    // ----------------------------------------------------------------- result

    /** Outcome of a registration attempt. */
    public static class Result {
        private final boolean success;
        private final int cardId;
        private final String message;

        private Result(boolean success, int cardId, String message) {
            this.success = success;
            this.cardId = cardId;
            this.message = message;
        }

        static Result success(int cardId, String message) {
            return new Result(true, cardId, message);
        }

        static Result failure(String message) {
            return new Result(false, -1, message);
        }

        public boolean isSuccess() { return success; }

        /** The new card ID, or -1 if registration failed. */
        public int getCardId() { return cardId; }

        public String getMessage() { return message; }

        @Override
        public String toString() {
            return (success ? "SUCCESS: " : "ERROR: ") + message;
        }
    }
}
