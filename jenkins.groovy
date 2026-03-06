/* groovylint-disable GStringExpressionWithinString, LineLength */
import groovy.json.JsonSlurper

// ─────────────────────────────────────────────────────────────
// ── Global Variable Declarations ────────────────────────────
// ─────────────────────────────────────────────────────────────

def SLACK_CURRENT_THREAD = ''
def JOB_START_TIMESTAMP = ''
def FAILED_COUNT_1ST = '0'
def RERUN_SKIPPED = true
def RERUN_TEST_OUTPUT = ''
def STARTTIME = 0
def RERUN_STARTTIME = 0
// Parsed from SIMULATOR_DEVICE (device|runtime|optionalId)
def DEVICE_NAME = ''
def RUNTIME_VERSION = ''
def SIMULATOR_ID = ''

// ─────────────────────────────────────────────────────────────
// ── HELPER METHODS ──────────────────────────────────────────
// ─────────────────────────────────────────────────────────────

def slackPostInitialMessage(String text, String channel) {
    int maxAttempts = 3
    int delaySec = 5
    def payload = groovy.json.JsonOutput.toJson([channel: channel, text: text])
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

        if (curlExit == '0') {
            try {
                def json = new JsonSlurper().parseText(slackResponse)
                if (json.ok) {
                    return json.ts ?: ''
                }
                echo "Slack post failed: ${slackResponse}"
            } catch (Exception e) {
                echo "Slack response parse error: ${e.message}"
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
    int maxAttempts = 3
    int delaySec = 5
    def payload = thread?.trim() ?
        groovy.json.JsonOutput.toJson([channel: channel, thread_ts: thread.trim(), text: message]) :
        groovy.json.JsonOutput.toJson([channel: channel, text: message])
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

        if (curlExit == '0') {
            try {
                if (slackResponse) {
                    def json = new JsonSlurper().parseText(slackResponse)
                    if (json.ok) {
                        return
                    }
                    echo "Slack post failed: ${slackResponse}"
                }
            } catch (Exception e) {
                echo "Slack response parse error: ${e.message}"
            }
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

def uploadFileToSlack(String filePath, String text, String channel, String thread) {
    try {
        slackUploadFile(
            credentialId: 'SLACK_TOKEN',
            channel: thread ? "${channel}:${thread}" : channel,
            filePath: filePath,
            initialComment: text,
            failOnError: false
        )
    } catch (Exception e) {
        echo "Slack file upload failed: ${e.message}"
    }
}

/**
 * Reads test counts from JUnit XML (fla-ios Reports.summary_counts).
 * Returns [failed:, executed:, disabled:] as strings; uses 'null' when file missing or parse fails.
 * Ruby -e is single-quoted with path in JUNIT_PATH to avoid zsh glob on [...].
 */
def readTestCountersFromJunit(String junitPath) {
    def counters = [failed: 'null', executed: 'null', disabled: 'null']
    if (!fileExists(junitPath)) {
        return counters
    }

    // Call fla-ios Ruby: path via env so -e can be single-quoted (avoids zsh glob on counts[:failed])
    def result = sh(
        script: """
            export JUNIT_PATH="${junitPath}"
            bundle exec ruby -r ./ruby/project/test.rb -e 'counts = Project::Test::Reports.summary_counts(ENV[\"JUNIT_PATH\"]); puts \"failed=\" + counts[:failed].to_s; puts \"executed=\" + counts[:executed].to_s; puts \"disabled=\" + counts[:disabled].to_s' 2>/dev/null || true
        """,
        returnStdout: true
    ).trim()

    result.split('\n').each { line ->
        def parts = line.split('=', 2)
        if (parts.size() == 2) {
            def key = parts[0].trim()
            def val = parts[1].trim()
            if (counters.containsKey(key)) { counters[key] = val }
        }
    }
    return counters
}

def readTestCountersLegacy(String outputDir) {
    def failed = sh(script: "cat ${outputDir}/how_many_failed 2>/dev/null || echo '0'", returnStdout: true).trim()
    def executed = sh(script: "cat ${outputDir}/how_many_executed 2>/dev/null || echo '0'", returnStdout: true).trim()
    def disabled = sh(script: "cat ${outputDir}/how_many_disabled 2>/dev/null || echo '0'", returnStdout: true).trim()
    return [failed: failed, executed: executed, disabled: disabled]
}

def formatDuration(long durationSec) {
    def hours = (durationSec / 3600).intValue()
    def minutes = ((durationSec % 3600) / 60).intValue()
    return hours > 0 ? "${hours}h ${minutes}m" : "${minutes}m"
}

def buildSlackMessage(boolean isSuccess, String jobName, String now, String duration,
                      String workers, String failed, String executed, String disabled) {
    def status = isSuccess ? "Completed Successfully" : "Completed with Failures"
    def buildUrl = (env.BUILD_URL ?: '').replace('localhost', 'skynet-studio.org')
    def buildLink = buildUrl ? "\n*Build:* <${buildUrl}|#${BUILD_NUMBER}>" : ""
    def allureLink = (isSuccess || !buildUrl) ? "" : "\n*Allure Report:* <${buildUrl}allure/|LINK>"

    return """*Test Run ${status}!*${buildLink}${allureLink}

*Job Name:* `${jobName}`
*Start Time:* `${now}`
*Duration:* `${duration}`

*Test Summary:*
- *Workers:* `${workers}`
- *Executed:* `${executed}` | *Failed:* `${failed}` | *Disabled:* `${disabled}`"""
}

def runXCUITests(Map config) {
    def parallelOptions = config.disableParallel ?
        '-parallel-testing-enabled NO' :
        "-parallel-testing-enabled YES -parallel-testing-worker-count ${config.workers}"

    def testMethodsArg = config.testMethods ?: ''
    // Reuse Build stage DerivedData (prebuild-once strategy, same as squad_specific_test_run.yml)
    def derivedDataPath = config.derivedDataPath ?: "${config.outputDir}/DerivedData"
    def destination = (config.simulatorId ?: '').trim() ?
        "id=${config.simulatorId}" :
        "platform=iOS Simulator,name=${config.device},OS=${config.runtime}"

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
            echo "--- Last 100 lines of test run ---"
            tail -100 "${config.outputDir}/test-run.log"
            exit \$_xc
        """,
        returnStatus: true
    )

    echo "xcodebuild test finished (exit code: ${exitCode})"

    // Exit code 0 = success, 65 = test failures (acceptable), others = infrastructure failure
    if (exitCode != 0 && exitCode != 65) {
        error "Build/infrastructure failure (exit code ${exitCode})"
    }

    return exitCode
}

def generateJunitReport(String xcresultPath, String outputPath) {
    sh """
        ./Build/bin/xcresultparser -o junit "${xcresultPath}" > "${outputPath}" 2>/dev/null || \
        xcresultparser -o junit "${xcresultPath}" > "${outputPath}" 2>/dev/null || true
    """

    if (fileExists(outputPath)) {
        // Fix failure messages (path via env to avoid zsh glob in -e)
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

// ─────────────────────────────────────────────────────────────
// ── PIPELINE ────────────────────────────────────────────────
// ─────────────────────────────────────────────────────────────

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
        // Credentials - available throughout pipeline
        SLACK_TOKEN = credentials('SLACK_TOKEN')

        // Cache directories
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
        // ═══════════════════════════════════════════════════════════
        // STAGE 1: SETUP
        // ═══════════════════════════════════════════════════════════
        stage('Setup') {
            steps {
                script {
                    def parts = params.SIMULATOR_DEVICE.split('\\|')
                    if (parts.size() >= 2) {
                        DEVICE_NAME = parts[0].trim()
                        RUNTIME_VERSION = parts[1].trim()
                        SIMULATOR_ID = (parts.size() >= 3 && parts[2].trim()) ? parts[2].trim() : ''
                    }
                    JOB_START_TIMESTAMP = sh(script: "date +%s", returnStdout: true).trim()

                    // ── Checkout repositories ──
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

                    // ── Collect build info ──
                    echo "[Setup] Collecting build information..."
                    NOW = sh(script: "date +'%d/%m/%Y %r'", returnStdout: true).trim()
                    BUILD_TAG = sh(script: 'git describe --tags "$(git rev-list --tags --max-count=1)" 2>/dev/null || echo "untagged-$(git rev-parse --short HEAD)"', returnStdout: true).trim()
                    BUILD_LAST_COMMIT = sh(script: 'git log --pretty=format:"%h | %an: %s" -n 1 2>/dev/null || echo "N/A"', returnStdout: true).trim()
                    BUILD_CURRENT_BRANCH = sh(script: 'git rev-parse --abbrev-ref HEAD 2>/dev/null || echo ""', returnStdout: true).trim()
                    STUBS_COMMIT = sh(script: 'cd integration-tests-stubs && git log --pretty=format:"%h | %an: %s" -n 1 2>/dev/null || echo "N/A"', returnStdout: true).trim()

                    FILE_NAME_PATTERN = "${JOB_NAME}_${BUILD_ID}_${BUILD_TAG}"
                    // Single source of truth: relative paths under workspace (used for TEST_OUTPUT and post/publish)
                    TEST_OUTPUT_REL = 'fastlane/test_output'
                    RERUN_TEST_OUTPUT_REL = 'fastlane/rerun_test_output'
                    TEST_OUTPUT = "${env.WORKSPACE}/${TEST_OUTPUT_REL}"

                    // ── Validate inputs ──
                    echo "[Setup] Validating inputs..."
                    def validationErrors = []

                    // Exact scheme match (align with squad_specific_test_run.yml validation)
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

                    // ── Setup caches ──
                    echo "[Setup] Setting up caches..."
                    sh """
                        mkdir -p "${COCOAPODS_CACHE}" "${BUNDLE_PATH}" "${SPM_CACHE_DIR}" || true
                        echo "Cache sizes:"
                        du -sh "${COCOAPODS_CACHE}" 2>/dev/null || echo "  CocoaPods: empty"
                        du -sh "${BUNDLE_PATH}" 2>/dev/null || echo "  Bundle: empty"
                    """

                    // ── Send Slack notification ──
                    echo "[Setup] Sending start notification to Slack..."
                    def startMsg = """*Test Run Started*

*Build:* <${env.BUILD_URL?.replace('localhost', 'skynet-studio.org')}|Jenkins #${BUILD_NUMBER}>
*Branch:* `${(BUILD_CURRENT_BRANCH && BUILD_CURRENT_BRANCH != 'HEAD') ? BUILD_CURRENT_BRANCH : params.FLA_BRANCH}` | *Tag:* `${BUILD_TAG}`
*Scheme:* `${params.SCHEME_NAME}`

*Config:* ${DEVICE_NAME} | iOS ${RUNTIME_VERSION} | ${params.WORKERS} workers

_Commits:_
• fla-ios: `${BUILD_LAST_COMMIT}`
• stubs: `${STUBS_COMMIT}`"""

                    SLACK_CURRENT_THREAD = slackPostInitialMessage(startMsg, params.REPORT_CHANNEL)
                }
            }
        }

        // ═══════════════════════════════════════════════════════════
        // STAGE 2: BUILD
        // Same strategy as fla-ios .github/workflows/squad_specific_test_run.yml:
        // prebuild once (buildspecs), then run tests using that build (derivedDataPath).
        // ═══════════════════════════════════════════════════════════
        stage('Build') {
            steps {
                script {
                    // ── Environment cleanup ──
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

                    // ── Use agent's xcbeautify (Rakefile expects Build/bin/xcbeautify) ──
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

                    // ── Ensure fla-ios ACKNOWLEDGEMENTS.txt exists (Xcode copy phase requires it) ──
                    sh """
                        if [ ! -f fla-ios/ACKNOWLEDGEMENTS.txt ]; then
                            mkdir -p fla-ios
                            touch fla-ios/ACKNOWLEDGEMENTS.txt
                            echo "Created placeholder fla-ios/ACKNOWLEDGEMENTS.txt (missing in tree)"
                        fi
                    """

                    // ── Install gems (needed for stubs and report stages) ──
                    withCredentials([string(credentialsId: 'ARTIFACTORY_TOKEN', variable: 'ARTIFACTORY_TOKEN')]) {
                        sh """
                            bundle config set --local path '${BUNDLE_PATH}'
                            bundle config set --local jobs 4
                            bundle install
                        """
                    }

                    // ── Regenerate Xcode project (removes stale file references after deletions) ──
                    echo "[Build] Regenerating Xcode project..."
                    sh "bundle exec rake prebuild"

                    // ── Build for testing (direct xcodebuild: avoids Rakefile treating warnings as failure) ──
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

                    // ── Generate stubs ──
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

        // ═══════════════════════════════════════════════════════════
        // STAGE 3: TEST
        // ═══════════════════════════════════════════════════════════
        stage('Test') {
            steps {
                script {
                    STARTTIME = System.currentTimeMillis()

                    // ── Resolve test methods if specified ──
                    def testMethods = ''
                    if (params.TEST_METHODS?.trim()) {
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

                    // ── Run tests ──
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

        // ═══════════════════════════════════════════════════════════
        // STAGE 4: RERUN (conditional)
        // ═══════════════════════════════════════════════════════════
        stage('Rerun Failed') {
            when { expression { return params.RERUN_ENABLED } }
            steps {
                script {
                    // ── Generate 1st run report to check for failures ──
                    def firstRunJunit = "${TEST_OUTPUT}/report.xml"
                    generateJunitReport("${TEST_OUTPUT}/${FILE_NAME_PATTERN}.xcresult", firstRunJunit)

                    def counters = readTestCountersFromJunit(firstRunJunit)
                    FAILED_COUNT_1ST = counters.failed

                    if (FAILED_COUNT_1ST == '0' || !FAILED_COUNT_1ST) {
                        echo "No failures detected, skipping rerun"
                        RERUN_SKIPPED = true
                        return
                    }

                    RERUN_SKIPPED = false
                    RERUN_STARTTIME = System.currentTimeMillis()

                    // ── Extract failed tests ──
                    def failedTests = extractFailedTestMethods(firstRunJunit, params.SCHEME_NAME)

                    if (!failedTests?.trim()) {
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

                    echo "Rerunning ${FAILED_COUNT_1ST} failed test(s)"

                    // ── Clean simulators and rerun ──
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

        // ═══════════════════════════════════════════════════════════
        // STAGE 5: REPORT
        // ═══════════════════════════════════════════════════════════
        stage('Report') {
            steps {
                script {
                    // Clean simulators after test run (align with squad_specific_test_run.yml)
                    sh "bundle exec rake cleansimulators 2>/dev/null || true"

                    def durationSec = ((System.currentTimeMillis() - STARTTIME) / 1000).intValue()
                    def duration = formatDuration(durationSec)

                    // ── Generate 1st run reports ──
                    echo "[Report] Generating reports (JUnit, Allure, CSV)..."
                    def firstRunJunit = "${TEST_OUTPUT}/report.xml"

                    if (!fileExists(firstRunJunit)) {
                        generateJunitReport("${TEST_OUTPUT}/${FILE_NAME_PATTERN}.xcresult", firstRunJunit)
                    }
                    generateFailedTestsCsv(firstRunJunit, "${TEST_OUTPUT}/failed_tests.csv")

                    // Generate Allure report
                    sh "~/scripts/xcresults export ${TEST_OUTPUT}/${FILE_NAME_PATTERN}.xcresult ${TEST_OUTPUT}/allure-results 2>/dev/null || true"

                    // Archive reports
                    sh "mkdir -p ~/JenkinsTestResults/${JOB_NAME} && cp ${firstRunJunit} ~/JenkinsTestResults/${JOB_NAME}/${BUILD_ID}.xml || true"

                    def counters = readTestCountersFromJunit(firstRunJunit)
                    if (FAILED_COUNT_1ST == '0') {
                        FAILED_COUNT_1ST = counters.failed
                    }

                    // ── Send 1st run Slack notification ──
                    // Note: If a prior stage (e.g. Rerun) fails, Report is skipped and this message is never sent.
                    echo "[Report] Sending 1st run results to Slack..."
                    def slackMsg = buildSlackMessage(
                        FAILED_COUNT_1ST == '0',
                        JOB_NAME, NOW, duration,
                        params.WORKERS,
                        FAILED_COUNT_1ST,
                        counters.executed,
                        counters.disabled
                    )

                    if (FAILED_COUNT_1ST != '0' && fileExists("${TEST_OUTPUT}/failed_tests.csv")) {
                        uploadFileToSlack("${TEST_OUTPUT}/failed_tests.csv", slackMsg, params.REPORT_CHANNEL, SLACK_CURRENT_THREAD)
                    } else {
                        slackPostMessage(slackMsg, params.REPORT_CHANNEL, SLACK_CURRENT_THREAD)
                    }

                    // ── Generate rerun reports if applicable ──
                    if (params.RERUN_ENABLED) {
                        if (RERUN_SKIPPED) {
                            slackPostMessage("_Rerun skipped - no failures in 1st run_", params.REPORT_CHANNEL, SLACK_CURRENT_THREAD)
                        } else if (RERUN_TEST_OUTPUT && fileExists("${RERUN_TEST_OUTPUT}")) {
                            echo "[Report] Generating rerun reports..."

                            def rerunJunit = "${RERUN_TEST_OUTPUT}/report.xml"
                            sh "f=\$(find \"${RERUN_TEST_OUTPUT}\" -maxdepth 1 -name '*.xcresult' 2>/dev/null | head -1); [ -n \"\$f\" ] && ./Build/bin/xcresultparser -o junit \"\$f\" > ${rerunJunit} || true"

                            if (fileExists(rerunJunit)) {
                                generateFailedTestsCsv(rerunJunit, "${RERUN_TEST_OUTPUT}/failed_tests_rerun.csv")
                                sh "cp ${rerunJunit} ~/JenkinsTestResults/${JOB_NAME}/${BUILD_ID}_rerun.xml || true"

                                def rerunDuration = formatDuration(((System.currentTimeMillis() - RERUN_STARTTIME) / 1000).intValue())
                                def rerunCounters = readTestCountersFromJunit(rerunJunit)

                                def rerunMsg = buildSlackMessage(
                                    rerunCounters.failed == '0',
                                    "${JOB_NAME} (Rerun)", NOW, rerunDuration,
                                    '1',
                                    rerunCounters.failed,
                                    rerunCounters.executed,
                                    '0'
                                )

                                if (rerunCounters.failed != '0' && fileExists("${RERUN_TEST_OUTPUT}/failed_tests_rerun.csv")) {
                                    uploadFileToSlack("${RERUN_TEST_OUTPUT}/failed_tests_rerun.csv", rerunMsg, params.REPORT_CHANNEL, SLACK_CURRENT_THREAD)
                                } else {
                                    slackPostMessage(rerunMsg, params.REPORT_CHANNEL, SLACK_CURRENT_THREAD)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // ═══════════════════════════════════════════════════════════
    // POST ACTIONS
    // ═══════════════════════════════════════════════════════════
    post {
        always {
            script {
                // ── Cleanup ──
                sh """
                    killall Simulator Xcode 'Problem Reporter' 2>/dev/null || true
                    echo "Job duration: \$(( (\$(date +%s) - ${JOB_START_TIMESTAMP}) / 60 )) minutes"
                """
            }

            // ── Publish reports ──
            allure([
                includeProperties: false,
                jdk: '',
                reportBuildPolicy: 'ALWAYS',
                results: [[path: "${TEST_OUTPUT_REL}/allure-results"]]
            ])

            junit(
                testResults: "${TEST_OUTPUT_REL}/report.xml",
                allowEmptyResults: true,
                skipPublishingChecks: false,
                skipMarkingBuildUnstable: true
            )

            junit(
                testResults: "${RERUN_TEST_OUTPUT_REL}/report.xml",
                allowEmptyResults: true,
                skipPublishingChecks: false,
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

            archiveArtifacts(
                artifacts: "${TEST_OUTPUT_REL}/report.xml, ${TEST_OUTPUT_REL}/failed_tests*.csv, ${TEST_OUTPUT_REL}/test-run.log, ${RERUN_TEST_OUTPUT_REL}/report.xml, ${RERUN_TEST_OUTPUT_REL}/failed_tests*.csv, ${RERUN_TEST_OUTPUT_REL}/test-run.log, Build/build-*.log",
                allowEmptyArchive: true,
                fingerprint: true
            )

            script {
                // Mark unstable builds as success (test failures don't fail the build)
                if (currentBuild.result == null || currentBuild.result == 'UNSTABLE') {
                    currentBuild.result = 'SUCCESS'
                }
            }
        }

        failure {
            script {
                def failureMsg = """*Build Failed*

*Job:* `${JOB_NAME}` | *Build:* <${env.BUILD_URL?.replace('localhost', 'skynet-studio.org')}|#${BUILD_NUMBER}>

Check logs for details."""
                slackPostMessage(failureMsg, params.REPORT_CHANNEL, SLACK_CURRENT_THREAD ?: '')
            }
        }
    }
}
