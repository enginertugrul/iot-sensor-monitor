
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Scanner;

public final class ArduinoPretender {

    private static final String DEFAULT_BACKEND_URL = "http://localhost:8080";

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private ArduinoPretender() {
    }

    public static void main(String[] args) {
        try (Scanner scanner = new Scanner(System.in)) {
            System.out.println("=== Arduino Pretender ===");
            System.out.println("Simulates an Arduino sending a sensor reading to the backend.");
            System.out.println();


            String sensorToken = readRequired(scanner, "Sensor token: ");
            System.out.println("Sensor token saved for this session.");
            System.out.println("---------------------------\n");

            while(true){
                SensorType sensorType = readSensorType(scanner);

                if (sensorType != SensorType.TEMPERATURE) {
                    System.out.printf(
                            "%n%s is not supported by the current backend.%n" +
                                    "Its only ingestion endpoint is POST /readings with celsiusValue.%n" +
                                    "Create/use a TEMPERATURE sensor token to send a reading.%n",
                            sensorType.displayName
                    );
                    return;
                }

                double celsiusValue = readFiniteDouble(scanner, "Temperature in Celsius: ");
                URI readingsUri = createReadingsUri(DEFAULT_BACKEND_URL);

                sendTemperatureReading(readingsUri, sensorToken, celsiusValue);

            }

        }
    }

    private static void sendTemperatureReading(URI readingsUri,
                                               String sensorToken,
                                               double celsiusValue) {

        String formBody = "sensorToken=" + encode(sensorToken)
                + "&celsiusValue=" + encode(Double.toString(celsiusValue));

        HttpRequest request = HttpRequest.newBuilder(readingsUri)
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                .POST(HttpRequest.BodyPublishers.ofString(formBody))
                .build();

        try {
            HttpResponse<String> response = HTTP_CLIENT.send(
                    request,
                    HttpResponse.BodyHandlers.ofString()
            );

            int statusCode = response.statusCode();

            if (statusCode >= 200 && statusCode < 300) {
                System.out.printf(
                        "%nSuccess: temperature reading %.2f °C was accepted (HTTP %d).%n",
                        celsiusValue,
                        statusCode
                );
                return;
            }

            System.err.printf(
                    "%nBackend rejected the reading (HTTP %d).%n",
                    statusCode
            );

            if (!response.body().isBlank()) {
                System.err.println("Response: " + response.body());
            }
        } catch (IOException exception) {
            System.err.println(
                    "\nConnection failed. Check that Spring Boot is running and the backend URL is reachable."
            );
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            System.err.println("\nRequest was interrupted.");
        }
    }

    private static SensorType readSensorType(Scanner scanner) {
        while (true) {
            System.out.println("\nSensor type:");
            System.out.println("1 - Temperature");
            System.out.println("2 - Humidity");
            System.out.println("3 - Motion");
            System.out.print("Select [1-3]: ");

            switch (scanner.nextLine().trim()) {
                case "1" -> {
                    return SensorType.TEMPERATURE;
                }
                case "2" -> {
                    return SensorType.HUMIDITY;
                }
                case "3" -> {
                    return SensorType.MOTION;
                }
                default -> System.out.println("Please enter 1, 2, or 3.");
            }
        }
    }

    private static double readFiniteDouble(Scanner scanner, String prompt) {
        while (true) {
            String rawValue = readRequired(scanner, prompt).replace(',', '.');

            try {
                double value = Double.parseDouble(rawValue);

                if (Double.isFinite(value)) {
                    return value;
                }
            } catch (NumberFormatException ignored) {
                // Show the validation message below.
            }

            System.out.println("Please enter a finite numeric value, such as 22.5.");
        }
    }

    private static String readRequired(Scanner scanner, String prompt) {
        while (true) {
            System.out.print(prompt);
            String value = scanner.nextLine().trim();

            if (!value.isEmpty()) {
                return value;
            }

            System.out.println("This value is required.");
        }
    }


    private static URI createReadingsUri(String backendUrl) {
        String normalizedUrl = backendUrl.replaceAll("/+$", "");
        return URI.create(normalizedUrl + "/readings");
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private enum SensorType {
        TEMPERATURE("Temperature"),
        HUMIDITY("Humidity"),
        MOTION("Motion");

        private final String displayName;

        SensorType(String displayName) {
            this.displayName = displayName;
        }
    }
}