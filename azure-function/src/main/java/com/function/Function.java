package com.function;

import com.microsoft.azure.functions.ExecutionContext;
import com.microsoft.azure.functions.HttpMethod;
import com.microsoft.azure.functions.HttpRequestMessage;
import com.microsoft.azure.functions.HttpResponseMessage;
import com.microsoft.azure.functions.HttpStatus;
import com.microsoft.azure.functions.annotation.AuthorizationLevel;
import com.microsoft.azure.functions.annotation.FunctionName;
import com.microsoft.azure.functions.annotation.HttpTrigger;

import java.util.HashMap;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Map;
import java.util.ArrayList;
import java.util.List;
import java.util.Arrays;
import java.util.Date;
import java.util.Calendar;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.Optional;

import javax.xml.bind.ValidationException;

public class Function {
    private void validateParameters(Map<String, String> parameters, List<String> licenses) throws ValidationException{
        // Check that all required parameters are populated
        List<String> required = new ArrayList<>(Arrays.asList("email", "givenName", "business", "phone", "license", 
                                                              "country", "address", "business_type", "business_sect", "postcode", "principle"));
        for (String param : required) {
            if (parameters.get(param) == null) throw new ValidationException("Missing one or more required parameters");
        }
        // Check that email is in correct format
        String email = parameters.get("email");
        if (!email.contains("@")) {
            throw new ValidationException("Invalid email address");
        }
        // Check that phone number is numeric only
        String phone = parameters.get("phone");
        for (int i = 0; i < phone.length(); i++){
            char character = phone.charAt(i);
            int ascii = (int) character;
            // Exceptions are [space # + -]
            if ((ascii < 48 || ascii > 58) && ascii != 32 && ascii != 35 && ascii != 43 && ascii != 44) {
                throw new ValidationException("Invalid phone number");
            }    
        }
        // Check that license is a known value
        String license = parameters.get("license");
        if (!licenses.contains(license)) {
            throw new ValidationException("License type (" + license + ") not recognised");
        }
        // Check that country is a char(2) in capital letters
        String country = parameters.get("country");
        for (int i = 0; i < country.length(); i++){
            int ascii = (int) country.charAt(i);
            if (ascii < 65 || ascii > 90) {
                throw new ValidationException("Incorrect country code format");
            }    
        }
        if (country.length() != 2) {
            throw new ValidationException("Incorrect country code length");
        }
        // Check that fields do not exceed their varchar limits
        Map<String, Integer> fieldMaxLengths = new HashMap<>();
        fieldMaxLengths.put("email", 200);
        fieldMaxLengths.put("business", 200);
        fieldMaxLengths.put("phone", 20);
        fieldMaxLengths.put("address", 200);
        fieldMaxLengths.put("business_type", 50);
        fieldMaxLengths.put("business_sect", 50);
        fieldMaxLengths.put("postcode", 10);
        for (Map.Entry<String, Integer> length : fieldMaxLengths.entrySet()) {
            if (parameters.get(length.getKey()).length() > length.getValue()) {
                throw new ValidationException("A parameter (" + length.getKey() + ") exceeds the max data length defined in the database.");
            }
        }
    }

    // Format the stackTrace into a readable string
    private String makeErrorMessage(Exception e){
        String message = e.getMessage() + " \n";
        StackTraceElement[] stackTrace = e.getStackTrace();
        for (int i = 0; i < stackTrace.length; i++) {
            message += "Index " + i + " of stack trace" + " array contains = " + stackTrace[i].toString() + "\n";
        }
        return message;
    }

    // Get the current date and renewal date
    private Map<String, Timestamp> getTimestamps(int duration) {
        Date today = new Date();
        Timestamp now = new Timestamp(today.getTime());
        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(now.getTime());
        calendar.add(Calendar.DAY_OF_MONTH, duration);
        Timestamp renewalDate = new Timestamp(calendar.getTime().getTime());
        Map<String, Timestamp> timestamps = new HashMap<>();
        timestamps.put("now", now);
        timestamps.put("renewalDate", renewalDate);
        return timestamps;
    }

    // Checks if the user already exists in the DB
    private boolean isUpgrade(Connection c, Map<String, String> parameters) throws SQLException {
        String SQL = "SELECT * FROM Users WHERE email = ?";
        try (PreparedStatement stmt = c.prepareStatement(SQL)) {
            stmt.setString(1, parameters.get("email"));
            ResultSet rs = stmt.executeQuery();
            return rs.next();
        }
    }

    // A new customer must have a unique customer number
    private int generateCustomerNum(Connection c) throws SQLException {
        String SQL = "SELECT MAX(customer_id) FROM Users";
        try (Statement stmt = c.createStatement()) {
            ResultSet rs = stmt.executeQuery(SQL);
            if (rs.next()) {
                return rs.getInt(1) + 1;
            } else {
                throw new SQLException("SQL query did not return data");
            }
        }
    }

    // Gets a list of all licenses
    private List<String> getAllLicenses(Connection c) throws SQLException {
        String SQL = "SELECT name FROM License";
        List<String> licenses = new ArrayList<>();
        try (Statement stmt = c.createStatement()) {
            ResultSet rs = stmt.executeQuery(SQL);
            while (rs.next()) {
                licenses.add(rs.getString(1));
            }
        }
        return licenses;
    }

    // Gets all the needed license info needed to create a new subscription
    private Map<String, Number> getLicenseInfo(Connection c, String license) throws SQLException {
        String SQL = "SELECT duration, latest_version, trials FROM License WHERE name = ?";
        Map<String, Number> info = new HashMap<>();
        try (PreparedStatement stmt = c.prepareStatement(SQL)) {
            stmt.setString(1, license);
            stmt.executeQuery();
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                info.put("duration", rs.getInt(1));
                info.put("currentVersion", rs.getFloat(2));
                info.put("trials", rs.getInt(3));
            } else {
                throw new SQLException("SQL query did not return data");
            }
        }
        return info;
    }

    // Adds a new user to the DB
    private void newUser(Connection c, Map<String, String> parameters, int customer_id, Map<String, Timestamp> timestamps) throws SQLException {
        String SQL = "INSERT INTO Users (email, name, business, phone, customer_id, status, language, created_date, updated_date, updated_by, address, business_type, business_sect, country, postcode, state) "
                   + "VALUES (?, ?, ?, ?, ?, 'Active', ?, ?, ?, 'API', ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement s = c.prepareStatement(SQL)) {
            s.setString(1, parameters.get("email"));
            if (parameters.get("surname") == null) {
                s.setString(2, parameters.get("givenName"));
            } else {
                s.setString(2, parameters.get("givenName") + " " + parameters.get("surname"));
            }
            s.setString(3, parameters.get("business"));
            s.setString(4, parameters.get("phone"));
            s.setInt(5, customer_id); 
            // If the country is China then their preferred language is Chinese
            if ("CN".equals(parameters.get("country"))) {
                s.setString(6, "CN");
            } else {
                s.setString(6, "EN");
            }
            s.setTimestamp(7, timestamps.get("now"));
            s.setTimestamp(8, timestamps.get("renewalDate"));
            s.setString(9, parameters.get("address"));
            s.setString(10, parameters.get("business_type"));
            s.setString(11, parameters.get("business_sect"));
            s.setString(12, parameters.get("country"));
            s.setString(13, parameters.get("postcode"));
            s.setString(14, parameters.get("state"));
            s.executeUpdate();
        }
    }

    // Adds a new subscription to the DB
    private void newSubscription(Connection c, Map<String, String> parameters, Map<String, Timestamp> timestamps, float currentVersion, int trials) throws SQLException {
        String SQL = "INSERT INTO Subscription (email, license, start_date, end_date, trials_remaining, status, current_version) " 
                   + "VALUES (?, ?, ?, ?, ?, 'Active', ?)";
        try (PreparedStatement s = c.prepareStatement(SQL)) {
            s.setString(1, parameters.get("email"));
            s.setString(2, parameters.get("license"));
            s.setTimestamp(3, timestamps.get("now"));
            s.setObject(4, timestamps.get("renewalDate"), Types.TIMESTAMP);
            s.setObject(5, trials, Types.INTEGER);
            s.setObject(6, currentVersion, Types.FLOAT);
            s.executeUpdate();
        }
    }

    // Adds a new entry to the target service rule DB
    private void newTargetService(Connection c, int customer_id, Map<String, Timestamp> timestamps) throws SQLException {
        String SQL = "INSERT INTO V2TSLR (TSCUNO, TSSEQN, TSDESC, TSTSLA, TSTSLB, TSTSLC, TSPREF, TSUPBY, TSUPDT, TSPRST) "
                   + "VALUES (?, 0, 'DEFAULT', 98.0, 95.0, 90.0, 'Y', 'API', ?, 999999)";
        try (PreparedStatement s = c.prepareStatement(SQL)) {
            s.setInt(1, customer_id);
            s.setTimestamp(2, timestamps.get("now"));
            s.executeUpdate();
        }
    }

    @FunctionName("createUser") 
    public HttpResponseMessage run(
            @HttpTrigger(
                name = "req",
                methods = {HttpMethod.POST},
                authLevel = AuthorizationLevel.FUNCTION)
                HttpRequestMessage<Optional<String>> request,
            final ExecutionContext context) {
        context.getLogger().info("Java HTTP trigger processed a request.");

        // Parse query parameters
        Map<String, String> parameters = request.getQueryParameters();
        
        // Set database connection string
        // ! Redacted in public version ! 
        String CS = "";
                
        // Assign variables for error handling
        boolean connected = false;
        int count = 0; 
        String message = "";

        // Try to connect query 5 times
        while (count < 5 && !connected){
            connected = true;
            try (Connection c = DriverManager.getConnection(CS)) {
                try {
                    // Get license information
                    Map<String, Number> licenseInfo = getLicenseInfo(c, parameters.get("license"));
                    Integer duration = (Integer) licenseInfo.get("duration");
                    Float currentVersion = (Float) licenseInfo.get("currentVersion");
                    Integer trials = (Integer) licenseInfo.get("trials");

                    validateParameters(parameters, getAllLicenses(c));
                    Map<String, Timestamp> timestamps = getTimestamps(duration);
                    
                    // Apply SQL updates to either add a user from scratch or give them a new subscription
                    if (isUpgrade(c, parameters)) {
                        newSubscription(c, parameters, timestamps, currentVersion, trials);
                    } else {
                        int customer_id = generateCustomerNum(c);
                        newUser(c, parameters, customer_id, timestamps);
                        newSubscription(c, parameters, timestamps, currentVersion, trials);
                        newTargetService(c, customer_id, timestamps);
                    }
                } catch (SQLException e) {
                    return request.createResponseBuilder(HttpStatus.INTERNAL_SERVER_ERROR).body(makeErrorMessage(e)).build();
                } catch (ValidationException e) {
                    return request.createResponseBuilder(HttpStatus.BAD_REQUEST).body(makeErrorMessage(e)).build();
                }
            } catch (SQLException e) {
                // Issue with connecting to the DB
                connected = false;
                message = e.getMessage();
            }
            count++;
        }

        if (!connected) {
            return request.createResponseBuilder(HttpStatus.INTERNAL_SERVER_ERROR).body("Could not connect to db after 5 tries (" + message + ")").build();
        }

        // Return appropriate message upon finishing task 
        return request.createResponseBuilder(HttpStatus.OK).body("Complete").build();
    }
}