# Jenkins Pipeline Improvements

## Credential Usage

### Recommendation

| Token | Storage | Access Method | Rationale |
|-------|---------|---------------|-----------|
| `SLACK_TOKEN` | Jenkins Credential (Secret text) | `environment { SLACK_TOKEN = credentials('SLACK_TOKEN') }` | Used in multiple stages - single declaration in environment block |
| `ARTIFACTORY_TOKEN` | Jenkins Credential (Secret text) | `withCredentials` in Build stage only | Only needed during build - scoped correctly |

**Key Change:** `SLACK_TOKEN` moved from multiple `withCredentials` blocks to single `environment` declaration. This:
- Reduces boilerplate code
- Makes token available throughout pipeline
- Follows Jenkins best practices for frequently-used credentials

```groovy
environment {
    SLACK_TOKEN = credentials('SLACK_TOKEN')  // Available everywhere
}
```

---

## Pipeline Structure

### Before (13 stages)
```
1. Checkout
2. Data Preparation
3. Restore Caches
4. Validate Inputs
5. Cleanup Before Build
6. Slack Notification Test Run Start
7. Build and Run Tests
8. Clean Simulators
9. Generate Reports (1st Run)
10. Rerun Failed Tests
11. Generate Reports (Rerun)
12. Teardown
13. Generate Test Summary
```

### After (5 stages)
```
1. Setup       - Checkout, validation, caching, start notification
2. Build       - Environment cleanup, project build, stub generation
3. Test        - Execute test suite
4. Rerun       - Rerun failed tests (conditional)
5. Report      - Generate all reports, send notifications
+ Post Actions - Cleanup, publish, archive
```

### Best Practices Applied

| Practice | Implementation |
|----------|----------------|
| **Single Responsibility** | Each stage has one clear purpose |
| **Fail Fast** | Validation happens early in Setup |
| **Minimize Stage Count** | Grouped related operations (13 → 5) |
| **Conditional Execution** | Rerun only when enabled and needed |
| **Separation of Concerns** | Test execution separate from reporting |
| **Cleanup in Post** | Teardown moved to `post.always` block |

---

## Stage Details

### Stage 1: Setup
Combines: Checkout + Data Preparation + Restore Caches + Validate Inputs + Slack Start Notification

- Checkout both repositories
- Collect build metadata (tags, commits, branches)
- Validate scheme, device, runtime exist
- Initialize cache directories
- Send Slack start notification

### Stage 2: Build
Combines: Cleanup Before Build + Build and Run Tests (build portion)

- Kill stale processes and simulators
- Clean workspace
- Configure Xcode version
- Install dependencies (with caching)
- Build project with `rake buildspecs`
- Generate test stubs

### Stage 3: Test
Single responsibility: Execute tests

- Resolve specific test methods if provided
- Run xcodebuild with configured parameters
- Handle exit codes (0=pass, 65=test failures, others=infrastructure fail)

### Stage 4: Rerun Failed (Conditional)
Only runs when `RERUN_ENABLED=true` AND failures exist

- Generate JUnit from 1st run to check failures
- Extract failed test methods
- Clean simulators
- Rerun with parallel disabled

### Stage 5: Report
Combines: Generate Reports (1st Run) + Generate Reports (Rerun) + Clean Simulators + Generate Test Summary

- Generate JUnit XML from xcresult
- Generate failed tests CSV
- Generate Allure report
- Send Slack notifications with results
- Archive reports

### Post Actions
Moved from stages: Teardown + Test Summary

- Kill remaining processes
- Publish Allure report
- Publish JUnit results
- Publish HTML report
- Archive artifacts
- Handle build failure notifications

---

## Slack: Why the "first run" message didn't send (e.g. build #146)

The **"Test Run Completed"** (first run results) message is sent only in the **Report** stage. If a **prior stage fails**, the pipeline skips Report and that message is never sent.

In build **#146**:

1. **Start notification** was sent successfully (the failure post used the same thread `thread_ts`, so the initial post had succeeded).
2. **Test** stage ran (tests ran a long time due to simulator/runtime issues).
3. **Rerun** stage failed with a parse error when building `-only-testing:...` arguments (parentheses in test names broke the shell).
4. Because **Rerun** failed, the pipeline went straight to **post { failure { ... } }** and never ran **Report**.
5. So the code that sends the first-run summary to Slack (in Report) never ran — **Slack did not fail**; the message was simply never sent.

Takeaway: any stage failure before Report (Build, Test, or Rerun) will prevent the first-run results message from being sent. The pipeline now logs the Slack API response for every post so you can confirm success or see errors in the Jenkins log.

---

## Helper Methods Refactored

### New Map-based Test Runner
```groovy
runXCUITests(
    scheme: params.SCHEME_NAME,
    device: params.DEVICE_NAME,
    runtime: params.RUNTIME_VERSION,
    workers: params.WORKERS,
    outputDir: TEST_OUTPUT,
    resultName: FILE_NAME_PATTERN,
    disableParallel: false,
    enableShipped: 'YES',
    testMethods: ''
)
```

### Extracted Report Generation
```groovy
generateJunitReport(xcresultPath, outputPath)
generateFailedTestsCsv(junitPath, csvPath)
extractFailedTestMethods(junitPath, scheme)
```

---

## Configuration

### Required Jenkins Credentials
| ID | Type | Purpose |
|----|------|---------|
| `SLACK_TOKEN` | Secret text | Slack Bot Token (xoxb-...) |
| `ARTIFACTORY_TOKEN` | Secret text | Artifactory access |
| `nordstrom_github_token` | Username/Password | GitHub access |

### Required Jenkins Plugins
| Plugin | Purpose |
|--------|---------|
| Git Parameter | Branch selection |
| JUnit | Test result publishing |
| HTML Publisher | HTML reports |
| Allure | Allure reports |
| Slack Notification | File uploads |

### Default Parameters
| Parameter | Default |
|-----------|---------|
| RUN_CONFIG | iPhone 12 \| 26.3.1 \| XCUITests-P0 \| 6 \| aqa-reports \| Xcode.app \| YES |
| Rerun | Enabled |
| FLA_BRANCH / INTG_BRANCH | main |

---

## Migration Checklist

- [x] Create Jenkins credential: `SLACK_TOKEN`
- [x] Create Jenkins credential: `ARTIFACTORY_TOKEN`
- [ ] Install HTML Publisher plugin
- [ ] Test with XCUITests-P0 scheme first
- [ ] Verify Slack notifications work
- [ ] Verify Allure report generation
