module com.example.coderunlauncher {
    requires javafx.controls;
    requires javafx.fxml;
    requires java.sql; // Enables JDBC functionality
    requires mysql.connector.j;
    requires bcrypt;
    requires jakarta.mail;
    requires java.net.http; // Open Trivia DB requests
    requires com.google.gson;

    // Added javafx.base so TableView can read User class data
    opens com.example.coderunlauncher to javafx.fxml, javafx.base;
    exports com.example.coderunlauncher;



}