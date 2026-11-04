//Program to normalize data from given CSV files, store normalized data in new CSV files
//See README.md for more information

import java.util.ArrayList;
import java.io.File;
import java.io.FileNotFoundException;
import java.util.Scanner;

public class DataNormalize
{
	//Tables for storing data
	private ArrayList<BooksData> tableBooks = new ArrayList<BooksData>();
	private ArrayList<BookAuthorsData> tableBookAuthors = new ArrayList<BookAuthorsData>();
	private ArrayList<AuthorsData> tableAuthors = new ArrayList<AuthorsData>();
	private ArrayList<BorrowerData> tableBorrower = new ArrayList<BorrowerData>();
	
	//Main program
	public static void main(String[] args)
	{
		
		Scanner input = new Scanner(System.in);
		System.out.print("Enter name of file with book data (e.g. \"books-1.csv\"): ");
		String fileName = input.next();
		File bookData = new File(fileName);
		
		try(Scanner bookIn = new Scanner(bookData))
		{
			//ELEPHANT
		}
		catch(FileNotFoundException e)
		{
			System.out.println("Error: File not found.");
		}
		catch(Exception e)
		{
			System.out.println(e);
		}
		
	}
	
	//Functions
	/*
	Need minor functions to:
	- Concatenate first/last name Strings into single String
	- Concatenate address/city/state Strings into single String
	- Convert phone number String to format specified in class comments
	
	Need major functions to:
	- Print tables to CSV files in correct format
	*/
}
