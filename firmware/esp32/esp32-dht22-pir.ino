#include <Arduino.h>
#include <WiFi.h>
#include <HTTPClient.h>
#include <DHT.h>
#include <time.h>
#include <sys/time.h>
#include "esp_sntp.h"

/*
 * ESP32 environmental monitoring client
 *
 * Hardware:
 *   DHT22 VCC  -> ESP32 3V3
 *   DHT22 DATA -> GPIO 4
 *   DHT22 GND  -> ESP32 GND
 *   PIR OUT    -> GPIO 27
 *   PIR VCC    -> ESP32 5V/VIN
 *   PIR GND    -> ESP32 GND
 *
 * PIR configuration:
 *   Trigger jumper -> L
 *   Time delay     -> Near minimum
 *   Sensitivity    -> Low to medium initially
 *
 * Network architecture:
 *   One DHT22 acquisition
 *     -> temperature POST
 *     -> humidity POST
 *
 *   Motion events are collected during a 67-second window
 *     -> motion POST
 *
 * All HTTP requests are serialized. A TCP connection may be reused by
 * immediately consecutive requests in one loop pass, but it is closed before
 * the ESP32 returns to its idle monitoring loop.
 */

// -----------------------------------------------------------------------------
// Configuration
// -----------------------------------------------------------------------------

constexpr char WIFI_SSID[] =
    "YOUR_WIFI_SSID";

constexpr char WIFI_PASSWORD[] =
    "YOUR_WIFI_PASSWORD";

constexpr char BACKEND_BASE_URL[] =
    "http://192.168.0.128:8080";


/*
 * SNTP sets the ESP32 system clock to UTC.
 *
 * Three servers provide fallback if one server cannot be reached.
 * UTC0 prevents local timezone or daylight-saving rules from affecting any
 * human-readable time operations. Unix epoch values are UTC regardless.
 */
constexpr char NTP_TIME_ZONE[] = "UTC0";
constexpr char NTP_SERVER_1[] = "pool.ntp.org";
constexpr char NTP_SERVER_2[] = "time.nist.gov";
constexpr char NTP_SERVER_3[] = "time.google.com";



constexpr char TEMPERATURE_SENSOR_TOKEN[] =
    "PASTE_SENSOR_TOKEN_HERE";

constexpr char HUMIDITY_SENSOR_TOKEN[] =
    "PASTE_SENSOR_TOKEN_HERE";

constexpr char MOTION_SENSOR_TOKEN[] =
    "PASTE_SENSOR_TOKEN_HERE";

// -----------------------------------------------------------------------------
// Hardware and timing
// -----------------------------------------------------------------------------

constexpr uint8_t DHT_PIN = 4;
constexpr uint8_t PIR_PIN = 27;

/*
 * Temperature and humidity share one physical DHT22 acquisition, so they use
 * the same interval.
 *
 * 17 and 67 are prime numbers within the requested ranges. Their primality is
 * not required for correctness because HTTP requests are already serialized,
 * but these longer intervals also reduce TCP connection churn.
 */
constexpr uint32_t DHT_POST_INTERVAL_MS = 17000UL;
constexpr uint32_t MOTION_POST_INTERVAL_MS = 67000UL;

constexpr uint32_t PIR_WARMUP_MS = 60000UL;

/*
 * A short separation is used between successful temperature and humidity
 * requests. A longer separation is used after failure so the backend and
 * ESP32 networking stack have time to recover.
 */
constexpr uint32_t HTTP_SUCCESS_PAUSE_MS = 100UL;
constexpr uint32_t HTTP_ERROR_RECOVERY_MS = 1500UL;

constexpr int32_t HTTP_CONNECT_TIMEOUT_MS = 3000;
constexpr uint16_t HTTP_RESPONSE_TIMEOUT_MS = 5000;

constexpr uint32_t WIFI_RECONNECT_TIMEOUT_MS = 5000UL;

/*
 * The locally installed ESP32 core defaults to a three-hour SNTP interval.
 * Set one hour explicitly so the system clock is corrected hourly.
 */
constexpr uint32_t NTP_SYNC_INTERVAL_MS = 60UL * 60UL * 1000UL;
constexpr uint32_t INITIAL_NTP_SYNC_TIMEOUT_MS = 15000UL;

/*
 * Reject an unset 1970-era system clock even if gettimeofday() itself succeeds.
 * This corresponds to 2024-01-01T00:00:00Z.
 */
constexpr int64_t EARLIEST_REASONABLE_UNIX_TIME_SECONDS = 1704067200LL;


constexpr uint8_t SAFE_RETRIES_PER_BATCH = 1;
constexpr uint32_t HTTP_RETRY_DELAY_MS = 1000UL;

/*
 * Internal return values for failures that happen outside HTTPClient itself.
 */
constexpr int HTTP_LOCAL_WIFI_UNAVAILABLE = -1000;
constexpr int HTTP_LOCAL_INITIALIZATION_FAILED = -1001;

static_assert(
    DHT_POST_INTERVAL_MS >= 2000UL,
    "The DHT22 requires at least two seconds between samples."
);

DHT dht(DHT_PIN, DHT22);

/*
 * This becomes true only after this boot receives a successful NTP update.
 * It prevents an old or unset system-clock value from entering the database.
 */
volatile bool ntpHasSynchronized = false;

// -----------------------------------------------------------------------------
// Scheduling
// -----------------------------------------------------------------------------

uint32_t nextDhtPostAt = 0;
uint32_t pirWarmupFinishesAt = 0;
uint32_t nextMotionPostAt = 0;

/*
 * This comparison remains correct when millis() eventually wraps around.
 * All configured intervals must remain below approximately 24.8 days.
 */
bool deadlineReached(
    uint32_t now,
    uint32_t deadline
) {
  return static_cast<int32_t>(now - deadline) >= 0;
}

// -----------------------------------------------------------------------------
// PIR event capture
// -----------------------------------------------------------------------------

enum class PirState : uint8_t {
  WARMING_UP,
  WAITING_FOR_LOW,
  MONITORING
};

PirState pirState = PirState::WARMING_UP;

/*
 * Only the interrupt handler writes pirRisingEdgeCount. The main loop only
 * reads snapshots of it.
 *
 * A monotonic counter avoids a snapshot-and-clear race:
 *
 *   - lastReportedPirRisingEdgeCount is the last successfully acknowledged
 *     snapshot.
 *   - If the current counter differs, at least one new activation is pending.
 *   - Events arriving during HTTP increase the counter beyond the current
 *     snapshot and therefore remain pending for the next report.
 *
 * A 32-bit aligned read/write is atomic on the ESP32. Counter wrap would
 * require billions of PIR activations and is not a practical concern here.
 */
volatile uint32_t pirRisingEdgeCount = 0;

uint32_t lastReportedPirRisingEdgeCount = 0;
uint32_t lastLoggedPirRisingEdgeCount = 0;

bool lastPirPinHigh = false;

void ARDUINO_ISR_ATTR recordPirRisingEdge() {
  ++pirRisingEdgeCount;
}

void servicePir() {
  const uint32_t now = millis();

  if (pirState == PirState::WARMING_UP) {
    if (!deadlineReached(now, pirWarmupFinishesAt)) {
      return;
    }

    pirState = PirState::WAITING_FOR_LOW;

    Serial.println("[PIR] Warm-up completed.");
    Serial.println("[PIR] Waiting for GPIO27 to become LOW before arming.");
  }

  if (pirState == PirState::WAITING_FOR_LOW) {
    if (digitalRead(PIR_PIN) == HIGH) {
      return;
    }

    /*
     * Calibration activity is deliberately discarded. The first reporting
     * window begins only after the sensor has produced a stable LOW.
     */
    pirRisingEdgeCount = 0;
    lastReportedPirRisingEdgeCount = 0;
    lastLoggedPirRisingEdgeCount = 0;
    lastPirPinHigh = false;

    attachInterrupt(
        digitalPinToInterrupt(PIR_PIN),
        recordPirRisingEdge,
        RISING
    );

    pirState = PirState::MONITORING;
    nextMotionPostAt = now + MOTION_POST_INTERVAL_MS;

    Serial.println("[PIR] Sensor armed.");
    Serial.println("[PIR] First motion reporting window started.");

    return;
  }

  const bool pinHigh = (digitalRead(PIR_PIN) == HIGH);

  if (pinHigh != lastPirPinHigh) {
    Serial.printf("[PIR] Raw GPIO changed to %s\n", pinHigh ? "HIGH" : "LOW"
    );

    lastPirPinHigh = pinHigh;
  }

  const uint32_t currentEdgeCount = pirRisingEdgeCount;

  if (currentEdgeCount != lastLoggedPirRisingEdgeCount) {
    const uint32_t newlyCapturedEvents = currentEdgeCount - lastLoggedPirRisingEdgeCount;

    Serial.printf(
        "[PIR] Captured %lu new motion activation(s).\n",
        static_cast<unsigned long>(newlyCapturedEvents)
    );

    lastLoggedPirRisingEdgeCount =
        currentEdgeCount;
  }
}

/*
 * Retry and recovery waits continue servicing the visible PIR state.
 *
 * Rising edges themselves are captured by the interrupt even while an HTTP
 * request is blocking.
 */
void waitWhileServicingPir(uint32_t durationMs) {
  const uint32_t startedAt = millis();

  while ( static_cast<uint32_t>( millis() - startedAt) < durationMs ) {
    servicePir();
    delay(10);
  }
}

// -----------------------------------------------------------------------------
// HTTP session
// -----------------------------------------------------------------------------

WiFiClient backendConnection;
HTTPClient backendRequest;

bool backendRequestInitialized = false;

/*
 * These values apply only to the current loop pass. A single safe reconnect
 * retry is shared by temperature, humidity, and motion. This prevents an
 * unavailable backend from causing six rapid connection attempts.
 */
bool remainingHttpPostsBlocked = false;
uint8_t safeRetriesUsedThisBatch = 0;

bool initializeBackendSession() {
  if (backendRequestInitialized) {
    return true;
  }

  String initialUrl = BACKEND_BASE_URL;
  initialUrl += "/readings/temperature";

  if (!backendRequest.begin(backendConnection, initialUrl)) {
    Serial.println("[HTTP] Could not initialize the backend URL.");

    return false;
  }

  backendRequest.setConnectTimeout(HTTP_CONNECT_TIMEOUT_MS);

  backendRequest.setTimeout(HTTP_RESPONSE_TIMEOUT_MS);

  backendRequest.setReuse(true);
  backendRequest.setUserAgent("ESP32-IoT-Monitor/2");

  backendRequestInitialized = true;
  return true;
}

void resetBackendSession() {
  /*
   * stop() is called before end() so the socket is actually closed even when
   * HTTP keep-alive says it could be reused.
   */
  backendConnection.stop();

  if (backendRequestInitialized) {
    backendRequest.end();
  }

  backendRequestInitialized = false;
}

// -----------------------------------------------------------------------------
// Wi-Fi
// -----------------------------------------------------------------------------

void connectToWiFi() {
  WiFi.persistent(false);
  WiFi.mode(WIFI_STA);
  WiFi.setAutoReconnect(true);

  /*
   * Disabling modem sleep improves local-network response consistency.
   * The tradeoff is slightly higher ESP32 power consumption.
   */
  WiFi.setSleep(false);

  WiFi.begin(WIFI_SSID, WIFI_PASSWORD);

  Serial.print("[Wi-Fi] Connecting");

  while (WiFi.status() != WL_CONNECTED) {
    servicePir();
    delay(500);
    Serial.print('.');
  }

  Serial.print("\n[Wi-Fi] Connected. ESP32 IP address: ");

  Serial.println(WiFi.localIP());
}

bool ensureWiFiConnected() {
  if (WiFi.status() == WL_CONNECTED) {
    return true;
  }

  resetBackendSession();

  Serial.println("[Wi-Fi] Reconnecting...");
  WiFi.reconnect();

  const uint32_t startedAt = millis();

  while (
      WiFi.status() != WL_CONNECTED &&
      static_cast<uint32_t>(
          millis() - startedAt
      ) < WIFI_RECONNECT_TIMEOUT_MS
  ) {
    servicePir();
    delay(100);
  }

  if (WiFi.status() == WL_CONNECTED) {
    Serial.print("[Wi-Fi] Reconnected. ESP32 IP address: ");

    Serial.println(WiFi.localIP());
    return true;
  }

  Serial.println("[Wi-Fi] Reconnection timed out.");

  return false;
}










// -----------------------------------------------------------------------------
// UTC system time
// -----------------------------------------------------------------------------





/*
 * ESP32 invokes this callback after its system clock has been adjusted from an
 * NTP response. Keep the callback short because it runs outside the main loop.
 */
void onNtpSynchronization(struct timeval *receivedTime) {
  (void)receivedTime;
  ntpHasSynchronized = true;
}



void startNetworkTimeSynchronization() {
  /*
   * Register the callback before SNTP starts so the initial synchronization
   * cannot be missed.
   */
  esp_sntp_set_time_sync_notification_cb(onNtpSynchronization);

  /*
   * Set the interval before configTzTime() starts the SNTP service. The system
   * clock will then refresh hourly without an NTP request for every reading.
   */
  esp_sntp_set_sync_interval(NTP_SYNC_INTERVAL_MS);
  configTzTime(NTP_TIME_ZONE,NTP_SERVER_1,NTP_SERVER_2,NTP_SERVER_3);

  Serial.println("[NTP] Background synchronization started with a one-hour interval.");
}




/*
 * Capture the current UTC instant as Unix epoch milliseconds.
 *
 * This calculation is the ESP32 equivalent of:
 *
 *   Instant.now().toEpochMilli()
 *
 * The multiplication is performed after conversion to 64 bits because current
 * epoch milliseconds do not fit in a 32-bit long.
 */
bool readCurrentEpochMilliseconds(int64_t &epochMilliseconds) {
  if (!ntpHasSynchronized) {
    return false;
  }

  struct timeval currentTime {};

  if (gettimeofday(&currentTime,nullptr) != 0) {
    return false;
  }

  const int64_t epochSeconds =
      static_cast<int64_t>(currentTime.tv_sec);

  if (epochSeconds < EARLIEST_REASONABLE_UNIX_TIME_SECONDS) {
    return false;
  }

  epochMilliseconds =
      epochSeconds * 1000LL +
      static_cast<int64_t>(currentTime.tv_usec) / 1000LL;

  return true;
}





void waitForInitialNetworkTime() {
  const uint32_t startedAt = millis();

  /*
   * Wait only for a bounded period. PIR servicing continues during the wait,
   * and the background SNTP client remains active if the wait times out.
   */
  while (
      !ntpHasSynchronized &&
      static_cast<uint32_t>(millis() - startedAt) <
          INITIAL_NTP_SYNC_TIMEOUT_MS ) {
    servicePir();
    delay(10);
  }

  int64_t initialEpochMilliseconds = 0;

  if (readCurrentEpochMilliseconds(initialEpochMilliseconds)) {
    Serial.print("[NTP] Initial UTC time is ready at epoch ms ");
    Serial.println(static_cast<long long>(initialEpochMilliseconds));
    return;
  }

  /*
   * Scheduled reading functions will keep suppressing POSTs until the callback
   * confirms a later successful synchronization.
   */
  Serial.println("[NTP] Initial synchronization timed out; timestamped POSTs remain paused.");
}









// -----------------------------------------------------------------------------
// Form URL encoding
// -----------------------------------------------------------------------------

String formUrlEncode(const char *plainText) {

  static constexpr char HEX_DIGITS[] = "0123456789ABCDEF";

  String encoded;

  while (*plainText != '\0') {
    const uint8_t character = static_cast<uint8_t>(*plainText++);

    const bool isUnreserved =
        (character >= 'a' && character <= 'z') ||
        (character >= 'A' && character <= 'Z') ||
        (character >= '0' && character <= '9') ||
        character == '-' ||
        character == '_' ||
        character == '.' ||
        character == '~';

    if (isUnreserved) {
      encoded += static_cast<char>(character);
    } else if (character == ' ') {
      encoded += '+';
    } else {
      encoded += '%';
      encoded += HEX_DIGITS[character >> 4];
      encoded += HEX_DIGITS[character & 0x0F];
    }
  }

  return encoded;
}

// -----------------------------------------------------------------------------
// HTTP POST operations
// -----------------------------------------------------------------------------

bool isSafelyRetryable(int statusCode) {
  /*
   * These errors occur before a complete form body should have been accepted.
   *
   * Read timeout, connection lost, payload failure, and "not connected" are
   * deliberately excluded because the backend may already have committed the
   * reading.
   */
  return
      statusCode == HTTP_LOCAL_WIFI_UNAVAILABLE ||
      statusCode == HTTPC_ERROR_CONNECTION_REFUSED ||
      statusCode == HTTPC_ERROR_SEND_HEADER_FAILED;
}

int postFormOnce(const char *endpointPath, const String &formBody) {
  if (!ensureWiFiConnected()) {
    return HTTP_LOCAL_WIFI_UNAVAILABLE;
  }

  if (!initializeBackendSession()) {
    return HTTP_LOCAL_INITIALIZATION_FAILED;
  }

  /*
   * Path-only setURL() changes the endpoint while preserving the current
   * same-host connection.
   *
   * setURL() clears request headers, so they must be added again for every
   * endpoint.
   */
  if (!backendRequest.setURL(endpointPath)) {
    Serial.printf("[HTTP] Could not select endpoint %s\n", endpointPath);

    resetBackendSession();
    return HTTP_LOCAL_INITIALIZATION_FAILED;
  }

  backendRequest.addHeader("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");

  backendRequest.addHeader("Accept", "application/json");

  const uint32_t requestStartedAt = millis();

  const int statusCode = backendRequest.POST(formBody);

  /*
   * Drain every positive HTTP response before the connection is reused.
   * Successful backend responses are ResponseEntity<Void>, so their bodies
   * should normally be empty.
   */
  String responseBody;

  if (statusCode > 0) {
    responseBody = backendRequest.getString();
  }

  const uint32_t elapsedMs = static_cast<uint32_t>( millis() - requestStartedAt );

  const bool accepted =
      statusCode >= 200 &&
      statusCode < 300;

  if (accepted) {
    Serial.printf(
        "[HTTP] %s accepted with status %d in %lu ms "
        "(RSSI=%ld dBm, heap=%lu)\n",
        endpointPath,
        statusCode,
        static_cast<unsigned long>(elapsedMs),
        static_cast<long>(WiFi.RSSI()),
        static_cast<unsigned long>(ESP.getFreeHeap())
    );

    /*
     * Keep the connection open for another immediately due endpoint.
     */
    return statusCode;
  }

  if (statusCode > 0) {
    Serial.printf(
        "[HTTP] %s rejected with status %d in %lu ms\n",
        endpointPath,
        statusCode,
        static_cast<unsigned long>(
            elapsedMs
        )
    );

    if (!responseBody.isEmpty()) {
      Serial.print("[HTTP] Backend response: ");
      Serial.println(responseBody);
    }

    /*
     * Start the next endpoint with a clean session after a backend rejection.
     */
    resetBackendSession();
    return statusCode;
  }

  const String errorMessage =
      HTTPClient::errorToString(statusCode);

  Serial.printf(
      "[HTTP] %s transport error after %lu ms: %s "
      "(RSSI=%ld dBm, heap=%lu)\n",
      endpointPath,
      static_cast<unsigned long>(
          elapsedMs
      ),
      errorMessage.c_str(),
      static_cast<long>(
          WiFi.RSSI()
      ),
      static_cast<unsigned long>(
          ESP.getFreeHeap()
      )
  );

  resetBackendSession();
  return statusCode;
}

bool postForm(const char *endpointPath, const String &formBody) {

  if (remainingHttpPostsBlocked) {
    Serial.printf(
        "[HTTP] Skipped %s because this HTTP batch is unavailable.\n",
        endpointPath
    );

    return false;
  }

  for (;;) {

    const int statusCode =  postFormOnce(endpointPath, formBody);

    if (statusCode >= 200 && statusCode < 300) {
      return true;
    }

    if (statusCode == HTTP_LOCAL_INITIALIZATION_FAILED) {
      remainingHttpPostsBlocked = true;
      return false;
    }

    if (!isSafelyRetryable(statusCode)) {
      /*
       * Delivery is ambiguous. Do not retry this reading automatically.
       *
       * The next endpoint may still be attempted after the caller's recovery
       * pause using a newly initialized HTTP session.
       */
      return false;
    }

    if (safeRetriesUsedThisBatch >= SAFE_RETRIES_PER_BATCH) {
      Serial.println(
          "[HTTP] Safe reconnect retry budget exhausted; "
          "remaining posts in this batch will be skipped."
      );

      remainingHttpPostsBlocked = true;
      return false;
    }

    ++safeRetriesUsedThisBatch;

    Serial.printf(
        "[HTTP] Retrying %s in %lu ms\n",
        endpointPath,
        static_cast<unsigned long>(
            HTTP_RETRY_DELAY_MS
        )
    );

    waitWhileServicingPir(
        HTTP_RETRY_DELAY_MS
    );
  }
}





// -----------------------------------------------------------------------------
// Sensor-specific form bodies
// -----------------------------------------------------------------------------



bool postTemperatureReading(float celsiusValue,int64_t recordedAtEpochMilliseconds) {
  String formBody = "sensorToken=";
  formBody += formUrlEncode(TEMPERATURE_SENSOR_TOKEN);

  formBody += "&celsiusValue=";
  formBody += String(celsiusValue,2);

  /*
   * Decimal epoch milliseconds contain only form-safe numeric characters.
   */
  formBody += "&recordedAt=";
  formBody += String(static_cast<long long>(recordedAtEpochMilliseconds));

  return postForm("/readings/temperature",formBody);
}



bool postHumidityReading(float humidityPercentage,int64_t recordedAtEpochMilliseconds) {
  String formBody = "sensorToken=";
  formBody += formUrlEncode(HUMIDITY_SENSOR_TOKEN);

  formBody += "&humidityPercentage=";
  formBody += String(humidityPercentage,2);

  formBody += "&recordedAt=";
  formBody += String(static_cast<long long>(recordedAtEpochMilliseconds));

  return postForm("/readings/humidity",formBody);
}




bool postMotionReading(bool motionDetected,int64_t recordedAtEpochMilliseconds) {
  String formBody = "sensorToken=";
  formBody += formUrlEncode(MOTION_SENSOR_TOKEN);

  formBody += "&motionDetected=";
  formBody += motionDetected ? "true" : "false";

  formBody += "&recordedAt=";
  formBody += String(static_cast<long long>(recordedAtEpochMilliseconds));

  return postForm("/readings/motion",formBody);
}










// -----------------------------------------------------------------------------
// Scheduled DHT22 operation
// -----------------------------------------------------------------------------



void postDhtReadingIfDue() {
  if (!deadlineReached(millis(),nextDhtPostAt)) {
    return;
  }

  /*
   * Temperature and humidity belong to one DHT22 acquisition. Both values
   * therefore receive exactly the same sampling timestamp.
   */
  const float humidityPercentage = dht.readHumidity();
  const float celsiusValue = dht.readTemperature();

  const bool validReading =
      !isnan(celsiusValue) &&
      !isnan(humidityPercentage) &&
      celsiusValue >= -273.15F &&
      humidityPercentage >= 0.0F &&
      humidityPercentage <= 100.0F;

  if (validReading) {
    Serial.printf(
        "[DHT22] Temperature: %.2f C, Humidity: %.2f %%\n",
        celsiusValue,
        humidityPercentage
    );

    /*
     * Capture the sample time before either HTTP request starts. Retries also
     * retain this original timestamp instead of producing a later one.
     */
    int64_t recordedAtEpochMilliseconds = 0;

    if (!readCurrentEpochMilliseconds(recordedAtEpochMilliseconds)) {
      Serial.println("[DHT22] Sample not posted because UTC time is not synchronized.");
    } else {
      const bool temperatureDelivered =
          postTemperatureReading(celsiusValue,recordedAtEpochMilliseconds);

      waitWhileServicingPir(
          temperatureDelivered
              ? HTTP_SUCCESS_PAUSE_MS
              : HTTP_ERROR_RECOVERY_MS
      );

      const bool humidityDelivered =
          postHumidityReading(humidityPercentage,recordedAtEpochMilliseconds);

      Serial.printf(
          "[DHT22] Temperature delivered=%s, humidity delivered=%s\n",
          temperatureDelivered ? "true" : "false",
          humidityDelivered ? "true" : "false"
      );
    }
  } else {
    Serial.println("[DHT22] Invalid sample; neither value was posted.");
  }

  /*
   * Completion-based scheduling avoids immediate catch-up transmissions after
   * slow HTTP operations or an unavailable clock.
   */
  nextDhtPostAt = millis() + DHT_POST_INTERVAL_MS;
}






// -----------------------------------------------------------------------------
// Scheduled motion operation
// -----------------------------------------------------------------------------



void postMotionIfDue() {
  if (
      pirState != PirState::MONITORING ||
      !deadlineReached(millis(),nextMotionPostAt)
  ) {
    return;
  }

  /*
   * Take a snapshot of the completed reporting window. Events arriving during
   * the subsequent HTTP request increment the live counter and remain pending.
   */
  const uint32_t includedEdgeCount = pirRisingEdgeCount;
  const uint32_t eventsInReport = includedEdgeCount - lastReportedPirRisingEdgeCount;
  const bool valueToPost = eventsInReport > 0;

  /*
   * Timestamp the completed-window snapshot before starting network I/O.
   */
  int64_t recordedAtEpochMilliseconds = 0;

  if (!readCurrentEpochMilliseconds(recordedAtEpochMilliseconds)) {
    /*
     * lastReportedPirRisingEdgeCount is deliberately left unchanged. Any true
     * motion event therefore remains pending until timestamped delivery is
     * possible.
     */
    Serial.println("[PIR] Window not posted because UTC time is not synchronized.");
    nextMotionPostAt = millis() + MOTION_POST_INTERVAL_MS;
    return;
  }

  const bool delivered = postMotionReading(valueToPost,recordedAtEpochMilliseconds);

  if (delivered) {
    /*
     * Consume only events included in the successfully accepted request.
     */
    lastReportedPirRisingEdgeCount = includedEdgeCount;
  }

  servicePir();

  Serial.printf(
      "[PIR] Window events=%lu, posted=%s, HTTP accepted=%s, raw GPIO=%s\n",
      static_cast<unsigned long>(eventsInReport),
      valueToPost ? "true" : "false",
      delivered ? "true" : "false",
      lastPirPinHigh ? "HIGH" : "LOW"
  );

  nextMotionPostAt = millis() + MOTION_POST_INTERVAL_MS;
}






// -----------------------------------------------------------------------------
// Arduino entry points
// -----------------------------------------------------------------------------

void setup() {
  Serial.begin(115200);
  delay(200);

  pinMode(PIR_PIN, INPUT);
  dht.begin();

  const uint32_t startedAt = millis();

  /*
   * The first DHT22 measurement is attempted after the sensor has had two
   * seconds to stabilize.
   */
  nextDhtPostAt = startedAt + 2000UL;

  pirWarmupFinishesAt = startedAt + PIR_WARMUP_MS;

  connectToWiFi();


    /*
   * A cold boot has no trustworthy wall-clock value. Start SNTP after Wi-Fi
   * obtains an IP address, then allow a bounded initial synchronization wait.
   */
  startNetworkTimeSynchronization();
  waitForInitialNetworkTime();


  /*
   * HTTP initialization is lazy. The first due reading creates the session.
   * This also makes later reconnects use exactly the same code path.
   */
  Serial.println("[Setup] Main loop started.");
}

void loop() {
  /*
   * Start a new logical HTTP batch.
   *
   * Temperature, humidity, and a simultaneously due motion report share one
   * reconnect budget and may reuse one TCP connection.
   */
  remainingHttpPostsBlocked = false;
  safeRetriesUsedThisBatch = 0;

  servicePir();

  /*
   * Deterministic order when deadlines coincide:
   *
   *   1. Temperature
   *   2. Humidity
   *   3. Motion
   */
  postDhtReadingIfDue();

  servicePir();
  postMotionIfDue();

  /*
   * Never retain an idle TCP connection between sensor cycles.
   */
  resetBackendSession();

  delay(10);
}