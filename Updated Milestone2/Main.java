
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Scanner;

public class Main {

    public static void main(String[] args) {

        String url = "jdbc:mysql://127.0.0.1:3306/Library_Milestone2";
        String username = "root";
        String password = "Joey512006!!";

        Scanner input = new Scanner(System.in);

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
                System.out.print("Enter your choice: ");

                choice = input.nextInt();
                input.nextLine();

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
            System.out.println("Database connection failed!");
            e.printStackTrace();
        }

        input.close();
    }

    public static void searchBooks(Connection connection, Scanner input) {

    System.out.print("Enter ISBN, book title, or author: ");
    String search = input.nextLine();

    String sql = """
        SELECT b.Isbn, b.Title,
               GROUP_CONCAT(DISTINCT a.Name SEPARATOR ', ') AS Authors,
               CASE
                   WHEN EXISTS (
                       SELECT 1
                       FROM BOOK_LOANS bl
                       WHERE bl.Isbn = b.Isbn
                       AND bl.Date_in IS NULL
                   )
                   THEN 'OUT'
                   ELSE 'IN'
               END AS Availability
        FROM BOOK b
        LEFT JOIN BOOK_AUTHORS ba ON b.Isbn = ba.Isbn
        LEFT JOIN AUTHORS a ON ba.Author_id = a.Author_id
        WHERE b.Isbn LIKE ?
           OR b.Title LIKE ?
           OR a.Name LIKE ?
        GROUP BY b.Isbn, b.Title
        """;

    try (PreparedStatement statement = connection.prepareStatement(sql)) {

        String searchValue = "%" + search + "%";

        statement.setString(1, searchValue);
        statement.setString(2, searchValue);
        statement.setString(3, searchValue);

        ResultSet results = statement.executeQuery();

        boolean found = false;

        while (results.next()) {

            found = true;

            System.out.println("\nISBN: " + results.getString("Isbn"));
            System.out.println("Title: " + results.getString("Title"));
            System.out.println("Authors: " + results.getString("Authors"));
            System.out.println("Availability: " + results.getString("Availability"));
            System.out.println("-----------------------------");
        }

        if (!found) {
            System.out.println("No matching books found.");
        }

    } catch (SQLException e) {
        System.out.println("Error searching for books.");
        e.printStackTrace();
    }
}




public static void checkOutBook(Connection connection, Scanner input) {

    System.out.print("Enter book ISBN: ");
    String isbn = input.nextLine();

    System.out.print("Enter borrower Card ID: ");
    int cardId = input.nextInt();
    input.nextLine();

    try {

        // 1. Check if the book exists
        String bookSQL = "SELECT Isbn FROM BOOK WHERE Isbn = ?";

        try (PreparedStatement statement = connection.prepareStatement(bookSQL)) {

            statement.setString(1, isbn);
            ResultSet result = statement.executeQuery();

            if (!result.next()) {
                System.out.println("Error: Book does not exist.");
                return;
            }
        }

        // 2. Check if the borrower exists
        String borrowerSQL = "SELECT Card_id FROM BORROWER WHERE Card_id = ?";

        try (PreparedStatement statement = connection.prepareStatement(borrowerSQL)) {

            statement.setInt(1, cardId);
            ResultSet result = statement.executeQuery();

            if (!result.next()) {
                System.out.println("Error: Borrower does not exist.");
                return;
            }
        }

        // 3. Check if the book is already checked out
        String loanSQL = "SELECT Loan_id FROM BOOK_LOANS WHERE Isbn = ? AND Date_in IS NULL";

        try (PreparedStatement statement = connection.prepareStatement(loanSQL)) {

            statement.setString(1, isbn);
            ResultSet result = statement.executeQuery();

            if (result.next()) {
                System.out.println("Error: Book is already checked out.");
                return;
            }
        }

        // 4. Check borrower's active loan count
        String countSQL = "SELECT COUNT(*) FROM BOOK_LOANS WHERE Card_id = ? AND Date_in IS NULL";

        try (PreparedStatement statement = connection.prepareStatement(countSQL)) {

            statement.setInt(1, cardId);
            ResultSet result = statement.executeQuery();

            result.next();

            if (result.getInt(1) >= 3) {
                System.out.println("Error: Borrower already has 3 active loans.");
                return;
            }
        }

        // 5. Check for unpaid fines
        String fineSQL = """
            SELECT COUNT(*)
            FROM FINES f
            JOIN BOOK_LOANS bl ON f.Loan_id = bl.Loan_id
            WHERE bl.Card_id = ? AND f.Paid = 0 AND f.Fine_amt > 0
            """;

        try (PreparedStatement statement = connection.prepareStatement(fineSQL)) {

            statement.setInt(1, cardId);
            ResultSet result = statement.executeQuery();

            result.next();

            if (result.getInt(1) > 0) {
                System.out.println("Error: Borrower has unpaid fines.");
                return;
            }
        }

        // 6. Create the new loan
        String insertSQL = """
            INSERT INTO BOOK_LOANS (Isbn, Card_id, Date_out, Due_date, Date_in)
            VALUES (?, ?, CURDATE(), DATE_ADD(CURDATE(), INTERVAL 14 DAY), NULL)
            """;

        try (PreparedStatement statement = connection.prepareStatement(insertSQL)) {

            statement.setString(1, isbn);
            statement.setInt(2, cardId);

            statement.executeUpdate();

            System.out.println("Book successfully checked out!");
            System.out.println("Due date: 14 days from today.");
        }

    } catch (SQLException e) {
        System.out.println("Error checking out book.");
        e.printStackTrace();
    }
}





public static void returnBook(Connection connection, Scanner input) {

    System.out.print("Enter ISBN, Card ID, or borrower name: ");
    String search = input.nextLine();

    String sql = """
        SELECT bl.Loan_id, bl.Isbn, b.Title,
               bl.Card_id, br.Bname, bl.Due_date
        FROM BOOK_LOANS bl
        JOIN BOOK b ON bl.Isbn = b.Isbn
        JOIN BORROWER br ON bl.Card_id = br.Card_id
        WHERE bl.Date_in IS NULL
        AND (
            bl.Isbn LIKE ?
            OR CAST(bl.Card_id AS CHAR) LIKE ?
            OR br.Bname LIKE ?
        )
        """;

    try (PreparedStatement statement = connection.prepareStatement(sql)) {

        String searchValue = "%" + search + "%";

        statement.setString(1, searchValue);
        statement.setString(2, searchValue);
        statement.setString(3, searchValue);

        ResultSet results = statement.executeQuery();

        int count = 0;

        while (results.next()) {

            count++;

            System.out.println("\nOption: " + count);
            System.out.println("Loan ID: " + results.getInt("Loan_id"));
            System.out.println("ISBN: " + results.getString("Isbn"));
            System.out.println("Title: " + results.getString("Title"));
            System.out.println("Card ID: " + results.getInt("Card_id"));
            System.out.println("Borrower: " + results.getString("Bname"));
            System.out.println("Due Date: " + results.getDate("Due_date"));
            System.out.println("-----------------------------");
        }

        if (count == 0) {
            System.out.println("No active loans found.");
            return;
        }

        System.out.print("Enter the option number of the book to return: ");
        int choice = input.nextInt();
        input.nextLine();

        if (choice < 1 || choice > count) {
            System.out.println("Invalid selection.");
            return;
        }

        // Find the selected loan
        statement.close();

        String findSQL = """
            SELECT Loan_id
            FROM BOOK_LOANS bl
            JOIN BOOK b ON bl.Isbn = b.Isbn
            JOIN BORROWER br ON bl.Card_id = br.Card_id
            WHERE bl.Date_in IS NULL
            AND (
                bl.Isbn LIKE ?
                OR CAST(bl.Card_id AS CHAR) LIKE ?
                OR br.Bname LIKE ?
            )
            ORDER BY bl.Loan_id
            """;

        int selectedLoanID = 0;

        try (PreparedStatement findStatement =
                 connection.prepareStatement(findSQL)) {

            findStatement.setString(1, searchValue);
            findStatement.setString(2, searchValue);
            findStatement.setString(3, searchValue);

            ResultSet selectedResults = findStatement.executeQuery();

            for (int i = 1; i <= choice; i++) {
                selectedResults.next();
                selectedLoanID = selectedResults.getInt("Loan_id");
            }
        }

        String updateSQL = """
            UPDATE BOOK_LOANS
            SET Date_in = CURDATE()
            WHERE Loan_id = ? AND Date_in IS NULL
            """;

        try (PreparedStatement updateStatement =
                 connection.prepareStatement(updateSQL)) {

            updateStatement.setInt(1, selectedLoanID);

            int updated = updateStatement.executeUpdate();

            if (updated > 0) {
                System.out.println("Book successfully returned!");
            } else {
                System.out.println("Unable to return book.");
            }
        }

    } catch (SQLException e) {
        System.out.println("Error returning book.");
        e.printStackTrace();
    }
}




public static void registerBorrower(Connection connection, Scanner input) {

    System.out.print("Enter borrower's full name: ");
    String name = input.nextLine();

    System.out.print("Enter SSN (XXX-XX-XXXX): ");
    String ssn = input.nextLine();

    System.out.print("Enter address: ");
    String address = input.nextLine();

    try {

        // 1. Check for duplicate SSN
        String checkSQL = "SELECT Card_id FROM BORROWER WHERE Ssn = ?";

        try (PreparedStatement statement = connection.prepareStatement(checkSQL)) {

            statement.setString(1, ssn);

            ResultSet result = statement.executeQuery();

            if (result.next()) {
                System.out.println("Error: A borrower with this SSN already exists.");
                return;
            }
        }

        // 2. Generate the next Card ID
        String idSQL = "SELECT COALESCE(MAX(Card_id), 1000) + 1 FROM BORROWER";

        int newCardID = 0;

        try (PreparedStatement statement = connection.prepareStatement(idSQL)) {

            ResultSet result = statement.executeQuery();

            if (result.next()) {
                newCardID = result.getInt(1);
            }
        }

        // 3. Insert the new borrower
        String insertSQL = """
            INSERT INTO BORROWER (Card_id, Ssn, Bname, Address)
            VALUES (?, ?, ?, ?)
            """;

        try (PreparedStatement statement = connection.prepareStatement(insertSQL)) {

            statement.setInt(1, newCardID);
            statement.setString(2, ssn);
            statement.setString(3, name);
            statement.setString(4, address);

            statement.executeUpdate();

            System.out.println("\nBorrower successfully registered!");
            System.out.println("Generated Card ID: " + newCardID);
        }

    } catch (SQLException e) {
        System.out.println("Error registering borrower.");
        e.printStackTrace();
    }
}


public static void viewFines(Connection connection) {

    // 1. Calculate fines for overdue books
    String calculateSQL = """
        INSERT INTO FINES (Loan_id, Fine_amt, Paid)
        SELECT Loan_id,
               DATEDIFF(COALESCE(Date_in, CURDATE()), Due_date) * 0.25,
               0
        FROM BOOK_LOANS
        WHERE DATEDIFF(COALESCE(Date_in, CURDATE()), Due_date) > 0
        ON DUPLICATE KEY UPDATE
            Fine_amt = IF(Paid = 0, VALUES(Fine_amt), Fine_amt)
        """;

    try (PreparedStatement statement =
             connection.prepareStatement(calculateSQL)) {

        statement.executeUpdate();

    } catch (SQLException e) {
        System.out.println("Error calculating fines.");
        e.printStackTrace();
        return;
    }

    // 2. Display outstanding fines grouped by borrower
    String displaySQL = """
        SELECT br.Card_id, br.Bname,
               SUM(f.Fine_amt) AS TotalFines
        FROM FINES f
        JOIN BOOK_LOANS bl ON f.Loan_id = bl.Loan_id
        JOIN BORROWER br ON bl.Card_id = br.Card_id
        WHERE f.Paid = 0 AND f.Fine_amt > 0
        GROUP BY br.Card_id, br.Bname
        ORDER BY br.Card_id
        """;

    try (PreparedStatement statement =
             connection.prepareStatement(displaySQL)) {

        ResultSet results = statement.executeQuery();

        boolean found = false;

        System.out.println("\n===== OUTSTANDING FINES =====");

        while (results.next()) {

            found = true;

            System.out.println("\nCard ID: " + results.getInt("Card_id"));
            System.out.println("Borrower: " + results.getString("Bname"));
            System.out.printf("Total Fine: $%.2f%n",
                    results.getDouble("TotalFines"));

            System.out.println("-----------------------------");
        }

        if (!found) {
            System.out.println("No outstanding fines found.");
        }

    } catch (SQLException e) {
        System.out.println("Error displaying fines.");
        e.printStackTrace();
    }
}





public static void payFines(Connection connection, Scanner input) {

    System.out.print("Enter borrower Card ID: ");
    int cardId = input.nextInt();
    input.nextLine();

    try {

        // 1. Check if borrower exists
        String borrowerSQL =
            "SELECT Card_id FROM BORROWER WHERE Card_id = ?";

        try (PreparedStatement statement =
                 connection.prepareStatement(borrowerSQL)) {

            statement.setInt(1, cardId);

            ResultSet result = statement.executeQuery();

            if (!result.next()) {
                System.out.println("Error: Borrower does not exist.");
                return;
            }
        }

        // 2. Check for outstanding fines
        String fineSQL = """
            SELECT SUM(f.Fine_amt)
            FROM FINES f
            JOIN BOOK_LOANS bl ON f.Loan_id = bl.Loan_id
            WHERE bl.Card_id = ?
            AND f.Paid = 0
            AND f.Fine_amt > 0
            """;

        double totalFine = 0;

        try (PreparedStatement statement =
                 connection.prepareStatement(fineSQL)) {

            statement.setInt(1, cardId);

            ResultSet result = statement.executeQuery();

            if (result.next()) {
                totalFine = result.getDouble(1);
            }
        }

        if (totalFine <= 0) {
            System.out.println("No outstanding fines for this borrower.");
            return;
        }

        System.out.printf("Total outstanding fines: $%.2f%n", totalFine);

        // 3. Check whether any books with unpaid fines are still checked out
        String activeSQL = """
            SELECT COUNT(*)
            FROM FINES f
            JOIN BOOK_LOANS bl ON f.Loan_id = bl.Loan_id
            WHERE bl.Card_id = ?
            AND f.Paid = 0
            AND f.Fine_amt > 0
            AND bl.Date_in IS NULL
            """;

        try (PreparedStatement statement =
                 connection.prepareStatement(activeSQL)) {

            statement.setInt(1, cardId);

            ResultSet result = statement.executeQuery();

            result.next();

            if (result.getInt(1) > 0) {
                System.out.println(
                    "Error: Return all books with outstanding fines before paying."
                );
                return;
            }
        }

        // 4. Mark all outstanding fines as paid
        String updateSQL = """
            UPDATE FINES f
            JOIN BOOK_LOANS bl ON f.Loan_id = bl.Loan_id
            SET f.Paid = 1
            WHERE bl.Card_id = ?
            AND f.Paid = 0
            AND f.Fine_amt > 0
            """;

        try (PreparedStatement statement =
                 connection.prepareStatement(updateSQL)) {

            statement.setInt(1, cardId);

            int updated = statement.executeUpdate();

            System.out.println("Fine payment successful!");
            System.out.printf("Amount paid: $%.2f%n", totalFine);
            System.out.println("Fine records updated: " + updated);
        }

    } catch (SQLException e) {
        System.out.println("Error processing fine payment.");
        e.printStackTrace();
    }
}




}
