pipelineJob('zeebe-RELEASE-pipeline')
{

    definition {
        cps {
            script(readFileFromWorkspace('.ci/pipelines/zeebe-release.groovy'))
            sandbox()
        }
    }

    parameters
    {
        stringParam('RELEASE_VERSION', '0.X.0', 'Zeebe version to release.')
        stringParam('DEVELOPMENT_VERSION', '0.Y.0-SNAPSHOT', 'Next Zeebe development version.')
        booleanParam('IS_LATEST', true, 'Should the docker image of this release be tagged as latest?')
        booleanParam('PUSH_CHANGES', true, 'If TRUE, push the changes to remote repositories. If FALSE, do not push changes to remote repositories. Must be used in conjunction with USE_LOCAL_CHECKOUT = TRUE to test the release!')
        booleanParam('USE_LOCAL_CHECKOUT', false, 'If TRUE, uses the local git repository to checkout the release tag to build.  If FALSE, checks out the release tag from the remote repositoriy. Must be used in conjunction with PUSH_CHANGES = FALSE to test the release!')
        booleanParam('SKIP_DEPLOY_TO_MAVEN_CENTRAL', false, 'If TRUE, skip the deployment to maven central. Should be used when testing the release.')
        booleanParam('SKIP_DEPLOY_TO_CAMUNDA_NEXUS', false, 'If TRUE, skip the deployment to Camunda nexus. Should be used when testing the release.')
    }

}
