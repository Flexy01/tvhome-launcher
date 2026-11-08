# Contributing to TvHome

Thank you for your interest in contributing to TvHome Launcher! TvHome is an independent, lightweight, open-source Apple TV (tvOS)-styled launcher built specifically for Android TV devices, with extreme optimization for budget hardware with 1 GB of RAM.

This document outlines our technical principles, local development environment setup, quality standards, and Pull Request workflow.

---

## Contribution Priorities

We welcome contributions across several key areas:

1. **Android TV Device Compatibility & Performance (Priority 1):**
   - Verification on real budget TV hardware (e.g. Xiaomi Mi TV Stick/Box, Chromecast, TCL, Philips, Sony).
   - Keeping RAM usage minimal (zero memory leaks, efficient bitmap recycling, no background services or polling).
   - Deterministic D-pad navigation across all UI elements and edges (Up, Down, Left, Right).

2. **tvOS Interface Fidelity & Motion (Priority 2):**
   - Accurate Apple TV (tvOS) visual design language, subtle spring animations, and typography (Inter font via OFL 1.1).
   - Smooth dock focus scaling, dynamic card elevation, glass blur effects, and immersive backdrop transitions.
   - Control Center, screensaver (Aerials / Clock), and quick-action panels.

3. **Content Providers & Top Shelf Integrations (Priority 3):**
   - Expanding dynamic Top Shelf support for streaming providers and Android TV `TvContract` / Watch Next channels.
   - Dynamic backdrop and poster art extraction, parsing, and caching.

4. **Localization & Accessibility (Priority 4):**
   - Adding or refining translations across locales while preserving resource parity with `values/strings.xml`.
   - Right-to-Left (RTL) layout fidelity (e.g. Hebrew `values-iw` / `layout-iw`, Arabic `values-ar`).
   - High-contrast readability and screen-reader accessibility for TV viewports.

5. **Code Quality, Security & Test Coverage (Priority 5):**
   - Maintaining 100% passing JVM unit tests and automated resource parity checks.
   - Diagnostic privacy discipline (zero leakage of personal identifiers, IP addresses, or tokens).

---

## Technical Architecture & Constraints

To ensure TvHome remains responsive on 1 GB RAM devices:

- **Pure Java + AndroidX:** We deliberately avoid Kotlin runtime overhead, Jetpack Compose, and heavy third-party UI libraries. Use AndroidX `RecyclerView`, `DynamicAnimation`, and standard Android views.
- **No Background Services:** The launcher does not run continuous background polling services or daemons.
- **Deterministic D-pad:** Remote control navigation must never leave focus in a dead end. Test all 4 directions from every edge.
- **No Hardcoded Device Logic:** Hardware- or vendor-specific tweaks belong in `app/src/main/res/values/config.xml` or custom themes, not in Java source code.
- **Package Name Stability:** The technical package name `com.gathin.tvhome` must be preserved for compatibility with existing TV installations and scripts.
- **Strict APK Size Budget:** The release APK must strictly remain **under 2 MiB** (currently ~1.44 MiB). Do not add large dependencies or uncompressed assets.

---

## Development Environment Setup

### Prerequisites

- **JDK 17:** Eclipse Temurin 17 or OpenJDK 17 is recommended.
- **Android SDK:**
  - Target SDK: Platform 34 (`platforms;android-34`)
  - Build Tools: `34.0.0`
- **Gradle:** Bundled Gradle wrapper (`gradlew`) is provided in `launcher/`.
- **Python 3.12+:** For running repository verification tests and ADB helper scripts.

### Quickstart

1. **Clone the repository:**
   ```bash
   git clone https://github.com/Flexy01/tvhome-launcher.git
   cd tvhome-launcher
   ```

2. **Build the Debug APK:**
   ```bash
   cd launcher
   ./gradlew assembleDebug
   ```

3. **Run the JVM Unit Test Suite:**
   ```bash
   ./gradlew testReleaseUnitTest
   ```
   *(All unit tests must pass with zero failures).*

4. **Build the Release APK and Verify Size:**
   ```bash
   ./gradlew assembleRelease
   ```

---

## Local Verification & Testing

Before submitting a Pull Request, verify your changes against our automated gates:

### 1. JVM Unit Tests & Lint
```bash
cd launcher
./gradlew testReleaseUnitTest lintRelease
```

### 2. Localization Resource Parity
Verify that your changes maintain parity between default strings and localized translations:
```bash
python -m unittest discover -s tests -p test_hebrew_resources.py -v
```

### 3. Full Repository Test Suite (Linux / WSL2)
The Python test suite validates debloat lists, path normalization, and security rules:
```bash
python3 -m unittest discover -s tests -v
```
*(Note: Shell-based path tests require POSIX execution via Linux, macOS, or WSL2).*

### 4. Testing on Real TV Hardware via ADB
To test your debug build on your TV without wiping existing user configurations:
```bash
# Connect to your TV over ADB
adb connect <tv-ip-address>:5555

# Install debug APK preserving app data (-r)
adb install -r launcher/app/build/outputs/apk/debug/app-debug.apk

# Launch TvHome
adb shell monkey -p com.gathin.tvhome -c android.intent.category.LAUNCHER 1
```

To capture a screenshot from the TV for your PR:
```bash
adb shell screencap -p /sdcard/tvhome-preview.png
adb pull /sdcard/tvhome-preview.png .
adb shell rm /sdcard/tvhome-preview.png
```

---

## Contributing TV Debloat Lists (`examples/`)

If you tested debloating on a TV model not yet listed in `examples/`:

1. Create `examples/<brand>-<model>/debloat.txt`.
2. Follow the standard format: one package per line, grouped with comments explaining their purpose.
3. Include TV model name, Android version, and firmware build in the header.
4. **Never list packages to uninstall.** Only use reversible disabling (`pm disable-user`).
5. Only list packages you have personally tested and lived with on hardware.
6. Add a row to `examples/README.md`.

---

## Pull Request Guidelines

1. **Atomic Changes:** Keep pull requests focused on a single feature, improvement, or bug fix.
2. **Fill Out the PR Template:** Complete all sections of `.github/pull_request_template.md`.
3. **Attach Visual Evidence:** For UI or animation changes, include a screenshot or short GIF captured from real TV hardware or an Android TV emulator.
4. **Privacy Protection:** Thoroughly inspect attached logs, stack traces, and screenshots. **Redact all sensitive information** (IP addresses, Wi-Fi SSIDs, authentication tokens, serial numbers, private paths).
5. **No Telemetry or Ads:** TvHome will never include ads, tracking, or user telemetry.
6. **License Compliance:** All contributions are released under the project's MIT License. Original upstream author attribution (`Copyright (c) 2026 Gathin`) must be respected and preserved.
7. **Code of Conduct:** All contributors and participants must adhere to our [Code of Conduct](CODE_OF_CONDUCT.md). Harassment or interpersonal conduct violations must be reported privately to `tvhomelauncher@gmail.com`, never in public pull requests or issues.

---

## Community & Contact

- **Questions, bug reports & TV compatibility:** [GitHub Issues](https://github.com/Flexy01/tvhome-launcher/issues)
- **Code of Conduct & moderation reports:** email `tvhomelauncher@gmail.com` privately (see [`CODE_OF_CONDUCT.md`](CODE_OF_CONDUCT.md))
- **Security vulnerabilities:** Private Vulnerability Reporting via the repository Security tab (see [`SECURITY.md`](SECURITY.md))
- **General inquiries:** `tvhomelauncher@gmail.com` for non-public maintainer correspondence
