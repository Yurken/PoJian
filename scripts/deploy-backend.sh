#!/usr/bin/env bash
# Deploy the current server/ working tree to an SSH host. Credentials stay there.
set -euo pipefail

if [[ "$#" -ne 1 ]]; then
    echo "Usage: $0 SSH_HOST (an SSH alias, hostname, or user@hostname)" >&2
    exit 2
fi
ssh_host="$1"
if [[ ! "$ssh_host" =~ ^([a-zA-Z0-9_][a-zA-Z0-9_.-]*@)?[a-zA-Z0-9][a-zA-Z0-9_.-]*$ ]]; then
    echo 'Invalid SSH_HOST; use an SSH alias, hostname, or user@hostname.' >&2
    exit 2
fi

project_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
local_tmp="$(mktemp -d "${TMPDIR:-/tmp}/pojian-deploy.XXXXXXXX")"
remote_tmp=""

cleanup() {
    rm -rf "$local_tmp"
    if [[ "$remote_tmp" == /tmp/pojian-deploy.* && "$remote_tmp" != *[!a-zA-Z0-9/._-]* ]]; then
        ssh -o BatchMode=yes "$ssh_host" "rm -rf -- '$remote_tmp'" || true
    fi
}
trap cleanup EXIT

revision="$(git -C "$project_dir" rev-parse --short=12 HEAD)"
release_id="$(date -u +%Y%m%dT%H%M%SZ)-${revision}-${RANDOM}"

# Package only backend files. Never ship runtime databases or credential files.
COPYFILE_DISABLE=1 tar --exclude='*/__pycache__' --exclude='*.py[cod]' --exclude='._*' \
    --exclude='*/.runtime' --exclude='*/.venv' --exclude='*/.git' --exclude='*/.env*' \
    --exclude='*.sqlite*' --exclude='*.db' --exclude='*.pem' \
    --exclude='*.key' --exclude='*.p12' --exclude='*.pfx' \
    --exclude='*[Cc]redential*' --exclude='*[Ss]ecret*' \
    --exclude='backend.env' \
    -czf "$local_tmp/server.tar.gz" -C "$project_dir" server

remote_uid="$(ssh -o BatchMode=yes "$ssh_host" 'id -u')"
if [[ "$remote_uid" == 0 ]]; then
    remote_runner=bash
elif [[ "$remote_uid" == *[!0-9]* || -z "$remote_uid" ]]; then
    echo 'The remote user ID was invalid.' >&2
    exit 1
else
    ssh -o BatchMode=yes "$ssh_host" 'sudo -n true'
    remote_runner='sudo -n bash'
fi
remote_tmp="$(ssh -o BatchMode=yes "$ssh_host" 'mktemp -d /tmp/pojian-deploy.XXXXXXXX')"
if [[ "$remote_tmp" != /tmp/pojian-deploy.* || "$remote_tmp" == *[!a-zA-Z0-9/._-]* ]]; then
    echo 'The remote temporary path was invalid.' >&2
    exit 1
fi

scp -o BatchMode=yes \
    "$local_tmp/server.tar.gz" \
    "$project_dir/deploy/linux/install-release.sh" \
    "$project_dir/deploy/linux/pojian-backend.service" \
    "$ssh_host:$remote_tmp/"
ssh -o BatchMode=yes "$ssh_host" \
    "$remote_runner '$remote_tmp/install-release.sh' '$remote_tmp' '$release_id'"
echo "Deployment completed on $ssh_host."
