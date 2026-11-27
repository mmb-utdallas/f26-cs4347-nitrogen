# Library Management System
CS 4347.003, Fall 2026

Project Members: George Chakiath Dani, Srijan Dhakal, Nolan Vesanok Rastabouth, Nathan Tin Nguyen, Teddy Blair Byrne, Shifat Islam Santo

Repo link: https://github.com/mmb-utdallas/f26-cs4347-nitrogen

## Overview
This project simulates a library database. The database stores data on library books, borrowers, loans, and fines. The interface program allows for searching the database, checking books in/out, creating new borrower accounts, and tracking/paying fines on overdue books.

The database section of this project is written in SQL and managed in mySQL. The interface program is written in Java.
## How to Build/Run
### Required Software:
- Java JDK (21 or later)
- MySQL Server (ver. 8.0 or later)
- MySQL Connector/J 26.10.0 (already included: mysql-connector-j-26.10.0.jar). No other libraries are needed.
### Instructions:
1. **Copy the CSV files.** MySQL only loads CSV files from its secure_file_priv folder. Find yours by running `SHOW VARIABLES LIKE 'secure_file_priv';` in MySQL (on Windows it is usually `C:/ProgramData/MySQL/MySQL Server <version>/Uploads/`, which is a hidden folder). Copy these 4 files from `Milestone1/input_csv_files/` into that folder: `authors.csv`, `books_output.csv`, `book_authors.csv`, `borrowers_output.csv`. (These are the output of `FileParserM1.java`.)
2. **Load the database.** If your folder is not `C:/ProgramData/MySQL/MySQL Server 26.10/Uploads/`, open `Library_Milestone2.sql` and find-and-replace that path with yours. Then run the whole file (in MySQL Workbench, or `mysql -u root -p < Library_Milestone2.sql`). It creates the `library_milestone2` database with 25,001 books and 1,000 borrowers.
3. **Run the program.** Keep `Main.java`, `mysql-connector-j-26.10.0.jar`, and the run script in the same folder. On Windows run `run_library.bat`; on Mac/Linux run `sh run_library.sh`. The script compiles and starts the program.
4. **Log in.** The program asks for your MySQL root password when it starts. It connects to `library_milestone2` on `127.0.0.1:3306` as `root` by default. To use something else, set the environment variables `LIBRARY_DB_URL`, `LIBRARY_DB_USER`, and `LIBRARY_DB_PASSWORD` before running.

## How to Use
As of Milestone 2, the interface program uses a text-based navigation system. Follow the on-screen prompts. Proper syntax for the menu options is as follows:
- **Main Menu:** Enter a number from 1-7 matching the action you wish to take. Option 7 exits the program.
- **Search Books:** Enter a string (can be a number or contain spaces). The search function attempts to match book title, author, and/or ISBN. Partial matches (i.e. substrings) are allowed and case does not matter. If you type more than one word, every word has to match somewhere (e.g. `mythology morford` finds *Classical Mythology* by Mark P. O. Morford). Each result shows the ISBN, title, all authors, and whether the book is IN or OUT.
- **Check Out Book:** Enter an ISBN and a borrower ID in the matching prompts (the ID can be typed as `ID000005`, `id000005`, or just `5`). If the book and borrower exist, the book is available, the borrower has fewer than 3 books out, and the borrower has no unpaid fines (including overdue books still checked out), then the book will be checked out, due in 14 days.
- **Return Book:** Enter an ISBN, a borrower ID, or a borrower's name. If entering a name, substrings are allowed. Select up to 3 books to return from the options given in the search results by typing their option numbers separated by spaces (e.g. `1 3`).
- **Register Borrower:** Enter the prospective borrower's full name, SSN (format XXX-XX-XXXX), and address in the matching prompts; phone number is optional. If the borrower does not already have a card (checked via SSN), they will be registered as a new borrower and receive a borrower ID in the same format as the existing ones (e.g. `ID001001`).
- **View Fines:** No additional input. All outstanding fines will be displayed, grouped by borrower and sorted by borrower ID. Paid fines are not shown. \[For simulation purposes, fines are recalculated at $0.25 per late day before displaying them. They are also recalculated before checkouts and payments and after returns.]
- **Pay Fines:** Enter a borrower ID. If the borrower has outstanding fines for returned books and does not have any overdue books currently checked out, all outstanding fines will be paid (no partial payments).

## File Structure
f26-cs4347-nitrogen
- README.md
- readme.pdf: PDF copy of this README
- Milestone1
  - FileParserM1.java: Parser program
  - input_csv_files: Folder containing the raw CSVs and the CSVs produced by FileParserM1.java
- Updated_Milestone2_Final
  - Main.java: Interface program
  - `Main.class`, `Main$Result.class`, `Main$TransactionWork.class`: Class files for Main.java
  - Library_Milestone2.sql: Database
  - mysql-connector-j-26.10.0.jar: Connector between interface program and database
  - run_library.bat: For running program on Windows
  - run_library.sh: For running program on Mac/Linux
