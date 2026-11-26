pipeline {
    agent { label 'build-server' }

    parameters {
        choice(
            name: 'ENVIRONMENT',
            choices: ['dev', 'prod'],
            description: 'Target environment (dev = t3.micro, prod = t3.small)'
        )
        choice(
            name: 'ACTION',
            choices: ['CREATE', 'DESTROY'],
            description: 'CREATE launches the EC2 instance; DESTROY terminates it'
        )
        string(
            name: 'AWS_REGION',
            defaultValue: 'ap-south-1',
            description: 'AWS Region to deploy into'
        )
        string(
            name: 'KEY_PAIR_NAME',
            defaultValue: 'my-aws-key',
            description: 'Existing AWS EC2 Key Pair name in that region'
        )
        booleanParam(
            name: 'DRY_RUN',
            defaultValue: false,
            description: 'Run Ansible in --check mode without making real AWS changes'
        )
    }

    environment {
        TARGET_STATE = "${params.ACTION == 'CREATE' ? 'present' : 'absent'}"
        CHECK_FLAG   = "${params.DRY_RUN ? '--check' : ''}"
    }

    stages {
        stage('Checkout Infra Code') {
            steps {
                checkout scm
            }
        }

        stage('Install & Verify Dependencies') {
            steps {
                echo "Bootstrapping Ansible & AWS dependencies on ${env.NODE_NAME}..."
                sh '''
                    set -e
                    # Install Ansible, boto3, and git if not already present
                    if ! command -v ansible-playbook &> /dev/null; then
                        sudo dnf update -y
                        sudo dnf install -y ansible-core python3-boto3 python3-botocore git
                    fi

                    # Install the amazon.aws collection if missing
                    if ! ansible-galaxy collection list amazon.aws 2>/dev/null | grep -q amazon.aws; then
                        ansible-galaxy collection install amazon.aws
                    fi

                    ansible --version | head -n 1
                '''
            }
        }

        stage('Execute Ansible Cloud Playbook') {
            steps {
                sh """
                    ansible-playbook ec2_provision.yml \
                    ${env.CHECK_FLAG} \
                    -e "env=${params.ENVIRONMENT}" \
                    -e "state=${env.TARGET_STATE}" \
                    -e "region=${params.AWS_REGION}" \
                    -e "key_pair_name=${params.KEY_PAIR_NAME}"
                """
            }
        }
    }

    post {
        success {
            echo "Cloud action '${params.ACTION}' completed on ${env.NODE_NAME}!"
        }
        failure {
            echo "Build failed on ${env.NODE_NAME}. Check console logs above."
        }
    }
}