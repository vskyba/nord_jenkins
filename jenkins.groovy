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

// ─────────────────────────────────────────────────────────────
// ── HELPER METHODS ──────────────────────────────────────────
// ─────────────────────────────────────────────────────────────

def slackPostInitialMessage(String text, String channel) {
    def slackResponse = sh(
        script: """
            curl -s -X POST \
                 -H "Authorization: Bearer \$SLACK_TOKEN" \
                 -H "Content-Type: application/json" \
                 -d '{"channel": "${channel}", "text": ${groovy.json.JsonOutput.toJson(text)}}' \
                 https://slack.com/api/chat.postMessage
        """,
        returnStdout: true
    ).trim()

    def json = new JsonSlurper().parseText(slackResponse)
    if (!json.ok) {
        echo "Slack post failed: ${slackResponse}"
        return ''
    }
    return json.ts ?: ''
}

def slackPostMessage(String message, String channel, String thread) {
    sh(
        script: """
            curl -s -X POST \
                 -H "Authorization: Bearer \$SLACK_TOKEN" \
                 -H "Content-Type: application/json" \
                 -d '{"channel": "${channel}", "thread_ts": "${thread}", "text": ${groovy.json.JsonOutput.toJson(message)}}' \
                 https://slack.com/api/chat.postMessage
        """,
        returnStatus: true
    )
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

def readTestCountersFromJunit(String junitPath) {
    if (!fileExists(junitPath)) {
        return [failed: '0', executed: '0', disabled: '0']
    }

    def result = sh(
        script: """
            bundle exec ruby -r ./ruby/project/test.rb -e "
                counts = Project::Test::Reports.summary_counts('${junitPath}')
                puts \"failed=#{counts[:failed]}\"
                puts \"executed=#{counts[:executed]}\"
                puts \"disabled=#{counts[:disabled]}\"
            " 2>/dev/null || echo "failed=0\\nexecuted=0\\ndisabled=0"
        """,
        returnStdout: true
    ).trim()

    def counters = [failed: '0', executed: '0', disabled: '0']
    result.split('\n').each { line ->
        def parts = line.split('=')
        if (parts.size() == 2) {
            counters[parts[0].trim()] = parts[1].trim()
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
    def allureLink = isSuccess ? "" : "\n*Allure Report:* <http://integration.nonprod.cmapps.vip.nordstrom.com:8080/job/${JOB_NAME}/${BUILD_NUMBER}/allure/|LINK>"

    return """*Test Run ${status}!*
${allureLink}
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

    def exitCode = sh(
        script: """
            echo "Running tests: ${config.scheme} (parallel: ${!config.disableParallel})"
            export AUTOMATION_ENABLE_SHIPPED_FEATURE_FLAGS=${config.enableShipped}

            BUILD_ID=dontKillMe xcodebuild test \
                -project Nordstrom.xcodeproj \
                -scheme "${config.scheme}" \
                -destination "platform=iOS Simulator,name=${config.device},OS=${config.runtime}" \
                ${parallelOptions} \
                -onlyUsePackageVersionsFromResolvedFile \
                -derivedDataPath "${config.outputDir}/DerivedData" \
                -resultBundlePath "${config.outputDir}/${config.resultName}.xcresult" \
                -skipPackagePluginValidation \
                -quiet \
                ${testMethodsArg}
        """,
        returnStatus: true
    )

    echo "xcodebuild exit code: ${exitCode}"

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
        // Fix failure messages using Ruby script
        sh """
            bundle exec ruby -r ./ruby/project/test.rb -e "
                Project::Test.fix_junit_failure_messages('${outputPath}')
            " 2>/dev/null || true
        """
    }
}

def generateFailedTestsCsv(String junitPath, String csvPath) {
    sh """
        bundle exec ruby -r ./ruby/project/test.rb -e "
            Project::Test::Reports.generate_failed_tests_to_csv('${junitPath}', '${csvPath}')
        " 2>/dev/null || ~/scripts/genFailedFromXML.py ${junitPath} || true
    """
}

def extractFailedTestMethods(String junitPath, String scheme) {
    return sh(
        script: """
            bundle exec ruby -r ./ruby/project/test.rb -e "
                result = Project::Test.failed_tests_from_xml('${junitPath}', '${scheme}')
                puts result
            " 2>/dev/null || echo ""
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
            name: 'DEVICE_NAME',
            choices: ['iPhone 12', 'iPhone 12 Pro Max', 'iPhone SE', 'iPhone 16 Pro', 'iPhone 15', 'iPhone 14'],
            description: 'iOS device for testing'
        )
        choice(
            name: 'RUNTIME_VERSION',
            choices: ['18.1', '18.0', '18.4', '17.5', '16.4'],
            description: 'iOS runtime version'
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
                    JOB_START_TIMESTAMP = sh(script: "date +%s", returnStdout: true).trim()

                    // ── Checkout repositories ──
                    echo "═══ Checking out repositories ═══"
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
                    echo "═══ Collecting build information ═══"
                    NOW = sh(script: "date +'%d/%m/%Y %r'", returnStdout: true).trim()
                    BUILD_TAG = sh(script: 'git describe --tags "$(git rev-list --tags --max-count=1)" 2>/dev/null || echo "untagged-$(git rev-parse --short HEAD)"', returnStdout: true).trim()
                    BUILD_LAST_COMMIT = sh(script: 'git log --pretty=format:"%h | %an: %s" -n 1 2>/dev/null || echo "N/A"', returnStdout: true).trim()
                    BUILD_CURRENT_BRANCH = sh(script: 'git rev-parse --abbrev-ref HEAD 2>/dev/null || echo ""', returnStdout: true).trim()
                    STUBS_COMMIT = sh(script: 'cd integration-tests-stubs && git log --pretty=format:"%h | %an: %s" -n 1 2>/dev/null || echo "N/A"', returnStdout: true).trim()

                    FILE_NAME_PATTERN = "${JOB_NAME}_${BUILD_ID}_${BUILD_TAG}"
                    TEST_OUTPUT = "${env.WORKSPACE}/fastlane/test_output"

                    // ── Validate inputs ──
                    echo "═══ Validating inputs ═══"
                    def validationErrors = []

                    def schemeExists = sh(script: "xcodebuild -list -project Nordstrom.xcodeproj 2>/dev/null | grep -q '${params.SCHEME_NAME}' && echo 'yes' || echo 'no'", returnStdout: true).trim()
                    if (schemeExists == 'no') {
                        validationErrors << "Scheme '${params.SCHEME_NAME}' not found"
                    }

                    def deviceExists = sh(script: "xcrun simctl list devices 2>/dev/null | grep -q '${params.DEVICE_NAME}' && echo 'yes' || echo 'no'", returnStdout: true).trim()
                    if (deviceExists == 'no') {
                        validationErrors << "Device '${params.DEVICE_NAME}' not found"
                    }

                    def runtimeExists = sh(script: "xcrun simctl list runtimes 2>/dev/null | grep -q 'iOS ${params.RUNTIME_VERSION}' && echo 'yes' || echo 'no'", returnStdout: true).trim()
                    if (runtimeExists == 'no') {
                        validationErrors << "iOS ${params.RUNTIME_VERSION} runtime not found"
                    }

                    if (validationErrors) {
                        validationErrors.each { echo "WARNING: ${it}" }
                    }

                    // ── Setup caches ──
                    echo "═══ Setting up caches ═══"
                    sh """
                        mkdir -p "${COCOAPODS_CACHE}" "${BUNDLE_PATH}" "${SPM_CACHE_DIR}" || true
                        echo "Cache sizes:"
                        du -sh "${COCOAPODS_CACHE}" 2>/dev/null || echo "  CocoaPods: empty"
                        du -sh "${BUNDLE_PATH}" 2>/dev/null || echo "  Bundle: empty"
                    """

                    // ── Send Slack notification ──
                    echo "═══ Sending start notification ═══"
                    def startMsg = """*Test Run Started*

*Build:* <${env.BUILD_URL}|Jenkins #${BUILD_NUMBER}>
*Branch:* `${BUILD_CURRENT_BRANCH ?: params.FLA_BRANCH}` | *Tag:* `${BUILD_TAG}`
*Scheme:* `${params.SCHEME_NAME}`

*Config:* ${params.DEVICE_NAME} | iOS ${params.RUNTIME_VERSION} | ${params.WORKERS} workers

_Commits:_
• fla-ios: `${BUILD_LAST_COMMIT}`
• stubs: `${STUBS_COMMIT}`"""

                    SLACK_CURRENT_THREAD = slackPostInitialMessage(startMsg, params.REPORT_CHANNEL)
                }
            }
        }

        // ═══════════════════════════════════════════════════════════
        // STAGE 2: BUILD
        // ═══════════════════════════════════════════════════════════
        stage('Build') {
            steps {
                script {
                    // ── Environment cleanup ──
                    echo "═══ Cleaning environment ═══"
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

                    // ── Build project ──
                    echo "═══ Building project ═══"
                    withCredentials([string(credentialsId: 'ARTIFACTORY_TOKEN', variable: 'ARTIFACTORY_TOKEN')]) {
                        sh """
                            bundle config set --local path '${BUNDLE_PATH}'
                            bundle config set --local jobs 4
                            bundle install

                            export SPM_CACHE_DIR="${SPM_CACHE_DIR}"
                            bundle exec rake buildspecs[${params.SCHEME_NAME}]
                        """
                    }

                    // ── Generate stubs ──
                    echo "═══ Generating stubs ═══"
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
                                bundle exec ruby -r ./ruby/project/test.rb -e "
                                    targets = '${params.TEST_METHODS}'.split(',').map(&:strip)
                                    puts Project::Test.find_swift_tests(targets, '${params.SCHEME_NAME}')
                                " 2>/dev/null || echo ""
                            """,
                            returnStdout: true
                        ).trim()
                    }

                    // ── Run tests ──
                    echo "═══ Running tests ═══"
                    sh "mkdir -p ${TEST_OUTPUT}"

                    runXCUITests(
                        scheme: params.SCHEME_NAME,
                        device: params.DEVICE_NAME,
                        runtime: params.RUNTIME_VERSION,
                        workers: params.WORKERS,
                        outputDir: TEST_OUTPUT,
                        resultName: FILE_NAME_PATTERN,
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
                                ${firstRunJunit} "${params.SCHEME_NAME}" "${params.DEVICE_NAME}" \
                                "${params.RUNTIME_VERSION}" ${TEST_OUTPUT}/DerivedData/SourcePackages || true
                        """
                        RERUN_TEST_OUTPUT = "${env.WORKSPACE}/fastlane/rerun_test_output"
                        return
                    }

                    echo "Rerunning ${FAILED_COUNT_1ST} failed test(s)"

                    // ── Clean simulators and rerun ──
                    sh "xcrun simctl erase all || true"

                    RERUN_TEST_OUTPUT = "${env.WORKSPACE}/fastlane/rerun_test_output"
                    sh "mkdir -p ${RERUN_TEST_OUTPUT}"

                    runXCUITests(
                        scheme: params.SCHEME_NAME,
                        device: params.DEVICE_NAME,
                        runtime: params.RUNTIME_VERSION,
                        workers: '1',
                        outputDir: RERUN_TEST_OUTPUT,
                        resultName: "${FILE_NAME_PATTERN}_rerun",
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
                    def durationSec = ((System.currentTimeMillis() - STARTTIME) / 1000).intValue()
                    def duration = formatDuration(durationSec)

                    // ── Generate 1st run reports ──
                    echo "═══ Generating reports ═══"
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
                    echo "═══ Sending 1st run results ═══"
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
                            echo "═══ Generating rerun reports ═══"

                            def rerunJunit = "${RERUN_TEST_OUTPUT}/report.xml"
                            sh "ls ${RERUN_TEST_OUTPUT}/*.xcresult 2>/dev/null && ./Build/bin/xcresultparser -o junit ${RERUN_TEST_OUTPUT}/*.xcresult > ${rerunJunit} || true"

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
                results: [[path: 'fastlane/test_output/allure-results']]
            ])

            junit(
                testResults: 'fastlane/test_output/report.xml',
                allowEmptyResults: true,
                skipPublishingChecks: false,
                skipMarkingBuildUnstable: true
            )

            junit(
                testResults: 'fastlane/rerun_test_output/report.xml',
                allowEmptyResults: true,
                skipPublishingChecks: false,
                skipMarkingBuildUnstable: true
            )

            publishHTML(target: [
                allowMissing: true,
                alwaysLinkToLastBuild: true,
                keepAll: true,
                reportDir: 'fastlane/test_output',
                reportFiles: 'report.xml',
                reportName: 'Test Results'
            ])

            archiveArtifacts(
                artifacts: 'fastlane/**/report.xml, fastlane/**/failed_tests*.csv, Build/specs-*.log',
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

*Job:* `${JOB_NAME}` | *Build:* <${env.BUILD_URL}|#${BUILD_NUMBER}>

Check logs for details."""
                slackPostMessage(failureMsg, params.REPORT_CHANNEL, SLACK_CURRENT_THREAD ?: '')
            }
        }
    }
}
