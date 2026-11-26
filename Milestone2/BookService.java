import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Book Search and Availability (Milestone 2).
 *
 * The librarian types ONE search string. It is split into words, and a book
 * matches when EVERY word appears (case-insensitive substring) in the book's
 * ISBN, its title, or the name of at least one of its authors. So
 *   "clean"          -> title match
 *   "martin"         -> author match
 *   "clean martin"   -> title AND author (combination)
 *   "9780132350884"  -> exact ISBN, at most one result
 *
 * Each result shows ISBN, title, ALL authors (comma separated) and IN/OUT.
 * A book is OUT when it has a BOOK_LOANS row with Date_in IS NULL.
 *
 * This class does no printing, so the same method can be used by the console
 * menu now and the GUI in Milestone 3.
 */
public class BookService {

    /** Upper limit on words in one search, to keep the generated SQL small. */
    private static final int MAX_SEARCH_TERMS = 10;

    private final Connection connection;

    public BookService(Connection connection) {
        this.connection = connection;
    }

    /**
     * Searches books by ISBN, title and author.
     *
     * @return matching books ordered by title (empty list if nothing matches)
     * @throws IllegalArgumentException if the search string is null/blank or has too many words
     * @throws SQLException             if the database query fails
     */
    public List<BookResult> searchBooks(String searchText) throws SQLException {
        if (searchText == null || searchText.trim().isEmpty()) {
            throw new IllegalArgumentException("Please enter an ISBN, title, or author to search for.");
        }
        String[] terms = searchText.trim().split("\\s+");
        if (terms.length > MAX_SEARCH_TERMS) {
            throw new IllegalArgumentException("Please use at most " + MAX_SEARCH_TERMS + " search words.");
        }

        // A full ISBN must give at most one book: if the whole input (ignoring
        // dashes) is exactly an ISBN, return only that book.
        String possibleIsbn = searchText.trim().replace("-", "");
        if (terms.length == 1) {
            List<BookResult> exact = runSearch("b.isbn = ?", List.of(possibleIsbn));
            if (!exact.isEmpty()) {
                return exact;
            }
        }

        // Build one condition per word. Each word may match the ISBN, the title,
        // or any author of the book. The author check is an EXISTS subquery so
        // the outer joins still collect ALL authors of a matching book.
        StringBuilder where = new StringBuilder();
        List<String> params = new ArrayList<>();
        for (String term : terms) {
            if (where.length() > 0) {
                where.append(" AND ");
            }
            where.append("(LOWER(b.isbn) LIKE ?"
                       + " OR LOWER(b.Title) LIKE ?"
                       + " OR EXISTS (SELECT 1 FROM book_authors ba2"
                       + "            JOIN authors a2 ON a2.Author_id = ba2.Author_id"
                       + "            WHERE ba2.Isbn = b.isbn AND LOWER(a2.Name) LIKE ?))");
            String pattern = "%" + escapeLike(term.toLowerCase()) + "%";
            // ISBNs are stored without dashes, so ignore dashes for the ISBN test.
            params.add("%" + escapeLike(term.replace("-", "").toLowerCase()) + "%");
            params.add(pattern);
            params.add(pattern);
        }
        return runSearch(where.toString(), params);
    }

    /**
     * Runs the search query with the given WHERE condition.
     * Only "?" placeholders are ever added to the SQL text; user input goes
     * through setString, so SQL injection is not possible.
     */
    private List<BookResult> runSearch(String whereCondition, List<String> params) throws SQLException {
        // GROUP BY b.isbn gives one row per book (no duplicates when a book has
        // several authors). GROUP_CONCAT joins all author names into one string.
        // The EXISTS subquery is 1 when the book has an active (unreturned) loan.
        String sql =
              "SELECT b.isbn, b.Title, "
            + "       GROUP_CONCAT(a.Name ORDER BY a.Name SEPARATOR ', ') AS Authors, "
            + "       EXISTS (SELECT 1 FROM book_loans bl "
            + "               WHERE bl.Isbn = b.isbn AND bl.Date_in IS NULL) AS Checked_out "
            + "FROM book b "
            + "LEFT JOIN book_authors ba ON ba.Isbn = b.isbn "
            + "LEFT JOIN authors a ON a.Author_id = ba.Author_id "
            + "WHERE " + whereCondition + " "
            + "GROUP BY b.isbn, b.Title "
            + "ORDER BY b.Title, b.isbn";

        List<BookResult> results = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            for (int i = 0; i < params.size(); i++) {
                ps.setString(i + 1, params.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String authors = rs.getString("Authors");
                    results.add(new BookResult(
                            rs.getString("isbn"),
                            rs.getString("Title"),
                            authors == null ? "" : authors,
                            !rs.getBoolean("Checked_out")));
                }
            }
        }
        return results;
    }

    /**
     * Makes %, _ and \ in the user's text match literally inside LIKE
     * (otherwise searching "50%" would treat % as a wildcard).
     */
    private static String escapeLike(String text) {
        return text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    /** One row of search output. */
    public static class BookResult {
        private final String isbn;
        private final String title;
        private final String authors;
        private final boolean available;

        public BookResult(String isbn, String title, String authors, boolean available) {
            this.isbn = isbn;
            this.title = title;
            this.authors = authors;
            this.available = available;
        }

        public String getIsbn() { return isbn; }
        public String getTitle() { return title; }
        /** All authors, comma separated (empty string if none are recorded). */
        public String getAuthors() { return authors; }
        public boolean isAvailable() { return available; }
        /** "IN" if the book can be checked out, otherwise "OUT". */
        public String getStatus() { return available ? "IN" : "OUT"; }

        @Override
        public String toString() {
            return isbn + " | " + title + " | " + authors + " | " + getStatus();
        }
    }

    /**
     * Command-line search (no GUI needed):
     *   java -cp ".;mysql-connector-j-26.10.0.jar" BookService "clean code"
     */
    public static void main(String[] args) {
        if (args.length == 0) {
            System.out.println("Usage: java BookService \"search words\"");
            return;
        }
        try (Connection conn = BorrowerService.connectFromEnvironment()) {
            List<BookResult> results = new BookService(conn).searchBooks(String.join(" ", args));
            if (results.isEmpty()) {
                System.out.println("No books found.");
                return;
            }
            System.out.println("ISBN | Title | Authors | Status");
            for (BookResult r : results) {
                System.out.println(r);
            }
        } catch (IllegalArgumentException e) {
            System.out.println("ERROR: " + e.getMessage());
        } catch (SQLException e) {
            System.out.println("ERROR: Database problem: " + e.getMessage());
        }
    }
}
