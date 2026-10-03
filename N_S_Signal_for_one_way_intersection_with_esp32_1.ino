#include <WiFi.h>
#include <WebServer.h>

// ─────────────────────────────────────────────
// ESP32 Access Point Credentials
// ─────────────────────────────────────────────
const char* ap_ssid     = "TrafficController";
const char* ap_password = "12345678";

// ESP32 will run the backend server
WebServer server(5050);

// Latest status JSON for UI polling
String latestJson = "{\"status\":\"waiting\"}";

// ─────────────────────────────────────────────
// Pins
// ─────────────────────────────────────────────
#define NS_RED    25
#define NS_YELLOW 26
#define NS_GREEN  27
#define NS_SENSOR 34

#define EW_RED    14
#define EW_YELLOW 12
#define EW_GREEN  13
#define EW_SENSOR 35

// Timing
#define MIN_GREEN   4000
#define MAX_GREEN   10000
#define YELLOW_TIME 2000
#define RED_BUFFER  1000

// ─────────────────────────────────────────────
// UI → ESP32 (POST /update)
// ─────────────────────────────────────────────
void handleUpdate() {
  if (!server.hasArg("plain")) {
    server.send(400, "application/json", "{\"error\":\"no body\"}");
    return;
  }

  String body = server.arg("plain");
  Serial.println("UI sent: " + body);

  server.send(200, "application/json", "{\"status\":\"ok\"}");
}

// ─────────────────────────────────────────────
// UI polls ESP32 (GET /status)
// ─────────────────────────────────────────────
void handleStatus() {
  server.send(200, "application/json", latestJson);
}

// ─────────────────────────────────────────────
// ESP32 updates UI state
// ─────────────────────────────────────────────
void sendData(int lightId, String color, int durationMs, int sensorActive) {
  latestJson = "{\"light_id\":" + String(lightId) +
               ",\"color\":\"" + color + "\"" +
               ",\"duration_ms\":" + String(durationMs) +
               ",\"sensor_active\":" + String(sensorActive) + "}";

  Serial.println("[STATUS] " + latestJson);
}

// ─────────────────────────────────────────────
// Traffic Logic
// ─────────────────────────────────────────────
void handlePhase(int redPin, int yellowPin, int greenPin,
                 int oppRedPin, int sensorPin, int lightId) {

  // GREEN
  digitalWrite(redPin, LOW);
  digitalWrite(yellowPin, LOW);
  digitalWrite(greenPin, HIGH);
  digitalWrite(oppRedPin, HIGH);

  Serial.printf("Light %d → GREEN\n", lightId);

  unsigned long start = millis();
  unsigned long currentDuration = MIN_GREEN;
  bool sensorTriggered = false;

  sendData(lightId, "GREEN", currentDuration, 0);

  while (millis() - start < currentDuration) {
    if (digitalRead(sensorPin) == LOW) {
      sensorTriggered = true;
      if (currentDuration < MAX_GREEN) {
        currentDuration += 1000;
        Serial.printf("  Sensor! Extending green to %lu ms\n", currentDuration);
        sendData(lightId, "GREEN", currentDuration, 1);
        delay(500);
      }
    }
    delay(10);
  }

  // YELLOW
  digitalWrite(greenPin, LOW);
  digitalWrite(yellowPin, HIGH);
  Serial.printf("Light %d → YELLOW\n", lightId);
  sendData(lightId, "YELLOW", YELLOW_TIME, sensorTriggered ? 1 : 0);
  delay(YELLOW_TIME);

  // RED
  digitalWrite(yellowPin, LOW);
  digitalWrite(redPin, HIGH);
  Serial.printf("Light %d → RED\n", lightId);
  sendData(lightId, "RED", RED_BUFFER, 0);
  delay(RED_BUFFER);
}

// ─────────────────────────────────────────────
// Setup
// ─────────────────────────────────────────────
void setup() {
  Serial.begin(115200);

  pinMode(NS_RED, OUTPUT);
  pinMode(NS_YELLOW, OUTPUT);
  pinMode(NS_GREEN, OUTPUT);
  pinMode(NS_SENSOR, INPUT);

  pinMode(EW_RED, OUTPUT);
  pinMode(EW_YELLOW, OUTPUT);
  pinMode(EW_GREEN, OUTPUT);
  pinMode(EW_SENSOR, INPUT);

  digitalWrite(NS_RED, HIGH);
  digitalWrite(EW_RED, HIGH);

  // Start Access Point
  WiFi.softAP(ap_ssid, ap_password);
  Serial.println("AP Started");
  Serial.print("SSID: "); Serial.println(ap_ssid);
  Serial.print("Password: "); Serial.println(ap_password);
  Serial.print("IP: "); Serial.println(WiFi.softAPIP());

  // Setup server routes
  server.on("/update", HTTP_POST, handleUpdate);
  server.on("/status", HTTP_GET, handleStatus);

  server.begin();
  Serial.println("Server running on port 5050");
}

// ─────────────────────────────────────────────
// Loop
// ─────────────────────────────────────────────
void loop() {
  server.handleClient();

  handlePhase(NS_RED, NS_YELLOW, NS_GREEN, EW_RED, NS_SENSOR, 1);
  handlePhase(EW_RED, EW_YELLOW, EW_GREEN, NS_RED, EW_SENSOR, 2);
}
