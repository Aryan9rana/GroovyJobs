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
            defaultValue: '',
            description: 'Optional: Existing AWS EC2 Key Pair name (leave blank to launch without SSH key)'
        )
        booleanParam(
            name: 'DRY_RUN',
            defaultValue: false,
            description: 'Run Ansible in --check mode without making real AWS changes'
        )
    }

    environment {
        TARGET_STATE    = "${params.ACTION == 'DESTROY' ? 'absent' : 'present'}"
        CHECK_FLAG      = "${params.DRY_RUN ? '--check' : ''}"
        TARGET_ENV      = "${params.ENVIRONMENT ?: 'dev'}"
        TARGET_REGION   = "${params.AWS_REGION ?: 'ap-south-1'}"
        TARGET_KEY_PAIR = "${params.KEY_PAIR_NAME ?: ''}"
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
                    # Install missing OS packages (Ansible, boto3, git)
                    MISSING_PKGS=""
                    command -v ansible-playbook >/dev/null 2>&1 || MISSING_PKGS="$MISSING_PKGS ansible-core"
                    command -v git >/dev/null 2>&1 || MISSING_PKGS="$MISSING_PKGS git"
                    python3 -c "import boto3, botocore" >/dev/null 2>&1 || MISSING_PKGS="$MISSING_PKGS python3-boto3 python3-botocore"

                    if [ -n "$MISSING_PKGS" ]; then
                        sudo dnf update -y
                        sudo dnf install -y $MISSING_PKGS
                    fi

                    # Install the amazon.aws collection if missing
                    if ! ansible-galaxy collection list 2>/dev/null | grep -qE '^amazon\.aws[[:space:]]'; then
                        ansible-galaxy collection install amazon.aws
                    fi

                    ansible --version | head -n 1
                '''
            }
        }

        stage('Execute Ansible Cloud Playbook') {
            steps {
                sh '''
                    set -e
                    set -- \
                        -e "env=$TARGET_ENV" \
                        -e "state=$TARGET_STATE" \
                        -e "region=$TARGET_REGION" \
                        -e "key_pair_name=$TARGET_KEY_PAIR"
                    if [ -n "$CHECK_FLAG" ]; then
                        set -- "$CHECK_FLAG" "$@"
                    fi
                    ansible-playbook ec2_provision.yml "$@"
                '''
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