/* groovylint-disable GStringExpressionWithinString, LineLength */
import groovy.json.JsonSlurper

// ─────────────────────────────────────────────────────────────
// ── Global Variable Declarations ────────────────────────────
// ─────────────────────────────────────────────────────────────

def SLACK_CURRENT_THREAD = ''  // Declare globally to avoid scope issues

// ─────────────────────────────────────────────────────────────
// ── HELPER METHODS (Outside pipeline { ... }) ───────────────
// ─────────────────────────────────────────────────────────────

def slackPostInitialMessage(String text, String token, String channel) {
    def slackResponse = sh(
        script: """
            curl -X POST -H Content-Type='application/json' \\
                 -F token='${token}' \\
                 -F text='${text}' \\
                 -F channel='${channel}' \\
                 -F as_user=true \\
                 -F username='Local Jenkins' \\
                 https://slack.com/api/chat.postMessage
        """,
        returnStdout: true
    ).trim()

    def json = new JsonSlurper().parseText(slackResponse)
    if (!json.ok) {
        error "Slack post failed! Full response: ${slackResponse}"
    }
    return json.ts ?: ''
}

def slackPostMessage(String message, String token, String channel, String thread) {
    def exitCode = sh(
        script: """
            curl -X POST -H "Content-Type=application/x-www-form-urlencoded" \\
            -F token='${token}' \\
            -F text='${message}' \\
            -F channel='${channel}' \\
            -F as_user=true \\
            -F thread_ts='${thread}' \\
            https://slack.com/api/chat.postMessage
        """,
        returnStatus: true
    )

    if (exitCode != 0) {
        echo 'WARNING: Slack message posting failed, but the build will continue.'
    }
}

def uploadFileToSlack(String filePath, String text, String channel, String thread) {
    try {
        def channelWithThread = thread ? "${channel}:${thread}" : channel
        
        slackUploadFile(
            credentialId: 'SLACK_TOKEN',                      // Pass token directly
            channel: channelWithThread,
            filePath: filePath,
            initialComment: text,
            failOnError: true
        )
        
        return 0
    } catch (Exception e) {
        echo "Slack file upload failed: ${e.message}"
        return 1
    }
}

def readTestCounters(String outputDir = "${env.WORKSPACE}/fastlane/test_output") {
    def failed   = sh(script: "cat ${outputDir}/how_many_failed",   returnStdout: true).trim()
    def executed = sh(script: "cat ${outputDir}/how_many_executed", returnStdout: true).trim()
    def disabled = sh(script: "cat ${outputDir}/how_many_disabled", returnStdout: true).trim()
    return [failed: failed, executed: executed, disabled: disabled]
}

def buildSlackMessage(boolean isSuccess,
                      String jobName,
                      String now,
                      String duration,
                      String workers,
                      String failed,
                      String executed,
                      String disabled) {
    if (isSuccess) {
        return """
*Test Run Completed Successfully!*

*Job Name:* `${jobName}`
*Start Time:* `${now}`
*Duration:* `${duration}`

*Test Summary:*
- *Disabled Tests:* `${disabled}`
- *Executed Tests:* `${executed}`
- *Failed Tests:* `${failed}`
"""
    } else {
        return """
*Test Run Completed with Failures!*

*Job Name:* `${jobName}`
*Allure Report:* <http://integration.nonprod.cmapps.vip.nordstrom.com:8080/job/${JOB_NAME}/${BUILD_NUMBER}/allure/|LINK>
*Start Time:* `${now}`
*Duration:* `${duration}`

*Test Summary:*
- *Parallel Workers:* `${workers}`
- *Disabled Tests:* `${disabled}`
- *Executed Tests:* `${executed}`
- *Failed Tests:* `${failed}`
"""
    }
                      }

def runXCUITests(String scheme,
                 String deviceName,
                 String runtimeVersion,
                 String workers,
                 String testOutput,
                 String fileNamePattern,
                 boolean isP0Scheme = false,
                 String enableShipped) {

    def parallelOptions = isP0Scheme ?
    '-parallel-testing-enabled NO \\' :
    "-parallel-testing-enabled YES -parallel-testing-worker-count ${workers} \\"

    def xcCommand = """
        echo "Executing xcodebuild with${isP0Scheme ? 'out' : ''} test plan: ${scheme}"
        export AUTOMATION_ENABLE_SHIPPED_FEATURE_FLAGS=${enableShipped}
        echo "AUTOMATION_ENABLE_SHIPPED_FEATURE_FLAGS=\$AUTOMATION_ENABLE_SHIPPED_FEATURE_FLAGS"

        BUILD_ID=dontKillMe xcodebuild test \\
            -verbose \\
            -project Nordstrom.xcodeproj \\
            -scheme "${scheme}" \\
            -destination "platform=iOS Simulator,name=${deviceName},OS=${runtimeVersion}" \\
            ${parallelOptions}
            -onlyUsePackageVersionsFromResolvedFile \\
            -derivedDataPath "${testOutput}/DerivedData" \\
            -resultBundlePath "${testOutput}/${fileNamePattern}.xcresult"
    """

    def exitCode = sh(script: xcCommand, returnStatus: true)
    echo "xcodebuild exited with code: ${exitCode}"

    //if (exitCode != 0 && exitCode != 65) {
        //error("❌ Build failed with unexpected exit code: ${exitCode}")
     //} else if (exitCode == 65) {
       // echo "⚠️ Tests failed (exit code 65), but build continues as expected."
     //}

    // Optional: store exitCode to env/global variable if needed for later logic
    return exitCode
                 }

// ─────────────────────────────────────────────────────────────
// ── DECLARATIVE PIPELINE ────────────────────────────────────
// ─────────────────────────────────────────────────────────────

pipeline {
    agent any
    tools { git 'Default' }

    parameters {
        gitParameter(
            branchFilter: 'origin/(.*)',
            defaultValue: 'main',
            name: 'FLA_BRANCH',
            type: 'PT_BRANCH',
            description: 'Select the fla-ios Git branch to checkout',
            selectedValue: 'DEFAULT',
            useRepository: 'https://github.com/Nordstrom-Internal/APP01031-fla-ios.git',
            quickFilterEnabled: true,
            sortMode: 'ASCENDING_SMART'
        )
        gitParameter(
            branchFilter: 'origin/(.*)',
            defaultValue: 'main', // Default branch
            name: 'INTG_BRANCH',
            type: 'PT_BRANCH', // Can be PT_BRANCH, PT_TAG, or PT_REVISION
            description: 'Select the ios-integration-test-stubs Git branch to checkout',
            selectedValue: 'DEFAULT',
            useRepository: 'https://github.com/Nordstrom-Internal/APP01031-integration-tests-stubs.git',
            quickFilterEnabled: true,
            sortMode: 'ASCENDING_SMART'
        )
        choice(
            name: 'DEVICE_NAME',
            choices: ['iPhone 12','iPhone 12 Pro Max', 'iPhone SE'],
            description: 'Pick something'
        )
        choice(
            name: 'REPORT_CHANNEL',
            choices: ['C02RQMF7DFF', 'CJDQEHCF5'],
            description: 'C02RQMF7DFF - cma-aqa-reports; CJDQEHCF5 - cma-ios-test-reports'
        )
        choice(
            name: 'RUNTIME_VERSION',
            choices: ['26.3.1', '18.1'],
            description: 'Pick something'
        )
        choice(
            name: 'XCODE_VERSION',
            choices: ['Xcode.app', 'Xcode_16.3.app'],
            description: 'Pick something'
        )
        choice(
            name: 'SCHEME_NAME',
            choices: [
                'XCUITests',
                'XCUITests-General',
                'XCUITests-FullLine',
                'XCUITests-SevenSummits',
                'XCUITests-ThunderMountains',
                'XCUITests-Fuji',
                'XCUITests-SevenSummits-Rack',
                'XCUITests-ThunderMountains-Rack',
                'XCUITests-Fuji-Rack',
                'XCUITests-P0',
                'XCUITests-P1',
                'XCUITests-Prod',
                'XCUITests-Prod-Rack',
                'XCUITests-P0-Rack',
                'XCUITests-Rack',
                'XCUITests-Analytics'
            ],
            description: 'Scheme name'
        )
        choice(
            name: 'WORKERS',
            choices: ['6', '1', '2', '3', '4', '5', '6', '8', '10'],
            description: 'Workers count'
        )
        choice(
            name: 'ENABLE_SHIPPED_FEATURE_FLAGS',
            choices: ['YES', 'NO'],
            description: 'Workers count'
        )
        booleanParam(
            name: 'RERUN_ENABLED',
            defaultValue: true,
            description: 'Enable rerun of failed tests'
        )
    }
    
    stages {

        stage('Checkout') {
            steps {
                script {
                    // Checkout main iOS app repo
                    checkout([
                        $class: 'GitSCM',
                        branches: [[name: "*/${params.FLA_BRANCH}"]],
                        userRemoteConfigs: [[
                            url: 'https://github.com/Nordstrom-Internal/APP01031-fla-ios.git',
                            credentialsId: 'nordstrom_github_token'
                        ]]
                    ])
        
                    // Define subdirectory for integration tests
                    def subDir = "${env.WORKSPACE}/integration-tests-stubs"
        
                    // Checkout integration test stubs into subdir
                    dir(subDir) {
                        checkout([
                            $class: 'GitSCM',
                            branches: [[name: "*/${params.INTG_BRANCH}"]],
                            doGenerateSubmoduleConfigurations: false,
                            extensions: [
                                [$class: 'RelativeTargetDirectory', relativeTargetDir: subDir]
                            ],
                            submoduleCfg: [],
                            userRemoteConfigs: [[
                                url: 'https://github.com/Nordstrom-Internal/APP01031-integration-tests-stubs.git',
                                credentialsId: 'nordstrom_github_token'
                            ]]
                        ])
                    }
                }
            }
        }

        stage('Data Preparation') {
            steps {
                script {
                    echo "CI is equal =$CI"
                    CI=false
                    echo "CI is equal =$CI"
                    SECONDS = 0
                    NOW = sh(script: "date +'%d/%m/%Y'+'%r'", returnStdout: true).trim()
                    BUILD_TAG = sh(script: 'git describe --tags "$(git rev-list --tags --max-count=1)" || echo "untagged-$(git rev-parse --short HEAD)"',returnStdout: true).trim()
                    BUILD_LAST_COMMITS_LIST = sh(
                        script: 'git log --pretty=format:"%h %aD | %an <%ae> : %s" --since=3.days -n 1',
                        returnStdout: true
                    ).trim()
                    BUILD_CURRENT_BRANCH = sh(
                        script: 'git show -s --pretty=%d HEAD | sed "s/.*origin\\///g" | sed "s/)//g"',
                        returnStdout: true
                    ).trim()
                    BUILD_TIMESTAMP = env.BUILD_ID
                    FILE_NAME_PATTERN = "${JOB_NAME}_${BUILD_TIMESTAMP}_TAG_${BUILD_TAG}"

                    STUBS_BRANCH = sh(
                        script: 'echo "${INTG_BRANCH}" | sed \'s/origin\\//origin\\//g\'',
                        returnStdout: true
                    ).trim()
                    STUBS_BRANCH_LOCAL = sh(
                        script: 'echo "${params.INTG_BRANCH}" | sed \'s/origin\\///g\'',
                        returnStdout: true
                    ).trim()

                    // Hard-coded token - consider Jenkins Credentials for security
                    SLACK_TOKEN = 'xoxb-2388559000-1381177971408-Bmv0yrCp14Ba7fJUZGqDCj8R'

                    TEST_OUTPUT = "${env.WORKSPACE}/fastlane/test_output"
                    FASTLANE_DIR = "${env.WORKSPACE}/fastlane"
                    XCODE_DERIVED_DATA = '~/Library/Developer/Xcode/DerivedData'
                    STUBS_COMMIT_DETAILS = sh(
                        script: '''
                            cd $WORKSPACE/integration-tests-stubs
                            git log --pretty=format:"%h - %an, %ar : %s" -n 1
                        ''',
                        returnStdout: true
                    ).trim()
                    sh "echo stubs commit is: '${STUBS_COMMIT_DETAILS}'"
                }
            }
        }

        stage('Cleanup Before Build') {
            steps {
                script {
                    sh """
                        echo "[CLEANUP BEFORE BUILD:]"

                        # Standard cleanup
                        rake clean || true
                        killall Xcode || true
                        killall Simulator || true
                        pkill -9 -f com.apple.CoreSimulator.CoreSimulatorService || true
                        xcrun simctl shutdown all || true
                        xcrun simctl erase all || true

                        rm -Rf "${FASTLANE_DIR}" || true
                        rm -Rf "${XCODE_DERIVED_DATA}/*" || true

                        # Set software rendering to avoid RenderBox GPU crashes
                        echo "[ENABLING SOFTWARE RENDERING FOR SIMULATORS:]"
                        defaults write com.apple.CoreSimulator.IndigoFramebufferServices FramebufferRendererHint -int 2 || true

                        echo "[DISABLING SWIFTLINT PHASE:]"
                        cd "$WORKSPACE"
                        gsed -i '/SwiftLint/Id' "$WORKSPACE/project.yml"
                        gsed -i '/basedOnDependencyAnalysis/Id' "$WORKSPACE/project.yml"
                        echo "[DISABLING SWIFTLINT PHASE: COMPLETED]"

                        echo "[CHOOSING XCODE VERSION:]"
                        sudo xcode-select -s /Applications/${XCODE_VERSION}
                        xcode-select -p
                        echo "[CHOOSING XCODE VERSION: COMPLETED]"

                        echo "[CLEANUP BEFORE BUILD: COMPLETED]"
                        """
                }
            }
        }

        stage('Slack Notification Test Run Start') {
            steps {
                script {
                    def reportText = """
*Started Test Run Details*

*Test Run ID:* `${BUILD_ID}`
*Job Name:* `${JOB_NAME}`
*Branch (fla-ios):* `${FLA_BRANCH}`
*Build Tag:* `${BUILD_TAG}`
*Scheme Name:* `${SCHEME_NAME}`

_Latest Commit (fla-ios):_ `${BUILD_LAST_COMMITS_LIST}`
_Latest Commit (integration-tests-stubs):_ `${STUBS_COMMIT_DETAILS}`

*Build Details:*
- *Xcode Version:* `${XCODE_VERSION}`
- *Device:* `${DEVICE_NAME}`
- *Runtime Version:* `${RUNTIME_VERSION}`
- *Workers count:* `${params.WORKERS}`
"""
                    SLACK_CURRENT_THREAD = slackPostInitialMessage(reportText, SLACK_TOKEN, REPORT_CHANNEL)
                    sh "echo Slack current thread is: ${SLACK_CURRENT_THREAD}"
                }
            }
        }

        stage('Running Full Parallel XCUITests Suite') {
            steps {
                withEnv(['CI=',
                'ARTIFACTORY_TOKEN=cmVmdGtuOjAxOjE3OTM5NjczNDg6NDk0eG1FUzRVOGZoemR5NDR4Q2k2UFVvMFhj']) {
                script {
                    STARTTIME = System.currentTimeMillis()

                    sh """
                        echo "[RUNNING FULL PARALLEL XCUITESTS SUITE:]"
                        cd ${env.WORKSPACE}
                        bundle install
                        rake prebuild
                    """

                    boolean isP0Scheme = (params.SCHEME_NAME == 'XCUITests-P0')
                    runXCUITests(params.SCHEME_NAME, params.DEVICE_NAME, params.RUNTIME_VERSION,
                                 params.WORKERS, TEST_OUTPUT, FILE_NAME_PATTERN, isP0Scheme, params.ENABLE_SHIPPED_FEATURE_FLAGS)
                }
            }
        }
        }

        stage('Generating Report Details for Slack (1st Run)') {
            steps {
                script {
                    sh """
                        echo "[GENERATING JUNIT | CUSTOM REPORTS:]"
                        BUILD_ID=dontKillMe nohup xcresultparser -o junit ${TEST_OUTPUT}/${FILE_NAME_PATTERN}.xcresult > ${TEST_OUTPUT}/report.xml
                        sleep 5
                        echo testoutput is: ${TEST_OUTPUT}
                        BUILD_ID=dontKillMe nohup cp ${TEST_OUTPUT}/report.xml ~/JenkinsTestResults/${JOB_NAME}/${BUILD_ID}.xml || true
                        BUILD_ID=dontKillMe nohup ~/scripts/genFailedFromXML.py ${TEST_OUTPUT}/report.xml
                        echo "[GENERATING JUNIT | CUSTOM REPORTS: COMPLETED]"
                    """
                    echo '[II GENERATING ALLURE REPORT:]'
                            sh """
                                BUILD_ID=dontKillMe nohup ~/scripts/xcresults export \\
                                    ${env.WORKSPACE}/fastlane/test_output/${FILE_NAME_PATTERN}.xcresult \\
                                    ${env.WORKSPACE}/fastlane/test_output/allure-results
                            """
                            echo '[II GENERATING ALLURE REPORT: COMPLETED]'

                    DURATIONSEC = ((System.currentTimeMillis() - STARTTIME) / 1000).intValue()
                    HOURS = (DURATIONSEC / 3600).intValue()
                    MINUTES = (DURATIONSEC / 60).intValue() % 60
                    DURATION = "${HOURS} hours ${MINUTES} minutes elapsed."

                    def counters  = readTestCounters("${env.WORKSPACE}/fastlane/test_output")
                    def failed    = counters.failed
                    def executed  = counters.executed
                    def disabled  = counters.disabled
                    echo "Failed is: ${failed}, Executed is: ${executed}, Disabled is: ${disabled}"

                    echo '[GENERATING REPORT DETAILS FOR SLACK: COMPLETED]'
                    echo '[POSTING REPORT OF 1ST RUN TO SLACK CHANNEL:]'

                    if (failed == '0') {
                        // No failures
                        def slackMessage = buildSlackMessage(true, JOB_NAME, NOW, DURATION, params.WORKERS, failed, executed, disabled)
                        slackPostMessage(slackMessage, SLACK_TOKEN, REPORT_CHANNEL, SLACK_CURRENT_THREAD)
                    } else {
                        // Some failures
                        def slackFailureMessage = buildSlackMessage(false, JOB_NAME, NOW, DURATION, params.WORKERS, failed, executed, disabled)
                        def fileReportCsv = "fastlane/test_output/test_run_results.csv"
                        echo "Filename pattern thread is: ${FILE_NAME_PATTERN}"
                        echo "Slack current thread is: ${SLACK_CURRENT_THREAD}"
                        uploadFileToSlack(fileReportCsv, slackFailureMessage, REPORT_CHANNEL, SLACK_CURRENT_THREAD)
                    }
                    echo '[POSTING REPORT OF 1ST RUN TO SLACK CHANNEL: COMPLETED]'
                }
            }
        }

        stage('Rerun Failed Tests (If Enabled)') {
            steps {
                script {
                    STARTTIME = System.currentTimeMillis()
                    def failedTestsCountPrevRun = sh(
                        script: 'cat $WORKSPACE/fastlane/test_output/how_many_failed',
                        returnStdout: true
                    ).trim()

                    if (params.RERUN_ENABLED) {
                        if (failedTestsCountPrevRun != '0') {
                            sh """
                                cd $WORKSPACE
                                xcrun simctl erase all || true
                                BUILD_ID=dontKillMe nohup ~/scripts/rerunTests_xml.py $WORKSPACE/fastlane/test_output/report.xml "${params.SCHEME_NAME}" "${params.DEVICE_NAME}" "${params.RUNTIME_VERSION}" $WORKSPACE/fastlane/test_output/DerivedData/SourcePackages
                                echo "[II RERUNNING FAILED TESTS: COMPLETED]"
                            """
                        }
                    }
                }
            }
        }

        stage('Generating Report Details for Slack (2nd Run)') {
            steps {
                script {
                    echo '[II GENERATING INITIAL JOB DETAILS VARIABLES:]'

                    def failedTestsCountPrevRun = sh(
                        script: 'cat $WORKSPACE/fastlane/test_output/how_many_failed',
                        returnStdout: true
                    ).trim()

                    def RERUN_TESTS_OUTPUT = "${env.WORKSPACE}/fastlane/rerun_test_output"
                    NOW = sh(script: "date +'%d/%m/%Y'+'%r'", returnStdout: true).trim()

                    echo '[II GENERATING INITIAL JOB DETAILS VARIABLES: COMPLETED]'

                    if (params.RERUN_ENABLED) {
                        if (failedTestsCountPrevRun == '0') {
                            echo '[II REPORT TO SLACK ABOUT 0 FAILURES:]'

                            slackPostMessage('Rerun script is not executed as we dont have failures in the previous build.', SLACK_TOKEN, REPORT_CHANNEL, SLACK_CURRENT_THREAD)
                        } else {
                            // Step 1: Generating JUnit report using xcresultparser
                            echo '[INFO] Generating JUnit report using xcresultparser...'
                            sh """
                                BUILD_ID=dontKillMe nohup xcresultparser -o junit "${RERUN_TESTS_OUTPUT}"/*.xcresult > ${RERUN_TESTS_OUTPUT}/report.xml
                            """
                            echo "[INFO] JUnit report generated at: ${RERUN_TESTS_OUTPUT}/report.xml"

                            // Step 2: Introducing a delay
                            echo '[INFO] Adding a 5-second delay to allow processes to complete...'
                            sh 'sleep 5'
                            echo '[INFO] Delay completed.'

                            // Step 3: Copying the JUnit report
                            echo '[INFO] Copying JUnit report to the shared location...'
                            sh """
                                BUILD_ID=dontKillMe nohup cp ${RERUN_TESTS_OUTPUT}/report.xml ~/JenkinsTestResults/${JOB_NAME}/${BUILD_ID}_rerun.xml || true
                            """
                            echo "[INFO] JUnit report copied to: ~/JenkinsTestResults/${JOB_NAME}/${BUILD_ID}_rerun.xml"

                            // Step 4: Generating custom failure reports
                            echo '[INFO] Generating custom failure reports from the JUnit report...'
                            sh """
                                BUILD_ID=dontKillMe nohup ~/scripts/genFailedFromXML.py ${RERUN_TESTS_OUTPUT}/report.xml rerun_test_output
                            """
                            echo '[INFO] Custom failure reports generated.'

                            echo '[II GENERATING REPORT DETAILS FOR SLACK:]'
                            DURATIONSEC = ((System.currentTimeMillis() - STARTTIME) / 1000).intValue()
                            HOURS = (DURATIONSEC / 3600).intValue()
                            MINUTES = (DURATIONSEC / 60).intValue() % 60
                            DURATION = "${HOURS} hours ${MINUTES} minutes elapsed."
                            def counters = readTestCounters("${RERUN_TESTS_OUTPUT}")
                            def failed   = counters.failed
                            def executed = counters.executed
                            echo '[II GENERATING REPORT DETAILS FOR SLACK: COMPLETED]'

                            echo '[II POSTING REPORT OF 2nd RUN TO SLACK CHANNEL:]'
                            if (failed == '0') {
                                def slackSuccessMsg = """
*Finished Test Run Successfully!*

*Job Name:* `${JOB_NAME}`
*Started At:* `${NOW}`
*Build Duration:* `${DURATION}`

*Test Summary:*
- *Parallel Workers:* `1`
- *Executed Tests:* `${executed}`
- *Failed Tests:* `${failed}`
"""
                                slackPostMessage(slackSuccessMsg, SLACK_TOKEN, REPORT_CHANNEL, SLACK_CURRENT_THREAD)
                            } else {
                                def slackFailureMsg = """
*Test Run Completed with Failures!*

*Job Name:* `${JOB_NAME}`
*Started At:* `${NOW}`
*Build Duration:* `${DURATION}`

*Test Summary:*
- *Parallel Workers:* `1`
- *Executed Tests:* `${executed}`
- *Failed Tests:* `${failed}`
"""
                                def fileReportCsv = "fastlane/rerun_test_output/test_run_results.csv"
                                echo "Slack file is: ${fileReportCsv}"
                                echo "Slack msg is: ${slackFailureMsg}"
                                echo "Slack token is: ${SLACK_TOKEN}"
                                echo "Slack channel is: ${REPORT_CHANNEL}"
                                echo "Slack current thread is: ${SLACK_CURRENT_THREAD}"
                                uploadFileToSlack(fileReportCsv, slackFailureMsg, REPORT_CHANNEL, SLACK_CURRENT_THREAD)
                            }
                            echo '[II POSTING REPORT OF 2nd RUN TO SLACK CHANNEL: COMPLETED]'
                        }
                    }
                }
            }
        }

        stage('Clean Up') {
            steps {
                script {
                    sh """
                        killall Simulator || true
                        killall Xcode || true
                        killall 'Problem Reporter' || true
                    """
                }
            }
        }
    }

    post {
        always {
            allure([
                includeProperties: false,
                jdk: '',
                reportBuildPolicy: 'ALWAYS',
                results: [[path: 'fastlane/test_output/allure-results']]
            ])

            junit(
                testResults: 'fastlane/test_output/report.xml',
                allowEmptyResults: false,
                healthScaleFactor: 1.0,
                keepLongStdio: true,
                // Remove testDataPublishers for testing
                skipPublishingChecks: true,
                skipMarkingBuildUnstable: true,
                skipOldReports: true
            )

            script {
                echo 'Checking build status...'
                if (currentBuild.result == null || currentBuild.result == 'UNSTABLE') {
                    echo 'Build is unstable. Marking it as SUCCESS.'
                    currentBuild.result = 'SUCCESS'
                } else {
                    echo "Build status: ${currentBuild.result}. No changes needed."
                }
            }
        }
        failure {
            echo 'FAILURE block triggered!'
            sh "~/scripts/build_failed_without_file.sh ${params.REPORT_CHANNEL} ${SLACK_CURRENT_THREAD}"
        }
    }
}
