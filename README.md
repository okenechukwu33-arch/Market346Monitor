# Market346Monitor

A two-phone Android monitor for an authorized Market 346 screen.

## What it does

- Same APK works as **Phone 1 Monitor** or **Phone 2 Receiver**.
- Phone 1 uses Android MediaProjection, so Android explicitly asks the user to allow screen capture.
- Phone 1 watches only the selected percentage area of the captured screen.
- Detection uses ML Kit OCR plus secondary red/green color detection to classify **LOCKED/SUSPENDED** or **UNLOCKED/ODDS**.
- Phone 1 exposes the current state on local Wi-Fi over a small HTTP endpoint on port **8765**.
- Phone 2 polls that endpoint every second and alerts on state changes with notification, sound and vibration.
- Alert history is stored locally on Phone 2.

## Important limitation

This version uses a **direct local-network connection** between the two phones. Both phones should be on the same Wi-Fi network. A cloud relay can be added later if the phones must be on different networks.

The detector is intentionally user-authorized and visible. It does not secretly record or upload the phone screen.

## Build without Android Studio

GitHub Actions builds the APK for you.

1. Put this project in a GitHub repository.
2. Open the **Actions** tab.
3. Run **Build Market346 Monitor APK** (or push to main/master).
4. Open the completed workflow run and download the **Market346Monitor-debug** artifact.

## Phone 1

1. Open the app and choose **PHONE 1 • MONITOR**.
2. Set Market ID to 346.
3. Set the monitoring rectangle using percentages. The default is Left 10%, Top 20%, Right 95%, Bottom 78%.
4. Tap **SAVE & START**.
5. Approve Android's screen-capture permission.
6. Open the authorized Market 346/Bet365 screen and keep the phone awake.

## Phone 2

1. Open the same APK and choose **PHONE 2 • RECEIVER**.
2. Enter Phone 1's local IP address, for example `192.168.1.10`.
3. Tap **CONNECT & LISTEN**.
4. Allow notifications so alerts can appear.

## Accuracy

The supplied design shows a green unlocked state and a red locked/suspended state. The detector combines OCR words such as `LOCKED`, `SUSPENDED`, `UNLOCKED`, and `ODDS` with color cues. For production accuracy, test the detector on real screenshots from the exact Market 346 layout and tune the selected area.
