// vim: set filetype=groovy:

def jdkVersion = 'jdk-8-latest'
def mavenVersion = 'maven-3.5-latest'
def mavenSettingsConfig = 'camunda-maven-settings'

def storeNumOfBuilds() {
  return env.BRANCH_NAME ==~ /(master|develop|stage)/ ? '10' : '3'
}

def joinJmhResults = '''\
#!/bin/bash -x
cat **/*/jmh-result.json | jq -s add > target/jmh-result.json
'''

pipeline {
    agent {
      kubernetes {
        cloud 'zeebe-ci'
        label "zeebe-ci-build_${env.JOB_BASE_NAME.replaceAll("%2F", "-").replaceAll("\\.", "-").take(20)}-${env.BUILD_ID}"
        defaultContainer 'jnlp'
        yamlFile '.ci/podSpecs/builderAgent.yml'
      }
    }

    // Environment
    environment {
      NEXUS = credentials("camunda-nexus")
    }

    options {
      buildDiscarder(logRotator(daysToKeepStr:'14', numToKeepStr:storeNumOfBuilds()))
      timestamps()
      timeout(time: 45, unit: 'MINUTES')
    }

    stages {
      stage('Install') {
        steps {
          container('maven') {
            // MaxRAMFraction = LIMITS_CPU because there are only maven build threads
            sh '''
              JAVA_TOOL_OPTIONS="$JAVA_TOOL_OPTIONS -XX:MaxRAMFraction=$((LIMITS_CPU))" \
              mvn -B -T$LIMITS_CPU -s settings.xml -DskipTests -Dskip-zbctl=false \
                  clean com.mycila:license-maven-plugin:check com.coveo:fmt-maven-plugin:check install
            '''
          }
          //stash name: "zeebe-dist", includes: "dist/target/zeebe-broker/**/*"
        }
      }
      stage('Verify') {
        failFast true
        parallel {
          stage('1 - Java Tests') {
            steps {
              container('maven') {
                // MaxRAMFraction = LIMITS_CPU+1 because there are LIMITS_CPU surefire threads + one maven thread
                sh '''
                  JAVA_TOOL_OPTIONS="$JAVA_TOOL_OPTIONS -XX:MaxRAMFraction=$((LIMITS_CPU+1))" \
                  mvn -B -T$LIMITS_CPU -s settings.xml verify -P skip-unstable-ci,retry-tests,parallel-tests
                '''
              }
            }
            post {
              failure {
                archiveArtifacts artifacts: '**/target/*-reports/**/*-output.txt,**/**/*.dumpstream,**/**/hs_err_*.log', allowEmptyArchive: true
              }
            }
          }

          stage('2 - JMH') {
            // delete this line to also run JMH on feature branch
            //when { anyOf { branch 'master'; branch 'develop' } }
            steps {
              container('maven-it') {
                // maxramfraction = limits_cpu+1 because there are limits_cpu surefire threads + one maven thread
                sh '''
                  JAVA_TOOL_OPTIONS="$JAVA_TOOL_OPTIONS -XX:MaxRAMFraction=2" \
                  mvn -B -s settings.xml integration-test -DskipTests -P jmh
                '''
              }
            }

            post {
              success {
                sh joinJmhResults
                jmhReport 'target/jmh-result.json'
              }
            }
          }

          stage('3 - Go Tests') {
            steps {
              //unstash name: "zeebe-dist"
              container('golang') {
                sh '''
                  #!/bin/bash -eux
                  apt-get -qq update
                  apt-get install -y -qq default-jre-headless
                  mkdir -p ${GOPATH}/src/github.com/zeebe-io
                  ln -s `pwd` ${GOPATH}/src/github.com/zeebe-io/zeebe
                  export CGO_ENABLED=0
                  cd ${GOPATH}/src/github.com/zeebe-io/zeebe/clients/go
                  make install-deps test
                  cd ${GOPATH}/src/github.com/zeebe-io/zeebe/clients/zbctl
                  make test
                '''
              }
            }
          }
        }
      }
//
//        stage('Deploy') {
//            when { branch 'develop' }
//            steps {
//                withMaven(jdk: jdkVersion, maven: mavenVersion, mavenSettingsConfig: mavenSettingsConfig) {
//                    sh 'mvn -B -T 1C generate-sources source:jar javadoc:jar deploy -DskipTests'
//                }
//            }
//        }
//
//        stage('Build Docker Image') {
//            when { branch 'develop' }
//            steps {
//                build job: 'zeebe-DISTRO-docker', parameters: [
//                    string(name: 'RELEASE_VERSION', value: "SNAPSHOT"),
//                    booleanParam(name: 'IS_LATEST', value: false)
//                ]
//            }
//        }
//
//        stage('Trigger Performance Tests') {
//            when { branch 'develop' }
//            steps {
//                build job: 'zeebe-cluster-performance-tests', wait: false
//            }
//        }
    }

    post {
        changed {
            sendBuildStatusNotificationToDevelopers(currentBuild.result)
        }
    }
}

void sendBuildStatusNotificationToDevelopers(String buildStatus = 'SUCCESS') {
    def buildResult = buildStatus ?: 'SUCCESS'
    def subject = "${buildResult}: Job '${env.JOB_NAME} [${env.BUILD_NUMBER}]'"
    def details = "${buildResult}: Job '${env.JOB_NAME} [${env.BUILD_NUMBER}]' see console output at ${env.BUILD_URL}'"

    emailext (
        subject: subject,
        body: details,
        recipientProviders: [[$class: 'DevelopersRecipientProvider'], [$class: 'RequesterRecipientProvider']]
    )
}
