## Description

<!-- Describe the changes proposed in this Pull Request and the problem they solve. -->

## Type of Change

- [ ] **Device Compatibility & Performance:** Budget TV hardware adaptation, 1 GB RAM optimization.
- [ ] **tvOS Interface & Motion:** Visual fidelity, animations, typography, Control Center, screensaver.
- [ ] **Content Providers & Top Shelf:** Streaming provider integration, Watch Next channels, artwork handling.
- [ ] **Localization & Accessibility:** Translations, resource parity, RTL layouts, screen reader accessibility.
- [ ] **TV Debloat List (`examples/`):** New or updated TV model package lists.
- [ ] **Bug Fix / Refactoring / Code Quality:** Resolving defects, improving tests, updating documentation.

## Test Environment

- **Device / Model:** <!-- e.g. Xiaomi Mi TV Stick, Chromecast with Google TV, Android TV Emulator -->
- **Android Version:** <!-- e.g. Android TV 9 / 10 / 11 / 12 -->
- **Firmware / Build (if applicable):** <!-- e.g. build RTT1.230922.001 -->

## Pre-Submission Verification Checklist

Please verify each item before submitting your Pull Request:

- [ ] **JVM Unit Tests:** `./gradlew testReleaseUnitTest` passes with zero failures.
- [ ] **Resource Parity:** If strings were added or changed, parity is maintained across `values/strings.xml` and localized `values-*/strings.xml`.
- [ ] **APK Size Budget:** Release APK remains under the 2 MiB limit (`./gradlew assembleRelease`).
- [ ] **D-pad Navigation:** Remote control navigation was verified from every edge (Up, Down, Left, Right) with zero focus traps.
- [ ] **Diagnostic Privacy:** All attached logs, stack traces, and screenshots have been checked to ensure **no private data** (IP addresses, auth tokens, device serial numbers, Wi-Fi SSIDs, private filesystem paths) is exposed.
- [ ] **Architecture Principles:** No new background services, polling daemons, or unnecessary permissions were introduced.
- [ ] **TV Debloat Lists (if applicable):** Non-destructive disabling only (`pm disable-user`), tested live, no uninstalls, header complete.
- [ ] **Visual Proof:** Screenshot or screen recording attached for UI/visual changes.
