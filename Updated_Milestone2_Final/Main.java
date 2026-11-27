
import java.io.Console;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Scanner;
import java.util.Set;

public class Main {

    // limits from the spec: max 3 books out per borrower, return up to 3 at a time
    private static final int MAX_ACTIVE_LOANS = 3;
    private static final int MAX_CHECKIN_AT_ONCE = 3;
    private static final int MAX_SEARCH_WORDS = 10;

    public static void main(String[] args) {

        Scanner input = new Scanner(System.in);

        // login info comes from environment variables so nobody's password ends up on github.
        // if LIBRARY_DB_PASSWORD isn't set, it just asks for the password when it starts
        String url = envOrDefault("LIBRARY_DB_URL", "jdbc:mysql://127.0.0.1:3306/library_milestone2");
        String username = envOrDefault("LIBRARY_DB_USER", "root");
        String password = System.getenv("LIBRARY_DB_PASSWORD");

        if (password == null) {
            password = readPassword(input, "MySQL password for " + username + ": ");
        }

        try (Connection connection = DriverManager.getConnection(url, username, password)) {

            System.out.println("Successfully connected to MySQL!");

            int choice = 0;

            while (choice != 7) {

                System.out.println("\n===== LIBRARY MANAGEMENT SYSTEM =====");
                System.out.println("1. Search Books");
                System.out.println("2. Check Out Book");
                System.out.println("3. Return Book");
                System.out.println("4. Register Borrower");
                System.out.println("5. View Fines");
                System.out.println("6. Pay Fines");
                System.out.println("7. Exit");

                String line = prompt(input, "Enter your choice: ");

                try {
                    choice = Integer.parseInt(line);
                } catch (NumberFormatException e) {
                    System.out.println("Please enter a number from 1 to 7.");
                    choice = 0;
                    continue;
                }

                switch (choice) {

                    case 1:
                        searchBooks(connection, input);
                        break;

                    case 2:
                        checkOutBook(connection, input);
                        break;

                    case 3:
                        returnBook(connection, input);
                        break;

                    case 4:
                        registerBorrower(connection, input);
                        break;

                    case 5:
                        viewFines(connection);
                        break;

                    case 6:
                        payFines(connection, input);
                        break;

                    case 7:
                        System.out.println("Exiting Library System...");
                        break;

                    default:
                        System.out.println("Invalid choice. Please try again.");
                }
            }

        } catch (SQLException e) {
            System.out.println("\nUnable to connect to the Library database.");
            System.out.println("Reason: " + e.getMessage());
            System.out.println("Make sure MySQL is running, Library_Milestone2.sql has been loaded,");
            System.out.println("and the password is correct (or set LIBRARY_DB_URL / LIBRARY_DB_USER / LIBRARY_DB_PASSWORD).");
        }

        input.close();
    }

    public static void searchBooks(Connection connection, Scanner input) {

    String search = prompt(input, "Enter ISBN, book title, or author: ");

    if (search.isEmpty()) {
        System.out.println("Please enter something to search for.");
        return;
    }

    String[] words = search.split("\\s+");

    if (words.length > MAX_SEARCH_WORDS) {
        System.out.println("Please use " + MAX_SEARCH_WORDS + " words or fewer.");
        return;
    }

    // each word has to show up in the isbn, title, or an author name, so "mythology morford" works.
    // the author check is its own subquery so the Authors column still shows ALL the authors,
    // not just the one that matched. the words go in as ? parameters, never pasted into the sql
    String wordCondition = """
        (b.Isbn LIKE ? ESCAPE '!'
         OR b.Title LIKE ? ESCAPE '!'
         OR EXISTS (
             SELECT 1
             FROM book_authors ba2
             JOIN authors a2 ON ba2.Author_id = a2.Author_id
             WHERE ba2.Isbn = b.Isbn
             AND a2.Name LIKE ? ESCAPE '!'
         ))
        """;

    List<String> conditions = new ArrayList<>();
    for (int i = 0; i < words.length; i++) {
        conditions.add(wordCondition);
    }

    String sql = """
        SELECT b.Isbn, b.Title,
               GROUP_CONCAT(DISTINCT a.Name SEPARATOR ', ') AS Authors,
               CASE
                   WHEN EXISTS (
                       SELECT 1
                       FROM book_loans bl
                       WHERE bl.Isbn = b.Isbn
                       AND bl.Date_in IS NULL
                   )
                   THEN 'OUT'
                   ELSE 'IN'
               END AS Availability
        FROM book b
        LEFT JOIN book_authors ba ON b.Isbn = ba.Isbn
        LEFT JOIN authors a ON ba.Author_id = a.Author_id
        WHERE %s
        GROUP BY b.Isbn, b.Title
        ORDER BY b.Title, b.Isbn
        """.formatted(String.join(" AND ", conditions));

    try (PreparedStatement statement = connection.prepareStatement(sql)) {

        int parameter = 1;
        for (String word : words) {
            String searchValue = containsPattern(word);
            statement.setString(parameter++, searchValue);
            statement.setString(parameter++, searchValue);
            statement.setString(parameter++, searchValue);
        }

        int found = 0;

        try (ResultSet results = statement.executeQuery()) {

            while (results.next()) {

                found++;

                String authors = results.getString("Authors");

                System.out.println("\nISBN: " + results.getString("Isbn"));
                System.out.println("Title: " + results.getString("Title"));
                System.out.println("Authors: " + (authors == null ? "(none listed)" : authors));
                System.out.println("Availability: " + results.getString("Availability"));
                System.out.println("-----------------------------");
            }
        }

        if (found == 0) {
            System.out.println("No matching books found.");
        } else {
            System.out.println(found + " book(s) found.");
        }

    } catch (SQLException e) {
        System.out.println("Error searching for books: " + e.getMessage());
    }
}




public static void checkOutBook(Connection connection, Scanner input) {

    String isbn = prompt(input, "Enter book ISBN: ");
    String cardId = normalizeCardId(prompt(input, "Enter borrower Card ID: "));

    if (isbn.isEmpty() || cardId.isEmpty()) {
        System.out.println("Error: Both an ISBN and a Card ID are required.");
        return;
    }

    try {

        // update fines first, otherwise someone with an overdue book could still check out
        // if nobody opened View Fines yet
        refreshFines(connection);

        Result result = runInTransaction(connection, () -> checkOutInTransaction(connection, isbn, cardId));
        System.out.println(result.message());

    } catch (SQLException e) {
        System.out.println("Error checking out book: " + e.getMessage());
    }
}

private static Result checkOutInTransaction(Connection connection, String isbn, String cardId) throws SQLException {

    // 1. Check if the book exists (FOR UPDATE locks the row so two people can't check out
    //    the same book at the same time)
    if (!exists(connection, "SELECT Isbn FROM book WHERE Isbn = ? FOR UPDATE", isbn)) {
        return Result.fail("Error: Book does not exist.");
    }

    // 2. Check if the borrower exists (locked too, so the 3 book limit can't be beaten)
    if (!exists(connection, "SELECT Card_id FROM borrower WHERE Card_id = ? FOR UPDATE", cardId)) {
        return Result.fail("Error: Borrower does not exist.");
    }

    // 3. Check if the book is already checked out (by anyone, including this borrower)
    if (exists(connection, "SELECT Loan_id FROM book_loans WHERE Isbn = ? AND Date_in IS NULL", isbn)) {
        return Result.fail("Error: Book is not available. It is already checked out.");
    }

    // 4. Check borrower's active loan count
    int activeLoans = countRows(connection,
        "SELECT COUNT(*) FROM book_loans WHERE Card_id = ? AND Date_in IS NULL", cardId);

    if (activeLoans >= MAX_ACTIVE_LOANS) {
        return Result.fail("Error: Borrower already has " + MAX_ACTIVE_LOANS + " books checked out.");
    }

    // 5. Check for unpaid fines (this includes overdue books that are still out)
    BigDecimal unpaid = unpaidFineTotal(connection, cardId, false);

    if (unpaid.signum() > 0) {
        return Result.fail(String.format(
            "Error: Borrower has unpaid fines ($%.2f). Fines must be paid before checking out.", unpaid));
    }

    // 6. Create the new loan, due exactly 14 days from today
    String insertSQL = """
        INSERT INTO book_loans (Isbn, Card_id, Date_out, Due_date, Date_in)
        VALUES (?, ?, CURDATE(), DATE_ADD(CURDATE(), INTERVAL 14 DAY), NULL)
        """;

    int loanId;

    try (PreparedStatement statement = connection.prepareStatement(insertSQL, Statement.RETURN_GENERATED_KEYS)) {

        statement.setString(1, isbn);
        statement.setString(2, cardId);
        statement.executeUpdate();

        try (ResultSet keys = statement.getGeneratedKeys()) {
            if (!keys.next()) {
                throw new SQLException("MySQL did not return the new Loan_id.");
            }
            loanId = keys.getInt(1);
        }
    }

    String dueDate;

    try (PreparedStatement statement = connection.prepareStatement(
            "SELECT Due_date FROM book_loans WHERE Loan_id = ?")) {

        statement.setInt(1, loanId);

        try (ResultSet result = statement.executeQuery()) {
            result.next();
            dueDate = result.getDate("Due_date").toString();
        }
    }

    return Result.ok("Book successfully checked out!\nLoan ID: " + loanId + "\nDue date: " + dueDate);
}





public static void returnBook(Connection connection, Scanner input) {

    String search = prompt(input, "Enter ISBN, Card ID, or borrower name: ");

    if (search.isEmpty()) {
        System.out.println("Please enter an ISBN, Card ID, or part of a borrower name.");
        return;
    }

    String sql = """
        SELECT bl.Loan_id, bl.Isbn, b.Title,
               bl.Card_id, br.Bname, bl.Due_date
        FROM book_loans bl
        JOIN book b ON bl.Isbn = b.Isbn
        JOIN borrower br ON bl.Card_id = br.Card_id
        WHERE bl.Date_in IS NULL
        AND (
            bl.Isbn LIKE ? ESCAPE '!'
            OR bl.Card_id LIKE ? ESCAPE '!'
            OR br.Bname LIKE ? ESCAPE '!'
        )
        ORDER BY bl.Loan_id
        """;

    // save the loans in the same order we print them, so option 2 is always the 2nd one shown
    List<Integer> loanIds = new ArrayList<>();
    List<String> titles = new ArrayList<>();

    try (PreparedStatement statement = connection.prepareStatement(sql)) {

        String searchValue = containsPattern(search);

        statement.setString(1, searchValue);
        statement.setString(2, searchValue);
        statement.setString(3, searchValue);

        try (ResultSet results = statement.executeQuery()) {

            while (results.next()) {

                loanIds.add(results.getInt("Loan_id"));
                titles.add(results.getString("Title"));

                System.out.println("\nOption: " + loanIds.size());
                System.out.println("Loan ID: " + results.getInt("Loan_id"));
                System.out.println("ISBN: " + results.getString("Isbn"));
                System.out.println("Title: " + results.getString("Title"));
                System.out.println("Card ID: " + results.getString("Card_id"));
                System.out.println("Borrower: " + results.getString("Bname"));
                System.out.println("Due Date: " + results.getDate("Due_date"));
                System.out.println("-----------------------------");
            }
        }

    } catch (SQLException e) {
        System.out.println("Error searching for loans: " + e.getMessage());
        return;
    }

    if (loanIds.isEmpty()) {
        System.out.println("No active loans found.");
        return;
    }

    String picks = prompt(input,
        "Enter up to " + MAX_CHECKIN_AT_ONCE + " option numbers to return, separated by spaces (e.g. 1 3): ");

    Set<Integer> chosen = new LinkedHashSet<>();

    for (String token : picks.split("[\\s,]+")) {

        if (token.isEmpty()) {
            continue;
        }

        int option;
        try {
            option = Integer.parseInt(token);
        } catch (NumberFormatException e) {
            System.out.println("Invalid selection: \"" + token + "\" is not a number. Nothing was returned.");
            return;
        }

        if (option < 1 || option > loanIds.size()) {
            System.out.println("Invalid selection: option " + option + " is not in the list. Nothing was returned.");
            return;
        }

        chosen.add(option);
    }

    if (chosen.isEmpty() || chosen.size() > MAX_CHECKIN_AT_ONCE) {
        System.out.println("Please choose between 1 and " + MAX_CHECKIN_AT_ONCE + " books. Nothing was returned.");
        return;
    }

    String updateSQL = """
        UPDATE book_loans
        SET Date_in = CURDATE()
        WHERE Loan_id = ? AND Date_in IS NULL
        """;

    try {

        // return all the picked books together. if one fails, none of them get returned
        Result result = runInTransaction(connection, () -> {

            StringBuilder returned = new StringBuilder();

            try (PreparedStatement statement = connection.prepareStatement(updateSQL)) {

                for (int option : chosen) {

                    statement.setInt(1, loanIds.get(option - 1));

                    if (statement.executeUpdate() == 0) {
                        return Result.fail("Error: Loan " + loanIds.get(option - 1)
                            + " was already returned. Nothing was returned.");
                    }

                    returned.append("\n  - ").append(titles.get(option - 1));
                }
            }

            return Result.ok(chosen.size() + " book(s) successfully returned:" + returned);
        });

        System.out.println(result.message());

        // update fines right away so a late return shows the right amount
        if (result.success()) {
            refreshFines(connection);
        }

    } catch (SQLException e) {
        System.out.println("Error returning book: " + e.getMessage());
    }
}




public static void registerBorrower(Connection connection, Scanner input) {

    String name = prompt(input, "Enter borrower's full name: ");
    String ssn = prompt(input, "Enter SSN (XXX-XX-XXXX): ");
    String address = prompt(input, "Enter address: ");
    String phone = prompt(input, "Enter phone number (optional, press Enter to skip): ");

    // NOT NULL in the table doesn't stop empty text, so we check it here
    if (name.isEmpty() || ssn.isEmpty() || address.isEmpty()) {
        System.out.println("Error: Name, SSN, and address are all required.");
        return;
    }

    if (!ssn.matches("\\d{3}-\\d{2}-\\d{4}")) {
        System.out.println("Error: SSN must be in the format XXX-XX-XXXX (e.g. 123-45-6789).");
        return;
    }

    if (name.length() > 100 || address.length() > 255 || phone.length() > 20) {
        System.out.println("Error: Name must be at most 100 characters, address 255, and phone 20.");
        return;
    }

    try {

        Result result = runInTransaction(connection, () -> {

            // 1. Check for duplicate SSN (one card per person)
            String existingCard = firstValue(connection, "SELECT Card_id FROM borrower WHERE Ssn = ?", ssn);

            if (existingCard != null) {
                return Result.fail("Error: A borrower with this SSN already exists (Card ID " + existingCard + ").");
            }

            // 2. Make the next Card ID in the same format as the existing ones (ID000001, ID000002, ...)
            //    FOR UPDATE stops two registrations from grabbing the same ID
            String idSQL = """
                SELECT COALESCE(MAX(CAST(SUBSTRING(Card_id, 3) AS UNSIGNED)), 0) + 1
                FROM borrower
                WHERE Card_id LIKE 'ID%'
                FOR UPDATE
                """;

            int nextID;

            try (PreparedStatement statement = connection.prepareStatement(idSQL);
                 ResultSet idResult = statement.executeQuery()) {

                idResult.next();
                nextID = idResult.getInt(1);
            }

            String newCardID = String.format("ID%06d", nextID);

            // 3. Insert the new borrower
            String insertSQL = """
                INSERT INTO borrower (Card_id, Ssn, Bname, Address, Phone)
                VALUES (?, ?, ?, ?, ?)
                """;

            try (PreparedStatement statement = connection.prepareStatement(insertSQL)) {

                statement.setString(1, newCardID);
                statement.setString(2, ssn);
                statement.setString(3, name);
                statement.setString(4, address);
                statement.setString(5, phone.isEmpty() ? null : phone);

                statement.executeUpdate();
            }

            return Result.ok("\nBorrower successfully registered!\nGenerated Card ID: " + newCardID);
        });

        System.out.println(result.message());

    } catch (SQLIntegrityConstraintViolationException e) {
        // backup check: the table itself won't allow two borrowers with the same SSN
        System.out.println("Error: A borrower with this SSN already exists.");
    } catch (SQLException e) {
        System.out.println("Error registering borrower: " + e.getMessage());
    }
}


// Updates the fines table for every late loan. The spec says in real life this would run every
// night, we just run it before showing fines, paying fines, or checking out.
//   returned late: (date_in - due_date) days * $0.25
//   still out:     (today - due_date) days * $0.25
//   already has an unpaid fine -> update the amount, already paid -> leave it alone
public static void refreshFines(Connection connection) throws SQLException {

    String calculateSQL = """
        INSERT INTO fines (Loan_id, Fine_amt, Paid)
        SELECT * FROM (
            SELECT Loan_id,
                   DATEDIFF(COALESCE(Date_in, CURDATE()), Due_date) * 0.25 AS Late_fine,
                   0 AS Not_paid
            FROM book_loans
            WHERE DATEDIFF(COALESCE(Date_in, CURDATE()), Due_date) > 0
        ) AS late
        ON DUPLICATE KEY UPDATE
            Fine_amt = IF(fines.Paid = 0, late.Late_fine, fines.Fine_amt)
        """;

    try (PreparedStatement statement = connection.prepareStatement(calculateSQL)) {
        statement.executeUpdate();
    }
}


public static void viewFines(Connection connection) {

    // 1. Calculate fines for overdue books
    try {
        refreshFines(connection);
    } catch (SQLException e) {
        System.out.println("Error calculating fines: " + e.getMessage());
        return;
    }

    // 2. Display outstanding fines grouped by borrower (paid fines are filtered out)
    String displaySQL = """
        SELECT br.Card_id, br.Bname,
               SUM(f.Fine_amt) AS TotalFines
        FROM fines f
        JOIN book_loans bl ON f.Loan_id = bl.Loan_id
        JOIN borrower br ON bl.Card_id = br.Card_id
        WHERE f.Paid = 0 AND f.Fine_amt > 0
        GROUP BY br.Card_id, br.Bname
        ORDER BY br.Card_id
        """;

    try (PreparedStatement statement = connection.prepareStatement(displaySQL);
         ResultSet results = statement.executeQuery()) {

        boolean found = false;

        System.out.println("\n===== OUTSTANDING FINES =====");

        while (results.next()) {

            found = true;

            System.out.println("\nCard ID: " + results.getString("Card_id"));
            System.out.println("Borrower: " + results.getString("Bname"));
            System.out.printf("Total Fine: $%.2f%n", results.getBigDecimal("TotalFines"));

            System.out.println("-----------------------------");
        }

        if (!found) {
            System.out.println("No outstanding fines found.");
        }

    } catch (SQLException e) {
        System.out.println("Error displaying fines: " + e.getMessage());
    }
}





public static void payFines(Connection connection, Scanner input) {

    String cardId = normalizeCardId(prompt(input, "Enter borrower Card ID: "));

    if (cardId.isEmpty()) {
        System.out.println("Error: A Card ID is required.");
        return;
    }

    try {

        // update fines first so the amount is current
        refreshFines(connection);

        Result result = runInTransaction(connection, () -> {

            // 1. Check if borrower exists
            if (!exists(connection, "SELECT Card_id FROM borrower WHERE Card_id = ?", cardId)) {
                return Result.fail("Error: Borrower does not exist.");
            }

            // 2. Check for outstanding fines
            BigDecimal totalFine = unpaidFineTotal(connection, cardId, true);

            if (totalFine.signum() <= 0) {
                return Result.fail("No outstanding fines for this borrower.");
            }

            // 3. Can't pay while a book with a fine is still checked out (spec rule)
            String activeSQL = """
                SELECT COUNT(*)
                FROM fines f
                JOIN book_loans bl ON f.Loan_id = bl.Loan_id
                WHERE bl.Card_id = ?
                AND f.Paid = 0
                AND f.Fine_amt > 0
                AND bl.Date_in IS NULL
                """;

            if (countRows(connection, activeSQL, cardId) > 0) {
                return Result.fail(String.format(
                    "Total outstanding fines: $%.2f%nError: Return all books with outstanding fines before paying.",
                    totalFine));
            }

            // 4. Mark all outstanding fines as paid (no partial payments)
            String updateSQL = """
                UPDATE fines f
                JOIN book_loans bl ON f.Loan_id = bl.Loan_id
                SET f.Paid = 1
                WHERE bl.Card_id = ?
                AND f.Paid = 0
                AND f.Fine_amt > 0
                """;

            int updated;

            try (PreparedStatement statement = connection.prepareStatement(updateSQL)) {
                statement.setString(1, cardId);
                updated = statement.executeUpdate();
            }

            return Result.ok(String.format(
                "Fine payment successful!%nAmount paid: $%.2f%nFine records updated: %d", totalFine, updated));
        });

        System.out.println(result.message());

    } catch (SQLException e) {
        System.out.println("Error processing fine payment: " + e.getMessage());
    }
}


// ---------- helpers ----------

// what happened: success decides if we save (commit) or undo (rollback), message is what we print
private record Result(boolean success, String message) {

    static Result ok(String message) {
        return new Result(true, message);
    }

    static Result fail(String message) {
        return new Result(false, message);
    }
}

@FunctionalInterface
private interface TransactionWork {
    Result run() throws SQLException;
}

// runs a group of database steps as one unit: if everything works it saves (commit),
// if anything fails it undoes all of it (rollback), so we never save half a checkout or payment
private static Result runInTransaction(Connection connection, TransactionWork work) throws SQLException {

    connection.setAutoCommit(false);

    try {
        Result result = work.run();

        if (result.success()) {
            connection.commit();
        } else {
            connection.rollback();
        }

        return result;

    } catch (SQLException | RuntimeException e) {
        try {
            connection.rollback();
        } catch (SQLException rollbackError) {
            e.addSuppressed(rollbackError);
        }
        throw e;

    } finally {
        connection.setAutoCommit(true);
    }
}

private static boolean exists(Connection connection, String sql, String value) throws SQLException {
    return firstValue(connection, sql, value) != null;
}

private static String firstValue(Connection connection, String sql, String value) throws SQLException {

    try (PreparedStatement statement = connection.prepareStatement(sql)) {

        statement.setString(1, value);

        try (ResultSet result = statement.executeQuery()) {
            return result.next() ? result.getString(1) : null;
        }
    }
}

private static int countRows(Connection connection, String sql, String value) throws SQLException {

    try (PreparedStatement statement = connection.prepareStatement(sql)) {

        statement.setString(1, value);

        try (ResultSet result = statement.executeQuery()) {
            result.next();
            return result.getInt(1);
        }
    }
}

// total unpaid fines for one borrower (lockRows = true locks those rows while paying)
private static BigDecimal unpaidFineTotal(Connection connection, String cardId, boolean lockRows) throws SQLException {

    String sql = """
        SELECT COALESCE(SUM(f.Fine_amt), 0)
        FROM fines f
        JOIN book_loans bl ON f.Loan_id = bl.Loan_id
        WHERE bl.Card_id = ?
        AND f.Paid = 0
        AND f.Fine_amt > 0
        """ + (lockRows ? " FOR UPDATE" : "");

    try (PreparedStatement statement = connection.prepareStatement(sql)) {

        statement.setString(1, cardId);

        try (ResultSet result = statement.executeQuery()) {
            result.next();
            return result.getBigDecimal(1);
        }
    }
}

// turns text into a LIKE pattern for "contains", and makes % and _ normal characters if someone types them
private static String containsPattern(String text) {
    String escaped = text.replace("!", "!!").replace("%", "!%").replace("_", "!_");
    return "%" + escaped + "%";
}

// lets you type ID000123, id000123, or just 123
private static String normalizeCardId(String raw) {

    String cardId = raw.trim().toUpperCase();

    if (cardId.matches("\\d{1,6}")) {
        return String.format("ID%06d", Integer.parseInt(cardId));
    }

    return cardId;
}

private static String prompt(Scanner input, String message) {

    System.out.print(message);

    // if the input runs out (like Ctrl+Z), exit instead of crashing
    if (!input.hasNextLine()) {
        exitNoInput();
    }

    return input.nextLine().trim();
}

private static String readPassword(Scanner input, String message) {

    Console console = System.console();

    // hides the password while you type it (works in a normal terminal like run_library.bat)
    if (console != null) {
        char[] typed = console.readPassword(message);
        if (typed == null) {
            exitNoInput();
        }
        return new String(typed);
    }

    return prompt(input, message);
}

private static void exitNoInput() {
    System.out.println("\nNo more input. Exiting Library System...");
    System.exit(0);
}

private static String envOrDefault(String name, String fallback) {
    String value = System.getenv(name);
    return (value == null || value.isBlank()) ? fallback : value;
}

}
