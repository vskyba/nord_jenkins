# Jenkins Pipeline — Verification Checklist

> Summary of all requests from the conversation, the solutions applied, and what to verify.
> Use this file to validate the current `jenkins.groovy` against every requirement.

---

## Context

- **File:** `jenkins.groovy` — Jenkins declarative pipeline for iOS XCUI tests.
- **Machine:** macOS agent, Xcode, iOS Simulators, Ruby/Bundler tooling.
- **Slack:** Uses `curl` to `chat.postMessage`; file uploads via `slackUploadFile` plugin.
- **Build log analyzed:** `#146.txt` — a ~3h failed run.

---

## Request 1: Analyze why build #146 took ~3 hours and Slack messages were missing

### Root cause (from #146.txt)
- **Long run:** The simulator couldn't launch the app — "iOS 26.3.1 runtime not found" warning appeared early, but the pipeline continued. xcodebuild retried endlessly (~9069s test time, ~942s stub generation).
- **Missing Slack "first run" message:** The "Test Run Completed" message is only sent in the **Report** stage. In #146, the **Rerun** stage failed with a **shell parse error** (parentheses in test names broke `-only-testing:` arguments), so the pipeline skipped Report and went to `post { failure }`. Only the "Build Failed" message was sent (and it succeeded: `"ok":true`).

### Verify
- [ ] Report stage comment explains this behavior.
- [ ] `post { failure }` always sends a failure notification to Slack.
- [ ] Rerun test methods are properly quoted to handle `()` in test names.

---

## Request 2: Fix rerun parse error — `()` in test names

### Problem
When passing `-only-testing:SomeClass/testMethod(param)` to xcodebuild via shell, the `()` were interpreted by the shell, causing a parse error.

### Solution applied
In the test runner, each `-only-testing:` token should be double-quoted to prevent shell expansion.

### Verify in current `jenkins.groovy`
- [ ] In `stage('Rerun Failed')` (line ~325-337): `failedTests` is passed directly in `${failedTests}`. Check if the Ruby `failed_tests_from_xml` method returns pre-quoted `-only-testing:` args or if they need quoting here.
- [ ] **POTENTIAL ISSUE:** The current code passes `${failedTests}` without quoting. If test names contain `()`, spaces, or shell metacharacters, this will fail. Verify the Ruby method output format. If it returns space-separated `-only-testing:Foo/bar(baz)` tokens, they need to be individually quoted.

---

## Request 3: Rewrite parameters — device params in one choice, with simulator ID

### Requirements
- Merge **only device-related** params (device name, iOS version, simulator UUID) into one choice.
- Default: **iPhone 12 | 26.3.1 | 0D445113-3DEE-49CB-B377-6764CF67535E**.
- Other params (scheme, workers, Slack channel, etc.) stay separate.
- When simulator ID is provided, use `-destination "id=<UUID>"`.
- When no ID, use `-destination "platform=iOS Simulator,name=<device>,OS=<version>"`.

### Verify in current `jenkins.groovy`
- [ ] **MISSING:** `SIMULATOR_DEVICE` parameter with `device|version|optionalId` format is not present. Current params are separate `DEVICE` and `IOS_VERSION` without simulator ID support.
- [ ] **MISSING:** Default choice should be `iPhone 12|26.3.1|0D445113-3DEE-49CB-B377-6764CF67535E`.
- [ ] **MISSING:** `IOS_VERSION` choices should include `26.3.1` (currently has `18.0, 17.5, 16.4`).
- [ ] **MISSING:** `runXCUITests` or equivalent should use `id=<UUID>` when simulator ID is present.
- [ ] Pipeline should parse the combined device choice into separate variables at the start of Setup.

---

## Request 4: Make logs more useful

### Requirements
- **Suppress noisy output:** Build stage and first test run should not flood the log. Only show the last N lines; full log goes to a file.
- **Add Slack API response logging:** After every Slack `curl`, log the response body so you can see `{"ok":true/false,...}` or curl errors.

### Verify in current `jenkins.groovy`
- [ ] **BUILD LOG:** `bundle exec rake buildspecs[...]` (line 217) outputs everything to console. Should pipe/redirect to a file and show only the tail. Currently: no suppression.
- [ ] **TEST LOG:** `xcodebuild test` (line 253-270) has `set +e` (good), `-quiet` flag (good), but all output goes to console. Should redirect to file and `tail -N`. Currently: no file redirect.
- [ ] **SLACK RESPONSE:** `slackSend` (line 8-30) calls `curl ... 2>/dev/null || true` — stderr is silenced and curl errors are swallowed by `|| true`. Response is parsed but never logged. If `ok` is false or curl fails, there's no log line.
- [ ] Add `echo "Slack API response: ${response}"` or similar after curl in `slackSend`.
- [ ] Remove `2>/dev/null` from the curl call (or change to `2>&1`) so curl error messages (like "Could not resolve host") are captured.

---

## Request 5: Explain why Slack failed for "first run" message in #146

### Answer (documented)
Slack didn't fail — the message was **never sent**. The first-run results message is sent only in the **Report** stage. In #146, the **Rerun** stage failed (parse error), so Report was skipped. Only the `post { failure }` "Build Failed" message was sent, and it succeeded.

### Verify
- [ ] This is documented clearly in the pipeline or in IMPROVEMENTS.md.

---

## Request 6: Slack retry logic + curl response logging

### Requirements
- Retry sending the same message if it failed the first time (up to 3 attempts, 5s delay).
- Log the curl response body after every attempt.
- Never fail the build because of a Slack error.

### Verify in current `jenkins.groovy`
- [ ] **MISSING:** `slackSend` has no retry logic. Single attempt, failures silenced.
- [ ] **MISSING:** No response logging — `2>/dev/null || true` suppresses everything.
- [ ] The `|| true` ensures the build doesn't fail on Slack errors (good).

---

## Request 7: Replace `localhost` with `skynet-studio.org` in all links

### Verify in current `jenkins.groovy`
- [ ] **MISSING:** `BUILD_URL` is used directly (line 189, 363, 404) without replacing `localhost`.
- [ ] All Slack messages that include `BUILD_URL` should use `BUILD_URL.replace('localhost', 'skynet-studio.org')` or equivalent.

---

## Request 8: Preserve multiline Slack report format

### Required format
```
*Test Run Completed Successfully!*
*Build:* <link|#N>

*Job Name:* `name`
*Start Time:* `time`
*Duration:* `Nm`

*Test Summary:*
- *Workers:* `N`
- *Executed:* `N` | *Failed:* `N` | *Disabled:* `N`
```

### Verify in current `jenkins.groovy`
- [ ] Current report format (line 362-365) is compact, not the requested multiline format.
- [ ] Missing: Job Name, Start Time, Workers in the report message.

---

## Summary of gaps in current `jenkins.groovy` (vs. all requests)

| # | Requirement | Status |
|---|------------|--------|
| 1 | Rerun `()` quoting fix | **NEEDS VERIFICATION** — `${failedTests}` unquoted on line 336 |
| 2 | Combined SIMULATOR_DEVICE param with UUID | **MISSING** — separate DEVICE + IOS_VERSION, no UUID, no 26.3.1 |
| 3 | Test log suppression (redirect to file + tail) | **MISSING** — full output to console |
| 4 | Build log suppression | **MISSING** — `rake buildspecs` outputs everything |
| 5 | Slack response logging | **MISSING** — `2>/dev/null || true` suppresses all |
| 6 | Slack retry logic (3 attempts) | **MISSING** |
| 7 | `localhost` → `skynet-studio.org` | **MISSING** |
| 8 | Multiline Slack report format | **PARTIAL** — compact format, missing fields |
| 9 | Allure link in failure reports | **MISSING** |
| 10 | `set +e` in test runner | **PRESENT** (line 255, 326) |
| 11 | Build/test logs archived | **PRESENT** for build log (line 397), test log not redirected to file |

---

## How to use this file

A Claude agent should:
1. Read `jenkins.groovy` and this checklist.
2. For each **MISSING** or **NEEDS VERIFICATION** item, apply the fix.
3. Check off each item after verification.
4. Run the pipeline and confirm from Jenkins logs that:
   - Slack messages include `skynet-studio.org` links.
   - Slack API responses appear in the log.
   - Build/test output is suppressed (only tail shown).
   - Rerun handles test names with `()`.
   - `SIMULATOR_DEVICE` param with `0D445113-3DEE-49CB-B377-6764CF67535E` is available.
