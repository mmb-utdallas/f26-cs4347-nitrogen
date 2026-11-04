// Part 1
import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;

//Part 2
import java.util.ArrayList;
import java.util.List;




public class MilestoneOne {
    public static void main(String[] args) {

    // Part 1: 
        String filePath = "books.csv"; 

        ArrayList<String> bookList = new ArrayList<>();
        ArrayList<String> authorList = new ArrayList<>();
        ArrayList<String> bookAuthorList = new ArrayList<>();

        int nextAuthorID = 1;


        // Part 2, Step 2:
        try (BufferedReader br = new BufferedReader(new FileReader(filePath))) {

            String header = br.readLine(); 

            String line;

            int rowCount = 0;

            while ((line = br.readLine()) != null) 
            {
                List<String> columns = parseCSVLine(line);

                if (columns.size() < 6) 
                {
                    continue;
                }

                String isbn10 = columns.get(0).trim();
                String isbn13 = columns.get(1).trim();
                String title = columns.get(2).trim();
                String author = columns.get(3).trim();

                String[] authors = author.split(",");

                String bookData = isbn10 + "," + title;

                if (!bookList.contains(bookData)) 
                {
                    bookList.add(bookData);
                }

                if (author.isEmpty()) 
                {
                    authors = new String[0];
                }

                for (int i = 0; i < authors.length; i++) 
                {
                    authors[i] = authors[i].trim();
                }

                for (String name : authors)
                {
                    if (name.isEmpty())
                    {
                        continue;
                    }

                    boolean found = false;
                    int authorID = 0;

                    for (String existing : authorList)
                    {
                        String[] parts = existing.split(",", 2);

                        if (parts[1].equalsIgnoreCase(name))
                        {
                            authorID = Integer.parseInt(parts[0]);
                            found = true;
                            break;
                        }
                    }

                    

                    if (!found) {
                        authorID = nextAuthorID;

                        authorList.add(authorID + "," + name);

                        nextAuthorID++;
                    }

                    String relationship = authorID + "," + isbn10;

                    if (!bookAuthorList.contains(relationship))
                    {
                        bookAuthorList.add(relationship);
                    }
                }


                if (rowCount < 5)
                {
                    System.out.println("ISBN10: " + isbn10);
                    System.out.println("ISBN13: " + isbn13);
                    System.out.println("Title: " + title);

                    for (String name : authors)
                    {
                        System.out.println("Author: " + name);
                    }

                    System.out.println("------------------------");
                }

                rowCount++;
            }


        } 
        catch (IOException e) {
            e.printStackTrace();
        }
    }


    // Part 2, Step 1: 
    public static List<String> parseCSVLine(String line) {

        List<String> columns = new ArrayList<>();
        StringBuilder value = new StringBuilder();

        boolean insideQuotes = false;

        for (int i = 0; i < line.length(); i++)
        {
            char current = line.charAt(i);

            if (current == '"')
            {
                insideQuotes = !insideQuotes;
            }
            else if (current == ',' && !insideQuotes)
            {
                columns.add(value.toString());
                value.setLength(0);
            }
            else {
                value.append(current);
            }
        }

        columns.add(value.toString());

        return columns;

       }  
        
    }