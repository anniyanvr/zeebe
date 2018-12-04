def gitConfig = '''\
#!/bin/bash -xe

git config --global user.email "ci@camunda.com"
git config --global user.name "camunda-jenkins"
'''

def gpgKeys = '''\
#!/bin/bash

if [ -e "${MVN_CENTRAL_GPG_KEY_SEC}" ]
then
  gpg -q --allow-secret-key-import --import ${MVN_CENTRAL_GPG_KEY_SEC} || echo 'Private GPG Sign Key is already imported!.'
  rm ${MVN_CENTRAL_GPG_KEY_SEC}
else
  echo 'Private GPG Key not found.'
fi

if [ -e "${MVN_CENTRAL_GPG_KEY_PUB}" ]
then
  gpg -q --import ${MVN_CENTRAL_GPG_KEY_PUB} || echo 'Public GPG Sign Key is already imported!.'
  rm ${MVN_CENTRAL_GPG_KEY_PUB}
else
  echo 'Public GPG Key not found.'
fi
'''

def setupGoPath = '''\
#!/bin/bash -eux
echo "== Go build environment =="
go version
echo "GOPATH=${GOPATH}"

PROJECT_ROOT="${GOPATH}/src/github.com/zeebe-io"
mkdir -p ${PROJECT_ROOT}
ln -fvs ${WORKSPACE} ${PROJECT_ROOT}/zeebe
'''

def mavenRelease = """\
mvn release:prepare release:perform -Dgpg.passphrase="\${GPG_PASSPHRASE}" -B \
    -Dresume=false \
    -Dskip-zbctl=false \
    -Dtag=${params.RELEASE_VERSION} \
    -DreleaseVersion=${params.RELEASE_VERSION} \
    -DdevelopmentVersion=${params.DEVELOPMENT_VERSION} \
    -DpushChanges=${params.PUSH_CHANGES} \
    -DremoteTagging=${params.PUSH_CHANGES} \
    -DlocalCheckout=${params.USE_LOCAL_CHECKOUT} \
    -Darguments='--settings=\${NEXUS_SETTINGS} -DskipTests=true -Dskip-zbctl=false -Dgpg.passphrase="\${GPG_PASSPHRASE}" -Dskip.central.release=${params.SKIP_DEPLOY_TO_MAVEN_CENTRAL} -Dskip.camunda.release=${params.SKIP_DEPLOY_TO_CAMUNDA_NEXUS}'
"""

def githubRelease = '''\
#!/bin/bash

cd dist/target

# create checksum files
sha1sum zeebe-distribution-${RELEASE_VERSION}.tar.gz > zeebe-distribution-${RELEASE_VERSION}.tar.gz.sha1sum
sha1sum zeebe-distribution-${RELEASE_VERSION}.zip > zeebe-distribution-${RELEASE_VERSION}.zip.sha1sum

# do github release
curl -sL https://github.com/aktau/github-release/releases/download/v0.7.2/linux-amd64-github-release.tar.bz2 | tar xjvf - --strip 3

./github-release release --user zeebe-io --repo zeebe --tag ${RELEASE_VERSION} --name "Zeebe ${RELEASE_VERSION}" --description ""

for f in zeebe-distribution-${RELEASE_VERSION}.{tar.gz,zip}{,.sha1sum}; do
    ./github-release upload --user zeebe-io --repo zeebe --tag ${RELEASE_VERSION} --name "${f}" --file "${f}"
done
'''

def changelog = '''\
#!/bin/bash -xeu
CLOG_VERSION=v0.9.3

curl -sL https://github.com/clog-tool/clog-cli/releases/download/${CLOG_VERSION}/clog-${CLOG_VERSION}-x86_64-unknown-linux-musl.tar.gz | tar xzvf -
chmod +x clog

./clog --setversion ${RELEASE_VERSION}
cat CHANGELOG.md

git commit -am 'chore(project): update CHANGELOG'
'''

node('ubuntu-large')
{
    try
    {
        timeout(time: 1, unit: 'HOURS')
        {
            timestamps
            {
                sshagent(['camunda-jenkins-github-ssh'])
                {
                    configFileProvider([
                        configFile(fileId: 'camunda-maven-settings', variable: 'NEXUS_SETTINGS')])
                    {
                        withCredentials([
                            string(credentialsId: 'password_maven_central_gpg_signing_key', variable: 'GPG_PASSPHRASE'),
                            file(credentialsId: 'maven_central_gpg_signing_key', variable: 'MVN_CENTRAL_GPG_KEY_SEC'),
                            file(credentialsId: 'maven_central_gpg_signing_key_pub', variable: 'MVN_CENTRAL_GPG_KEY_PUB'),
                            string(credentialsId: 'github-camunda-jenkins-token', variable: 'GITHUB_TOKEN')])
                        {
                            stage('Prepare')
                            {
                                sh gitConfig
                                sh gpgKeys
                            }

                            stage('zeebe')
                            {
                                git branch: "release-${params.RELEASE_VERSION}", credentialsId: 'camunda-jenkins-github-ssh', url: "git@github.com:zeebe-io/zeebe.git"
                                withMaven(jdk: 'jdk-8-latest', maven: 'maven-3.5-latest', mavenSettingsConfig: 'camunda-maven-settings')
                                {
                                    sh setupGoPath
                                    sh changelog
                                    sh mavenRelease
                                }
                            }

                            if (params.PUSH_CHANGES)
                            {
                                stage('GitHub Release')
                                {
                                    sh githubRelease
                                }

                                stage('Build Docker Image')
                                {
                                    build job: 'zeebe-DISTRO-docker', parameters: [
                                        string(name: 'RELEASE_VERSION', value: params.RELEASE_VERSION),
                                        booleanParam(name: 'IS_LATEST', value: params.IS_LATEST)
                                    ]
                                }

                                stage('Publish Docs')
                                {
                                    build job: 'zeebe-docs', parameters: [
                                        string(name: 'RELEASE_VERSION', value: params.RELEASE_VERSION)
                                    ]
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    catch(e)
    {
        emailext recipientProviders: [[$class: 'RequesterRecipientProvider']], subject: "Job '${env.JOB_NAME} [${env.BUILD_NUMBER}]' failed", body: "Check console output at ${env.BUILD_URL}"
        throw e
    }
}
