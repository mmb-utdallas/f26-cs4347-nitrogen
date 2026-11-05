/*
Group: Team Nitrogen
Assignment: Milestone 1
Coder: George Chakiath Dani
Purpose: Reads the original books and borrowers CSV files, normalizes
the data into the required database tables, and writes the resulting
records into separate CSV output files. 
*/


import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;

import java.util.ArrayList;
import java.util.List;

import java.io.FileWriter;


public class FileParserM1 {
    public static void main(String[] args) {


        // ========================================
        // PART 1 & 2: READ AND NORMALIZE BOOK DATA
        // ========================================

    
        // Location of the original books CSV file
        String filePath = "books.csv"; 


        // Lists to store the normalized books CSV
        ArrayList<String> bookList = new ArrayList<>();
        ArrayList<String> authorList = new ArrayList<>();
        ArrayList<String> bookAuthorList = new ArrayList<>();

        // Each new author receives a unique ID, starting at 1
        int nextAuthorID = 1;

        try (BufferedReader br = new BufferedReader(new FileReader(filePath))) {

            String line;

            // Read the header row
            String header = br.readLine();

            // Read the remaining book records
            System.out.println("Parsing books.csv...");
            
            while ((line = br.readLine()) != null)
            {
                // Separate the CSV line into individual columns
                List<String> columns = parseCSV(line);

                // Skip rows that do not contain enough columns
                if (columns.size() < 4)
                {
                    continue;
                }

                // Extract the book information from the original file 
                String isbn10 = columns.get(0).trim();
                String title = columns.get(2).trim();
                String author = columns.get(3).trim();

                // Separate multiple authors into individual names 
                String[] authors = author.split(",");

                for (int i = 0; i < authors.length; i++)
                {
                    authors[i] = authors[i].trim();
                }

                // Process each author associated with the current book 
                for (String name: authors)
                {
                    // Ignore empty author names 
                     if (name.isEmpty())
                     {
                        continue;
                     }

                     boolean found = false;
                     int authorID = 0;

                    // Check whether this author already exists in the author list 
                    for (String existing : authorList)
                    {
                        // Separate the existing author's ID and name 
                        String[] parts = existing.split(",", 2);

                        // Compare names without considering uppercase or lowercase
                        if (parts[1].replace("\"", "").equalsIgnoreCase(name))
                        {
                            // Reuse the existing author's ID 
                          authorID = Integer.parseInt(parts[0]);
                          found = true;
                          break;
                        }
                    }

                    // If the author is new, assign a unique ID and store the author 
                    if (!found)
                    {
                        authorID = nextAuthorID;

                        // Add the author ID and name to the author list 
                        authorList.add(authorID + ",\"" + name.replace("\"", "\"\"") + "\"");

                        // Prepare the next available author ID 
                        nextAuthorID++;
                    }

                    // Connect the author to the current book using their IDs
                    String relationship = authorID + "," + isbn10;

                    // Avoid adding duplicate author-book relationships
                    if (!bookAuthorList.contains(relationship))
                    {
                        bookAuthorList.add(relationship);
                    }
                        
                }


                // Store the book using its ISBN-10 and title  
                String bookData = isbn10 + ",\"" + title.replace("\"", "\"\"") + "\"";

                // Avoid adding duplicate books 
                if (!bookList.contains(bookData))
                {
                    bookList.add(bookData);
                }
/*
                // Display book information for verification 
                System.out.println("Number of columns: " + columns.size());
                
                // Display the first three columns
                System.out.println("Column 0: " + columns.get(0));
                System.out.println("Column 1: " + columns.get(1));
                System.out.println("Column 2: " + columns.get(2));

                System.out.println("----------------------");
*/
            }

        }
        catch(IOException e)
        {
            // Display an error if the file cannot be read
            e.printStackTrace();
        }

        // =======================================
        // WRITE NORMALIZED BOOK DATA 
        // =======================================


        // Create the books output file 
        try (FileWriter writer = new FileWriter("books_output.csv"))
        {
            System.out.println("Writing to books_output.csv...");
            // Write the column headers
            writer.write("Isbn,Title\n");

            // Write each normalized book record
            for (String book : bookList)
            {
                writer.write(book + "\n");
            }
        }
        catch(IOException e)
        {
            e.printStackTrace();
        }

        // Create the authors output file 
        try (FileWriter writer = new FileWriter("authors.csv"))
        {
            System.out.println("Writing to authors.csv...");
            // Write the column headers
            writer.write("Author_id,Name\n");

            // Write each author and thier assigned ID 
            for (String author : authorList)
            {
                writer.write(author + "\n");
            }
        }
        catch(IOException e)
        {
            e.printStackTrace();
        }

        // Create the book-author and relationship output file 
        try (FileWriter writer = new FileWriter("book_authors.csv"))
        {
            System.out.println("Writing to book_authors.csv...");
            // Write the column headers 
            writer.write("Author_id,Isbn\n");

            // Write each author-book relationship
            for (String relationship : bookAuthorList)
            {
                writer.write(relationship + "\n");
            }
        }
        catch(IOException e)
        {
            e.printStackTrace();
        }

        // ========================================
        // PART 3: READ AND NORMALIZE BORROWER DATA
        // ========================================

        // Store the normalized borrower records 
        ArrayList<String> borrowerList = new ArrayList<>();

        // Location of the original borrowers CSV file 
        String borrowerFilePath = "borrowers.csv";

        try (BufferedReader br = new BufferedReader(new FileReader(borrowerFilePath)))
        {
            String line;

            // Skip the header row 
            System.out.println("Parsing borrowers.csv...");
            br.readLine();

            // Read each borrower record 
            while ((line = br.readLine()) != null)
            {
                // Separate CSV line into individual columns 
                List<String> columns = parseCSV(line);

                // Skip rows that do not contain all required columns 
                if (columns.size() < 9)
                {
                    continue;
                }

                // Extract the borrower's information 
                String cardID = columns.get(0).trim();
                String ssn = columns.get(1).trim();

                // Extract the borrower's first and last name
                String firstName = columns.get(2).trim();
                String lastName = columns.get(3).trim();

                // Combine the first and last name into one full name 
                String name = firstName + " " + lastName;

                // Extract the street address, city, and state
                String address = columns.get(5).trim();
                String city = columns.get(6).trim();
                String state = columns.get(7).trim();
                String phone = columns.get(8).trim();

                // Arrange the borrower information according to the normalized schema
                // Email is intentionally excluded because it is not part of the required schema 
                String fullAddress = address + ", " + city + ", " + state;

                String borrower = cardID + ",\"" + ssn + "\",\"" + name + "\",\"" + fullAddress + "\",\"" + phone + "\"";

            // Add the normalized borrower record to the list 
            borrowerList.add(borrower);
            }
        }
        catch(IOException e)
        {
            e.printStackTrace();
        }


        // ===========================================
        // WRITE NORMALIZED BORROWER DATA
        // ===========================================

        // Create the borrowers output file
        try (FileWriter writer = new FileWriter("borrowers_output.csv"))
        {
            System.out.println("Writing to borrowers_output.csv...");
            // Write the column headers 
            writer.write("Card_id,Ssn,Bname,Address,Phone\n");

            // Write each normalized borrower record 
            for (String borrower : borrowerList)
            {
                writer.write(borrower + "\n");
            }
        }
        catch(IOException e)
        {
            e.printStackTrace();
        }
        System.out.println("Parsing complete.");
    }

        // ==========================================
        // HELPER METHOD: PARSE CSV DATA
        // ==========================================

    // Separate a CSV line into columns while handling commas inside quotation marks
    public static List<String> parseCSV(String line) {
      
        List<String> columns = new ArrayList<>();
        StringBuilder value = new StringBuilder();

        // Tracks whether the current value is inside the quotation marks
        boolean insideQuotes = false; 

        // Examine each character in the CSV line 
        for (int i = 0; i < line.length(); i++)
        {
            char current = line.charAt(i);

            // Handle quotation marks and escaped quotes inside quoted values.
            if (current == '"')
            {
                if (insideQuotes && i + 1 < line.length() && line.charAt(i + 1) == '"')
                {
                    value.append('"');
                    i++;
                }
                else
                {
                    insideQuotes = !insideQuotes;
                }
            }
            // Only treat commas as column separators when outside quotation marks.
            else if (current == ',' && !insideQuotes)
            {
                columns.add(value.toString());
                value.setLength(0);
            }
            else {
                value.append(current);
            }
        }

        // Add the final column to the list
        columns.add(value.toString());

        return columns;
        
    }
}
