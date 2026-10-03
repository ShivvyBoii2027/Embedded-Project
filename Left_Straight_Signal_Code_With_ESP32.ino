// ---------------- PIN DEFINITIONS ----------------
const int RED = 25;
const int YELLOW = 26;
const int GREEN = 27;

const int L_RED = 32;
const int L_YELLOW = 33;
const int L_GREEN = 14;

// ---------------- TIMING ----------------
int leftGreenTime = 5000;
int straightGreenTime = 7000;
int yellowTime = 2000;
int blinkInterval = 300;

// ---------------- FUNCTIONS ----------------
void allRed() {
  digitalWrite(RED, HIGH);
  digitalWrite(YELLOW, LOW);
  digitalWrite(GREEN, LOW);

  digitalWrite(L_RED, HIGH);
  digitalWrite(L_YELLOW, LOW);
  digitalWrite(L_GREEN, LOW);
}

void runNS() {

  // LEFT GREEN
  digitalWrite(RED, HIGH);
  digitalWrite(GREEN, LOW);
  digitalWrite(YELLOW, LOW);

  digitalWrite(L_RED, LOW);
  digitalWrite(L_GREEN, HIGH);
  digitalWrite(L_YELLOW, LOW);

  delay(leftGreenTime);

  // LEFT RED
  digitalWrite(L_GREEN, LOW);
  digitalWrite(L_RED, HIGH);
  delay(300);

  // STRAIGHT GREEN + LEFT YELLOW BLINK
  digitalWrite(RED, LOW);
  digitalWrite(GREEN, HIGH);
  digitalWrite(L_RED, LOW);

  unsigned long startBlink = millis();
  unsigned long endBlink = startBlink + straightGreenTime;

  while (millis() < endBlink) {
    digitalWrite(L_YELLOW, HIGH);
    delay(blinkInterval);
    digitalWrite(L_YELLOW, LOW);
    delay(blinkInterval);
  }

  // BOTH SOLID YELLOW
  digitalWrite(GREEN, LOW);
  digitalWrite(YELLOW, HIGH);

  digitalWrite(L_YELLOW, HIGH);
  digitalWrite(L_RED, LOW);

  delay(yellowTime);

  // ALL RED
  allRed();
}

void setup() {
  pinMode(RED, OUTPUT);
  pinMode(YELLOW, OUTPUT);
  pinMode(GREEN, OUTPUT);

  pinMode(L_RED, OUTPUT);
  pinMode(L_YELLOW, OUTPUT);
  pinMode(L_GREEN, OUTPUT);

  allRed();
}

void loop() {
  unsigned long t = millis() % 30000;

  if (t < 15000) {
    runNS();

    // WAIT until the 15-second NS window ends
    while ((millis() % 30000) < 15000) {
      allRed();
    }

  } else {
    allRed();
  }
}