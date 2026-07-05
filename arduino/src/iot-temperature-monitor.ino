#include <UIPEthernet.h>

// --- Network Configuration ---
// MAC address for the Ethernet module (can be arbitrary, but must be unique on your local network)
byte mac[] = { 0xDE, 0xAD, 0xBE, 0xEF, 0xFE, 0xED };

EthernetClient client;

// --- Backend Configuration ---
// Replace with the local IP address of your computer running Spring Boot
IPAddress server(192, 168, 0, 128);
int port = 8080;

const char POST_ENDPOINT[] = "/readings";
const char SENSOR_TOKEN[] = "PASTE_SENSOR_TOKEN_HERE";


// --- Timer Variables ---
unsigned long last_connection_time = 0;             // Last time you connected to the server, in milliseconds
const unsigned long posting_interval = 10000;       // Delay between updates, in milliseconds (10 seconds)







void setup() {


  Serial.begin(9600);


  pinMode(A5,INPUT);

  Serial.println("Initializing ENC28J60 Ethernet...");

  // Force a static IP address in the 192.168.0.x range.
  // Make sure .50 isn't already used by another device on your network.
  IPAddress staticIP(192, 168, 0, 50);

  // Initialize Ethernet with the MAC and Static IP, bypassing DHCP entirely
  Ethernet.begin(mac, staticIP);

  Serial.print("Arduino IP Address forced to: ");
  Serial.println(Ethernet.localIP());

  delay(1000);
}





void loop() {
  // 1. Read incoming data from the server
  // It is crucial to read the response from the server, even if you do nothing with it.
  // Otherwise, the incoming buffer fills up and the ENC28J60 will freeze.
  if (client.available()) {
    char c = client.read();
    Serial.print(c); // Print the server response (e.g., HTTP 200 OK) to the Serial Monitor
  }


  // // 2. Check if 5 seconds have passed since the last request
  // // We use millis() instead of delay(5000) so the Arduino doesn't completely freeze
  // // between requests, allowing it to process the server response above.
  if( millis() - last_connection_time > posting_interval ){

    int read_value = analogRead(A5);
    double temperature_value = thermistor(read_value);
    send_post_request(temperature_value);

  }

}

void send_post_request(double value_to_send) {
  // Ensure any previous connection is closed before attempting a new one
  client.stop();

  Serial.print("\nConnecting to Spring Boot server... Sending value: ");
  Serial.println(value_to_send);

  // Attempt to connect to the backend
  if (client.connect(server, port)) {
    Serial.println("Connected!");

    // 1. Convert only the temperature to a String (uses minimal memory)
    String tempStr = String(value_to_send, 2);

    // 2. Manually calculate the exact Content-Length mathematically
    // Length of "sensorToken=" is 12
    // Length of "&celsiusValue=" is 14
    int contentLength = 12 + strlen(SENSOR_TOKEN) + 14 + tempStr.length();

    // --- Send standard HTTP POST request headers ---
    client.print("POST ");
    client.print(POST_ENDPOINT);
    client.println(" HTTP/1.1");

    client.print("Host: ");
    client.println(server);

    client.println("Content-Type: application/x-www-form-urlencoded");
    client.println("Connection: close");

    client.print("Content-Length: ");
    client.println(contentLength);

    // An empty line marks the end of the headers and the beginning of the body
    client.println();

    // --- Send the payload piece-by-piece ---
    // Use print() instead of println() so we don't accidentally add \r\n to the end of the body!
    client.print("sensorToken=");
    client.print(SENSOR_TOKEN);
    client.print("&celsiusValue=");
    client.print(tempStr);

    // Record the time this request was sent to reset the timer
    last_connection_time = millis();

    Serial.println("POST request dispatched.");
  } else {
    Serial.println("Connection failed. Is the Spring Boot server running and accessible?");
  }
}






double thermistor(int read_value) {
    // Prevent division by zero if the analog pin shorts to ground
    if (read_value == 0) {
        return -999.0; // Return an obvious error value your backend can recognize
    }

    // 1. Calculate the resistance of the NTC (Assumes NTC is tied to 5V)
    double logR = log(11000.0 * ((1024.0 / read_value) - 1));

    // 2. Apply Steinhart-Hart coefficients
    double temperature_value = 1.0 / (0.001129148 + (0.000234125 + (0.0000000876741 * logR * logR)) * logR);

    // 3. Convert from Kelvin to Celsius
    temperature_value = temperature_value - 273.15;

    return temperature_value;
}








