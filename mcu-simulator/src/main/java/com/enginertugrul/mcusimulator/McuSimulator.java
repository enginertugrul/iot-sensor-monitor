package com.enginertugrul.mcusimulator;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Scanner;

public final class McuSimulator {

    private static final String DEFAULT_BACKEND_URL = "http://localhost:8080";
    private static final Duration CONNECTION_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);
    private static final int MAX_DISPLAYED_RESPONSE_BODY_LENGTH = 1_000;

    private final Scanner scanner;
    private final HttpClient httpClient;
    private final URI backendBaseUri;

    // Tokens are retained only for this application session.
    private final Map<SensorType,String> sensorTokens = new EnumMap<>(SensorType.class);

    private McuSimulator(Scanner scanner,HttpClient httpClient,URI backendBaseUri) {
        this.scanner = scanner;
        this.httpClient = httpClient;
        this.backendBaseUri = backendBaseUri;
    }

    public static void main(String[] args) {
        URI backendBaseUri;

        try {
            backendBaseUri = resolveBackendBaseUri(args);
        } catch (IllegalArgumentException exception) {
            System.err.println("Invalid backend URL: " + exception.getMessage());
            return;
        }

        try (HttpClient httpClient = HttpClient.newBuilder().connectTimeout(CONNECTION_TIMEOUT).build(); Scanner scanner = new Scanner(System.in)) {
            new McuSimulator(scanner,httpClient,backendBaseUri).run();
        }
    }

    private void run() {
        printWelcomeMessage();

        while (true) {
            printMainMenu();
            Optional<String> input = readLine("Select an option: ");

            if (input.isEmpty()) {
                break;
            }

            String selection = normalizeCommand(input.get());

            if (isMenuExitCommand(selection)) {
                break;
            }

            Optional<SensorType> selectedSensor = SensorType.fromMenuChoice(selection);

            if (selectedSensor.isEmpty()) {
                System.out.println("Unknown option. Select a listed sensor or enter 0 to exit.");
                continue;
            }

            if (openSensorMenu(selectedSensor.get()) == Navigation.EXIT) {
                break;
            }
        }

        printExitMessage();
    }

    private Navigation openSensorMenu(SensorType sensorType) {
        while (true) {
            printSensorMenu(sensorType);
            Optional<String> input = readLine("Select an option: ");

            if (input.isEmpty()) {
                return Navigation.EXIT;
            }

            String selection = normalizeCommand(input.get());

            switch (selection) {
                case "1" -> {
                    String sensorToken = sensorTokens.get(sensorType);

                    if (sensorToken == null) {
                        System.out.printf("%nNo token is configured for the %s sensor.%n",sensorType.displayName);
                        System.out.println("Select option 2 to configure its token.");
                        continue;
                    }

                    if (promptAndSendReading(sensorType,sensorToken) == Navigation.EXIT) {
                        return Navigation.EXIT;
                    }
                }
                case "2" -> {
                    if (configureSensorToken(sensorType) == Navigation.EXIT) {
                        return Navigation.EXIT;
                    }
                }
                case "3" -> clearSensorToken(sensorType);
                case "0", "b", "back" -> {
                    return Navigation.CONTINUE;
                }
                case "q", "quit", "exit" -> {
                    return Navigation.EXIT;
                }
                default -> System.out.println("Unknown option. Select 1, 2, 3, or 0.");
            }
        }
    }

    private Navigation configureSensorToken(SensorType sensorType) {
        System.out.printf("%nConfigure token for the %s sensor.%n",sensorType.displayName);
        System.out.println("Leave the value blank or enter 'b' to cancel.");
        System.out.println("Enter 'q' to terminate the application.");

        Optional<String> input = readLine("Sensor token: ");

        if (input.isEmpty()) {
            return Navigation.EXIT;
        }

        String sensorToken = input.get().trim();

        if (sensorToken.isEmpty()) {
            System.out.println("Token configuration cancelled.");
            return Navigation.CONTINUE;
        }

        String command = normalizeCommand(sensorToken);

        if (isExitCommand(command)) {
            return Navigation.EXIT;
        }

        if (isBackCommand(command)) {
            System.out.println("Token configuration cancelled.");
            return Navigation.CONTINUE;
        }

        sensorTokens.put(sensorType,sensorToken);
        System.out.printf("%s sensor token saved for this session.%n",sensorType.displayName);
        return Navigation.CONTINUE;
    }

    private void clearSensorToken(SensorType sensorType) {
        if (sensorTokens.remove(sensorType) == null) {
            System.out.printf("No token is configured for the %s sensor.%n",sensorType.displayName);
            return;
        }

        System.out.printf("%s sensor token cleared.%n",sensorType.displayName);
    }

    private Navigation promptAndSendReading(SensorType sensorType,String sensorToken) {
        while (true) {
            System.out.printf("%n%s%n",sensorType.readingInstructions);
            System.out.println("Leave the value blank or enter 'b' to return.");
            System.out.println("Enter 'q' to terminate the application.");

            Optional<String> input = readLine("Value: ");

            if (input.isEmpty()) {
                return Navigation.EXIT;
            }

            String rawValue = input.get().trim();

            if (rawValue.isEmpty()) {
                return Navigation.CONTINUE;
            }

            String command = normalizeCommand(rawValue);

            if (isExitCommand(command)) {
                return Navigation.EXIT;
            }

            if (isBackCommand(command)) {
                return Navigation.CONTINUE;
            }

            try {
                return sendReading(sensorType,sensorToken,sensorType.parse(rawValue));
            } catch (InvalidConsoleInputException exception) {
                System.out.println(exception.getMessage());
            }
        }
    }

    private Navigation sendReading(SensorType sensorType,String sensorToken,SensorValue sensorValue) {
        URI endpointUri = createEndpointUri(sensorType);

        // Capture the simulated sampling time before starting network I/O.
        long recordedAt = Instant.now().toEpochMilli();
        String formBody = buildFormBody(sensorToken,sensorType.requestFieldName,sensorValue.wireValue(),recordedAt);

        HttpRequest request = HttpRequest.newBuilder(endpointUri)
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type","application/x-www-form-urlencoded; charset=UTF-8")
                .header("Accept","application/json")
                .POST(HttpRequest.BodyPublishers.ofString(formBody,StandardCharsets.UTF_8))
                .build();

        try {
            HttpResponse<String> response = httpClient.send(request,HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            printResponse(sensorType,sensorValue,response);
            return Navigation.CONTINUE;
        } catch (IOException exception) {
            System.err.printf("%nConnection failed for the %s sensor.%n",sensorType.displayName);
            System.err.println("Verify that the backend is running and reachable at " + backendBaseUri);
            return Navigation.CONTINUE;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            System.err.println("\nThe request was interrupted. MCU Simulator will terminate.");
            return Navigation.EXIT;
        }
    }

    private void printResponse(SensorType sensorType,SensorValue sensorValue,HttpResponse<String> response) {
        int statusCode = response.statusCode();

        if (statusCode >= 200 && statusCode < 300) {
            System.out.printf("%nSuccess: %s reading %s was accepted (HTTP %d).%n",sensorType.displayName,sensorValue.displayValue(),statusCode);
            return;
        }

        System.err.printf("%nBackend rejected the %s reading (HTTP %d).%n",sensorType.displayName.toLowerCase(Locale.ROOT),statusCode);

        switch (statusCode) {
            case 400 -> System.err.println("The reading value or request format is invalid.");
            case 401 -> System.err.println("The configured sensor token is invalid.");
            case 409 -> System.err.println("The registered sensor is inactive.");
            default -> System.err.println("The backend returned an unexpected response.");
        }

        printResponseBody(response.body());
    }

    private void printResponseBody(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) {
            return;
        }

        String normalizedBody = responseBody.strip();

        if (normalizedBody.length() > MAX_DISPLAYED_RESPONSE_BODY_LENGTH) {
            normalizedBody = normalizedBody.substring(0,MAX_DISPLAYED_RESPONSE_BODY_LENGTH) + "...";
        }

        System.err.println("Response: " + normalizedBody);
    }

    private URI createEndpointUri(SensorType sensorType) {
        return URI.create(backendBaseUri.toString() + sensorType.endpointPath);
    }

    private static String buildFormBody(String sensorToken,String requestFieldName,String requestValue,long recordedAt) {
        return "sensorToken=" + encode(sensorToken)
                + "&" + encode(requestFieldName)
                + "=" + encode(requestValue)
                + "&recordedAt=" + encode(Long.toString(recordedAt));
    }

    private Optional<String> readLine(String prompt) {
        System.out.print(prompt);
        System.out.flush();

        if (!scanner.hasNextLine()) {
            return Optional.empty();
        }

        return Optional.of(scanner.nextLine());
    }

    private void printWelcomeMessage() {
        System.out.println("=== MCU Simulator ===");
        System.out.println("Simulates temperature, humidity, and motion sensor readings.");
        System.out.println("Backend: " + backendBaseUri);
        System.out.println("Each sensor type requires its own registered sensor token.");
    }

    private void printMainMenu() {
        System.out.println("\n--- Main menu ---");

        for (SensorType sensorType : SensorType.values()) {
            System.out.printf("%s - %s sensor%n",sensorType.menuChoice,sensorType.displayName);
        }

        System.out.println("0 - Exit");
    }

    private void printSensorMenu(SensorType sensorType) {
        boolean tokenConfigured = sensorTokens.containsKey(sensorType);

        System.out.printf("%n--- %s sensor ---%n",sensorType.displayName);
        System.out.println("Token: " + (tokenConfigured ? "configured" : "not configured"));
        System.out.println("1 - Send a reading");
        System.out.println(tokenConfigured ? "2 - Replace sensor token" : "2 - Configure sensor token");
        System.out.println("3 - Clear sensor token");
        System.out.println("0 - Back to main menu");
        System.out.println("q - Exit");
    }

    private static void printExitMessage() {
        System.out.println("\nMCU Simulator stopped.");
    }

    private static URI resolveBackendBaseUri(String[] args) {
        if (args.length > 1) {
            throw new IllegalArgumentException("Expected zero arguments or one backend URL argument");
        }

        String configuredUrl = args.length == 0 || args[0].isBlank() ? DEFAULT_BACKEND_URL : args[0];
        String normalizedUrl = removeTrailingSlashes(configuredUrl.trim());
        URI uri;

        try {
            uri = URI.create(normalizedUrl);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Backend URL is malformed",exception);
        }

        String scheme = uri.getScheme();

        if (scheme == null || (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https"))) {
            throw new IllegalArgumentException("Backend URL must use HTTP or HTTPS");
        }

        if (uri.getHost() == null) {
            throw new IllegalArgumentException("Backend URL must contain a valid host");
        }

        if (uri.getRawUserInfo() != null) {
            throw new IllegalArgumentException("Backend URL must not contain user information");
        }

        int port = uri.getPort();

        if (port != -1 && (port < 1 || port > 65_535)) {
            throw new IllegalArgumentException("Backend URL port must be between 1 and 65535");
        }

        if (uri.getRawQuery() != null || uri.getRawFragment() != null) {
            throw new IllegalArgumentException("Backend URL must not contain a query or fragment");
        }

        return uri;
    }

    private static String removeTrailingSlashes(String value) {
        int endIndex = value.length();

        while (endIndex > 0 && value.charAt(endIndex - 1) == '/') {
            endIndex--;
        }

        return value.substring(0,endIndex);
    }

    private static String normalizeCommand(String input) {
        return input.trim().toLowerCase(Locale.ROOT);
    }

    private static boolean isBackCommand(String command) {
        return command.equals("b") || command.equals("back");
    }

    private static boolean isMenuExitCommand(String command) {
        return command.equals("0") || isExitCommand(command);
    }

    private static boolean isExitCommand(String command) {
        return command.equals("q") || command.equals("quit") || command.equals("exit");
    }

    private static String encode(String value) {
        return URLEncoder.encode(value,StandardCharsets.UTF_8);
    }

    private static SensorValue parseTemperature(String rawValue) {
        double value = parseFiniteDouble(rawValue);

        if (value < -273.15) {
            throw new InvalidConsoleInputException("Temperature cannot be below absolute zero (-273.15 °C).");
        }

        return new SensorValue(Double.toString(value),String.format(Locale.ROOT,"%.2f °C",value));
    }

    private static SensorValue parseHumidity(String rawValue) {
        double value = parseFiniteDouble(rawValue);

        if (value < 0.0 || value > 100.0) {
            throw new InvalidConsoleInputException("Humidity must be between 0 and 100 percent.");
        }

        return new SensorValue(Double.toString(value),String.format(Locale.ROOT,"%.2f %% RH",value));
    }

    private static SensorValue parseMotion(String rawValue) {
        return switch (normalizeCommand(rawValue)) {
            case "true", "t", "yes", "y", "1", "detected" -> new SensorValue("true","motion detected");
            case "false", "f", "no", "n", "0", "clear" -> new SensorValue("false","motion clear");
            default -> throw new InvalidConsoleInputException("Enter true/false, yes/no, 1/0, detected, or clear.");
        };
    }

    private static double parseFiniteDouble(String rawValue) {
        try {
            double value = Double.parseDouble(rawValue.replace(',','.'));

            if (Double.isFinite(value)) {
                return value;
            }
        } catch (NumberFormatException ignored) {
            // Invalid numeric input is reported consistently below.
        }

        throw new InvalidConsoleInputException("Enter a finite numeric value, such as 22.5.");
    }

    @FunctionalInterface
    private interface ReadingParser {

        SensorValue parse(String rawValue);
    }

    private record SensorValue(String wireValue,String displayValue) {
    }

    private enum Navigation {
        CONTINUE,
        EXIT
    }

    private enum SensorType {

        TEMPERATURE("1","Temperature","/readings/temperature","celsiusValue","Enter temperature in Celsius.",McuSimulator::parseTemperature),
        HUMIDITY("2","Humidity","/readings/humidity","humidityPercentage","Enter relative humidity as a percentage from 0 to 100.",McuSimulator::parseHumidity),
        MOTION("3","Motion","/readings/motion","motionDetected","Enter whether motion is detected.",McuSimulator::parseMotion);

        private final String menuChoice;
        private final String displayName;
        private final String endpointPath;
        private final String requestFieldName;
        private final String readingInstructions;
        private final ReadingParser readingParser;

        SensorType(String menuChoice,String displayName,String endpointPath,String requestFieldName,String readingInstructions,ReadingParser readingParser) {
            this.menuChoice = menuChoice;
            this.displayName = displayName;
            this.endpointPath = endpointPath;
            this.requestFieldName = requestFieldName;
            this.readingInstructions = readingInstructions;
            this.readingParser = readingParser;
        }

        private SensorValue parse(String rawValue) {
            return readingParser.parse(rawValue);
        }

        private static Optional<SensorType> fromMenuChoice(String menuChoice) {
            for (SensorType sensorType : values()) {
                if (sensorType.menuChoice.equals(menuChoice)) {
                    return Optional.of(sensorType);
                }
            }

            return Optional.empty();
        }
    }

    private static final class InvalidConsoleInputException extends IllegalArgumentException {

        private InvalidConsoleInputException(String message) {
            super(message);
        }
    }
}