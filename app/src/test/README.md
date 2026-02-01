# 🧪 DashBuddy Recognition Pipeline Tests

This directory contains the **Snapshot Testing Infrastructure** for the DashBuddy Screen Recognition
Pipeline. Unlike standard unit tests, this system is **data-driven**, using captured UI
hierarchies (JSON) to verify that the app correctly identifies screens.

## 📂 Directory Structure

* **`java/.../matchers/`**: Contains the test logic.
    * `InboxProcessorTest.kt`: The "Bot" that sorts new snapshots.
    * `UnknownScreenAnalysisTest.kt`: The "X-Ray" tool for analyzing unrecognized screens.
    * `SensitiveDataScanTest.kt`: Safety check for PII.
    * `*RegressionTest.kt`: Standard tests for known screen types.
* **`resources/snapshots/`**: Contains the test data.
    * `INBOX/`: The landing zone for raw, unclassified JSON dumps.
    * `SENSITIVE/`: **Redacted** snapshots of banking/personal screens.
    * `[SCREEN_TYPE]/`: Categorized snapshots for regression testing.

---

## 🚀 The Inbox Workflow (How to Add Tests)

We use an **"Inbox Zero"** approach to adding new test cases. You do not need to manually create
folders or move files.

### 1. Capture & Dump

Export the UI Node hierarchy (as JSON) from the device logs or the debugger.
Drop the raw `.json` files into:
`app/src/test/resources/snapshots/INBOX/`

*(Note: The `INBOX` folder is `.gitignored` to prevent accidental commits of raw data.)*

### 2. Run the Processor

Run the **`InboxProcessorTest`** in Android Studio.

This automation bot performs three actions:

1. **Safety Check:** Scans for sensitive keywords (Bank, Routing #, etc.).
2. **Recognition:** Runs the files against the pipeline.
3. **Auto-Sort:**
    * **If Recognized:** It **MOVES** the file to the correct regression folder (e.g.,
      `snapshots/DASH_PAUSED/`).
    * **If Sensitive:** It **FAILS** and prints the sensitive text (see below).
    * **If Unknown:** It leaves the file in `INBOX` and prints an X-Ray report.

### 3. Handle the Results

* **✅ Files Disappeared?** Good. They were recognized and moved to their folders. You can now commit
  them.
* **❌ Test Failed (Sensitive)?** The file contains PII.
    1. Open the JSON file in `INBOX`.
    2. Manually replace numbers/names with `[REDACTED]`.
    3. Move the file manually to `snapshots/SENSITIVE/`.
* **❓ Files Left in Inbox?** These are **Unknown Screens**.
    1. Read the "X-Ray Report" in the console output.
    2. Use the text/IDs found to write a new `ScreenMatcher`.
    3. Add the matcher to `TestMatcherFactory.kt`.
    4. Run `InboxProcessorTest` again to auto-sort them.

---

## 🛡️ Handling Sensitive Data

**Strict Rule:** Never commit raw financial or personal data to Git.

1. **Detection:** The `SensitiveScreenMatcher` runs first. It triggers on keywords like "Routing
   Number", "Available Balance", etc.
2. **Redaction:** You must manually edit the JSON files to remove values while preserving the
   structure (IDs/Layouts).
3. **Verification:** Place redacted files in `snapshots/SENSITIVE/` and run
   `SensitiveScreenRegressionTest` to ensure they are still recognized as "Sensitive" (and thus
   blocked from logging).

---

## 🏗️ Adding New Matchers

If you create a new `ScreenMatcher`:

1. **Define:** Create the matcher class in `src/main/java/.../matchers/`.
2. **Register:** Add the new matcher to **`TestMatcherFactory.kt`**.
    * *Note: This mimics the Dagger/Hilt injection graph for local tests.*
3. **Test:** Run `InboxProcessorTest` to verify it now picks up previously "Unknown" files.

---

## 🚦 Running Regression

To verify the entire pipeline (e.g., before a Pull Request):

Run **`AllMatchersSuite`**.

This executes all regression tests (`IdleMap`, `DashPaused`, `Loading`, etc.) in parallel, ensuring
no recent changes have broken existing recognition logic.