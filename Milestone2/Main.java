import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.ResultSet;

public class Main {
    public static void main(String[] args) {

        String url = "jdbc:mysql://127.0.0.1:3306/Library_Milestone2";
        String username = "root";
        String password = "Joey512006!!";

        try {
            Connection connection = DriverManager.getConnection(
                url, username, password
            );

            System.out.println("Successfully connected to MySQL!");
            Statement statement = connection.createStatement();
            String query = "SELECT * FROM BOOK";
            ResultSet resultSet = statement.executeQuery(query);
            while (resultSet.next()) {
                String isbn = resultSet.getString("Isbn");
                String title = resultSet.getString("Title");
                System.out.println(isbn + " - " + title);
            }
            

            connection.close();

        } catch (SQLException e) {
            System.out.println("Connection failed!");
            e.printStackTrace();
        }
    }
}