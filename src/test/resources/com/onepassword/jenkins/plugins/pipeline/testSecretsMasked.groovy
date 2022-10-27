def config = [
    connectHost: "OP_HOST",
    connectCredentialId: "connect-credential-id"
]

def secrets = [
    [envVar: 'DOCKER_USERNAME', secretRef: 'op://acceptance-tests/test-secret/password']
]

node {
    sh 'curl -sSfLo op.zip OP_CLI_URL && unzip -o op.zip && rm op.zip'
    withSecrets(config: config, secrets: secrets) {
        sh '''
            echo $DOCKER_USERNAME
            echo "not sensitive data"
        '''
    }
}
