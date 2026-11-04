//Class for AUTHORS data points
public class AuthorsData
{
	//Data variables
	int authorID; //Primary key
	String name; //Full name; format as given in file
	
	//Constructors
	public AuthorsData() //Default constructor with placeholder values
	{
		authorID = -1;
		name = "PLACEHOLDER";
	}
	
	public AuthorsData(int a, String n) //Constructor with data
	{
		authorID = a;
		name = n;
	}
	
	//Get functions
	public int getAuthorID() //Returns author ID
	{
		return authorID;
	}
	
	public String getName() //Returns author name
	{
		return name;
	}
	
	//Compare primary key (author ID)
	public boolean isSamePrimary(AuthorsData other)
	{
		return (other.getAuthorID() == authorID);
	}
}