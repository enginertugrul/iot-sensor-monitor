# API Reference

This reference describes every HTTP interface of IoT Sensor Monitor. It covers the ingestion endpoints that MCUs call, the reading stream of the dashboard, the statistics endpoints, and the shared error format. Terms such as *ingestion token*, *storage resolution*, and *display granularity* are defined in [Architecture](ARCHITECTURE.md#terminology-and-domain-model). Installation and configuration steps are in the [Setup Guide](SETUP.md).

## Table of Contents

- [Overview and Shared Conventions](#overview-and-shared-conventions)
- [Authentication, Ownership, and CSRF Rules](#authentication-ownership-and-csrf-rules)
- [Sensor Ingestion Endpoints](#sensor-ingestion-endpoints)
- [Reading Stream (Server-Sent Events)](#reading-stream-server-sent-events)
- [Statistics Series Endpoint](#statistics-series-endpoint)
- [Statistics CSV Export Endpoint](#statistics-csv-export-endpoint)
- [Error Handling (RFC 9457 Problem Details)](#error-handling-rfc-9457-problem-details)
- [Browser Pages and HTML Form Routes (Appendix)](#browser-pages-and-html-form-routes-appendix)

---

## Overview and Shared Conventions

### Endpoint Summary

| Method        | Path                                                                                                       | Client                                                  | Authentication                         | Success response            |
|---------------|------------------------------------------------------------------------------------------------------------|---------------------------------------------------------|----------------------------------------|-----------------------------|
| `POST`        | [`/readings/temperature`](#sensor-ingestion-endpoints)                                                     | MCU, `McuSimulator`                                     | Ingestion token in the form body       | `200 OK`, empty body        |
| `POST`        | [`/readings/humidity`](#sensor-ingestion-endpoints)                                                        | MCU, `McuSimulator`                                     | Ingestion token in the form body       | `200 OK`, empty body        |
| `POST`        | [`/readings/motion`](#sensor-ingestion-endpoints)                                                          | MCU, `McuSimulator`                                     | Ingestion token in the form body       | `200 OK`, empty body        |
| `GET`         | [`/api/sensors/{sensorId}/readings/stream`](#reading-stream-server-sent-events)                            | Dashboard page (`EventSource`)                          | Session cookie                         | `text/event-stream`         |
| `GET`         | [`/api/sensors/{sensorId}/statistics/series`](#statistics-series-endpoint)                                 | Scripts and other HTTP clients; the UI does not call it | Session cookie                         | `application/json`          |
| `GET`         | [`/api/sensors/{sensorId}/statistics/export.csv`](#statistics-csv-export-endpoint)                         | Statistics page (download form)                         | Session cookie                         | `text/csv` file             |
| `GET`, `POST` | [Page and form routes](#browser-pages-and-html-form-routes-appendix), such as `/login` and `/user/sensors` | Browser                                                 | Session cookie, except on public pages | HTML page or `302` redirect |

The six API endpoints share one [error format](#error-handling-rfc-9457-problem-details). A few framework errors use another format; see [Errors Outside the Problem Format](#errors-outside-the-problem-format). [Authentication, Ownership, and CSRF Rules](#authentication-ownership-and-csrf-rules) explains how each kind of client authenticates.

Each endpoint section has the same parts: a short endpoint table, then Request, Response, Status Codes, and an example.

### Base URL

The application listens on port `8080`. Docker Compose publishes this port on the host address `APP_BIND_ADDRESS` and the host port `APP_PORT` (default `8080`). The [Setup Guide](SETUP.md#configuration-reference) describes both variables.

The examples in this file use `http://localhost:8080`. This address works when `APP_BIND_ADDRESS` is `127.0.0.1` or `0.0.0.0`, and when you run the application without Docker. If `APP_BIND_ADDRESS` is a LAN address, Docker publishes the port only on that address, so use it instead of `localhost`. An MCU always uses the LAN address of the host, for example `http://192.168.0.128:8080`, which is the default in both firmware sketches.

All interfaces use plain HTTP without TLS. The project assumes a trusted local network, so do not expose the application to the internet. [Deployment Scope and Security Assumptions](ARCHITECTURE.md#deployment-scope-and-security-assumptions) explains this scope.

### Content Types

| Interface                       | Request body                        | Response body                             |
|---------------------------------|-------------------------------------|-------------------------------------------|
| Ingestion                       | `application/x-www-form-urlencoded` | Empty                                     |
| Reading stream                  | None                                | `text/event-stream`, with JSON event data |
| Statistics series               | None                                | `application/json`                        |
| CSV export                      | None                                | `text/csv;charset=UTF-8`                  |
| Errors of the six API endpoints | —                                   | `application/problem+json`                |

The JSON examples in this file are formatted for readability; the server sends compact JSON. A field without a value is sent as `null`. It is not left out. JSON field order has no meaning, but the examples show the order that the server uses.

In the field tables, *Instant* means an ISO-8601 string in UTC, and *Date* means an ISO-8601 date string.

### Units

MCUs send values in canonical units: degrees Celsius (`celsiusValue`), percent of relative humidity (`humidityPercentage`), and motion as a boolean (`motionDetected`). The server stores numeric readings with the units `C` and `PERCENT`.

The session-authenticated endpoints return temperatures in the preferred unit of the logged-in owner: `CELSIUS`, `FAHRENHEIT`, or `KELVIN`. The owner sets this unit on the account settings page (`/user/settings`). Humidity is always in percent. The endpoints have no unit parameter, so the stored preference always applies. [Units](ARCHITECTURE.md#units) explains the conversion.

### Timestamps

| Where                                                | Format                                                                                                                           | Example                         |
|------------------------------------------------------|----------------------------------------------------------------------------------------------------------------------------------|---------------------------------|
| `recordedAt` form field of the ingestion endpoints   | Unix epoch milliseconds (UTC), as an integer. Two text formats also work; see [Accepted Value Formats](#accepted-value-formats). | `1793524460512`                 |
| `startInclusive` and `endExclusive` query parameters | ISO-8601 date-time with `Z` or a UTC offset                                                                                      | `2026-11-01T06:00:00Z`          |
| Instants in JSON and CSV responses                   | ISO-8601 in UTC                                                                                                                  | `2026-11-01T07:07:44.912518Z`   |
| `timestamp` field of the reading stream              | ISO-8601 with the UTC offset of the sensor's time zone                                                                           | `2026-11-01T12:16:02.348+03:00` |
| Local dates                                          | ISO-8601 date                                                                                                                    | `2026-11-01`                    |

- Responses show fractional seconds only when they are not zero. The one exception is the `timestamp` of a [Spring Boot error body](#errors-outside-the-problem-format), which always has three decimal places. Use a standard ISO-8601 parser.
- A query parameter without `Z` or an offset, such as `2026-11-01T06:00:00`, does not name an instant. The server rejects it with `400 INVALID_REQUEST`.
- In a query string, encode the `+` of an offset as `%2B`, for example `2026-11-01T09:00:00%2B03:00`. A plain `+` arrives at the server as a space.

### Sensor IDs

`{sensorId}` is the numeric ID of a sensor. The UI shows it in page addresses, for example `/?sensorId=7` on the dashboard and `/user/sensors/7/edit` on the sensors page. On the API endpoints, a value that is not a 64-bit integer, such as `abc` or `9223372036854775808`, returns `400 INVALID_REQUEST`.

---

## Authentication, Ownership, and CSRF Rules

The application has two kinds of clients, and each kind uses its own credential. The design behind these rules is described in [Authentication, Account Flows, and Ingestion Security](ARCHITECTURE.md#authentication-account-flows-and-ingestion-security).

| Routes                                                                 | Credential                                          | CSRF token                                      | Data scope                         |
|------------------------------------------------------------------------|-----------------------------------------------------|-------------------------------------------------|------------------------------------|
| `POST /readings/temperature`, `/readings/humidity`, `/readings/motion` | `sensorToken` form field                            | Not needed                                      | The one sensor that owns the token |
| `/api/**`                                                              | `JSESSIONID` session cookie from the form login     | Needed for `POST`, `PUT`, `PATCH`, and `DELETE` | Sensors of the logged-in owner     |
| Pages and forms                                                        | `JSESSIONID` session cookie; public pages need none | Needed for every form `POST`                    | Data of the logged-in owner        |

### Ingestion Tokens

- The owner creates a sensor on the sensors page (`/user/sensors`). The page shows the ingestion token once, right after the sensor is created. The [Setup Guide](SETUP.md#quick-start-with-docker-and-account-onboarding) shows the steps.
- A token has 43 characters of URL-safe Base64 (`A`–`Z`, `a`–`z`, `0`–`9`, `-`, `_`). It needs no URL encoding in a form body.
- The server removes leading and trailing whitespace from the token before it checks it. [Sensor ingestion tokens](ARCHITECTURE.md#sensor-ingestion-tokens) describes how the server stores and finds tokens.
- Every request carries the token. Ingestion uses no login session and no CSRF token.
- A token works only on the endpoint of its sensor type. For example, the token of a `HUMIDITY` sensor sent to `/readings/temperature` returns `400 INVALID_SENSOR_READING`.
- The token of a deactivated sensor returns `409 INACTIVE_SENSOR`. Activating the sensor again enables the same token. A token cannot be rotated; see [Limitations](ARCHITECTURE.md#limitations).

### Browser Sessions

- Browsers log in with the form at `/login`. Spring Security then recognizes the browser by its `JSESSIONID` cookie. Only users with a verified email address can log in.
- The `/api/**` endpoints accept only this session. There are no API keys and no bearer tokens. A script must send the session cookie of a logged-in browser, for example a value that you copy from the developer tools of the browser. Treat this cookie like a password.
- Without a valid session, `/api/**` returns a problem response instead of a redirect:
  - `401 AUTHENTICATION_REQUIRED`: the request has no logged-in session. This includes a session that ended with logout or a timeout.
  - `401 SESSION_EXPIRED`: the server marked the session as expired, for example after a password change or a password reset of the owner.
- Pages redirect to `/login` instead, or to `/login?sessionExpired` for an expired session.
- Logout invalidates the session and deletes the `JSESSIONID` cookie.

### Ownership

- Every API query includes the owner ID from the session. A sensor of another user returns `404 SENSOR_NOT_FOUND`, exactly like a sensor that does not exist. The API therefore does not reveal which sensor IDs exist.
- The owner and the temperature unit always come from the session and the stored account. Extra request parameters such as `ownerId` or `temperatureUnit` have no effect.

### CSRF Protection

Cross-site request forgery (CSRF) is an attack in which another web page makes the browser send a request with the user's session cookie. Spring Security checks a CSRF token on every request that can change state, with one exception for MCUs:

- The exemption covers only `POST` on the three exact ingestion paths. `PUT`, `PATCH`, or `DELETE` on these paths, and `POST` on any other path, fail with `403` without a valid CSRF token. Outside `/api/**`, this `403` does not use the problem format; see [Errors Outside the Problem Format](#errors-outside-the-problem-format).
- A browser `GET` on an ingestion path without a session is redirected to `/login`. These paths accept readings only with `POST`.
- On `/api/**`, a `POST`, `PUT`, `PATCH`, or `DELETE` request without a valid CSRF token gets `403 ACCESS_DENIED`. All `/api/**` endpoints use `GET`, so their clients do not need a CSRF token.
- Browser forms send the token in the hidden `_csrf` field. Thymeleaf adds this field to the forms automatically.

**Related tests:**

- `SecurityConfigWebMvcTest`: limits of the CSRF exemption, `401` and `403` problem responses, and the login redirect for pages.
- `StatisticsApiControllerWebMvcTest`: owner and unit from the session, and extra parameters that have no effect.
- `StatisticsApiIT`: the same `404` for missing and foreign sensors.

---

## Sensor Ingestion Endpoints

MCUs and `McuSimulator` send one reading per request to the endpoint of the sensor type. The ESP32 firmware, the Arduino Nano firmware, and `McuSimulator` all use this contract. A test with `McuSimulator` therefore runs the same server code as real hardware. To send readings without hardware, follow the [no-hardware path](SETUP.md#no-hardware-path-using-mcusimulator); to flash a board, follow the [hardware path](SETUP.md#hardware-path-esp32-and-arduino-nano).

| Item              | Value                                                                            |
|-------------------|----------------------------------------------------------------------------------|
| Methods and paths | `POST /readings/temperature`, `POST /readings/humidity`, `POST /readings/motion` |
| Clients           | ESP32 firmware, Arduino Nano firmware, `McuSimulator`                            |
| Authentication    | `sensorToken` form field; no session cookie and no CSRF token                    |
| Request           | `application/x-www-form-urlencoded` body                                         |
| Success response  | `200 OK`, empty body                                                             |

### Request

| Method and path              | Sensor type   | Value field          |
|------------------------------|---------------|----------------------|
| `POST /readings/temperature` | `TEMPERATURE` | `celsiusValue`       |
| `POST /readings/humidity`    | `HUMIDITY`    | `humidityPercentage` |
| `POST /readings/motion`      | `MOTION`      | `motionDetected`     |

- Send the header `Content-Type: application/x-www-form-urlencoded`. A `charset=UTF-8` parameter is allowed, and the ESP32 firmware and `McuSimulator` send it. Any other content type, for example JSON, returns `415 UNSUPPORTED_MEDIA_TYPE`.
- The ESP32 firmware and `McuSimulator` also send `Accept: application/json`. This header is optional, because a successful response has no body.

| Field                | Endpoint    | Type            | Validation                                                                                                   | Meaning                           |
|----------------------|-------------|-----------------|--------------------------------------------------------------------------------------------------------------|-----------------------------------|
| `sensorToken`        | All three   | String          | Required, not blank, at most 256 characters                                                                  | Ingestion token of the sensor     |
| `celsiusValue`       | Temperature | Decimal number  | Required, finite, at least `-273.15`                                                                         | Temperature in degrees Celsius    |
| `humidityPercentage` | Humidity    | Decimal number  | Required, finite, from `0` to `100`                                                                          | Relative humidity in percent      |
| `motionDetected`     | Motion      | Boolean         | Required; send `true` or `false`. Other spellings: see [Accepted Value Formats](#accepted-value-formats).    | Motion report for the last window |
| `recordedAt`         | All three   | Integer or text | Required; send Unix epoch milliseconds. Text formats: see [Accepted Value Formats](#accepted-value-formats). | Time when the MCU took the sample |

- *Finite* means that the server rejects `NaN`, `Infinity`, `-Infinity`, and numbers outside the `double` range, such as `1e309`.
- Both firmware sketches send numbers with two decimal places, for example `21.43`.

#### Accepted Value Formats

Spring MVC converts the form fields with its standard converters, and these converters accept more spellings than the clients send. The ESP32 firmware, the Arduino Nano firmware, and `McuSimulator` always send the standard form. New clients should do the same.

| Field            | Standard form                                    | Other accepted forms                                                                                                                                                              |
|------------------|--------------------------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `motionDetected` | `true` or `false`                                | `on` or `off`, `yes` or `no`, and `1` or `0`. Letter case does not matter, so `TRUE` and `Yes` also mean `true`.                                                                  |
| `recordedAt`     | Unix epoch milliseconds, such as `1793524460512` | An ISO-8601 instant with `Z` or an offset, such as `2026-11-01T09:14:20.512Z` or `2026-11-01T12:14:20.512+03:00`. An RFC 1123 date-time, such as `Sun, 01 Nov 2026 09:14:20 GMT`. |

- Any other value returns `400 INVALID_REQUEST`. Examples are `motionDetected=maybe`, `motionDetected=y`, `recordedAt=1793524460512.5`, and a `recordedAt` date-time without `Z` or an offset.
- An RFC 1123 date-time has whole seconds only, and its day of the week must match the date.
- In a form body, a plain `+` arrives at the server as a space, so encode the `+` of an offset as `%2B`. `curl --data-urlencode` does this for you.

#### Rules for `recordedAt`

`recordedAt` is the sampling time on the MCU, not the time of arrival, so the MCU clock must be correct. Both firmware sketches synchronize the clock with NTP or SNTP and send nothing before the first synchronization. With the default settings of `SensorReadingIngestionPolicy`, the server accepts a reading only if `recordedAt`:

- is not earlier than the creation time of the sensor;
- is at most 6 hours before the server time (`maximum-lateness`, `PT6H`);
- is at most 2 minutes after the server time (`maximum-future-skew`, `PT2M`);
- belongs to a sensor-local day whose raw data is still inside raw retention (`raw-retention`, `P30D`), so that the server can summarize the day again. With the default values, every reading that passes the 6-hour rule also passes this rule.

A reading that is exactly 6 hours late or exactly 2 minutes ahead is still accepted. The server truncates `recordedAt` to microseconds, the resolution of PostgreSQL `timestamptz`. An epoch-millisecond value therefore keeps its exact value, but ISO-8601 text loses any digits after the sixth decimal place. The server accepts late readings on purpose and rebuilds the affected summaries; see [Late Readings](ARCHITECTURE.md#late-readings).

#### Motion Readings

`motionDetected` reports a time window, not a single signal of the PIR sensor. The ESP32 firmware posts a motion reading about every 67 seconds. It sends `true` when the PIR sensor produced at least one rising edge since the last accepted report, and `false` otherwise. Several edges in one window therefore produce one `true` reading. A `true` reading can trigger a motion alert rule (`EVENT_DETECTED` with `MOTION_DETECTED`), and the statistics count the `true` readings (`trueSampleCount`).

### Response

An accepted reading returns `200 OK` with an empty body. The server sends this response only after the database transaction has committed. At that point, the server has stored the reading, rewound any affected rollup checkpoints, and evaluated the alert rules. A triggered alert email is sent later on a separate mail thread, so it does not delay the response. [End-to-End Data Flow](ARCHITECTURE.md#end-to-end-data-flow) describes each step.

The server stores every accepted request as a new reading and does not detect duplicates. A client must therefore not resend a request that the server may already have received; see [Ingestion and MCUs](ARCHITECTURE.md#ingestion-and-mcus).

### Status Codes

The server runs the checks in this order. The first failure decides the response, and a rejected reading leaves no data behind.

| Step | Check                                                              | Status | `code`                   |
|------|--------------------------------------------------------------------|--------|--------------------------|
| 1    | The body is form-encoded                                           | `415`  | `UNSUPPORTED_MEDIA_TYPE` |
| 2    | All fields are present, can be converted, and pass Bean Validation | `400`  | `INVALID_REQUEST`        |
| 3    | The token belongs to a sensor                                      | `401`  | `INVALID_SENSOR_TOKEN`   |
| 4    | The sensor is active                                               | `409`  | `INACTIVE_SENSOR`        |
| 5    | The sensor type matches the endpoint                               | `400`  | `INVALID_SENSOR_READING` |
| 6    | `recordedAt` is inside the accepted window                         | `400`  | `INVALID_SENSOR_READING` |
| 7    | The value passes the domain rules of `SensorReading`               | `400`  | `INVALID_SENSOR_READING` |

- The `415` response has the header `Accept: application/x-www-form-urlencoded`.
- An unexpected server error returns `500 INTERNAL_ERROR`.
- The `detail` text is the same for every error with the same `code`. It does not say which field or which timestamp rule failed. `McuSimulator` prints a hint for `400`, `401`, and `409`.

### Examples

The examples use fixed values. To try them, replace the tokens with the ingestion tokens of your sensors. Also set `recordedAt` to the current time, because the server rejects readings that are more than 6 hours old. On Linux and in Git Bash, `date +%s%3N` prints the current time in epoch milliseconds. `McuSimulator` sets the time automatically.

A temperature reading in the format of the Arduino Nano firmware. The value `1793524460512` is `2026-11-01T09:14:20.512Z`:

```bash
curl -i -X POST http://localhost:8080/readings/temperature \
  -H "Content-Type: application/x-www-form-urlencoded" \
  --data "sensorToken=kR4v9TqN2xLm7WcB0zYd5HsF8uJe3G_P1oQiXt6n-bM&celsiusValue=21.43&recordedAt=1793524460512"
```

The server answers `HTTP/1.1 200` with an empty body.

A humidity reading in the format of the ESP32 firmware:

```bash
curl -i -X POST http://localhost:8080/readings/humidity \
  -H "Content-Type: application/x-www-form-urlencoded; charset=UTF-8" \
  --data "sensorToken=Zp7Lq2Wv9Xk4Bn6Tr1Yc8Hm3Js5Fd0Ga-Ue7Io2Pw4Q&humidityPercentage=48.60&recordedAt=1793524471038"
```

A motion reading in the format of the ESP32 firmware:

```bash
curl -i -X POST http://localhost:8080/readings/motion \
  -H "Content-Type: application/x-www-form-urlencoded; charset=UTF-8" \
  --data "sensorToken=Mb3Kx8Rt_Ny5Vq1Lp9Wz2Cf7Hs4Dj6Ge0Ua8Oi3Tm5E&motionDetected=true&recordedAt=1793524490771"
```

A temperature reading with an ISO-8601 `recordedAt` and a UTC offset. `--data-urlencode` encodes the `+` of the offset, and the server stores the same instant as in the first example:

```bash
curl -i -X POST http://localhost:8080/readings/temperature \
  --data-urlencode "sensorToken=kR4v9TqN2xLm7WcB0zYd5HsF8uJe3G_P1oQiXt6n-bM" \
  --data-urlencode "celsiusValue=21.43" \
  --data-urlencode "recordedAt=2026-11-01T12:14:20.512+03:00"
```

If the owner has deactivated the temperature sensor, the first request returns `409`. Standard headers, such as `Date` and the security headers that Spring Security adds, are left out:

```text
HTTP/1.1 409
Cache-Control: no-store
Content-Type: application/problem+json
```

```json
{
  "detail": "The sensor is inactive",
  "instance": "/readings/temperature",
  "status": 409,
  "title": "Conflict",
  "type": "about:blank",
  "code": "INACTIVE_SENSOR",
  "timestamp": "2026-11-01T09:14:20.874129Z"
}
```

**Related tests:**

- `SensorIngestionControllerWebMvcTest`: form binding without login or CSRF, field validation, and `415` for JSON bodies.
- `SensorIngestionApiIT`: accepted and rejected readings over HTTP.

---

## Reading Stream (Server-Sent Events)

The dashboard (`/`) keeps its reading table current through a reading stream. Server-Sent Events (SSE) is a standard HTTP format. The server keeps the response open and writes text events into it, and browsers read these events with `EventSource`. [Live Dashboard Streaming (SSE)](ARCHITECTURE.md#live-dashboard-streaming-sse) describes the server design, and [Limits and Defaults](ARCHITECTURE.md#limits-and-defaults) lists the configurable limits.

| Item             | Value                                                     |
|------------------|-----------------------------------------------------------|
| Method and path  | `GET /api/sensors/{sensorId}/readings/stream`             |
| Client           | Dashboard page (`EventSource`)                            |
| Authentication   | `JSESSIONID` session cookie                               |
| Request          | Path variable `sensorId`; no query parameters and no body |
| Success response | `200 OK`, `text/event-stream`; the response stays open    |

### Request

| Part                      | Value                                                                          |
|---------------------------|--------------------------------------------------------------------------------|
| `sensorId` (path)         | ID of a sensor of the logged-in owner                                          |
| Cookie                    | `JSESSIONID` of a logged-in session                                            |
| `Accept` header           | Must allow `text/event-stream`; otherwise the response is `406 NOT_ACCEPTABLE` |
| Query parameters and body | None                                                                           |

The server loads the first snapshot before it starts the response. An unknown sensor or a sensor of another user therefore gets a normal `404` problem response, not an empty stream.

### Response

#### Response Headers

| Header              | Value                    | Purpose                                                                      |
|---------------------|--------------------------|------------------------------------------------------------------------------|
| `Content-Type`      | `text/event-stream`      | SSE format                                                                   |
| `Cache-Control`     | `no-store, no-transform` | No caching, and no changes by proxies                                        |
| `X-Accel-Buffering` | `no`                     | Turns off response buffering in proxies that read this header, such as nginx |

#### Events

| Event                | `data`                                                        | When the server sends it                                                      |
|----------------------|---------------------------------------------------------------|-------------------------------------------------------------------------------|
| `readings`           | `RecentSensorReadingsDTO` as JSON                             | First message after the stream opens, then only when the snapshot has changed |
| Comment `:keepalive` | None                                                          | When about 15 seconds have passed without another message                     |
| `stream-ended`       | `{"code":"SESSION_EXPIRED"}` or `{"code":"SENSOR_NOT_FOUND"}` | Just before the server closes the stream for this reason                      |

- About every 2 seconds, the server reloads the 10 latest readings and compares them with the last snapshot. Only a changed snapshot produces a `readings` event.
- The server checks the 15-second keepalive limit during the same refresh, so a comment can arrive a few seconds after the 15-second mark.
- Each `readings` event contains the complete list of the latest readings. Replace the displayed list with it; do not append it. The stream sends the current state, not a history: after a reconnect, the client receives a fresh snapshot.
- The messages have no `id` or `retry` field, so `EventSource` uses its default reconnection delay.
- A line that starts with `:` is a comment. `EventSource` ignores it; the comment only keeps the connection active.
- A stream keeps the temperature unit that was active when it opened. After a change on the settings page, the next stream uses the new unit.

#### `readings` Payload

`RecentSensorReadingsDTO`:

| Field      | Type                 | Meaning                                                                         |
|------------|----------------------|---------------------------------------------------------------------------------|
| `sensorId` | Integer              | ID of the sensor                                                                |
| `readings` | Array, 0 to 10 items | The latest readings, newest first (ordered by `recordedAt`, then by reading ID) |

Each item is a `SensorReadingSnapshotDTO`:

| Field                  | Type              | Meaning                                                                                 |
|------------------------|-------------------|-----------------------------------------------------------------------------------------|
| `sensorType`           | String            | `TEMPERATURE`, `HUMIDITY`, or `MOTION`                                                  |
| `installationLocation` | String            | Installation location of the sensor, as entered by the owner                            |
| `numericValue`         | Number or `null`  | Temperature in the owner's unit, or humidity in percent; `null` for motion              |
| `booleanValue`         | Boolean or `null` | Motion report; `null` for temperature and humidity                                      |
| `unitSymbol`           | String            | `°C`, `°F`, or `K` for temperature, `% RH` for humidity, and an empty string for motion |
| `timestamp`            | String            | `recordedAt` in the sensor's time zone, ISO-8601 with offset                            |
| `timeZoneId`           | String            | IANA time zone ID of the sensor, such as `Europe/Istanbul`                              |
| `offset`               | String            | UTC offset of `timestamp`, such as `+03:00`, or `Z` for UTC                             |

The server converts temperatures with floating-point arithmetic and does not round them. Round `numericValue` for display.

#### End of a Stream

| Situation                                                                                                                                   | What the client receives                                     | Expected client behavior                                          |
|---------------------------------------------------------------------------------------------------------------------------------------------|--------------------------------------------------------------|-------------------------------------------------------------------|
| Logout or another end of the session                                                                                                        | `stream-ended` with `SESSION_EXPIRED`                        | Stop and log in again                                             |
| Password change or password reset of the owner                                                                                              | `stream-ended` with `SESSION_EXPIRED`, within about 1 second | Stop and log in again                                             |
| The sensor was deleted                                                                                                                      | `stream-ended` with `SENSOR_NOT_FOUND`                       | Stop                                                              |
| 5-minute connection lifetime, a refresh that did not finish within 15 seconds, a full worker queue, or an unexpected error during a refresh | The response ends without an event                           | Reconnect; `EventSource` does this automatically                  |
| Application shutdown                                                                                                                        | The response ends without an event                           | During the shutdown, new connections get `503 STREAM_UNAVAILABLE` |

`EventSource` reconnects after every closed response, even after `stream-ended`. A client must therefore call `close()` when it receives `stream-ended`. `dashboard.js` does this and shows an unavailable status. [How a Stream Ends](ARCHITECTURE.md#how-a-stream-ends) explains how the server detects each case.

### Status Codes

| Status | `code`                    | When                                                                                                             |
|--------|---------------------------|------------------------------------------------------------------------------------------------------------------|
| `200`  | —                         | The stream is open                                                                                               |
| `400`  | `INVALID_REQUEST`         | `sensorId` is not a 64-bit integer                                                                               |
| `401`  | `AUTHENTICATION_REQUIRED` | No logged-in session                                                                                             |
| `401`  | `SESSION_EXPIRED`         | The session was expired, for example after a password change                                                     |
| `404`  | `SENSOR_NOT_FOUND`        | Unknown sensor, or a sensor of another user                                                                      |
| `406`  | `NOT_ACCEPTABLE`          | The `Accept` header does not allow `text/event-stream`. The response has the header `Accept: text/event-stream`. |
| `503`  | `STREAM_UNAVAILABLE`      | 100 streams are already open, the stream worker queue is full, or the application is shutting down               |

### Example

```bash
curl -N -H "Accept: text/event-stream" -b "JSESSIONID=<session-id>" \
  http://localhost:8080/api/sensors/12/readings/stream
```

`-N` turns off output buffering in curl, so events appear when they arrive. Sensor 12 is a new `MOTION` sensor (installation location `Garage`, time zone `Europe/Istanbul`) on an ESP32, which posts a motion report about every 67 seconds. Its first snapshot therefore has only one reading, and the server sends keepalive comments between two reports. Standard headers, such as `Date` and the security headers that Spring Security adds, are left out:

```text
HTTP/1.1 200
Cache-Control: no-store, no-transform
X-Accel-Buffering: no
Content-Type: text/event-stream

event:readings
data:{"sensorId":12,"readings":[{"sensorType":"MOTION","installationLocation":"Garage","numericValue":null,"booleanValue":false,"unitSymbol":"","timestamp":"2026-11-01T12:16:02.348+03:00","timeZoneId":"Europe/Istanbul","offset":"+03:00"}]}

:keepalive

:keepalive

:keepalive

:keepalive

event:readings
data:{"sensorId":12,"readings":[{"sensorType":"MOTION","installationLocation":"Garage","numericValue":null,"booleanValue":true,"unitSymbol":"","timestamp":"2026-11-01T12:17:09.355+03:00","timeZoneId":"Europe/Istanbul","offset":"+03:00"},{"sensorType":"MOTION","installationLocation":"Garage","numericValue":null,"booleanValue":false,"unitSymbol":"","timestamp":"2026-11-01T12:16:02.348+03:00","timeZoneId":"Europe/Istanbul","offset":"+03:00"}]}

```

When the owner logs out in the browser, the stream ends with this event:

```text
event:stream-ended
data:{"code":"SESSION_EXPIRED"}

```

**Related tests:**

- `SensorReadingStreamControllerWebMvcTest`: endpoint contract.
- `SensorReadingStreamIT`: snapshots, keepalive comments, session end, sensor deletion, and capacity over HTTP.

---

## Statistics Series Endpoint

`GET /api/sensors/{sensorId}/statistics/series` returns the statistics of one sensor as JSON. It uses the same statistics service and the same `SensorStatisticsSeriesDTO` as the statistics page. The page itself does not call this endpoint, because it embeds the same data in its HTML. [Statistics Resolution, Coverage, and CSV Export](ARCHITECTURE.md#statistics-resolution-coverage-and-csv-export) describes how the server chooses and combines tiers. This section describes only the request and the response.

| Item             | Value                                                                                         |
|------------------|-----------------------------------------------------------------------------------------------|
| Method and path  | `GET /api/sensors/{sensorId}/statistics/series`                                               |
| Client           | Scripts and other HTTP clients; the UI does not call it                                       |
| Authentication   | `JSESSIONID` session cookie                                                                   |
| Request          | Path variable `sensorId`; query parameters `startInclusive`, `endExclusive`, and `resolution` |
| Success response | `200 OK`, `application/json` (`SensorStatisticsSeriesDTO`)                                    |

### Request

| Parameter        | In    | Required | Format                                        | Meaning                                                        |
|------------------|-------|----------|-----------------------------------------------|----------------------------------------------------------------|
| `sensorId`       | Path  | Yes      | Integer                                       | ID of a sensor of the logged-in owner                          |
| `startInclusive` | Query | Yes      | ISO-8601 date-time with `Z` or an offset      | Start of the range (included)                                  |
| `endExclusive`   | Query | Yes      | ISO-8601 date-time with `Z` or an offset      | End of the range (excluded)                                    |
| `resolution`     | Query | No       | `AUTO` (default), `RAW`, `HOURLY`, or `DAILY` | Requested storage resolution; an empty value also means `AUTO` |

Write `resolution` in upper case. Another spelling, such as `auto`, returns `400 INVALID_REQUEST`.

#### Range Rules

The server first checks that the sensor belongs to the logged-in owner. An unknown or foreign sensor therefore returns `404`, even when the range is invalid. Then the server checks the range against the current time (`asOf`) and the defaults of `StatisticsQueryPolicy`:

- `startInclusive` must be before `endExclusive`.
- The range can be at most 1,095 days long (`maximum-range`, `P1095D`).
- `startInclusive` must be before the current time.
- If `endExclusive` is in the future, the server evaluates the range only up to `asOf`. The response then reports `asOf` as `evaluatedEndExclusive`, and `conditions.containsIncompleteIntervals` is `true`.

A failed check returns `400 INVALID_STATISTICS_QUERY`.

#### Resolution and Point Budget

A series has at most 400 points (`chart-point-budget`).

| `resolution` | Behavior                                                                                                                                                                                                              |
|--------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `AUTO`       | The server chooses the finest tier that fits the budget, as described in [Resolution Choice](ARCHITECTURE.md#resolution-choice).                                                                                      |
| `RAW`        | One point per reading. If the range needs more than 400 points, the response is `400 INVALID_STATISTICS_QUERY`.                                                                                                       |
| `HOURLY`     | One point per UTC hour. If the range needs more than 400 points, the response is `400 INVALID_STATISTICS_QUERY`.                                                                                                      |
| `DAILY`      | One point per sensor-local day. If the range has more than 400 days, the server groups the days into `WEEKLY` points (weeks start on Monday) or `MONTHLY` points. With the default limits, `WEEKLY` is always enough. |

`resolvedResolution` in the response shows the storage resolution that the server used, and `displayGranularity` shows the bucket size of the points.

### Response

`SensorStatisticsSeriesDTO`:

| Field                     | Type    | Meaning                                                             |
|---------------------------|---------|---------------------------------------------------------------------|
| `sensor`                  | Object  | The sensor and its units (see below)                                |
| `requestedStartInclusive` | Instant | Start as requested                                                  |
| `requestedEndExclusive`   | Instant | End as requested                                                    |
| `evaluatedStartInclusive` | Instant | Start of the evaluated range; the same as the requested start       |
| `evaluatedEndExclusive`   | Instant | The requested end, or `asOf` if the requested end was in the future |
| `asOf`                    | Instant | Server time of the query                                            |
| `requestedResolution`     | String  | `AUTO`, `RAW`, `HOURLY`, or `DAILY`                                 |
| `resolvedResolution`      | String  | `RAW`, `HOURLY`, or `DAILY`                                         |
| `displayGranularity`      | String  | `RAW`, `HOURLY`, `DAILY`, `WEEKLY`, or `MONTHLY`                    |
| `status`                  | String  | Range status (see below)                                            |
| `conditions`              | Object  | Three flags that explain the range status (see below)               |
| `fullyCovered`            | Boolean | `true` when all three flags in `conditions` are `false`             |
| `pointBudget`             | Integer | Maximum number of points (400 by default)                           |
| `csvExport`               | Object  | Whether this series can be exported as it is (see below)            |
| `coverage`                | Object  | Coverage of the three tiers at `asOf` (see below)                   |
| `periodMetrics`           | Object  | Totals for the evaluated range (see below)                          |
| `points`                  | Array   | The series points, oldest first                                     |

#### `sensor`

| Field               | Type             | Meaning                                                                                         |
|---------------------|------------------|-------------------------------------------------------------------------------------------------|
| `id`                | Integer          | Sensor ID                                                                                       |
| `name`              | String           | Sensor name                                                                                     |
| `type`              | String           | `TEMPERATURE`, `HUMIDITY`, or `MOTION`                                                          |
| `timeZoneId`        | String           | IANA time zone ID of the sensor                                                                 |
| `canonicalUnit`     | String or `null` | Storage unit: `C` or `PERCENT`; `null` for motion                                               |
| `displayUnit`       | String or `null` | `CELSIUS`, `FAHRENHEIT`, or `KELVIN` for temperature, `PERCENT` for humidity; `null` for motion |
| `displayUnitSymbol` | String or `null` | `°C`, `°F`, or `K` for temperature, `% RH` for humidity; `null` for motion                      |

#### `status` and `conditions`

The meaning of the point statuses `COMPLETE`, `NO_SAMPLES`, `PARTIAL`, `ROLLUP_DELAY`, and `EXPIRED` is defined in [Tier Stitching and Status](ARCHITECTURE.md#tier-stitching-and-status). The server derives the range `status` from the points. It checks the rows of this table from top to bottom and uses the first one that applies:

| Range `status`      | Condition                                                                |
|---------------------|--------------------------------------------------------------------------|
| `EXPIRED`           | Every point of the range has expired                                     |
| `PARTIALLY_EXPIRED` | Some points have expired, and others have not                            |
| `ROLLUP_DELAY`      | At least one point waits for a rollup                                    |
| `PARTIAL`           | At least one point is still open, or the requested end was in the future |
| `NO_SAMPLES`        | All data for the range is available, and the range has no readings       |
| `COMPLETE`          | All data for the range is available, and the range has readings          |

| `conditions` field               | `true` when                                                                                       |
|----------------------------------|---------------------------------------------------------------------------------------------------|
| `containsExpiredIntervals`       | At least one point is `EXPIRED`; for a `RAW` series, when raw data does not cover the whole range |
| `containsRollupDelayedIntervals` | At least one point is `ROLLUP_DELAY`                                                              |
| `containsIncompleteIntervals`    | At least one point is `PARTIAL`, or the requested end was after `asOf`                            |

#### `csvExport`

| Field       | Type    | Meaning                                                                                                            |
|-------------|---------|--------------------------------------------------------------------------------------------------------------------|
| `available` | Boolean | `true` when `resolvedResolution` is `HOURLY` or `DAILY` and `rowCount` is at most `rowLimit`                       |
| `rowCount`  | Integer | Summary rows of this series; `0` for a `RAW` series. Daily rows are counted before any weekly or monthly grouping. |
| `rowLimit`  | Integer | Maximum rows of one CSV export (2,500 by default)                                                                  |

For a `RAW` series, `available` is `false`. The [CSV export endpoint](#statistics-csv-export-endpoint) can still export the same range, because it uses a summary tier.

#### `coverage`

`coverage` has the fields `raw`, `hourly`, and `daily`. Each one is a `StatisticsTierCoverageDTO`:

| Field                                                   | Type              | Meaning                                                                                                                                                                                                                          |
|---------------------------------------------------------|-------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `resolution`                                            | String            | `RAW`, `HOURLY`, or `DAILY`                                                                                                                                                                                                      |
| `retentionWindowFromInclusive`                          | Instant           | Start of the retention window at `asOf`; see the rules below the table                                                                                                                                                           |
| `representedFromInclusive`, `representedUntilExclusive` | Instant or `null` | The part of the retention window that the tier can serve. Raw: the retention window up to `asOf`. Hourly and daily: the range of the rollup checkpoint inside the retention window. Both are `null` when there is no such range. |
| `rollupProgress`                                        | Object or `null`  | `null` for raw; for hourly and daily, see the next table                                                                                                                                                                         |

The retention window of each tier starts at these times:

- **Raw:** `asOf` minus 30 days, rounded down to the full hour, but not before the creation of the sensor.
- **Hourly:** `asOf` minus 90 days, rounded down to the full hour.
- **Daily:** the start of the sensor-local day that contains `asOf` minus 730 days.

`rollupProgress` (`StatisticsRollupProgressDTO`) reports the state of the [rollup checkpoint](ARCHITECTURE.md#rollup-checkpoints):

| Field                                           | Type              | Meaning                                                                                                                                                                                                             |
|-------------------------------------------------|-------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `verifiedFromInclusive`, `safeThroughExclusive` | Instant or `null` | The checkpoint range `[coverage_started_at, covered_until)`; `null` before the first rollup run                                                                                                                     |
| `rollupDueUntilExclusive`                       | Instant           | End of the buckets that should be summarized by now. Hourly: `asOf` minus the 5-minute grace period, rounded down to the full hour. Daily: the start of the sensor-local day that contains `asOf` minus 15 minutes. |
| `lagSeconds`                                    | Integer           | Seconds by which the rollup is behind `rollupDueUntilExclusive`; `0` when it is up to date                                                                                                                          |
| `delayed`                                       | Boolean           | `true` when `lagSeconds` is greater than `0`                                                                                                                                                                        |

#### `periodMetrics`

| Field               | Type             | Meaning                                                                                                                                                                                   |
|---------------------|------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `available`         | Boolean          | `false` when `conditions.containsExpiredIntervals` or `conditions.containsRollupDelayedIntervals` is `true`. In that case, `sourceSampleCount` is `0` and both metric objects are `null`. |
| `sourceSampleCount` | Integer          | Number of readings in the evaluated range                                                                                                                                                 |
| `numericMetrics`    | Object or `null` | Temperature or humidity totals; `null` when the range has no readings, and always `null` for motion                                                                                       |
| `motionMetrics`     | Object or `null` | Motion counts; always `null` for temperature and humidity                                                                                                                                 |

`numericMetrics` (`StatisticsNumericMetricsDTO`) has the decimal fields `sum`, `minimum`, `average`, and `maximum`, in the display unit. The server computes `average` from the exact sum with 34 significant digits (`MathContext.DECIMAL128`), so it can have many decimal places. Round it for display.

`motionMetrics` (`StatisticsMotionMetricsDTO`) has the integer fields `totalSampleCount`, `trueSampleCount`, and `falseSampleCount`, and the decimal field `truePercentage` (0 to 100). `truePercentage` is `null` when `totalSampleCount` is `0`.

#### `points`

Each point is a `StatisticsSeriesPointDTO`. Raw points and interval points use different fields:

| Field                                     | Type              | Raw point (`granularity` is `RAW`)                                      | Interval point (`HOURLY`, `DAILY`, `WEEKLY`, `MONTHLY`)                                            |
|-------------------------------------------|-------------------|-------------------------------------------------------------------------|----------------------------------------------------------------------------------------------------|
| `granularity`                             | String            | `RAW`                                                                   | Display granularity of the series                                                                  |
| `sourceReadingId`                         | Integer or `null` | ID of the reading                                                       | `null`                                                                                             |
| `recordedAt`                              | Instant or `null` | Time of the reading                                                     | `null`                                                                                             |
| `bucketStart`, `bucketEnd`                | Instant or `null` | `null`                                                                  | Interval `[bucketStart, bucketEnd)`, clipped to the evaluated range                                |
| `localDateStart`, `localDateEndExclusive` | Date or `null`    | `null`                                                                  | Sensor-local dates of daily, weekly, and monthly points; `null` for hourly points                  |
| `timeZoneId`                              | String or `null`  | `null`                                                                  | Sensor time zone of daily, weekly, and monthly points; `null` for hourly points                    |
| `status`                                  | String            | Point status                                                            | Point status                                                                                       |
| `sourceSampleCount`                       | Integer or `null` | `1`                                                                     | Number of readings; `null` when the point is `EXPIRED` or `ROLLUP_DELAY`                           |
| `numericMetrics`                          | Object or `null`  | The reading value: `sum`, `minimum`, `average`, and `maximum` are equal | Metrics of the interval; `null` when there are no readings or no data                              |
| `motionMetrics`                           | Object or `null`  | Counts for the one reading                                              | Counts of the interval; `null` when there is no data                                               |
| `finalizedAt`, `refreshedAt`              | Instant or `null` | `null`                                                                  | When the summary row was first written and when it was last rebuilt; see the rules below the table |

`finalizedAt` and `refreshedAt` of an interval point follow these rules:

- A point from a summary row has the time when the row was first written and the time when it was last rebuilt.
- A weekly or monthly point with the status `COMPLETE` or `NO_SAMPLES` has the latest values of its days, if every day has them.
- All other points have `null`, for example a point that comes from raw readings.

### Status Codes

| Status | `code`                     | When                                                                                                                                    |
|--------|----------------------------|-----------------------------------------------------------------------------------------------------------------------------------------|
| `200`  | —                          | Success                                                                                                                                 |
| `400`  | `INVALID_REQUEST`          | A required parameter is missing, a date-time cannot be parsed, `resolution` has an unknown value, or `sensorId` is not a 64-bit integer |
| `400`  | `INVALID_STATISTICS_QUERY` | The range or the resolution breaks one of the rules above                                                                               |
| `401`  | `AUTHENTICATION_REQUIRED`  | No logged-in session                                                                                                                    |
| `401`  | `SESSION_EXPIRED`          | The session was expired, for example after a password change                                                                            |
| `404`  | `SENSOR_NOT_FOUND`         | Unknown sensor, or a sensor of another user                                                                                             |
| `406`  | `NOT_ACCEPTABLE`           | The `Accept` header does not allow JSON                                                                                                 |

### Example

Sensor 7 is an Arduino Nano temperature sensor named `Living room`, in the time zone `Europe/Istanbul`. It posts a reading every 10 seconds, so each full hour has 360 readings. Its first reading was at `2026-09-14T08:41:12.345Z`, and the owner's unit is `CELSIUS`. This request asks for three hours and leaves out `resolution`:

```bash
curl -b "JSESSIONID=<session-id>" \
  "http://localhost:8080/api/sensors/7/statistics/series?startInclusive=2026-11-01T06:00:00Z&endExclusive=2026-11-01T09:00:00Z"
```

The range is longer than 1 hour, so `AUTO` does not use raw readings. Three hourly points fit the budget, and the hourly rollup has covered all three hours, so `AUTO` resolves to `HOURLY`:

```json
{
  "sensor": {
    "id": 7,
    "name": "Living room",
    "type": "TEMPERATURE",
    "timeZoneId": "Europe/Istanbul",
    "canonicalUnit": "C",
    "displayUnit": "CELSIUS",
    "displayUnitSymbol": "°C"
  },
  "requestedStartInclusive": "2026-11-01T06:00:00Z",
  "requestedEndExclusive": "2026-11-01T09:00:00Z",
  "evaluatedStartInclusive": "2026-11-01T06:00:00Z",
  "evaluatedEndExclusive": "2026-11-01T09:00:00Z",
  "asOf": "2026-11-01T09:12:31.204317Z",
  "requestedResolution": "AUTO",
  "resolvedResolution": "HOURLY",
  "displayGranularity": "HOURLY",
  "status": "COMPLETE",
  "conditions": {
    "containsExpiredIntervals": false,
    "containsRollupDelayedIntervals": false,
    "containsIncompleteIntervals": false
  },
  "fullyCovered": true,
  "pointBudget": 400,
  "csvExport": {
    "available": true,
    "rowCount": 3,
    "rowLimit": 2500
  },
  "coverage": {
    "raw": {
      "resolution": "RAW",
      "retentionWindowFromInclusive": "2026-10-02T09:00:00Z",
      "representedFromInclusive": "2026-10-02T09:00:00Z",
      "representedUntilExclusive": "2026-11-01T09:12:31.204317Z",
      "rollupProgress": null
    },
    "hourly": {
      "resolution": "HOURLY",
      "retentionWindowFromInclusive": "2026-08-03T09:00:00Z",
      "representedFromInclusive": "2026-09-14T08:00:00Z",
      "representedUntilExclusive": "2026-11-01T09:00:00Z",
      "rollupProgress": {
        "verifiedFromInclusive": "2026-09-14T08:00:00Z",
        "safeThroughExclusive": "2026-11-01T09:00:00Z",
        "rollupDueUntilExclusive": "2026-11-01T09:00:00Z",
        "lagSeconds": 0,
        "delayed": false
      }
    },
    "daily": {
      "resolution": "DAILY",
      "retentionWindowFromInclusive": "2024-10-31T21:00:00Z",
      "representedFromInclusive": "2026-09-13T21:00:00Z",
      "representedUntilExclusive": "2026-10-31T21:00:00Z",
      "rollupProgress": {
        "verifiedFromInclusive": "2026-09-13T21:00:00Z",
        "safeThroughExclusive": "2026-10-31T21:00:00Z",
        "rollupDueUntilExclusive": "2026-10-31T21:00:00Z",
        "lagSeconds": 0,
        "delayed": false
      }
    }
  },
  "periodMetrics": {
    "available": true,
    "sourceSampleCount": 1080,
    "numericMetrics": {
      "sum": 23255.91,
      "minimum": 21.12,
      "average": 21.53325,
      "maximum": 21.93
    },
    "motionMetrics": null
  },
  "points": [
    {
      "granularity": "HOURLY",
      "sourceReadingId": null,
      "recordedAt": null,
      "bucketStart": "2026-11-01T06:00:00Z",
      "bucketEnd": "2026-11-01T07:00:00Z",
      "localDateStart": null,
      "localDateEndExclusive": null,
      "timeZoneId": null,
      "status": "COMPLETE",
      "sourceSampleCount": 360,
      "numericMetrics": {
        "sum": 7704.36,
        "minimum": 21.12,
        "average": 21.401,
        "maximum": 21.75
      },
      "motionMetrics": null,
      "finalizedAt": "2026-11-01T07:07:44.912518Z",
      "refreshedAt": "2026-11-01T07:07:44.912518Z"
    },
    {
      "granularity": "HOURLY",
      "sourceReadingId": null,
      "recordedAt": null,
      "bucketStart": "2026-11-01T07:00:00Z",
      "bucketEnd": "2026-11-01T08:00:00Z",
      "localDateStart": null,
      "localDateEndExclusive": null,
      "timeZoneId": null,
      "status": "COMPLETE",
      "sourceSampleCount": 360,
      "numericMetrics": {
        "sum": 7752.42,
        "minimum": 21.31,
        "average": 21.5345,
        "maximum": 21.78
      },
      "motionMetrics": null,
      "finalizedAt": "2026-11-01T08:07:56.081932Z",
      "refreshedAt": "2026-11-01T08:07:56.081932Z"
    },
    {
      "granularity": "HOURLY",
      "sourceReadingId": null,
      "recordedAt": null,
      "bucketStart": "2026-11-01T08:00:00Z",
      "bucketEnd": "2026-11-01T09:00:00Z",
      "localDateStart": null,
      "localDateEndExclusive": null,
      "timeZoneId": null,
      "status": "COMPLETE",
      "sourceSampleCount": 360,
      "numericMetrics": {
        "sum": 7799.13,
        "minimum": 21.44,
        "average": 21.66425,
        "maximum": 21.93
      },
      "motionMetrics": null,
      "finalizedAt": "2026-11-01T09:08:07.640275Z",
      "refreshedAt": "2026-11-01T09:08:07.640275Z"
    }
  ]
}
```

A motion sensor uses `motionMetrics` instead of `numericMetrics`. This is one point of a `DAILY` series of sensor 9 (`Hallway`, ESP32, `Europe/Istanbul`), which posted 1,280 motion reports on 31 October 2026:

```json
{
  "granularity": "DAILY",
  "sourceReadingId": null,
  "recordedAt": null,
  "bucketStart": "2026-10-30T21:00:00Z",
  "bucketEnd": "2026-10-31T21:00:00Z",
  "localDateStart": "2026-10-31",
  "localDateEndExclusive": "2026-11-01",
  "timeZoneId": "Europe/Istanbul",
  "status": "COMPLETE",
  "sourceSampleCount": 1280,
  "numericMetrics": null,
  "motionMetrics": {
    "totalSampleCount": 1280,
    "trueSampleCount": 128,
    "falseSampleCount": 1152,
    "truePercentage": 10
  },
  "finalizedAt": "2026-10-31T21:22:05.402117Z",
  "refreshedAt": "2026-10-31T21:22:05.402117Z"
}
```

**Related tests:**

- `StatisticsApiControllerWebMvcTest`: parameter binding with offsets, `AUTO` as the default, JSON fields, and invalid parameters.
- `StatisticsApiIT`: numeric and motion JSON, an owned sensor without readings, and the rejection of an empty time range, against PostgreSQL 17.

---

## Statistics CSV Export Endpoint

`GET /api/sensors/{sensorId}/statistics/export.csv` returns the summaries of one sensor as a CSV file. The statistics page offers it as a normal `GET` download form. The export uses only the summary tiers `HOURLY` and `DAILY`. The reasons for its limits are described in [CSV Export](ARCHITECTURE.md#csv-export).

| Item             | Value                                                          |
|------------------|----------------------------------------------------------------|
| Method and path  | `GET /api/sensors/{sensorId}/statistics/export.csv`            |
| Client           | Statistics page (download form)                                |
| Authentication   | `JSESSIONID` session cookie                                    |
| Request          | Same path variable and query parameters as the series endpoint |
| Success response | `200 OK`, `text/csv;charset=UTF-8` file attachment             |

### Request

The path variable and the query parameters are the same as for the [series endpoint](#statistics-series-endpoint), and the same range rules apply. Only `resolution` behaves differently:

| `resolution`     | Rows                                                                                                                                 |
|------------------|--------------------------------------------------------------------------------------------------------------------------------------|
| `AUTO` (default) | One row per UTC hour if the rows fit the row limit and no row is `EXPIRED` or `ROLLUP_DELAY`; otherwise one row per sensor-local day |
| `HOURLY`         | One row per UTC hour                                                                                                                 |
| `DAILY`          | One row per sensor-local day, without weekly or monthly grouping                                                                     |
| `RAW`            | Rejected with `400 INVALID_STATISTICS_QUERY`                                                                                         |

An export has at most 2,500 rows (`csv-export-row-limit`). If a `HOURLY` or `DAILY` export would have more rows, the response is `400 INVALID_STATISTICS_QUERY`. With the default limits, an `AUTO` export always fits, because the longest allowed range has fewer than 2,500 days.

### Response

#### Response Headers

| Header                   | Value                                                                     |
|--------------------------|---------------------------------------------------------------------------|
| `Content-Type`           | `text/csv;charset=UTF-8`                                                  |
| `Content-Disposition`    | `attachment`, with the file name in `filename` and in `filename*` (UTF-8) |
| `Content-Length`         | Size of the file in bytes                                                 |
| `Cache-Control`          | `no-store`                                                                |
| `X-Content-Type-Options` | `nosniff`                                                                 |

The file name has the form `sensor-{sensorId}-statistics-{resolution}-{start}-to-{end}.csv`. `{resolution}` is the resolved resolution in lower case (`hourly` or `daily`). `{start}` and `{end}` are the evaluated range in UTC, written as in `20261101T060000Z`. Error responses of this endpoint never contain the `Content-Disposition` header.

#### File Format

- The file is UTF-8 without a byte order mark. Lines end with CRLF (`\r\n`), and the first line contains the column names.
- A text cell is quoted with `"` only when it contains a comma, a quote, or a line break. A quote inside the cell is doubled.
- An empty cell means that there is no value.
- Decimal numbers use a dot, no exponent, and no trailing zeros.
- A text cell that could start a spreadsheet formula gets a leading `'`. [CSV Export](ARCHITECTURE.md#csv-export) lists the exact rule.

#### Columns

Columns for all sensor types:

| Column                               | Content                                                      |
|--------------------------------------|--------------------------------------------------------------|
| `granularity`                        | `HOURLY` or `DAILY`                                          |
| `sensor_id`                          | Sensor ID                                                    |
| `sensor_name`                        | Sensor name                                                  |
| `sensor_type`                        | `TEMPERATURE`, `HUMIDITY`, or `MOTION`                       |
| `sensor_time_zone`                   | IANA time zone ID of the sensor                              |
| `period_time_zone`                   | `UTC` for hourly rows; the sensor's time zone for daily rows |
| `local_date`                         | Sensor-local date of a daily row; empty for hourly rows      |
| `period_start_utc`, `period_end_utc` | Interval of the row in UTC, clipped to the evaluated range   |
| `status`                             | Point status of the row                                      |

Then, for temperature and humidity sensors:

| Column                                 | Content                                                                 |
|----------------------------------------|-------------------------------------------------------------------------|
| `sample_count`                         | Number of readings; empty when the row is `EXPIRED` or `ROLLUP_DELAY`   |
| `canonical_storage_unit`               | `C` or `PERCENT`                                                        |
| `metric_unit`                          | `CELSIUS`, `FAHRENHEIT`, `KELVIN`, or `PERCENT`                         |
| `metric_unit_symbol`                   | `°C`, `°F`, `K`, or `% RH`                                              |
| `sum`, `minimum`, `average`, `maximum` | Metrics in `metric_unit`; empty when the row has no readings or no data |

Or, for motion sensors:

| Column                                                          | Content                                                                                |
|-----------------------------------------------------------------|----------------------------------------------------------------------------------------|
| `total_sample_count`, `true_sample_count`, `false_sample_count` | Counts of motion readings; empty when the row has no data                              |
| `true_percentage`                                               | Share of `true` readings, from 0 to 100; empty when the row has no readings or no data |

The last two columns are the same for all sensor types:

| Column                                 | Content                                                                                                               |
|----------------------------------------|-----------------------------------------------------------------------------------------------------------------------|
| `finalized_at_utc`, `refreshed_at_utc` | The same values as `finalizedAt` and `refreshedAt` in the series; empty when the row does not come from a summary row |

### Status Codes

| Status | `code`                     | When                                                                                                                                    |
|--------|----------------------------|-----------------------------------------------------------------------------------------------------------------------------------------|
| `200`  | —                          | The CSV file                                                                                                                            |
| `400`  | `INVALID_REQUEST`          | A required parameter is missing, a date-time cannot be parsed, `resolution` has an unknown value, or `sensorId` is not a 64-bit integer |
| `400`  | `INVALID_STATISTICS_QUERY` | The range breaks a rule, `resolution` is `RAW`, or the export would have more rows than the limit                                       |
| `401`  | `AUTHENTICATION_REQUIRED`  | No logged-in session                                                                                                                    |
| `401`  | `SESSION_EXPIRED`          | The session was expired, for example after a password change                                                                            |
| `404`  | `SENSOR_NOT_FOUND`         | Unknown sensor, or a sensor of another user                                                                                             |
| `406`  | `NOT_ACCEPTABLE`           | The `Accept` header does not allow `text/csv`. The response has the header `Accept: text/csv`.                                          |

### Example

This request exports the same three hours of sensor 7 as the [series example](#statistics-series-endpoint):

```bash
curl -i -b "JSESSIONID=<session-id>" \
  "http://localhost:8080/api/sensors/7/statistics/export.csv?startInclusive=2026-11-01T06:00:00Z&endExclusive=2026-11-01T09:00:00Z"
```

`AUTO` selects `HOURLY`, because three rows fit the limit and all hours are covered. Standard headers, such as `Date` and the security headers that Spring Security adds, are left out:

```text
HTTP/1.1 200
Cache-Control: no-store
X-Content-Type-Options: nosniff
Content-Disposition: attachment; filename="sensor-7-statistics-hourly-20261101T060000Z-to-20261101T090000Z.csv"; filename*=UTF-8''sensor-7-statistics-hourly-20261101T060000Z-to-20261101T090000Z.csv
Content-Type: text/csv;charset=UTF-8
Content-Length: 884

granularity,sensor_id,sensor_name,sensor_type,sensor_time_zone,period_time_zone,local_date,period_start_utc,period_end_utc,status,sample_count,canonical_storage_unit,metric_unit,metric_unit_symbol,sum,minimum,average,maximum,finalized_at_utc,refreshed_at_utc
HOURLY,7,Living room,TEMPERATURE,Europe/Istanbul,UTC,,2026-11-01T06:00:00Z,2026-11-01T07:00:00Z,COMPLETE,360,C,CELSIUS,°C,7704.36,21.12,21.401,21.75,2026-11-01T07:07:44.912518Z,2026-11-01T07:07:44.912518Z
HOURLY,7,Living room,TEMPERATURE,Europe/Istanbul,UTC,,2026-11-01T07:00:00Z,2026-11-01T08:00:00Z,COMPLETE,360,C,CELSIUS,°C,7752.42,21.31,21.5345,21.78,2026-11-01T08:07:56.081932Z,2026-11-01T08:07:56.081932Z
HOURLY,7,Living room,TEMPERATURE,Europe/Istanbul,UTC,,2026-11-01T08:00:00Z,2026-11-01T09:00:00Z,COMPLETE,360,C,CELSIUS,°C,7799.13,21.44,21.66425,21.93,2026-11-01T09:08:07.640275Z,2026-11-01T09:08:07.640275Z
```

To save the file under the name from `Content-Disposition`, use `curl -O -J` instead of `curl -i`.

**Related tests:**

- `StatisticsExportControllerWebMvcTest`: download headers and error responses.
- `StatisticsApiIT`: numeric and motion CSV, and the rejected `RAW` export.

---

## Error Handling (RFC 9457 Problem Details)

All errors of the six API endpoints use RFC 9457 Problem Details: a small JSON object with the content type `application/problem+json`. [Errors Outside the Problem Format](#errors-outside-the-problem-format) lists the few framework errors that use another format. Three handlers in `controller.advice` create the problem responses:

| Handler                        | Errors                                                                                                                                                                                              |
|--------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `ApiExceptionHandler`          | Application errors and Spring MVC errors inside the four API controllers: `SensorIngestionController`, `SensorReadingStreamController`, `StatisticsApiController`, and `StatisticsExportController` |
| `ApiSecurityExceptionHandler`  | Spring Security errors on `/api/**`: missing authentication, denied access, and expired sessions                                                                                                    |
| `ApiMediaTypeExceptionHandler` | `415` and `406` errors on the ingestion, stream, and statistics paths, including the ones that Spring MVC raises before it selects a controller                                                     |

The pages use HTML error pages instead; see the [appendix](#browser-pages-and-html-form-routes-appendix).

### Response Format

Every problem response has the headers `Content-Type: application/problem+json` and `Cache-Control: no-store`.

| Field       | Type    | Content                                                     |
|-------------|---------|-------------------------------------------------------------|
| `type`      | String  | Always `about:blank`                                        |
| `title`     | String  | Standard reason phrase of the status, such as `Bad Request` |
| `status`    | Integer | HTTP status code                                            |
| `detail`    | String  | Fixed English text for the `code`                           |
| `instance`  | String  | Request path, without the query string                      |
| `code`      | String  | Stable error code                                           |
| `timestamp` | Instant | Server time of the error                                    |

- React to `code` in client code. `detail` and `title` are for people.
- The body never contains submitted values, tokens, or the query string. There is no list of field errors.
- `415` and `406` responses also have an `Accept` header that lists the media types that the endpoint supports.

Example: an export request with `resolution=RAW`:

```json
{
  "detail": "The statistics request is invalid",
  "instance": "/api/sensors/7/statistics/export.csv",
  "status": 400,
  "title": "Bad Request",
  "type": "about:blank",
  "code": "INVALID_STATISTICS_QUERY",
  "timestamp": "2026-11-01T09:20:47.318205Z"
}
```

### Application Error Codes

| `code`                     | Status | `detail`                                              | Endpoints                  | Cause                                                                                                                 |
|----------------------------|--------|-------------------------------------------------------|----------------------------|-----------------------------------------------------------------------------------------------------------------------|
| `INVALID_REQUEST`          | `400`  | `The request is invalid`                              | All six                    | A parameter or form field is missing or cannot be converted, or it fails Bean Validation                              |
| `INVALID_SENSOR_READING`   | `400`  | `The sensor reading is invalid`                       | Ingestion                  | Wrong sensor type for the endpoint, `recordedAt` outside the accepted window, or a value that breaks the domain rules |
| `INVALID_STATISTICS_QUERY` | `400`  | `The statistics request is invalid`                   | Series, CSV export         | Invalid range, a range over 1,095 days, point budget or row limit exceeded, or a `RAW` CSV export                     |
| `INVALID_SENSOR_TOKEN`     | `401`  | `The sensor token is invalid`                         | Ingestion                  | No sensor has this ingestion token                                                                                    |
| `AUTHENTICATION_REQUIRED`  | `401`  | `Authentication is required to access this resource`  | `/api/**`                  | No logged-in session                                                                                                  |
| `SESSION_EXPIRED`          | `401`  | `The authenticated session has expired`               | `/api/**`                  | The session was expired, for example after a password change                                                          |
| `ACCESS_DENIED`            | `403`  | `Access to the requested resource is denied`          | `/api/**`                  | Spring Security denied the request, for example a `POST` without a valid CSRF token                                   |
| `SENSOR_NOT_FOUND`         | `404`  | `The requested sensor was not found`                  | Stream, series, CSV export | Unknown sensor, or a sensor of another user                                                                           |
| `INACTIVE_SENSOR`          | `409`  | `The sensor is inactive`                              | Ingestion                  | The sensor is deactivated                                                                                             |
| `STREAM_UNAVAILABLE`       | `503`  | `Recent reading streaming is temporarily unavailable` | Stream                     | 100 streams are open, the stream worker queue is full, or the application is shutting down                            |
| `INTERNAL_ERROR`           | `500`  | `An unexpected error occurred`                        | All six                    | Unexpected server error                                                                                               |

### Framework Error Codes

When Spring MVC rejects a request inside one of the four API controllers, `ApiExceptionHandler` builds the code from the HTTP status. `ApiMediaTypeExceptionHandler` uses the same codes and texts for `406` and `415`.

| Status      | `code`                   | `detail`                                             |
|-------------|--------------------------|------------------------------------------------------|
| `400`       | `INVALID_REQUEST`        | `The request is invalid`                             |
| `404`       | `NOT_FOUND`              | `The requested resource was not found`               |
| `405`       | `METHOD_NOT_ALLOWED`     | `The request method is not supported`                |
| `406`       | `NOT_ACCEPTABLE`         | `The requested response media type is not supported` |
| `413`       | `PAYLOAD_TOO_LARGE`      | `The request payload is too large`                   |
| `415`       | `UNSUPPORTED_MEDIA_TYPE` | `The request media type is not supported`            |
| Other `4xx` | `REQUEST_FAILED`         | `The request could not be completed`                 |
| `5xx`       | `INTERNAL_ERROR`         | `An unexpected error occurred`                       |

Spring MVC raises an unknown path (`404`) and an unsupported HTTP method (`405`) before it selects a controller. These two errors therefore do not reach `ApiExceptionHandler`, and they do not use the problem format; see the next section.

### Errors Outside the Problem Format

`ApiExceptionHandler` handles only errors inside the four API controllers, and `ApiSecurityExceptionHandler` handles only `/api/**`. Spring Boot's default error handling answers these requests instead:

| Request                                                                                                          | Status |
|------------------------------------------------------------------------------------------------------------------|--------|
| A logged-in request to an unknown path under `/api/`, such as `/api/sensors/7/readings`                          | `404`  |
| An HTTP method that the path does not support, such as `GET /readings/temperature` with a logged-in session      | `405`  |
| `PUT`, `PATCH`, or `DELETE` on an ingestion path, or a form `POST` outside `/api/**`, without a valid CSRF token | `403`  |

Each response has the header `Content-Type: application/json` and a JSON body with four fields:

| Field       | Type    | Content                                                    |
|-------------|---------|------------------------------------------------------------|
| `timestamp` | Instant | Server time of the error, always with three decimal places |
| `status`    | Integer | HTTP status code                                           |
| `error`     | String  | Standard reason phrase of the status, such as `Not Found`  |
| `path`      | String  | Request path, without the query string                     |

- The body has no `code` field, so client code must use the HTTP status code instead.
- The `405` response also has an `Allow` header with the methods that the path supports, such as `Allow: POST`.
- Clients that do not ask for HTML, such as `curl`, get this JSON body. Browsers send `Accept: text/html`, so they get an HTML error page instead; see the [appendix](#browser-pages-and-html-form-routes-appendix).

Example: `GET /readings/temperature` with a logged-in session:

```json
{
  "timestamp": "2026-11-02T17:01:12.524Z",
  "status": 405,
  "error": "Method Not Allowed",
  "path": "/readings/temperature"
}
```

Without a logged-in session, an unknown path under `/api/` returns `401 AUTHENTICATION_REQUIRED` in the problem format, because Spring Security checks the session first.

**Related tests:**

- `ApiExceptionHandlerWebMvcTest`: domain errors as problem details, no submitted values in the body, and the mapping from status to code.
- `ApiMediaTypeExceptionHandlerWebMvcTest`: `415` and `406` before a controller is called.
- `ApiSecurityExceptionHandlerTest`: security problem responses.
- `ApiProblemDetailsTest`: standard fields plus `code` and `timestamp`.

---

## Browser Pages and HTML Form Routes (Appendix)

These routes belong to the server-rendered Thymeleaf UI. In a browser, they return an HTML page or a `302` redirect, never JSON or problem details. Every `POST` form must contain the CSRF token in the `_csrf` field. Except on the public routes, a browser request without a session is redirected to `/login`. The account onboarding steps are in the [Setup Guide](SETUP.md#quick-start-with-docker-and-account-onboarding).

- After a successful `POST`, the server redirects (Post/Redirect/Get), so reloading the page does not send the form again.
- On `/register`, the sensor pages, the alert rule page, and the account settings page, invalid input renders the same page again with error messages. The email verification, password recovery, and password change forms redirect instead and show the errors on the next page.
- On the sensor and alert rule pages, an unknown sensor or alert rule shows the `error/404` page (`PageExceptionHandler`). Other page errors use the templates `error/400.html`, `error/404.html`, and `error/500.html`. A status without a template, such as `403` or `405`, shows the default Spring Boot "Whitelabel Error Page".
- Static files under `/css/**`, `/js/**`, and `/images/**`, and the file `/favicon.png`, are public.

### Public Routes

The email verification and password recovery pages remember the pending email address in the HTTP session.

| Method | Path                              | Form fields                                                                                                  | Result                                                                                                                                                                 |
|--------|-----------------------------------|--------------------------------------------------------------------------------------------------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `GET`  | `/login`                          | —                                                                                                            | Login page                                                                                                                                                             |
| `POST` | `/login`                          | `username` (email address), `password`                                                                       | Verified account: redirect to `/`. Unverified account: logout and redirect to `/verify-email?verificationRequired=true`. Wrong credentials: redirect to `/login?error` |
| `GET`  | `/register`                       | —                                                                                                            | Registration page                                                                                                                                                      |
| `POST` | `/register`                       | `email`, `password`, `confirmPassword`, `preferredLanguage`, `preferredTemperatureUnit`, `preferredTimezone` | The account is created and the first verification code is sent; redirect to `/verify-email`                                                                            |
| `GET`  | `/verify-email`                   | —                                                                                                            | Email verification page                                                                                                                                                |
| `POST` | `/verify-email`                   | `code`                                                                                                       | Correct code: redirect to `/login`. Otherwise: redirect to `/verify-email`                                                                                             |
| `POST` | `/verify-email/request`           | `email`                                                                                                      | Requests a code for this address; redirect to `/verify-email`                                                                                                          |
| `POST` | `/verify-email/resend`            | —                                                                                                            | Requests a new code for the pending address; redirect to `/verify-email`                                                                                               |
| `POST` | `/verify-email/change-address`    | —                                                                                                            | Clears the pending address; redirect to `/verify-email`                                                                                                                |
| `GET`  | `/forgot-password`                | —                                                                                                            | Password recovery page                                                                                                                                                 |
| `POST` | `/forgot-password/request`        | `email`                                                                                                      | Requests a reset code for this address; redirect to `/forgot-password`                                                                                                 |
| `POST` | `/forgot-password/reset`          | `code`, `password`, `confirmPassword`                                                                        | Correct code: the password is changed, the session is logged out, redirect to `/login`. Otherwise: redirect to `/forgot-password`                                      |
| `POST` | `/forgot-password/resend`         | —                                                                                                            | Requests a new code for the pending address; redirect to `/forgot-password`                                                                                            |
| `POST` | `/forgot-password/change-address` | —                                                                                                            | Clears the pending address; redirect to `/forgot-password`                                                                                                             |

### Routes for Logged-In Users

| Method | Path                                      | Form fields or query parameters                                                                             | Result                                                                                                                                |
|--------|-------------------------------------------|-------------------------------------------------------------------------------------------------------------|---------------------------------------------------------------------------------------------------------------------------------------|
| `GET`  | `/`                                       | `sensorId` (optional)                                                                                       | Dashboard with the 10 latest readings of the selected sensor; the page opens the reading stream                                       |
| `GET`  | `/statistics`                             | `sensorId`, `preset`, `startDate`, `endDate`, `resolution`, `startInclusive`, `endExclusive` (all optional) | Statistics page with chart, table, and CSV download form                                                                              |
| `GET`  | `/user/sensors`                           | —                                                                                                           | Sensor list and creation form                                                                                                         |
| `POST` | `/user/sensors`                           | `type`, `name`, `city`, `district`, `installationLocation`, `timezone`                                      | The sensor is created; redirect to `/user/sensors`, which shows the ingestion token once                                              |
| `GET`  | `/user/sensors/{sensorId}/edit`           | —                                                                                                           | Edit page                                                                                                                             |
| `POST` | `/user/sensors/{sensorId}/edit`           | `name`, `city`, `district`, `installationLocation`, `timezone`                                              | Redirect to `/user/sensors`                                                                                                           |
| `POST` | `/user/sensors/{sensorId}/activate`       | —                                                                                                           | Redirect to `/user/sensors`                                                                                                           |
| `GET`  | `/user/sensors/{sensorId}/deactivate`     | —                                                                                                           | Confirmation page                                                                                                                     |
| `POST` | `/user/sensors/{sensorId}/deactivate`     | —                                                                                                           | Redirect to `/user/sensors`                                                                                                           |
| `GET`  | `/user/sensors/{sensorId}/delete`         | —                                                                                                           | Confirmation page                                                                                                                     |
| `POST` | `/user/sensors/{sensorId}/delete`         | —                                                                                                           | Deletes the sensor with its readings, summaries, checkpoints, and alert rules; redirect to `/user/sensors`                            |
| `GET`  | `/user/alert-rules`                       | —                                                                                                           | Alert rule list and creation forms                                                                                                    |
| `POST` | `/user/alert-rules/numeric-threshold`     | `sensorId`, `comparisonOperator`, `thresholdValue`, `cooldownMinutes`                                       | Redirect to `/user/alert-rules`                                                                                                       |
| `POST` | `/user/alert-rules/motion-detected`       | `sensorId`, `cooldownMinutes`                                                                               | Redirect to `/user/alert-rules`                                                                                                       |
| `POST` | `/user/alert-rules/{alertRuleId}/enable`  | —                                                                                                           | Redirect to `/user/alert-rules`                                                                                                       |
| `POST` | `/user/alert-rules/{alertRuleId}/disable` | —                                                                                                           | Redirect to `/user/alert-rules`                                                                                                       |
| `POST` | `/user/alert-rules/{alertRuleId}/delete`  | —                                                                                                           | Redirect to `/user/alert-rules`                                                                                                       |
| `GET`  | `/user/settings`                          | —                                                                                                           | Account settings page                                                                                                                 |
| `POST` | `/user/settings`                          | `preferredLanguage`, `temperatureUnit`, `preferredTimezone`                                                 | Redirect to `/user/settings`                                                                                                          |
| `GET`  | `/user/settings/password`                 | —                                                                                                           | Password change page                                                                                                                  |
| `POST` | `/user/settings/password`                 | `currentPassword`, `password`, `confirmPassword`                                                            | Success: all sessions of the user expire, and the browser is redirected to `/login`. Otherwise: redirect to `/user/settings/password` |
| `POST` | `/logout`                                 | —                                                                                                           | Ends the session; redirect to `/login?logout`                                                                                         |

### Form Values

| Field                                              | Accepted values                                                                                                                                                                                                                                                                                   |
|----------------------------------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `type`                                             | `TEMPERATURE`, `HUMIDITY`, or `MOTION`. The type cannot be changed later.                                                                                                                                                                                                                         |
| `name`, `city`, `district`, `installationLocation` | Required, at most 100 characters each. The name must be unique among the owner's sensors, ignoring case.                                                                                                                                                                                          |
| `timezone`, `preferredTimezone`                    | Time zone ID, at most 64 characters. The server accepts only the IANA IDs that the forms offer, such as `Europe/Istanbul`, and rejects offset IDs such as `+03:00`. The sensor time zone is locked after the first reading; see [Two kinds of time zone](ARCHITECTURE.md#two-kinds-of-time-zone). |
| `email`                                            | Valid email address, at most 320 characters                                                                                                                                                                                                                                                       |
| `password`, `currentPassword`                      | At least 8 characters and at most 72 UTF-8 bytes                                                                                                                                                                                                                                                  |
| `confirmPassword`                                  | Must be equal to password                                                                                                                                                                                                                                                                         |
| `code`                                             | Exactly 8 digits                                                                                                                                                                                                                                                                                  |
| `sensorId` (alert rule forms)                      | A sensor of the owner. A numeric threshold rule needs a `TEMPERATURE` or `HUMIDITY` sensor, and a motion rule needs a `MOTION` sensor.                                                                                                                                                            |
| `comparisonOperator`                               | `ABOVE` or `BELOW`                                                                                                                                                                                                                                                                                |
| `thresholdValue`                                   | Finite number, in the owner's temperature unit for temperature sensors and in percent for humidity sensors. After conversion to Celsius, a temperature threshold must be at least `-273.15`. A humidity threshold must be from `0` to `100`.                                                      |
| `cooldownMinutes`                                  | 1 to 10,080                                                                                                                                                                                                                                                                                       |
| `preferredLanguage`                                | `ENGLISH` or `TURKISH`                                                                                                                                                                                                                                                                            |
| `temperatureUnit`, `preferredTemperatureUnit`      | `CELSIUS`, `FAHRENHEIT`, or `KELVIN`                                                                                                                                                                                                                                                              |
| `preset`                                           | `LAST_HOUR`, `TODAY`, `LAST_7_DAYS` (default), `LAST_30_DAYS`, `LAST_90_DAYS`, `ONE_YEAR`, `CUSTOM` (with `startDate` and `endDate` as ISO-8601 dates), or `EXACT` (with `startInclusive` and `endExclusive` as ISO-8601 date-times)                                                              |
| `resolution` (statistics page)                     | `AUTO` (default), `RAW`, `HOURLY`, or `DAILY`                                                                                                                                                                                                                                                     |
