import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;


public class MilestoneOne {
    public static void main(String[] args) {

    // Part 1: 
        String filePath = "books.csv"; 

        try (BufferedReader br = new BufferedReader(new FileReader(filePath))) {

            String line;

            int rowCount = 0;


            while ((line = br.readLine()) != null && rowCount < 5)
            {
                String[] columns = line.split("\t");

                System.out.println("Number of colums: " + columns.length);
                // System.out.println(columns[0]);
                
                System.out.println("Column 0: " + columns[0]);
                System.out.println("Column 1: " + columns[1]);
                System.out.println("Column 2: " + columns[2]);

                System.out.println("----------------------");

                rowCount++;
               
            }

        }
        catch(IOException e)
        {
            e.printStackTrace();
        }


        





    }
}