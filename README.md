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
### Instructions:
- Run file Library_Milestone2.sql to load the database into your desired server.
- Set MySQL credentials in Main.java to match those of the desired database.
- Make sure files Main.java, mysql-connector-j-26.10.0.jar, run_library.bat are all in the same folder.
- Run file run_library.bat to launch the interface program.

## How to Use
As of Milestone 2, the interface program uses a text-based navigation system. Follow the on-screen prompts. Proper syntax for the menu options is as follows:
- **Main Menu:** Enter a number from 1-7 matching the action you wish to take. Option 7 exits the program.
- **Search Books:** Enter a string (can be a number or contain spaces). The search function attempts to match book title, author, and/or ISBN. Partial matches (i.e. substrings) are allowed.
- **Check Out Book:** Enter an ISBN and a borrower ID in the matching prompts. If the book and borrower exist, the book is available, and the borrower is able to check out new books, then the book will be checked out.
- **Return Book:** Enter an ISBN, a borrower ID, or a borrower's name. If entering a name, substrings are allowed. Select the desired book to return from the options given in the search results.
- **Register Borrower:** Enter the prospective borrower's full name, SSN, and address in the matching prompts. If the borrower does not already have a card (checked via SSN), they will be registered as a new borrower and receive a borrower ID.
- **View Fines:** No additional input. All outstanding fines will be displayed, grouped by borrower and sorted by borrower ID. \[For simulation purposes, this function recalculates/updates outstanding fine balances before displaying them.]
- **Pay Fines:** Enter a borrower ID. If the borrower has outstanding fines for returned books and does not have any overdue books currently checked out, all outstanding fines will be paid.

## File Structure
f26-cs4347-nitrogen
- README.md
- Milestone1
- - FileParserM1.java: Parser program
- - input_csv_files: Folder containing CSVs used in FileParserM1.java
- Milestone2
- - Main.java: Interface program
- - Main.class: Class file for Main.java
- - Library_Milestone2.sql: Database
- - mysql-connector-j-26.10.0.jar: Connector between interface program and database
- - run_library.bat: For running program
