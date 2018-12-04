// vim: set filetype=groovy:

def githubOrga = 'zeebe-io'
def gitRepository = 'zeebe'
def gitBranch = 'cloud-ci'

def dslScriptsToExecute = '''\
.ci/jobs/*.dsl
.ci/views/*.dsl
'''

def dslScriptPathToMonitor = '''\
.ci/jobs/.*\\.dsl
.ci/pipelines/.*
.ci/views/.*\\.dsl
'''

def setBrokenViewAsDefault = '''\
import jenkins.model.Jenkins

def jenkins = Jenkins.instance
def broken = jenkins.getView('Broken')
if (broken) {
  jenkins.setPrimaryView(broken)
}
'''

def seedJob = job('seed-job-zeebe') {

  displayName 'Seed Job Zeebe'
  description 'JobDSL Seed Job for Camunda Zeebe'

  scm {
    git {
      remote {
        github "${githubOrga}/${gitRepository}", 'ssh'
        credentials 'camunda-jenkins-github-ssh'
      }
      branch gitBranch
      extensions {
        localBranch gitBranch
        pathRestriction {
          includedRegions(dslScriptPathToMonitor)
          excludedRegions('')
        }
      }
    }
  }

  triggers {
    githubPush()
  }

  label 'master'
  jdk '(Default)'

  steps {
    jobDsl {
      targets(dslScriptsToExecute)
      removedJobAction('DELETE')
      removedViewAction('DELETE')
      failOnMissingPlugin(true)
      ignoreMissingFiles(false)
      unstableOnDeprecation(true)
      sandbox(true)
    }
    systemGroovyCommand(setBrokenViewAsDefault){
      sandbox(true)
    }
  }

  wrappers {
    timestamps()
    timeout {
      absolute 5
    }
  }

  publishers {
    extendedEmail {
      triggers {
        statusChanged {
          sendTo {
            culprits()
            requester()
          }
        }
      }
    }
  }

  logRotator(-1, 5, -1, 1)

}

queue(seedJob)

multibranchPipelineJob('zeebe') {

  displayName 'Camunda Zeebe'
  description 'MultiBranchJob for Camunda Zeebe'

  branchSources {
    github {
      repoOwner githubOrga
      repository gitRepository

      includes 'cloud-ci'
      excludes '.*.tmp'

      scanCredentialsId 'camunda-jenkins-github'
      checkoutCredentialsId 'camunda-jenkins-github-ssh'

      buildForkPRHead true
      buildForkPRMerge false

      buildOriginPRHead true
      buildOriginPRMerge false
    }
  }

  orphanedItemStrategy {
    discardOldItems {
      numToKeep 20
    }
    defaultOrphanedItemStrategy {
      pruneDeadBranches true
      daysToKeepStr '1'
      numToKeepStr '20'
    }
  }

  triggers {
    periodic(1440) // Minutes - Re-index once a day, if not triggered before
  }
}

// Community projects

def repositories = [
    'zeebe-simple-monitor',
    'spring-zeebe',
    'zeebe-cluster-tests',
    'zeebe.io',
    'zeebe-script-worker',
    'zeebe-workbench',
]

repositories.each { repository ->
    job("seed-job-${repository}") {

      scm {
        git {
          remote {
            github "zeebe-io/${repository}", 'ssh'
            credentials 'camunda-jenkins-github-ssh'
          }

          if (repository.equals("zeebe")) {
            gitBranch = 'develop'
          }
          else {
            gitBranch = 'master'
          }

          branch gitBranch

          extensions {
            localBranch gitBranch
            pathRestriction {
                includedRegions '\\.ci/.*'
                excludedRegions ''
            }
          }
        }
      }

      triggers {
        githubPush()
      }

      label 'master'
      jdk 'jdk-8-latest'

      steps {
        dsl {
          external(".ci/**/*.dsl")
        }
      }

      wrappers {
        timestamps()
        timeout {
          absolute 60 // timeout after 60 mins
        }
      }

      publishers {
        extendedEmail {
          triggers {
            firstFailure {
              sendTo {
                culprits()
              }
            }
            fixed {
              sendTo {
                culprits()
              }
            }
          }
        }
      }

      logRotator(-1, 5, -1, 1)

    }
}
