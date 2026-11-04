//Class for BOOK_AUTHORS data-relation points
public class BookAuthorsData
{
	//Data variables
	int authorID; //Part of primary key
	String isbn13; //Part of primary key; ISBN-13
	
	//Constructors
	public BookAuthorsData() //Default constructor with placeholder values
	{
		authorID = -1;
		isbn13 = "PLACEHOLDER";
	}
	
	public BookAuthorsData(int a, String i) //Constructor with data
	{
		authorID = a;
		isbn13 = i;
	}
	
	//Get functions
	public int getAuthorID() //Returns author ID
	{
		return authorID;
	}
	
	public String getISBN() //Returns ISBN
	{
		return isbn13;
	}
	
	//Compare primary key (author ID + ISBN-13)
	public boolean isSamePrimary(BookAuthorsData other)
	{
		return ((other.getAuthorID() == authorID) && (other.getISBN() == isbn13));
	}
}