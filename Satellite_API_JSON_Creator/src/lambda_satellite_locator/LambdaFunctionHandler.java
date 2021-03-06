package lambda_satellite_locator;


import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Calendar;
import java.util.HashMap;
import java.util.Map;
import java.util.Vector;

import javax.json.Json;
import javax.json.JsonArray;
import javax.json.JsonArrayBuilder;
import javax.json.JsonBuilderFactory;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;

import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3Client;
import com.amazonaws.services.s3.model.ObjectMetadata;
import com.amazonaws.AmazonServiceException;

/**
 * An AWS Lambda Function to pull a list of Satellite TLEs and using those
 * TLEs calculate a desired period of orbital travel. It generates a JSON
 * file with the calculated orbital tracks and uploads them to an Amazon S3 instance
 * hosting a Web GL Globe - https://www.chromeexperiments.com/globe
 * 
 * 
 * (TLE - Two Line Element, a data format used for Orbital Mechanics/Propagation)
 * 
 * @author Maxwell Heller
 * @version 0.9
 *
 */

public class LambdaFunctionHandler implements RequestHandler<Object, Object> {

	
    @Override
    public Object handleRequest(Object input, Context context) {
        context.getLogger().log("Input: " + input);
        
        URL celestrak_station = null;
        URL celestrak_iridium = null;
        URL celestrak_NOAA = null;
        URL celestrak_visual = null;
		try {
			celestrak_station = new URL("http://celestrak.com/NORAD/elements/stations.txt");
			celestrak_iridium = new URL("http://celestrak.com/NORAD/elements/iridium.txt");
			celestrak_NOAA = new URL("http://celestrak.com/NORAD/elements/noaa.txt");
			celestrak_visual = new URL("http://celestrak.com/NORAD/elements/visual.txt");
		} catch (MalformedURLException e2) {
			// TODO Auto-generated catch block
			e2.printStackTrace();
		}
        
        BufferedReader in = null;
		try {
			in = new BufferedReader( new InputStreamReader(celestrak_station.openStream()));
		} catch (IOException e2) {
			// TODO Auto-generated catch block
			e2.printStackTrace();
		}
        
        Map<String, Object> config = new HashMap<String, Object>();
        JsonBuilderFactory globe_json = Json.createBuilderFactory(config);
        
        JsonArrayBuilder globe = globe_json.createArrayBuilder();
        
       
        
        double lat = 0,lon = 0, alt = 0;
        
        //String for the Station Name
        String sat_Name = null;
        
        //The delta time between calculated positions in minutes
    	double step = 0.1;
    	
    	Calendar calendar = Calendar.getInstance();
    	int dayOfYear = calendar.get(Calendar.DAY_OF_YEAR);
    	int currentYear = calendar.get(Calendar.YEAR);
    	
    	int startYr = currentYear;
    	int stopYr = currentYear;
    	
    	//In hours
    	double period = 1.5;
    	
    	double current_day_fraction = ((double)calendar.get(Calendar.HOUR_OF_DAY) + (((double)calendar.get(Calendar.MINUTE))/60)) / 24;               
    	double future_day_fraction = (((double)calendar.get(Calendar.HOUR_OF_DAY) + period) + ((double)(calendar.get(Calendar.MINUTE))/60))/24;
    	
    	double current_time = dayOfYear + current_day_fraction;
    	double future_time = dayOfYear + future_day_fraction;
    	
    	double temp_time = current_time;

    	//Variables for calculating ECI position and then converting to Lat,Lon,Alt
    	double julian = 0;
    	double x = 0;
    	double y = 0;
    	double z = 0;
    	
    	
        
        for(int i = 0; i < 6; i++){
        	try {
        	//Decides which input stream to pull Sat TLE's from
        	if(i==3){
					in = new BufferedReader( new InputStreamReader(celestrak_iridium.openStream()));
					sat_Name = in.readLine();					
        	}
        	else if(i==4){	
					in = new BufferedReader( new InputStreamReader(celestrak_NOAA.openStream()));
					sat_Name = in.readLine();
	        		while(!sat_Name.equals("NOAA 19 [+]             ")){
	        			sat_Name = in.readLine();
	        		}
			}else if(i == 5){
				in = new BufferedReader( new InputStreamReader(celestrak_visual.openStream()));
        		sat_Name = in.readLine();
        		while(!sat_Name.equals("KORONAS-FOTON           ")){
        			sat_Name = in.readLine();
        		}
        	}else{
        		sat_Name = in.readLine();
        	}}
        	catch (IOException e1) {
				// TODO Auto-generated catch block
				e1.printStackTrace();
			}
        	

        	int inputLength = 0;
        	while(!(sat_Name.charAt(inputLength) == ' ' && sat_Name.charAt(inputLength + 1) == ' ')){
        		inputLength++;
        	}
        	
        	sat_Name = sat_Name.substring(0, inputLength);
        	
        	System.out.println(sat_Name);
        	
        	String card1 = null;
        	String card2 = null;
			try {
				card1 = in.readLine();
	        	card2 = in.readLine();
			} catch (IOException e1) {
				// TODO Auto-generated catch block
				e1.printStackTrace();
			}

        	
        	Sgp4Data data = null;
        	Sgp4Unit location = new Sgp4Unit();
        	
        	Vector<Sgp4Data> results = null;
			try {
				results = location.runSgp4(card1, card2, startYr, current_time,
				        stopYr, future_time, step);
			} catch (SatElsetException e1) {
				// TODO Auto-generated catch block
				e1.printStackTrace();
			}
        	
        	JsonArrayBuilder coord = globe_json.createArrayBuilder();
        	
        	double[] pos = new double[3];
        	double julian_mod = 0;
        	
        	for (int j = 0; j < results.size(); j++) {
                 data = (Sgp4Data) results.elementAt(j);
                 
                 temp_time = current_time + ((j*step)/60)/24;
                 
                 
                 julian = myJday(temp_time);
                 
                 pos[0] = data.getX();
                 pos[1] = data.getY();
                 pos[2] = data.getZ();

                 julian_mod = julian - 2400000.5;
                 
                 pos = GeoFunctions.GeodeticLLA(pos, julian_mod);
                 
                 lat = (int)Math.toDegrees(pos[0]);
                 lon = (int)Math.toDegrees(pos[1]);
                 alt = pos[2];
                 alt = (Math.abs(alt/(100*alt)) *((double)(j)/(period*60)))*2;
                 alt = alt * 10000;
             	 alt = (int)alt;
             	 alt = alt / 10000.0;
                 
                 x = data.getX();
                 y = data.getY();
                 z = data.getZ();
                 
                 //Calculating the Latitude, and performing calculations to get a higher level of accuracy
                 lat = Math.atan( z / Math.sqrt((x* x) + (y * y)));
           
                 lat = (int)Math.toDegrees(lat);
                 
                 System.out.print(" Lat: " + (int)lat);
                 System.out.print(" Lon: " + (int)lon);
                 System.out.print(" Alt: " + alt + "\n");
                  
        		 coord.add((int)lat)
        			.add((int)lon)
        			.add(alt);
        		 

             }
        	
        	JsonArrayBuilder station = globe.add(globe_json.createArrayBuilder()
        			.add(sat_Name).add(coord));
        	
        	
        }

        //Builds the Json Array
        JsonArray finished_json = globe.build();

        //Turns the Json array into a Inputstream/Buffered stream format
        String str = finished_json.toString();
        InputStream is = new ByteArrayInputStream(str.getBytes());
        BufferedInputStream json_out = new BufferedInputStream(is);
       
		
		String bucket = "webglobetest";
        
        try {
			in.close();
		} catch (IOException e1) {
			// TODO Auto-generated catch block
			e1.printStackTrace();
		}
        
 		//Creates Object metadata to tell S3 that our file is json formatted
        ObjectMetadata ob = new ObjectMetadata();
        ob.setContentDisposition("application/json");
        
        //Using the BufferedInputStream, uploads our Json file the
        //the root directory of the partner site hosted on Amazon S3
        final AmazonS3 s3 = new AmazonS3Client();
		try {
		    s3.putObject("webglobetest", "globe.json", json_out, ob);
		    
		} catch (AmazonServiceException e1) {
		    System.err.println(e1.getErrorMessage());
		    System.exit(1);
		}
		
		try {
			in.close();
		} catch (IOException e1) {
			// TODO Auto-generated catch block
			e1.printStackTrace();
		}
        
        //Returns null and releases the Lambda Function
        return null;
    }
    
    //Calculates the Julian date given the current year and time instance
    //used for a single coordinate set
    static double myJday(double time){
		
		Calendar current_year = Calendar.getInstance();
		
		int yr = current_year.get(Calendar.YEAR);
		
		int year = 0;
		
		if (yr < 57)
		    year = yr + 2000;
		  else
		    year = yr + 1900;
		  double jd_year = 2415020.5 + (year-1900)*365 + Math.floor((year-1900-1)/4);
		  return jd_year + time - 1.0;

	}
    

}


