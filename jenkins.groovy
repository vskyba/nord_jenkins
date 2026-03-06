/* groovylint-disable GStringExpressionWithinString, LineLength */
// Avoid groovy.json.JsonSlurper in pipeline: it returns LazyMap which causes NotSerializableException when Jenkins serializes state.

// ═══════════════════════════════════════════════════════════════════════════
// ═  GLOBAL STATE
// ═  Slack thread, timestamps, test counts, device/runtime from SIMULATOR_DEVICE
// ═══════════════════════════════════════════════════════════════════════════

def SLACK_CURRENT_THREAD = ''
def JOB_START_TIMESTAMP = ''
def FAILED_COUNT_1ST = '0'
def RERUN_SKIPPED = true
def RERUN_TEST_OUTPUT = ''
def STARTTIME = 0
def RERUN_STARTTIME = 0
def DEVICE_NAME = ''
def RUNTIME_VERSION = ''
def SIMULATOR_ID = ''
def NOW = ''
def BUILD_TAG = ''
def BUILD_LAST_COMMIT = ''
def STUBS_COMMIT = ''
def FILE_NAME_PATTERN = ''
def TEST_OUTPUT_REL = ''
def RERUN_TEST_OUTPUT_REL = ''
def TEST_OUTPUT = ''

// ═══════════════════════════════════════════════════════════════════════════
// ═  HELPERS
// ═  Slack API, test counters, duration formatting, xcodebuild test runner
// ═  JSON built manually to stay within Jenkins script-security sandbox.
// ═══════════════════════════════════════════════════════════════════════════

/** Sandbox-safe: escape string for JSON value (so Slack receives newlines as \\n in JSON). */
def escapeForJson(Object s) {
    if (s == null) return 'null'
    def t = s.toString()
    return '"' + t.replace('\\', '\\\\').replace('"', '\\"').replace('\n', '\\n').replace('\r', '\\r') + '"'
}

/** Sandbox-safe: serialize value to JSON (no JsonOutput). */
def toJsonValue(Object v) {
    if (v == null) return 'null'
    if (v instanceof Boolean) return v ? 'true' : 'false'
    if (v instanceof Number) return v.toString()
    if (v instanceof String) return escapeForJson(v)
    if (v instanceof List) return '[' + v.collect { toJsonValue(it) }.join(',') + ']'
    if (v instanceof Map) return '{' + v.entrySet().collect { escapeForJson(it.key.toString()) + ':' + toJsonValue(it.value) }.join(',') + '}'
    return escapeForJson(v.toString())
}

def slackPostInitialMessage(String text, String channel) {
    int maxAttempts = 5
    int delaySec = 5
    def payload = toJsonValue([channel: channel, text: text])
    def payloadFile = "${env.WORKSPACE}/slack_payload_init.json"
    writeFile file: payloadFile, text: payload

    for (int attempt = 1; attempt <= maxAttempts; attempt++) {
        def result = sh(
            script: """
                set +e
                _out=\$(curl -4 -sS --connect-timeout 15 --retry 2 --retry-delay 3 -X POST \
                     -H "Authorization: Bearer \$SLACK_TOKEN" \
                     -H "Content-Type: application/json; charset=utf-8" \
                     -d @"${payloadFile}" \
                     https://slack.com/api/chat.postMessage 2>&1)
                echo "CURL_EXIT:\$?"
                echo "\$_out"
                exit 0
            """,
            returnStdout: true
        ).trim()

        def lines = result.split('\n')
        def curlExit = lines.find { it.startsWith('CURL_EXIT:') }?.replaceFirst('CURL_EXIT:', '') ?: '?'
        def slackResponse = lines.findAll { !it.startsWith('CURL_EXIT:') }.join('\n').trim()
        echo "Slack API (start) attempt ${attempt}/${maxAttempts} (curl exit: ${curlExit}): ${slackResponse}"

        if (curlExit == '0' && slackResponse?.trim()) {
            if (slackResponse.contains('"ok":true')) {
                def matcher = (slackResponse =~ /"ts"\s*:\s*"([^"]+)"/)
                if (matcher.find()) {
                    return matcher.group(1) ?: ''
                }
            } else {
                echo "Slack post failed: ${slackResponse}"
            }
        }

        if (attempt < maxAttempts) {
            echo "Retrying Slack start post in ${delaySec}s..."
            sh(script: "sleep ${delaySec}", returnStatus: true)
        }
    }

    echo "Slack start post failed after ${maxAttempts} attempts. Continuing without thread_ts."
    return ''
}

def slackPostMessage(String message, String channel, String thread) {
    int maxAttempts = 5
    int delaySec = 5
    def payload = thread?.trim() ?
        toJsonValue([channel: channel, thread_ts: thread.trim(), text: message]) :
        toJsonValue([channel: channel, text: message])
    def payloadFile = "${env.WORKSPACE}/slack_payload.json"
    writeFile file: payloadFile, text: payload

    for (int attempt = 1; attempt <= maxAttempts; attempt++) {
        def result = sh(
            script: """
                set +e
                _out=\$(curl -4 -sS --connect-timeout 15 --retry 2 --retry-delay 3 -X POST \
                     -H "Authorization: Bearer \$SLACK_TOKEN" \
                     -H "Content-Type: application/json; charset=utf-8" \
                     -d @"${payloadFile}" \
                     https://slack.com/api/chat.postMessage 2>&1)
                echo "CURL_EXIT:\$?"
                echo "\$_out"
                exit 0
            """,
            returnStdout: true
        ).trim()

        def lines = result.split('\n')
        def curlExit = lines.find { it.startsWith('CURL_EXIT:') }?.replaceFirst('CURL_EXIT:', '') ?: '?'
        def slackResponse = lines.findAll { !it.startsWith('CURL_EXIT:') }.join('\n').trim()
        echo "Slack API attempt ${attempt}/${maxAttempts} (curl exit: ${curlExit}): ${slackResponse}"

        if (curlExit == '0' && slackResponse?.trim()) {
            if (slackResponse.contains('"ok":true')) {
                return
            }
            echo "Slack post failed: ${slackResponse}"
        } else {
            echo "Slack post may have failed (curl exit ${curlExit}, e.g. 6=resolve host)."
        }

        if (attempt < maxAttempts) {
            echo "Retrying Slack post in ${delaySec}s..."
            sh(script: "sleep ${delaySec}", returnStatus: true)
        }
    }

    echo "Slack post failed after ${maxAttempts} attempts. Build continues."
}

/** Uploads a file to Slack (channel, optional thread) with initial comment. Uses Slack files.upload API. On failure, posts message only. */
def uploadFileToSlack(String filePath, String text, String channel, String thread) {
    if (!fileExists(filePath)) {
        echo "Slack upload skipped: file not found: ${filePath}. Posting message only."
        slackPostMessage(text, channel, thread)
        return
    }
    def threadVal = thread?.trim() ?: ''
    try {
        writeFile file: "${env.WORKSPACE}/.slack_upload_comment.txt", text: text ?: ''
        def result = sh(script: """
            set +e
            comment=\$(cat "${env.WORKSPACE}/.slack_upload_comment.txt")
            _out=\$(curl -4 -sS -X POST \\
              -H "Authorization: Bearer \$SLACK_TOKEN" \\
              -F "channels=${channel}" \\
              -F "thread_ts=${threadVal}" \\
              -F "initial_comment=\$comment" \\
              -F "file=@${filePath}" \\
              https://slack.com/api/files.upload 2>&1)
            _ec=\$?
            echo "\$_out"
            echo "CURL_EXIT:\$_ec"
        """, returnStdout: true).trim()
        def lines = result.split('\n')
        def curlExit = lines.find { it.startsWith('CURL_EXIT:') }?.replaceFirst('CURL_EXIT:', '') ?: '?'
        def slackResponse = lines.findAll { !it.startsWith('CURL_EXIT:') }.join('\n').trim()
        if (curlExit == '0' && slackResponse?.contains('"ok":true')) {
            echo "Slack file upload succeeded for ${filePath}"
        } else {
            echo "Slack file upload failed (curl exit: ${curlExit}). Posting message only. Response: ${slackResponse.take(200)}"
            slackPostMessage(text, channel, thread)
        }
    } catch (Exception e) {
        echo "Slack file upload failed: ${e.message}. Posting message only."
        slackPostMessage(text, channel, thread)
    }
}

/**
 * Reads test counts from a JUnit report using Reports.summary_counts only.
 * Returns [failed, executed, disabled] as strings; uses '0' when file missing or parse fails.
 * Call with the path to the JUnit XML for that run (first run or rerun).
 */
def readSummaryCounts(String junitPath) {
    def safe = { v -> (v != null && v != 'null' && v?.trim() != '') ? v.trim() : '0' }
    def out = [failed: '0', executed: '0', disabled: '0']
    if (!fileExists(junitPath)) return out
    def result = sh(
        script: """
            export JUNIT_PATH="${junitPath}"
            bundle exec ruby -r ./ruby/project/test.rb -e 'c = Project::Test::Reports.summary_counts(ENV[\"JUNIT_PATH\"]); puts \"failed=\"+c[:failed].to_s; puts \"executed=\"+c[:executed].to_s; puts \"disabled=\"+c[:disabled].to_s' 2>/dev/null || true
        """,
        returnStdout: true
    ).trim()
    result.split('\n').each { line ->
        def parts = line.split('=', 2)
        if (parts.size() == 2 && out.containsKey(parts[0].trim())) {
            out[parts[0].trim()] = safe(parts[1].trim())
        }
    }
    return out
}

def formatDuration(long durationSec) {
    def hours = (durationSec / 3600).intValue()
    def minutes = ((durationSec % 3600) / 60).intValue()
    return hours > 0 ? "${hours}h ${minutes}m" : "${minutes}m"
}

/** Normalized BUILD_URL for links (localhost → skynet-studio.org). */
def normalizedBuildUrl() {
    def raw = env.BUILD_URL ?: ''
    return raw.replaceAll('(?i)localhost', 'skynet-studio.org').replace('127.0.0.1', 'skynet-studio.org')
}

/** Builds Slack test-run summary. Pass disabled only for 1st run (null for rerun → not shown). */
def buildSlackMessage(boolean isSuccess, String jobName, String now, String duration,
                      String workers, String failed, String executed,
                      String disabled = null,
                      List<String> extraLines = []) {
    def status = isSuccess ? "Completed Successfully" : "Completed with Failures"
    def buildUrl = normalizedBuildUrl()
    def buildLink = buildUrl ? "*Build:* <${buildUrl}|#${BUILD_NUMBER}>" : ""
    def allureReportUrl = buildUrl ? (buildUrl.endsWith('/') ? "${buildUrl}allure/" : "${buildUrl}/allure/") : ''
    def allureLink = allureReportUrl ? "\n*Allure Report:* <${allureReportUrl}|View report>" : ""
    def disabledPart = (disabled != null && disabled != '') ? "  • Disabled: `${disabled}`" : ""

    def body = """*Test Run ${status}*

${buildLink}${allureLink}

*Job:* `${jobName}`
*Started:* `${now}`
*Duration:* `${duration}`

*Summary*
• Executed: `${executed}`  • Failed: `${failed}`  • Workers: `${workers}`${disabledPart}"""
    if (extraLines) {
        body += "\n\n" + extraLines.findAll { it?.trim() }.join("\n")
    }
    return body
}

def runXCUITests(Map config) {
    def parallelOptions = config.disableParallel ?
        '-parallel-testing-enabled NO' :
        "-parallel-testing-enabled YES -parallel-testing-worker-count ${config.workers}"

    def testMethodsArg = config.testMethods ?: ''
    // Reuse Build DerivedData (prebuild-once; same as fla-ios squad_specific_test_run)
    def derivedDataPath = config.derivedDataPath ?: "${config.outputDir}/DerivedData"
    def destination = (config.simulatorId ?: '').trim() ?
        "id=${config.simulatorId}" :
        "platform=iOS Simulator,name=${config.device},OS=${config.runtime}"

    echo "────────────────────  xcodebuild test: ${config.scheme}  ────────────────────"
    echo "Running tests: ${config.scheme} (parallel: ${!config.disableParallel}, full log: ${config.outputDir}/test-run.log)"
    def exitCode = sh(
        script: """
            set +e
            export AUTOMATION_ENABLE_SHIPPED_FEATURE_FLAGS=${config.enableShipped}
            BUILD_ID=dontKillMe xcodebuild test \
                -project Nordstrom.xcodeproj \
                -scheme "${config.scheme}" \
                -destination "${destination}" \
                ${parallelOptions} \
                -onlyUsePackageVersionsFromResolvedFile \
                -derivedDataPath "${derivedDataPath}" \
                -resultBundlePath "${config.outputDir}/${config.resultName}.xcresult" \
                -skipPackagePluginValidation \
                -quiet \
                ${testMethodsArg ? testMethodsArg.split().collect { '"' + it.replaceAll('"', '\\\\"') + '"' }.join(' ') : ''} \
                > "${config.outputDir}/test-run.log" 2>&1
            _xc=\$?
            echo "────────────────────  Last 100 lines of test run  ────────────────────"
            tail -100 "${config.outputDir}/test-run.log"
            exit \$_xc
        """,
        returnStatus: true
    )

    echo "────────────────────  xcodebuild test finished (exit code: ${exitCode})  ────────────────────"
    echo "xcodebuild test finished (exit code: ${exitCode})"
    // Exit: 0 = success, 65 = test failures (ok), other = infrastructure failure
    if (exitCode != 0 && exitCode != 65) {
        error "Build/infrastructure failure (exit code ${exitCode})"
    }

    return exitCode
}

/** Converts xcresult to JUnit XML using xcresultparser (available on server). */
def generateJunitReport(String xcresultPath, String outputPath) {
    sh "xcresultparser -o junit \"${xcresultPath}\" > \"${outputPath}\" 2>/dev/null || true"

    if (fileExists(outputPath)) {
        // Normalize failure messages; path via env for safe Ruby -e
        sh """
            export JUNIT_FIX_PATH="${outputPath}"
            bundle exec ruby -r ./ruby/project/test.rb -e 'Project::Test.fix_junit_failure_messages(ENV[\"JUNIT_FIX_PATH\"])' 2>/dev/null || true
        """
    }
}

def generateFailedTestsCsv(String junitPath, String csvPath) {
    sh """
        export JUNIT_CSV_IN="${junitPath}"
        export JUNIT_CSV_OUT="${csvPath}"
        bundle exec ruby -r ./ruby/project/test.rb -e 'Project::Test::Reports.generate_failed_tests_to_csv(ENV[\"JUNIT_CSV_IN\"], ENV[\"JUNIT_CSV_OUT\"])' 2>/dev/null || ~/scripts/genFailedFromXML.py \"${junitPath}\" || true
    """
}

def extractFailedTestMethods(String junitPath, String scheme) {
    return sh(
        script: """
            export XML_PATH="${junitPath}"
            export SCHEME_NAME="${scheme}"
            bundle exec ruby -r ./ruby/project/test.rb -e 'result = Project::Test.failed_tests_from_xml(ENV[\"XML_PATH\"], ENV[\"SCHEME_NAME\"]); puts result' 2>/dev/null || echo ""
        """,
        returnStdout: true
    ).trim()
}

/** Returns [flakyCount, realCount, flakyNote, flakyTestNames] by comparing 1st run vs rerun failed tests. flakyTestNames = test name part (after last /) for CSV Flaky column. */
def computeFlakyAndReal(String firstRunJunit, String rerunJunit, String scheme) {
    def firstFailed = extractFailedTestMethods(firstRunJunit, scheme).split(/\s+/).findAll { it?.trim() }.toSet()
    def rerunFailed = extractFailedTestMethods(rerunJunit, scheme).split(/\s+/).findAll { it?.trim() }.toSet()
    def flaky = firstFailed - rerunFailed
    def real = rerunFailed
    def flakyNote = (flaky.size() > 0 || real.size() > 0) ? "Flaky: ${flaky.size()} | Real failures: ${real.size()}" : ''
    // Extract test name (e.g. testFoo()) from "-only-testing:bundle/Class/testFoo()" for fla-ios CSV
    def flakyTestNames = flaky.collect { it.split('/').last()?.trim() ?: '' }.findAll { it }
    return [flakyCount: flaky.size(), realCount: real.size(), flakyNote: flakyNote, flakyTestNames: flakyTestNames]
}

/** Returns a short root-cause hint string from JUnit failure/error messages (keyword clustering). */
def getFailureHints(String junitPath) {
    if (!fileExists(junitPath)) return ''
    try {
        def xml = readFile(junitPath)
        def matcher = (xml =~ /<(?:failure|error)[^>]*message="([^"]*)"[^>]*>/)
        def messages = []
        while (matcher.find()) { messages << matcher.group(1) }
        if (messages.isEmpty()) {
            matcher = (xml =~ /<(?:failure|error)[^>]*>([^<]+)/)
            while (matcher.find()) { messages << matcher.group(1) }
        }
        if (messages.isEmpty()) return ''
        def keywords = [
            'timeout': 0, 'timed out': 0, 'wait': 0,
            'element': 0, 'not found': 0, 'located': 0,
            'assertion': 0, 'assert': 0, 'expected': 0,
            'nil': 0, 'null': 0, 'optional': 0,
            'crash': 0, 'fatal': 0, 'exception': 0,
            'network': 0, 'connection': 0, 'url': 0
        ]
        def lower = messages.collect { it?.toLowerCase() ?: '' }
        keywords.keySet().each { k ->
            keywords[k] = lower.count { it.contains(k) }
        }
        def sorted = keywords.findAll { it.value > 0 }.sort { -it.value }
        def buckets = sorted.size() > 3 ? sorted[0..2] : sorted
        if (buckets.isEmpty()) return ''
        return "Hints: " + buckets.collect { "${it.key} (${it.value})" }.join(', ')
    } catch (Exception e) {
        echo "Failure hints parse error: ${e.message}"
        return ''
    }
}

/** Writes run_summary.json (run-level metrics + flaky/hints/anomaly) for archiving and dashboards. */
def writeRunSummary(String outputDir, Map summary) {
    def path = "${outputDir}/run_summary.json"
    writeFile file: path, text: toJsonValue(summary)
    echo "Written ${path}"
}

/** Escapes string for safe use in HTML. */
def escapeHtml(String s) {
    if (s == null) return ''
    return s.toString()
        .replace('&', '&amp;')
        .replace('<', '&lt;')
        .replace('>', '&gt;')
        .replace('"', '&quot;')
}

/** Generates run_summary.html for visualising run summary (open from Jenkins artifacts or Run Summary report). */
def writeRunSummaryHtml(Map summary, String outputDir) {
    def json = toJsonValue(summary)
    def jsonEscaped = json.replace('\\', '\\\\').replace('</', '<\\/').replace('<!--', '<\\!--')
    def hintsEscaped = escapeHtml(summary.failure_hints as String)
    def anomalyEscaped = escapeHtml(summary.anomaly as String)
    def flakyListHtml = (summary.flaky_tests && summary.flaky_tests.size() > 0)
        ? summary.flaky_tests.collect { escapeHtml(it as String) }.join('\n')
        : ''
    def html = """<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>Run Summary · ${summary.job_name} #${summary.build_id}</title>
  <style>
    body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; margin: 1rem; background: #f5f5f5; }
    h1 { font-size: 1.25rem; margin: 0 0 1rem 0; color: #333; }
    .card { background: #fff; border-radius: 8px; box-shadow: 0 1px 3px rgba(0,0,0,0.1); padding: 1rem; margin-bottom: 1rem; }
    .card h2 { font-size: 0.875rem; margin: 0 0 0.5rem 0; color: #666; text-transform: uppercase; letter-spacing: 0.05em; }
    .grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(140px, 1fr)); gap: 0.75rem; }
    .metric { background: #f8f9fa; padding: 0.5rem 0.75rem; border-radius: 6px; }
    .metric .value { font-weight: 600; font-size: 1.1rem; }
    .metric .label { font-size: 0.75rem; color: #666; }
    .insight { padding: 0.5rem 0; border-bottom: 1px solid #eee; }
    .insight:last-child { border-bottom: none; }
    .anomaly { background: #fff3cd; border-left: 4px solid #ffc107; padding: 0.5rem 0.75rem; border-radius: 0 6px 6px 0; }
    .flaky-list { font-family: monospace; font-size: 0.8rem; max-height: 200px; overflow-y: auto; }
    .muted { color: #999; font-size: 0.875rem; }
  </style>
</head>
<body>
  <h1>Run Summary · ${summary.job_name} #${summary.build_id}</h1>
  <div class="card">
    <h2>Run info</h2>
    <div class="grid">
      <div class="metric"><span class="label">Scheme</span><div class="value">${summary.scheme ?: '-'}</div></div>
      <div class="metric"><span class="label">Timestamp</span><div class="value muted">${summary.timestamp_iso ?: '-'}</div></div>
      <div class="metric"><span class="label">Rerun</span><div class="value">${summary.has_rerun ? 'Yes' : 'No'}</div></div>
    </div>
  </div>
  <div class="card">
    <h2>Metrics</h2>
    <div class="grid">
      <div class="metric"><span class="label">Duration</span><div class="value">${summary.duration_sec ?: 0}s</div></div>
      <div class="metric"><span class="label">Executed</span><div class="value">${summary.executed ?: '0'}</div></div>
      <div class="metric"><span class="label">Failed</span><div class="value">${summary.failed ?: '0'}</div></div>
      <div class="metric"><span class="label">Disabled</span><div class="value">${summary.disabled ?: '0'}</div></div>
    </div>
  </div>
  <div class="card">
    <h2>Insights</h2>
    <div class="insight"><span class="label">Flaky / Real</span> ${summary.flaky_count ?: 0} flaky, ${summary.real_count ?: 0} real</div>
    ${hintsEscaped ? "<div class=\"insight\"><span class=\"label\">Hints</span> ${hintsEscaped}</div>" : ''}
    ${anomalyEscaped ? "<div class=\"insight anomaly\"><span class=\"label\">Anomaly</span> ${anomalyEscaped}</div>" : ''}
    ${flakyListHtml ? "<div class=\"insight\"><span class=\"label\">Flaky tests</span><pre class=\"flaky-list\">" + flakyListHtml + "</pre></div>" : ''}
  </div>
  <div class="card muted">
    <small>Generated from run_summary.json. Raw: <a href="run_summary.json">run_summary.json</a></small>
  </div>
  <script type="application/json" id="run-summary-data">${jsonEscaped}</script>
</body>
</html>"""
    writeFile file: "${outputDir}/run_summary.html", text: html
    echo "Written ${outputDir}/run_summary.html"
}

/** Checks duration/fail count vs recent baseline; returns anomaly message or ''. Baseline in ~/JenkinsTestResults/JOB_NAME/baseline.json. */
def checkAnomaly(long durationSec, String failedCountStr, String executedCountStr, String jobName) {
    def failedCount = (failedCountStr != null && failedCountStr != 'null') ? failedCountStr.toInteger() : 0
    def baselineDir = "${env.HOME}/JenkinsTestResults/${jobName}"
    def baselineFile = "${baselineDir}/baseline.json"
    def keep = 30
    def entries = []
    try {
        def raw = sh(script: "cat '${baselineFile}' 2>/dev/null || echo '[]'", returnStdout: true).trim()
        // Assign only the collected list (plain maps); never hold parseText result (LazyMap) in a variable.
        entries = ((new groovy.json.JsonSlurper().parseText(raw)) as List).collect { [d: (it?.d ?: 0) as Long, f: (it?.f ?: 0) as Integer] }
    } catch (Exception e) {
        echo "Baseline read skipped: ${e.message}"
    }
    entries << [d: durationSec, f: failedCount]
    if (entries.size() > keep) entries = entries[-keep..-1]
    def json = '[' + entries.collect { "{\"d\":${it.d},\"f\":${it.f}}" }.join(',') + ']'
    def tmpFile = "${env.WORKSPACE}/.baseline_tmp.json"
    writeFile file: tmpFile, text: json
    sh "mkdir -p '${baselineDir}' && cp '${tmpFile}' '${baselineFile}'"
    if (entries.size() < 5) return ''
    def durations = entries.collect { it.d as Long }
    def fails = entries.collect { it.f as Integer }
    def avgD = durations.sum() / durations.size()
    def avgF = fails.sum() / fails.size()
    def stdD = Math.sqrt(durations.collect { (it - avgD) ** 2 }.sum() / durations.size())
    def stdF = Math.sqrt(fails.collect { (it - avgF) ** 2 }.sum() / fails.size())
    def msgs = []
    if (stdD > 0 && durationSec > avgD + 2 * stdD) {
        msgs << "Duration ${durationSec}s is ~${(durationSec / avgD).intValue()}x recent avg (${avgD.intValue()}s)"
    }
    if (stdF > 0 && failedCount > avgF + 2 * stdF && failedCount > 0) {
        msgs << "Fail count ${failedCount} much higher than recent avg (${avgF.intValue()})"
    }
    return msgs ? "Anomaly: " + msgs.join('; ') : ''
}

// ═══════════════════════════════════════════════════════════════════════════
// ═  PIPELINE
// ═  Setup → Build → Test → Rerun (optional) → Report → Post
// ═══════════════════════════════════════════════════════════════════════════

pipeline {
    agent any
    tools { git 'Default' }

    options {
        disableConcurrentBuilds()
        timeout(time: 20, unit: 'HOURS')
        timestamps()
        buildDiscarder(logRotator(numToKeepStr: '30', artifactNumToKeepStr: '10'))
    }

    environment {
        SLACK_TOKEN = credentials('SLACK_TOKEN')
        COCOAPODS_CACHE = "${env.HOME}/.cocoapods"
        BUNDLE_PATH = "${env.WORKSPACE}/vendor/bundle"
        SPM_CACHE_DIR = "${env.WORKSPACE}/spm_cache"
    }

    parameters {
        gitParameter(
            branchFilter: 'origin/(.*)',
            defaultValue: 'main',
            name: 'FLA_BRANCH',
            type: 'PT_BRANCH',
            description: 'fla-ios branch',
            selectedValue: 'DEFAULT',
            useRepository: 'https://github.com/Nordstrom-Internal/APP01031-fla-ios.git',
            quickFilterEnabled: true,
            sortMode: 'ASCENDING_SMART'
        )
        gitParameter(
            branchFilter: 'origin/(.*)',
            defaultValue: 'main',
            name: 'INTG_BRANCH',
            type: 'PT_BRANCH',
            description: 'integration-tests-stubs branch',
            selectedValue: 'DEFAULT',
            useRepository: 'https://github.com/Nordstrom-Internal/APP01031-integration-tests-stubs.git',
            quickFilterEnabled: true,
            sortMode: 'ASCENDING_SMART'
        )
        choice(
            name: 'SIMULATOR_DEVICE',
            choices: [
                'iPhone 12|26.3.1|0D445113-3DEE-49CB-B377-6764CF67535E',
                'iPhone 12|26.3.1',
                'iPhone 12|18.0',
                'iPhone 12 Pro Max|26.3.1',
                'iPhone 16 Pro|26.3.1',
                'iPhone 15|26.3.1',
                'iPhone 14|26.3.1',
                'iPhone SE|26.3.1'
            ],
            description: 'Simulator: Device | iOS version | optional UUID. Default: iPhone 12, 26.3.1 (ID 0D44...)'
        )
        choice(
            name: 'SCHEME_NAME',
            choices: [
                'XCUITests-AllTests', 'XCUITests', 'XCUITests-General', 'XCUITests-FullLine',
                'XCUITests-SevenSummits', 'XCUITests-ThunderMountains', 'XCUITests-Fuji',
                'XCUITests-SevenSummits-Rack', 'XCUITests-ThunderMountains-Rack', 'XCUITests-Fuji-Rack',
                'XCUITests-P0', 'XCUITests-P1', 'XCUITests-Prod', 'XCUITests-Prod-Rack',
                'XCUITests-P0-Rack', 'XCUITests-Rack', 'XCUITests-Account', 'XCUITests-Checkout',
                'XCUITests-PDP', 'XCUITests-PHP', 'XCUITests-SBN', 'XCUITests-MAST',
                'XCUITests-Looks', 'XCUITests-Analytics'
            ],
            description: 'Test scheme'
        )
        choice(
            name: 'WORKERS',
            choices: ['6', '1', '2', '4', '8'],
            description: 'Parallel workers'
        )
        choice(
            name: 'REPORT_CHANNEL',
            choices: ['C02RQMF7DFF', 'CJDQEHCF5'],
            description: 'Slack channel (C02RQMF7DFF=aqa-reports, CJDQEHCF5=ios-test-reports)'
        )
        choice(
            name: 'XCODE_VERSION',
            choices: ['Xcode.app', 'Xcode_16.3.app'],
            description: 'Xcode version'
        )
        choice(
            name: 'ENABLE_SHIPPED_FEATURE_FLAGS',
            choices: ['YES', 'NO'],
            description: 'Enable shipped feature flags'
        )
        booleanParam(
            name: 'RERUN_ENABLED',
            defaultValue: true,
            description: 'Rerun failed tests'
        )
        string(
            name: 'TEST_METHODS',
            defaultValue: '',
            description: 'Specific test methods (comma-separated, empty = all)'
        )
    }

    stages {

        // ┌─────────────────────────────────────────────────────────────────────┐
        // │  STAGE 1: SETUP                                                      │
        // │  Checkout fla-ios + integration-tests-stubs · validate · caches · Slack
        // └─────────────────────────────────────────────────────────────────────┘
        stage('Setup') {
            steps {
                script {
                    echo """
╔══════════════════════════════════════════════════════════════════════════╗
║  STAGE 1: SETUP                                                          ║
║  Checkout fla-ios + integration-tests-stubs · validate · caches · Slack  ║
╚══════════════════════════════════════════════════════════════════════════╝"""
                    def parts = params.SIMULATOR_DEVICE.split('\\|')
                    if (parts.size() >= 2) {
                        DEVICE_NAME = parts[0].trim()
                        RUNTIME_VERSION = parts[1].trim()
                        SIMULATOR_ID = (parts.size() >= 3 && parts[2].trim()) ? parts[2].trim() : ''
                    }
                    JOB_START_TIMESTAMP = sh(script: "date +%s", returnStdout: true).trim()

                    echo "────────────────────  Checkout repositories  ────────────────────"
                    echo "[Setup] Checking out repositories..."
                    checkout([
                        $class: 'GitSCM',
                        branches: [[name: "*/${params.FLA_BRANCH}"]],
                        userRemoteConfigs: [[
                            url: 'https://github.com/Nordstrom-Internal/APP01031-fla-ios.git',
                            credentialsId: 'nordstrom_github_token'
                        ]]
                    ])

                    dir("${env.WORKSPACE}/integration-tests-stubs") {
                        checkout([
                            $class: 'GitSCM',
                            branches: [[name: "*/${params.INTG_BRANCH}"]],
                            userRemoteConfigs: [[
                                url: 'https://github.com/Nordstrom-Internal/APP01031-integration-tests-stubs.git',
                                credentialsId: 'nordstrom_github_token'
                            ]]
                        ])
                    }

                    echo "────────────────────  Collect build information  ────────────────────"
                    echo "[Setup] Collecting build information..."
                    NOW = sh(script: "date +'%d/%m/%Y %r'", returnStdout: true).trim()
                    BUILD_TAG = sh(script: 'git describe --tags "$(git rev-list --tags --max-count=1)" 2>/dev/null || echo "untagged-$(git rev-parse --short HEAD)"', returnStdout: true).trim()
                    BUILD_LAST_COMMIT = sh(script: 'git log --pretty=format:"%h | %an: %s" -n 1 2>/dev/null || echo "N/A"', returnStdout: true).trim()
                    STUBS_COMMIT = sh(script: 'cd integration-tests-stubs && git log --pretty=format:"%h | %an: %s" -n 1 2>/dev/null || echo "N/A"', returnStdout: true).trim()

                    FILE_NAME_PATTERN = "${JOB_NAME}_${BUILD_ID}_${BUILD_TAG}"
                    TEST_OUTPUT_REL = 'fastlane/test_output'
                    RERUN_TEST_OUTPUT_REL = 'fastlane/rerun_test_output'
                    TEST_OUTPUT = "${env.WORKSPACE}/${TEST_OUTPUT_REL}"

                    echo "────────────────────  Validate inputs  ────────────────────"
                    echo "[Setup] Validating inputs..."
                    def validationErrors = []

                    def schemeExists = sh(script: "xcodebuild -list -project Nordstrom.xcodeproj 2>/dev/null | grep -E \"^\\\\s*${params.SCHEME_NAME}\\\\s*\\\$\" && echo 'yes' || echo 'no'", returnStdout: true).trim()
                    if (schemeExists == 'no') {
                        validationErrors << "Scheme '${params.SCHEME_NAME}' not found in project"
                    }

                    def deviceExists = sh(script: "xcrun simctl list devices 2>/dev/null | grep -q '${DEVICE_NAME}' && echo 'yes' || echo 'no'", returnStdout: true).trim()
                    if (deviceExists == 'no') {
                        validationErrors << "Device '${DEVICE_NAME}' not found"
                    }

                    def runtimeExists = sh(script: "xcrun simctl list runtimes 2>/dev/null | grep -q 'iOS ${RUNTIME_VERSION}' && echo 'yes' || echo 'no'", returnStdout: true).trim()
                    if (runtimeExists == 'no') {
                        validationErrors << "iOS ${RUNTIME_VERSION} runtime not found"
                    }

                    if (validationErrors) {
                        validationErrors.each { echo "WARNING: ${it}" }
                    }

                    echo "────────────────────  Setup caches  ────────────────────"
                    echo "[Setup] Setting up caches..."
                    sh """
                        mkdir -p "${COCOAPODS_CACHE}" "${BUNDLE_PATH}" "${SPM_CACHE_DIR}" || true
                        echo "Cache sizes:"
                        du -sh "${COCOAPODS_CACHE}" 2>/dev/null || echo "  CocoaPods: empty"
                        du -sh "${BUNDLE_PATH}" 2>/dev/null || echo "  Bundle: empty"
                    """

                    echo "────────────────────  Send start notification to Slack  ────────────────────"
                    echo "[Setup] Sending start notification to Slack..."
                    def startMsg = """*Test Run Started*

*Build:* <${normalizedBuildUrl()}|Jenkins #${BUILD_NUMBER}>
*Branch fla-ios:* `${params.FLA_BRANCH}`  •  *Branch intg-stubs:* `${params.INTG_BRANCH}`  •  *Tag:* `${BUILD_TAG}`
*Scheme:* `${params.SCHEME_NAME}`

*Config:* ${DEVICE_NAME}  •  iOS ${RUNTIME_VERSION}  •  ${params.WORKERS} workers

_Commits_
• fla-ios: `${BUILD_LAST_COMMIT}`
• stubs: `${STUBS_COMMIT}`"""

                    SLACK_CURRENT_THREAD = slackPostInitialMessage(startMsg, params.REPORT_CHANNEL)
                }
            }
        }

        // ┌─────────────────────────────────────────────────────────────────────┐
        // │  STAGE 2: BUILD                                                     │
        // │  Clean env · xcbeautify · ACKNOWLEDGEMENTS · prebuild · build-for-testing · stubs
        // │  (Same flow as fla-ios squad_specific_test_run; direct xcodebuild to avoid warning-as-failure)
        // └─────────────────────────────────────────────────────────────────────┘
        stage('Build') {
            steps {
                script {
                    echo """
╔══════════════════════════════════════════════════════════════════════════╗
║  STAGE 2: BUILD                                                          ║
║  Clean env · xcbeautify · ACKNOWLEDGEMENTS · prebuild · build-for-testing · stubs
╚══════════════════════════════════════════════════════════════════════════╝"""
                    echo "────────────────────  Clean environment  ────────────────────"
                    echo "[Build] Cleaning environment..."
                    sh """
                        rake clean || true
                        killall Xcode Simulator 'Problem Reporter' 2>/dev/null || true
                        pkill -9 -f com.apple.CoreSimulator.CoreSimulatorService 2>/dev/null || true
                        xcrun simctl shutdown all 2>/dev/null || true
                        xcrun simctl erase all 2>/dev/null || true

                        rm -rf "${env.WORKSPACE}/fastlane" || true

                        # GPU crash prevention
                        defaults write com.apple.CoreSimulator.IndigoFramebufferServices FramebufferRendererHint -int 2 || true

                        # Disable SwiftLint
                        gsed -i '/SwiftLint/Id' project.yml 2>/dev/null || true
                        gsed -i '/basedOnDependencyAnalysis/Id' project.yml 2>/dev/null || true

                        # Select Xcode
                        sudo xcode-select -s /Applications/${params.XCODE_VERSION}
                        echo "Using: \$(xcode-select -p)"
                    """

                    echo "────────────────────  xcbeautify (Build/bin)  ────────────────────"
                    sh """
                        mkdir -p Build/bin
                        if [ ! -x Build/bin/xcbeautify ] && command -v xcbeautify >/dev/null 2>&1; then
                            ln -sf "\$(command -v xcbeautify)" Build/bin/xcbeautify
                        fi
                        if [ ! -x Build/bin/xcbeautify ]; then
                            echo "ERROR: Build/bin/xcbeautify not found. Install on the agent (e.g. brew install xcbeautify) and ensure it is on PATH."
                            exit 1
                        fi
                    """

                    echo "────────────────────  fla-ios/ACKNOWLEDGEMENTS.txt  ────────────────────"
                    sh """
                        if [ ! -f fla-ios/ACKNOWLEDGEMENTS.txt ]; then
                            mkdir -p fla-ios
                            touch fla-ios/ACKNOWLEDGEMENTS.txt
                            echo "Created placeholder fla-ios/ACKNOWLEDGEMENTS.txt (missing in tree)"
                        fi
                    """

                    echo "────────────────────  Prebuild (bundle install + rake prebuild)  ────────────────────"
                    withCredentials([string(credentialsId: 'ARTIFACTORY_TOKEN', variable: 'ARTIFACTORY_TOKEN')]) {
                        withEnv(['CI=', "ARTIFACTORY_TOKEN=${env.ARTIFACTORY_TOKEN}"]) {
                            sh """
                                bundle config set --local path '${BUNDLE_PATH}'
                                bundle config set --local jobs 4
                                bundle install

                                echo "[Build] Regenerating Xcode project..."
                                bundle exec rake prebuild
                            """
                        }
                    }
                    echo "────────────────────  Build for testing (xcodebuild)  ────────────────────"
                    echo "[Build] Building for testing (full log: Build/build-${params.SCHEME_NAME}.log)"
                    sh """
                        set -o pipefail
                        export SPM_CACHE_DIR="${SPM_CACHE_DIR}"
                        export MODULE_CACHE_DIR="${env.WORKSPACE}/Build/DerivedData/ModuleCache"
                        export SYMROOT="${env.WORKSPACE}/Build/DerivedData/Products"

                        mkdir -p Build/DerivedData
                        xcodebuild build-for-testing \\
                            -project Nordstrom.xcodeproj \\
                            -configuration AdHoc \\
                            -sdk iphonesimulator \\
                            -scheme "${params.SCHEME_NAME}" \\
                            -destination 'generic/platform=iOS Simulator,OS=${RUNTIME_VERSION}' \\
                            -derivedDataPath "${env.WORKSPACE}/Build/DerivedData" \\
                            -onlyUsePackageVersionsFromResolvedFile \\
                            -skipPackagePluginValidation \\
                            2>&1 | tee "Build/build-${params.SCHEME_NAME}.log" \\
                            | Build/bin/xcbeautify --quieter --disable-logging | tail -80
                    """
                    echo "[Build] Finished."

                    echo "────────────────────  Generate stubs (LocalStubServer-cli)  ────────────────────"
                    echo "[Build] Generating stubs..."
                    sh """
                        /usr/bin/xcrun --sdk macosx swift run --package-path ./scripts/LocalStubServer-cli \
                            localstubserver-cli \
                            ./Build/DerivedData/Products/AdHoc-iphonesimulator/fla-ios${params.SCHEME_NAME}-Runner.app/PlugIns/fla-ios${params.SCHEME_NAME}.xctest \
                            ./integration-tests-stubs false 2>/dev/null || true
                    """
                }
            }
        }

        // ┌─────────────────────────────────────────────────────────────────────┐
        // │  STAGE 3: TEST                                                      │
        // │  Resolve test methods (if any) · run xcodebuild test · reuse DerivedData
        // └─────────────────────────────────────────────────────────────────────┘
        stage('Test') {
            steps {
                script {
                    echo """
╔══════════════════════════════════════════════════════════════════════════╗
║  STAGE 3: TEST                                                           ║
║  Resolve test methods (if any) · run xcodebuild test · reuse DerivedData ║
╚══════════════════════════════════════════════════════════════════════════╝"""
                    STARTTIME = System.currentTimeMillis()

                    def testMethods = ''
                    if (params.TEST_METHODS?.trim()) {
                        echo "────────────────────  Resolve test methods  ────────────────────"
                        echo "Resolving specific test methods: ${params.TEST_METHODS}"
                        testMethods = sh(
                            script: """
                                export TEST_METHODS_LIST="${params.TEST_METHODS.replaceAll('\\\\', '\\\\\\\\').replaceAll('"', '\\\\"')}"
                                export SCHEME_FOR_TESTS="${params.SCHEME_NAME}"
                                bundle exec ruby -r ./ruby/project/test.rb -e 'targets = (ENV[\"TEST_METHODS_LIST\"] || \"\").to_s.split(\",\").map(&:strip); puts Project::Test.find_swift_tests(targets, ENV[\"SCHEME_FOR_TESTS\"])' 2>/dev/null || echo ""
                            """,
                            returnStdout: true
                        ).trim()
                    }

                    echo "────────────────────  Run xcodebuild test  ────────────────────"
                    echo "[Test] Running tests (full log: ${TEST_OUTPUT}/test-run.log)"
                    sh "mkdir -p ${TEST_OUTPUT}"

                    runXCUITests(
                        scheme: params.SCHEME_NAME,
                        device: DEVICE_NAME,
                        runtime: RUNTIME_VERSION,
                        simulatorId: SIMULATOR_ID,
                        workers: params.WORKERS,
                        outputDir: TEST_OUTPUT,
                        resultName: FILE_NAME_PATTERN,
                        derivedDataPath: "${env.WORKSPACE}/Build/DerivedData",
                        disableParallel: (params.SCHEME_NAME == 'XCUITests-P0'),
                        enableShipped: params.ENABLE_SHIPPED_FEATURE_FLAGS,
                        testMethods: testMethods
                    )
                }
            }
        }

        // ┌─────────────────────────────────────────────────────────────────────┐
        // │  STAGE 4: REPORT 1ST RUN                                            │
        // │  JUnit · CSV · Allure for 1st run · Slack with Build + Allure + CSV │
        // └─────────────────────────────────────────────────────────────────────┘
        stage('Report 1st Run') {
            steps {
                script {
                    echo """
╔══════════════════════════════════════════════════════════════════════════╗
║  STAGE 4: REPORT 1ST RUN                                                 ║
║  JUnit · CSV · Allure · Slack (Build + Allure link + failed_tests.csv)   ║
╚══════════════════════════════════════════════════════════════════════════╝"""
                    def firstRunJunit = "${TEST_OUTPUT}/report.xml"
                    echo "────────────────────  Generate 1st run reports  ────────────────────"
                    generateJunitReport("${TEST_OUTPUT}/${FILE_NAME_PATTERN}.xcresult", firstRunJunit)
                    generateFailedTestsCsv(firstRunJunit, "${TEST_OUTPUT}/failed_tests.csv")
                    sh "~/scripts/xcresults export ${TEST_OUTPUT}/${FILE_NAME_PATTERN}.xcresult ${TEST_OUTPUT}/allure-results 2>/dev/null || true"
                    sh "mkdir -p ~/JenkinsTestResults/${JOB_NAME} && cp ${firstRunJunit} ~/JenkinsTestResults/${JOB_NAME}/${BUILD_ID}.xml || true"

                    def counters = readSummaryCounts(firstRunJunit)
                    FAILED_COUNT_1ST = counters.failed
                    def durationSec = ((System.currentTimeMillis() - STARTTIME) / 1000).intValue()
                    def duration = formatDuration(durationSec)

                    def extraLines = []
                    def hints = getFailureHints(firstRunJunit)
                    if (hints?.trim()) extraLines << "_${hints}_"
                    def anomalyMsg = checkAnomaly(durationSec, counters.failed, counters.executed, JOB_NAME)
                    if (anomalyMsg?.trim()) extraLines << "⚠️ ${anomalyMsg}"

                    echo "────────────────────  Send 1st run to Slack (Build + Allure + CSV)  ────────────────────"
                    def firstRunSlackMsg = buildSlackMessage(
                        counters.failed == '0',
                        JOB_NAME, NOW, duration,
                        params.WORKERS,
                        counters.failed,
                        counters.executed,
                        counters.disabled,
                        extraLines
                    )

                    if (counters.failed != '0' && fileExists("${TEST_OUTPUT}/failed_tests.csv")) {
                        uploadFileToSlack("${TEST_OUTPUT}/failed_tests.csv", firstRunSlackMsg, params.REPORT_CHANNEL, SLACK_CURRENT_THREAD)
                    } else {
                        slackPostMessage(firstRunSlackMsg, params.REPORT_CHANNEL, SLACK_CURRENT_THREAD)
                    }
                }
            }
        }

        // ┌─────────────────────────────────────────────────────────────────────┐
        // │  STAGE 5: RERUN FAILED (optional)                                   │
        // │  Extract failed tests from report.xml · rerun with single worker     │
        // └─────────────────────────────────────────────────────────────────────┘
        stage('Rerun Failed') {
            when { expression { return params.RERUN_ENABLED } }
            steps {
                script {
                    echo """
╔══════════════════════════════════════════════════════════════════════════╗
║  STAGE 5: RERUN FAILED (optional)                                        ║
║  Extract failed tests from report.xml · rerun with single worker          ║
╚══════════════════════════════════════════════════════════════════════════╝"""
                    def firstRunJunit = "${TEST_OUTPUT}/report.xml"
                    if (FAILED_COUNT_1ST == '0') {
                        echo "────────────────────  No failures, skip rerun  ────────────────────"
                        echo "No failures detected, skipping rerun"
                        RERUN_SKIPPED = true
                        return
                    }

                    RERUN_SKIPPED = false
                    RERUN_STARTTIME = System.currentTimeMillis()

                    def failedTests = extractFailedTestMethods(firstRunJunit, params.SCHEME_NAME)

                    if (!failedTests?.trim()) {
                        echo "────────────────────  Legacy rerun (extract failed tests)  ────────────────────"
                        echo "Could not extract failed tests, using legacy rerun"
                        sh """
                            xcrun simctl erase all || true
                            BUILD_ID=dontKillMe ~/scripts/rerunTests_xml.py \
                                "${firstRunJunit}" "${params.SCHEME_NAME}" "${DEVICE_NAME}" \
                                "${RUNTIME_VERSION}" "${TEST_OUTPUT}/DerivedData/SourcePackages" || true
                        """
                        RERUN_TEST_OUTPUT = "${env.WORKSPACE}/${RERUN_TEST_OUTPUT_REL}"
                        return
                    }

                    echo "────────────────────  Rerun failed tests (single worker)  ────────────────────"
                    echo "Rerunning ${FAILED_COUNT_1ST} failed test(s)"
                    sh "xcrun simctl erase all || true"

                    RERUN_TEST_OUTPUT = "${env.WORKSPACE}/${RERUN_TEST_OUTPUT_REL}"
                    sh "mkdir -p ${RERUN_TEST_OUTPUT}"

                    runXCUITests(
                        scheme: params.SCHEME_NAME,
                        device: DEVICE_NAME,
                        runtime: RUNTIME_VERSION,
                        simulatorId: SIMULATOR_ID,
                        workers: '1',
                        outputDir: RERUN_TEST_OUTPUT,
                        resultName: "${FILE_NAME_PATTERN}_rerun",
                        derivedDataPath: "${env.WORKSPACE}/Build/DerivedData",
                        disableParallel: true,
                        enableShipped: params.ENABLE_SHIPPED_FEATURE_FLAGS,
                        testMethods: failedTests
                    )
                }
            }
        }

        // ┌─────────────────────────────────────────────────────────────────────┐
        // │  STAGE 6: REPORT (rerun + run_summary)                               │
        // │  Cleansimulators · rerun Slack · run_summary.json/html              │
        // └─────────────────────────────────────────────────────────────────────┘
        stage('Report') {
            steps {
                script {
                    echo """
╔══════════════════════════════════════════════════════════════════════════╗
║  STAGE 6: REPORT                                                         ║
║  Cleansimulators · rerun Slack · run_summary.json/html                   ║
╚══════════════════════════════════════════════════════════════════════════╝"""
                    sh "bundle exec rake cleansimulators 2>/dev/null || true"

                    def durationSec = ((System.currentTimeMillis() - STARTTIME) / 1000).intValue()
                    def summaryFlakyCount = 0
                    def summaryRealCount = 0
                    def summaryHasRerun = false
                    def summaryFlakyTests = []
                    def firstRunJunit = "${TEST_OUTPUT}/report.xml"
                    def counters = readSummaryCounts(firstRunJunit)
                    def hints = getFailureHints(firstRunJunit)
                    def anomalyMsg = checkAnomaly(durationSec, counters.failed, counters.executed, JOB_NAME)

                    if (params.RERUN_ENABLED) {
                        if (RERUN_SKIPPED) {
                            slackPostMessage("_Rerun skipped - no failures in 1st run_", params.REPORT_CHANNEL, SLACK_CURRENT_THREAD)
                        } else if (RERUN_TEST_OUTPUT && fileExists("${RERUN_TEST_OUTPUT}")) {
                            echo "────────────────────  Generate rerun reports  ────────────────────"
                            echo "[Report] Generating rerun reports..."

                            def rerunJunit = "${RERUN_TEST_OUTPUT}/report.xml"
                            def rerunXcresult = sh(script: "find \"${RERUN_TEST_OUTPUT}\" -maxdepth 1 -name '*.xcresult' 2>/dev/null | head -1", returnStdout: true).trim()
                            if (rerunXcresult) {
                                generateJunitReport(rerunXcresult, rerunJunit)
                            }

                            if (fileExists(rerunJunit)) {
                                generateFailedTestsCsv(rerunJunit, "${RERUN_TEST_OUTPUT}/failed_tests_rerun.csv")
                                sh "cp ${rerunJunit} ~/JenkinsTestResults/${JOB_NAME}/${BUILD_ID}_rerun.xml || true"

                                def rerunDuration = formatDuration(((System.currentTimeMillis() - RERUN_STARTTIME) / 1000).intValue())
                                def rerunCounters = readSummaryCounts(rerunJunit)

                                def flakyReal = computeFlakyAndReal(firstRunJunit, rerunJunit, params.SCHEME_NAME)
                                summaryFlakyCount = flakyReal.flakyCount
                                summaryRealCount = flakyReal.realCount
                                summaryHasRerun = true
                                summaryFlakyTests = flakyReal.flakyTestNames ?: []

                                def rerunExtra = []
                                if (flakyReal.flakyNote?.trim()) rerunExtra << "_${flakyReal.flakyNote}_"
                                def rerunHints = getFailureHints(rerunJunit)
                                if (rerunHints?.trim()) rerunExtra << "_${rerunHints}_"

                                def rerunMsg = buildSlackMessage(
                                    rerunCounters.failed == '0',
                                    "${JOB_NAME} (Rerun)", NOW, rerunDuration,
                                    '1',
                                    rerunCounters.failed,
                                    rerunCounters.executed,
                                    null,
                                    rerunExtra
                                )

                                if (rerunCounters.failed != '0' && fileExists("${RERUN_TEST_OUTPUT}/failed_tests_rerun.csv")) {
                                    uploadFileToSlack("${RERUN_TEST_OUTPUT}/failed_tests_rerun.csv", rerunMsg, params.REPORT_CHANNEL, SLACK_CURRENT_THREAD)
                                } else {
                                    slackPostMessage(rerunMsg, params.REPORT_CHANNEL, SLACK_CURRENT_THREAD)
                                }
                            }
                        }
                    }

                    def timestampIso = sh(script: "date -u +%Y-%m-%dT%H:%M:%SZ 2>/dev/null || date +%Y-%m-%dT%H:%M:%S", returnStdout: true).trim()
                    def runSummary = [
                        job_name: JOB_NAME,
                        build_id: BUILD_ID,
                        scheme: params.SCHEME_NAME,
                        timestamp_iso: timestampIso,
                        duration_sec: durationSec,
                        executed: counters.executed,
                        failed: counters.failed,
                        disabled: counters.disabled,
                        flaky_count: summaryFlakyCount,
                        real_count: summaryRealCount,
                        failure_hints: hints?.trim() ?: '',
                        anomaly: anomalyMsg?.trim() ?: '',
                        has_rerun: summaryHasRerun,
                        flaky_tests: summaryFlakyTests
                    ]
                    writeRunSummary(TEST_OUTPUT, runSummary)
                    writeRunSummaryHtml(runSummary, TEST_OUTPUT)
                }
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // ═  POST
    // ═  Cleanup · Allure · JUnit · HTML report · archive artifacts · UNSTABLE→SUCCESS
    // ═══════════════════════════════════════════════════════════════════════════
    post {
        always {
            script {
                echo """
╔══════════════════════════════════════════════════════════════════════════╗
║  POST · Cleanup · Allure · JUnit · HTML · archive · UNSTABLE→SUCCESS     ║
╚══════════════════════════════════════════════════════════════════════════╝"""
                echo "────────────────────  Cleanup  ────────────────────"
                sh """
                    killall Simulator Xcode 'Problem Reporter' 2>/dev/null || true
                    echo "Job duration: \$(( (\$(date +%s) - ${JOB_START_TIMESTAMP}) / 60 )) minutes"
                """
            }

            allure([
                includeProperties: false,
                jdk: '',
                reportBuildPolicy: 'ALWAYS',
                results: [[path: "${TEST_OUTPUT_REL}/allure-results"]]
            ])

            junit(
                testResults: "${TEST_OUTPUT_REL}/report.xml",
                allowEmptyResults: true,
                skipPublishingChecks: true,
                skipMarkingBuildUnstable: true
            )

            junit(
                testResults: "${RERUN_TEST_OUTPUT_REL}/report.xml",
                allowEmptyResults: true,
                skipPublishingChecks: true,
                skipMarkingBuildUnstable: true
            )

            publishHTML(target: [
                allowMissing: true,
                alwaysLinkToLastBuild: true,
                keepAll: true,
                reportDir: TEST_OUTPUT_REL,
                reportFiles: 'report.xml',
                reportName: 'Test Results'
            ])

            publishHTML(target: [
                allowMissing: true,
                alwaysLinkToLastBuild: true,
                keepAll: true,
                reportDir: TEST_OUTPUT_REL,
                reportFiles: 'run_summary.html',
                reportName: 'Run Summary'
            ])

            archiveArtifacts(
                artifacts: "${TEST_OUTPUT_REL}/report.xml, ${TEST_OUTPUT_REL}/failed_tests*.csv, ${TEST_OUTPUT_REL}/run_summary.json, ${TEST_OUTPUT_REL}/run_summary.html, ${TEST_OUTPUT_REL}/test-run.log, ${RERUN_TEST_OUTPUT_REL}/report.xml, ${RERUN_TEST_OUTPUT_REL}/failed_tests*.csv, ${RERUN_TEST_OUTPUT_REL}/test-run.log, Build/build-*.log",
                allowEmptyArchive: true,
                fingerprint: true
            )

            script {
                // Test failures mark build UNSTABLE; we treat as SUCCESS so job does not fail
                if (currentBuild.result == null || currentBuild.result == 'UNSTABLE') {
                    currentBuild.result = 'SUCCESS'
                }
            }
        }

        failure {
            script {
                def failureMsg = """*Build Failed*

*Job:* `${JOB_NAME}`
*Build:* <${normalizedBuildUrl()}|#${BUILD_NUMBER}>

_Check logs for details._"""
                slackPostMessage(failureMsg, params.REPORT_CHANNEL, SLACK_CURRENT_THREAD ?: '')
            }
        }
    }
}
