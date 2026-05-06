pipeline {
    agent any

    environment {
        // --- DISCORD CONFIGURATION ---
        // Webhook URL and Thread ID derived from project context
        DISCORD_WEBHOOK_URL = "https://discord.com/api/webhooks/1471469221614583810/pfMtLyRbTDKiUGMJyVBjhhJ3RDgQelOX71iMGqWg3HdrlokqBJSt1Ox3aC4yTkkGtZ-_"
        THREAD_ID = "1471468999282786397"

        // --- ASSETS ---
        ICON_URL = "https://gitlab.com/uploads/-/system/group/avatar/121690756/SinceRPG.png?width=48"
        THUMBNAIL_URL = "https://gitlab.com/uploads/-/system/group/avatar/121690756/SinceRPG.png?width=48"
        BOT_NAME = "SinceBooster Build"

        // --- COLORS (Decimal format for Discord API) ---
        COLOR_PENDING = "16766720" // Yellow/Orange
        COLOR_SUCCESS = "5763719"  // Green
        COLOR_FAIL = "15548997"    // Red

        // --- TEXTS ---
        NO_CHANGELOG_TEXT = "No specific changelog provided."
        FAIL_DESC_TEXT = "A compilation error occurred. Please check the Jenkins Console logs for details."
    }

    stages {
        stage('Prepare & Build') {
            steps {
                script {
                    // Ensure the Gradle wrapper has execution permissions in the Linux environment
                    sh 'chmod +x gradlew'

                    // 1. Notify Discord that the build process has started
                    sh 'python3 build_booster.py --start || echo "Discord Start Notify Failed"'

                    try {
                        // 2. Run the Gradle build task
                        sh './gradlew clean build'
                    } catch (Exception e) {
                        // 3. If Gradle fails, update the Discord message to the 'Fail' state
                        sh 'python3 build_booster.py --fail'
                        error "Build failed: ${e.message}"
                    }
                }
            }
        }

        stage('Finalize') {
            steps {
                script {
                    // 4. On success, update Discord message to 'Success' and attach the JAR file
                    sh 'python3 build_booster.py'
                }
            }
        }
    }

    post {
        always {
            // Cleanup the workspace to prevent disk bloat on the server
            cleanWs()
        }
    }
}