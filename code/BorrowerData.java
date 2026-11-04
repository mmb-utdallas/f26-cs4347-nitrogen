//Class for storing BORROWER data points
public class BorrowerData
{
	//Data variables
	int cardID; //Primary key; format as the # portion of "ID######"
	String ssn; //Format as: ###-##-####
	String name; //Full name; format as: "First Last" (as given in file)
	String address; //Format as: "Address; City, ST"
	String phoneNum; //Format as: ###-###-####
	
	//Constructors
	public BorrowerData() //Default constructor with placeholder values
	{
		cardID = -1;
		ssn = "PLACEHOLDER";
		name = "PLACEHOLDER";
		address = "PLACEHOLDER";
		phoneNum = "PLACEHOLDER";
	}
	
	public BorrowerData(int c, String s, String n, String a, String p) //Constructor with data
	{
		cardID = c;
		ssn = s;
		name = n;
		address = a;
		phoneNum = p;
	}
	
	//Get functions
	public int getCardID() //Returns card ID
	{
		return cardID;
	}
	
	public String getSSN() //Returns SSN
	{
		return ssn;
	}
	
	public String getName() //Returns borrower name
	{
		return name;
	}
	
	public String getAddress() //Returns address
	{
		return address;
	}
	
	public String getPhoneNum() //Returns phone number
	{
		return phoneNum;
	}
	
	//Compare primary key (card ID)
	public boolean isSamePrimary(BorrowerData other)
	{
		return (other.getCardID() == cardID);
	}
}