cachedir="~/jenkins_cache"
repo_mirror="${cachedir}/git-repo.git"
repo_url="ssh://git@github.com/PhilipsHueDev/repo.git"
cache_workspace="${cachedir}/hue-bridge-reference"

options_repoinit="--reference=${cache_workspace} --repo-url=${repo_mirror} --no-repo-verify"

def git_url
def manifest_repository
def manifest_branch
def manifest_file
def project_build_dir

def checkout_project
def project_branchname
def project_path
def project_remote

def setDefaults() {
    git_url = "ssh://git@github.com/PhilipsHueDev/"
    manifest_repository = "hue-bridge-compositions"
    manifest_branch = "develop"
    manifest_file = "default.xml"
    project_build_dir = "."

    checkout_project = false
    project_branchname = "develop"
    project_path = ""
    project_remote = ""
}

def setProject(Map params = [:]) {
    setDefaults()
    if (params.git_url) {
        git_url = "${params.git_url}"
    }
    if (params.manifest_repository) {
        manifest_repository = "${params.manifest_repository}"
    }
    if (params.manifest_file) {
        manifest_file = "${params.manifest_file}"
    }
    if (params.manifest_branch) {
        manifest_branch = "${params.manifest_branch}"
    }
    if (params.project_build_dir) {
        project_build_dir = "${params.project_build_dir}"
    }
    if (params.checkout_project) {
        checkout_project = params.checkout_project
        if (params.project_branchname) {
            project_branchname = "${params.project_branchname}"
        }
        if (params.project_remote) {
            project_remote = "${params.project_remote}"
        }
        if (params.project_path) {
            project_path = "${params.project_path}"
        }
    }
    echoConfiguration()
}

def echoConfiguration() {
    echo "git_url: ${git_url}"
    echo "manifest_repository: ${manifest_repository}"
    echo "manifest_file: ${manifest_file}"
    echo "manifest_branch: ${manifest_branch}"
    echo "project_build_dir: ${project_build_dir}"
    if (checkout_project) {
        echo "checkout_project: true"
        echo "project_branchname: ${project_branchname}"
        echo "project_path: ${project_path}"
        echo "project_remote: ${project_remote}"
    }
    else {
        echo "checkout_project: false"
    }
}

/**
 * Cleanup workspace
 */
def cleanup() {
    deleteDir()
}

/**
 * Google limits the number of clones of repo allowed within an hour. Keeping a local clone of the
 * repo repository avoids having the build fail due to regular cloning from google. The local clone
 * is kept up to date. Google does not seem to limit the number of fetches, so that seems to be okay
 */
def updateRepoMirror() {
    lock('repo_cache') {
        sh "\n    echo \$0; cat \$0" +
           "\n    if ! [ -d ${repo_mirror} ]; then" +
           "\n        mkdir -p `dirname ${repo_mirror}`" +
           "\n        git clone --mirror --bare ${repo_url} ${repo_mirror}" +
           "\n    else" +
           "\n        cd ${repo_mirror}" +
           "\n        while [ -f index.lock ]; do" +
           "\n            echo 'wait for git lock to clear...'" +
           "\n            sleep 1" +
           "\n        done" +
           "\n        git remote update" +
           "\n    fi" +
           "\n"
    }
}

def updateCachedWorkspace() {
    lock('repo_cache') {
        sh "cd ${cache_workspace}; repo sync -j8 -d"
    }
}

/**
 * Checkout a reference workspace
 *
 * The reference checkout is checked out normally with repo using the latest manifest state.
 * A reference manifest.xml is then created from the workspace composition, and stashed so
 * that downstream nodes can be checked out with exactly the same state. The manifest.xml is
 * also archived as a build artifact.
 */
def checkoutReferenceWorkspace() {
    sh "repo init -u ${git_url}${manifest_repository}.git -m ${manifest_file} -b ${manifest_branch} ${options_repoinit}"
    sh "repo sync -j8 -d"
    if (checkout_project) {
        echo "Checking out branch ${project_branchname} for project ${project_remote}"
        sh "cd ${project_path} && git fetch ${project_remote} && git checkout --force --detach ${project_remote}/${project_branchname}"
    }
    sh "repo manifest -r -o manifest.xml --suppress-upstream-revision"
    stash(name: "manifest", includes: "manifest.xml")
}

/**
 * Checkout a workspace (using the reference workspace)
 *
 * Use stashed manifest.xml to do exact checkout
 */
def checkoutWorkspace() {
    unstash("manifest")
    // Prevent overwriting the link destination
    sh "repo init -u ssh://git@github.com/PhilipsHueDev/${manifest_repository}.git ${options_repoinit}"
    sh "cp --remove-destination manifest.xml .repo/manifest.xml"
    sh "repo sync -j8 -d"
}

def checkoutPullRequestWorkspace() {
    // Prevent overwriting the link destination
    sh "repo init -u ${git_url}${manifest_repository}.git -m ${manifest_file} -b ${manifest_branch} ${options_repoinit}"
    sh "repo sync -j8 -d"
    sh "cd .repo/repo && git checkout develop"
}

/**
 * Execute a build step
 *
 * @param reference            Checkout reference workspace (default:false)
 */
def checkout(Map params = [:]) {
    deleteDir()

    if (params.reference) {
        updateCachedWorkspace()
        checkoutReferenceWorkspace()
    }
    else {
        checkoutWorkspace()
    }
}

def testFunc() {
    echo "Test Func"
}

/**
 * Generate revision.txt
 */
def generateRevisionFile() {
    sh "repo forall -c git show -s --format=%ct > timestamps.txt"
    sh "cd .repo/manifests; git show -s --format=%ct >> ../../timestamps.txt"
    highestTimestamp=0

    timestampsString = readFile('timestamps.txt')
    def timestampsLines = timestampsString.split('\n')
    for (int i = 0; i < timestampsLines.size(); i++) {
        int timestamp = timestampsLines[i].toInteger()
        if (timestamp > highestTimestamp) {
            highestTimestamp=timestamp
        }
    }
    sh "rm timestamps.txt"

    sh "date -u -d @${highestTimestamp} +'%y%m%d%H%M' > revision.txt"
    stash(includes: "revision.txt", name: 'revision')
}

def publishCtest(pattern) {
    sh "echo pattern = ${pattern}"
    step([$class: 'XUnitBuilder',
        thresholds: [
            [$class: 'SkippedThreshold', failureThreshold: '0'],
            [$class: 'FailedThreshold', failureThreshold: '0']],
        tools: [
            [$class: 'CTestType', pattern: pattern]]])
//    step([$class: 'XUnitPublisher', 
//        testTimeMargin: '3000', 
//        thresholdMode: 1, 
//        thresholds: [
//            [$class: 'FailedThreshold', failureNewThreshold: '0', failureThreshold: '0', unstableNewThreshold: '0', unstableThreshold: '0'], 
//            [$class: 'SkippedThreshold', failureNewThreshold: '0', failureThreshold: '0', unstableNewThreshold: '0', unstableThreshold: '0']
//        ], 
//        tools: [
//            [$class: 'CTestType', deleteOutputFiles: true, failIfNotNew: true, pattern: pattern, skipNoTestFiles: false, stopProcessingIfError: true]
//        ]
//    ])
}

def publishNUnit(pattern) {
    step([$class: 'XUnitPublisher', 
        testTimeMargin: '3000', 
        thresholdMode: 1, 
        thresholds: [
            [$class: 'FailedThreshold', failureNewThreshold: '0', failureThreshold: '0', unstableNewThreshold: '0', unstableThreshold: '0'],
            [$class: 'SkippedThreshold', failureNewThreshold: '0', failureThreshold: '0', unstableNewThreshold: '0', unstableThreshold: '0']
        ], 
        tools: [
            [$class: 'NUnitJunitHudsonTestType', deleteOutputFiles: true, failIfNotNew: true, pattern: pattern, skipNoTestFiles: false, stopProcessingIfError: true]
        ]
    ])
}

def publishjUnit(pattern) {
    junit pattern
}

def publishCoverage(reportFile) {
    // TODO: Needs non-stable version of Cobertura plugin
    // TODO: Add limits and require report
//    step([$class: 'CoberturaPublisher',
//        coberturaReportFile: reportFile,
//    ])
}

def isRunningOnDeveloperSlave() {
    def result = true
    if (env.JENKINS_DEDICATED_AGENT) {
        result = false
    }
    return result
}

/**
 * make wrapper
 * 
 * @param directory         Directory to build
 * @param build             Build type (debug/release)
 * @param build_type        Build type (debug/release)
 * @param targets           Targets to build (default:none)
 * @param jobs              Number of job executors (default:1)
 * @param options           Options (default:none)
 */
def make(Map params = [:]) {
    build = ""
    build_type = ""
    int jobs = 1
    if (params.build) {
        build = "BUILD=${params.build}"
    }
    if (params.build_type) {
        build_type = "BUILD_TYPE=${params.build_type}"
    }
    if (params.jobs) {
        jobs = params.jobs.toInteger()
        if (isRunningOnDeveloperSlave()) {
            jobs = jobs > 4 ? 4 : jobs
        }
    }
    sh "make -C ${params.directory} -j${jobs} ${build} ${build_type} ${params.targets ?: ''} ${params.options ?: ''}"
}

/**
 * Execute a build step
 * 
 * @param directory         Directory to build
 * @param build             Build type (debug/release)
 * @param publish_ctest     Publish CTest reports (default:false)
 * @param publish_coverage  Publish coverage reports (default:false)
 */
def makeBuild(Map params = [:]) {
    build_dir = "${project_build_dir}/${params.directory}"

    if (params.publish_coverage) {
        make(directory:"${build_dir}", build:"${params.build}", build_type:"${params.build}", jobs:"${params.jobs ?: '1'}", targets:"coverage", options:"EXECUTE_UNITTESTS=0")
    }
    else if (params.publish_ctest) {
        make(directory:"${build_dir}", build:"${params.build}", build_type:"${params.build}", jobs:"${params.jobs ?: '1'}", targets:"unit-test", options:"EXECUTE_UNITTESTS=0")
    }
    else {
        make(directory:"${build_dir}", build:"${params.build}", build_type:"${params.build}", jobs:"${params.jobs ?: '1'}", options:"EXECUTE_UNITTESTS=0")
    }
    
    if (params.publish_ctest) {
        publishCtest("${build_dir}/**/Testing/**/Test.xml")
    }
    if (params.publish_coverage) {
        stashname = "coverage-${params.directory}-${params.build}"
        stashes_coverage.add(stashname)
        echo "stashes_coverage.add(${stashname})"
        stash(name: stashname, includes: "${build_dir}/build_dir/**/Testing/*/CodeCoverage.xml")
    }
}

/**
 * Execute a MS build step
 * 
 * @param solution          Path to MS solution file
 * @param build             Build type (Debug/Release; default:Release)
 * @param target            Build target (default:Rebuild)
 */
def msBuild(Map params = [:]) {
    build_dir = "${project_build_dir}/${params.solution}"
    build = "Release"
    target = "Rebuild"
    if (params.build) {
        build = params.build
    }
    if (params.target) {
        target = params.target
    }
    bat "\"C:\\Program Files (x86)\\Microsoft Visual Studio\\2017\\BuildTools\\VC\\Auxiliary\\Build\\vcvars32.bat\" & MSBuild.exe \"${build_dir}\" /m /p:ReferencePath=\"C:\\Program Files (x86)\\NUnit 2.6.2\\bin\\framework\" /t:${target} /p:Platform=\"Any CPU\" /p:Configuration=${build}"
}

/**
 * Run unittests
 * 
 * @param path		    Path to nunit dll
 * @param build             Build type (Debug/Release; default:Release)
 * @param unittests         nunit dll unittest
 */
def msRunUnittests(Map params = [:]) {
   build = "Release"
  
   if (params.path) {
       path = params.path
   }
  
   if (params.build) {
       build = params.build
   }
  
   bat returnStatus: true, script: "\"C:\\Program Files (x86)\\NUnit 2.6.2\\bin\\nunit-console.exe\" ${params.path}\\${build}\\${params.unittests}"
   publishNUnit('TestResult.xml')
}

/**
 * Execute a MSBuild step on Linux
 * 
 * @param solution          Path to MS solution file
 * @param build             Build type (Debug/Release; default:Release)
 * @param target            Build target (default:Rebuild)
 */
def msBuildLinux(Map params = [:]) {
    build_dir = "${project_build_dir}/${params.solution}"
    build = "Release"
    target = "Rebuild"
    if (params.build) {
        build = params.build
    }
    if (params.target) {
        target = params.target
    }
    sh "msbuild /p:Configuration=${build} /t:${target} \"${build_dir}\""
}

/**
 * Archive build artifacts to network drive.
 * Artifact folder is tarred and gzipped before copying.
 *
 * @param artifactsPath     Directory containing artifacts to archive
 * @param archivePath       Path to copy artifacts to
 * @param branch            Branch
 */
def archiveArtifactsToNetwork(Map params = [:]) {
    sh "\narchive_filename=\"`date +'%Y-%m-%d_%H%M'`_`cat ${params.artifactsPath}/revision.txt`.tar.gz\"" +
       "\ndir_to=\"${params.archivePath}/${params.branch}\"" +
       "\nmkdir -p \${dir_to}" +
       "\ncd ${params.artifactsPath}/" +
       "\ntar zcf ../\${archive_filename} ." +
       "\ncp ../\${archive_filename} \${dir_to}" +
       "\n"
}

def tryBuildComposition() {
    if ("${manifest_branch}" == "${project_branchname}")
    {
        job_branchname = project_branchname.replaceAll("\\/", "%2F")
        try {
            build job: "../${manifest_repository}/${job_branchname}", wait: false, parameters: [[$class: 'StringParameterValue', name: 'TRIGGER_PROJECT', value: "${project_path}"], [$class: 'StringParameterValue', name: 'TRIGGER_BRANCH', value: "${project_branchname}"]]
        }
        catch (err) {
            echo "Failed: ${err}"
        }
    }
    else
    {
        echo "No branch '${project_branchname}' on composition archive"
    }
}

return this
