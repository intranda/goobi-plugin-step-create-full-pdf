pipeline {
  agent {
    docker {
      image 'nexus.intranda.com:4443/maven:3.6-jdk-11'
      registryUrl 'https://nexus.intranda.com:4443'
      registryCredentialsId 'jenkins-docker'
      args '-v $HOME/.m2:/var/maven/.m2:z -u 1000 -ti -e _JAVA_OPTIONS=-Duser.home=/var/maven -e MAVEN_CONFIG=/var/maven/.m2'
    }
  }

  options {
    buildDiscarder logRotator(artifactDaysToKeepStr: '', artifactNumToKeepStr: '15', daysToKeepStr: '90', numToKeepStr: '')
  }

  
  
  stages {
    stage('prepare') {
      steps {
        sh 'git clean -fdx'
      }
    }

    stage('build') {
      steps {
        sh 'mvn -f goobi-plugin-step-createfullpdf/pom.xml install'
        recordIssues enabledForFailure: true, aggregatingResults: true, tools: [java(), javaDoc()]
      }
    }
  }
  
  post {
    success {
      archiveArtifacts artifacts: '**/target/*.jar, */plugin_*.xml, plugin_*.xml', fingerprint: true, onlyIfSuccessful: true
    }
    changed {
      emailext(
        subject: '${DEFAULT_SUBJECT}',
        body: '${DEFAULT_CONTENT}',
        recipientProviders: [requestor(),culprits()],
        attachLog: true
      )
    }
  }
}
