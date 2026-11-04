//Class for BOOK data points
public class BooksData
{
	//Data variables
	String isbn13; //Primary key; ISBN-13
	String title;
	
	//Constructors
	public BooksData() //Default constructor with placeholder values
	{
		isbn13 = "PLACEHOLDER";
		title = "PLACEHOLDER";
	}
	
	public BooksData(String i, String t) //Constructor with data
	{
		isbn13 = i;
		title = t;
	}
	
	
	//Get functions
	public String getISBN() //Returns ISBN
	{
		return isbn13;
	}
	
	public String getTitle() //Returns book title
	{
		return title;
	}
	
	//Compare primary key (ISBN-13)
	public boolean isSamePrimary(BooksData other)
	{
		return (other.getISBN() == isbn13);
	}
}