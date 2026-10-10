#!/usr/bin/env bash
# Run as root on the target Linux host, with the bundle directory and release ID.
set -euo pipefail

if [[ "$EUID" -ne 0 || "$#" -ne 2 ]]; then
    echo 'Usage: sudo install-release.sh BUNDLE_DIRECTORY RELEASE_ID' >&2
    exit 2
fi
bundle="$1"
release_id="$2"
if [[ "$bundle" != /tmp/pojian-deploy.* || "$bundle" == *[!a-zA-Z0-9/._-]* ||
      "$release_id" == *[!a-zA-Z0-9_-]* || -z "$release_id" ]]; then
    echo 'Invalid deployment arguments.' >&2
    exit 2
fi

base=/opt/pojian-backend
env_file=/etc/pojian/backend.env
state_dir=/var/lib/pojian-backend
unit=pojian-backend.service
unit_file="/etc/systemd/system/$unit"
stage="$base/releases/.stage-$release_id"
release="$base/releases/$release_id"
recovery="$base/.deploy-$release_id"
previous_release=""
previous_active=false
previous_enabled=false
previous_unit=false
mutated=false
created_stage=false
created_release=false
created_recovery=false

exec 9>/run/lock/pojian-backend-deploy.lock
flock -n 9 || { echo 'Another backend deployment is running.' >&2; exit 1; }

rollback() {
    echo 'Deployment failed; restoring the previous release and service unit.' >&2
    if [[ -n "$previous_release" ]]; then
        ln -s "$previous_release" "$base/.rollback-$release_id" || return 1
        mv -Tf "$base/.rollback-$release_id" "$base/current" || return 1
    else
        rm -f "$base/current" || return 1
    fi
    if "$previous_unit"; then
        install -m 0644 "$recovery/unit.previous" "$unit_file" || return 1
    else
        systemctl disable --now "$unit" >/dev/null 2>&1
        rm -f "$unit_file" || return 1
    fi
    systemctl daemon-reload || return 1
    if "$previous_unit"; then
        if "$previous_enabled"; then
            systemctl enable "$unit" >/dev/null 2>&1 || return 1
        else
            systemctl disable "$unit" >/dev/null 2>&1 || return 1
        fi
        if "$previous_active"; then
            systemctl restart "$unit" || return 1
            systemctl is-active --quiet "$unit" || return 1
        else
            systemctl stop "$unit" || return 1
        fi
    fi
}

finish() {
    status="$?"
    trap - EXIT
    set +e
    if [[ "$status" -ne 0 ]] && "$mutated"; then
        if ! rollback; then
            echo "Automatic rollback failed; service backup remains at $recovery. Check journald." >&2
            created_recovery=false
        fi
    fi
    if "$created_stage"; then rm -rf "$stage"; fi
    if "$created_recovery"; then rm -rf "$recovery"; fi
    rm -f "$base/.current-$release_id" "$base/.previous-$release_id"
    if [[ "$status" -ne 0 ]] && "$created_release" && [[ -d "$release" ]]; then
        if [[ "$(readlink -f "$base/current" 2>/dev/null)" != "$release" ]]; then
            rm -rf "$release"
        fi
    fi
    exit "$status"
}
trap finish EXIT

# Refuse missing credentials; never create placeholder provider keys.
if [[ ! -f "$env_file" || -L "$env_file" ]]; then
    echo 'Provision /etc/pojian/backend.env with real provider settings before deploying.' >&2
    exit 1
fi
chown root:root "$env_file"
chmod 0600 "$env_file"

for command in python3 systemctl systemd-run systemd-analyze runuser useradd; do
    command -v "$command" >/dev/null || { echo "Required command is missing: $command" >&2; exit 1; }
done
/usr/bin/python3 - <<'PY'
import sys
if sys.version_info < (3, 10):
    raise SystemExit('Backend deployment requires /usr/bin/python3 version 3.10 or newer.')
PY
if ! id pojian >/dev/null 2>&1; then
    useradd --system --user-group --no-create-home --home-dir "$state_dir" --shell /usr/sbin/nologin pojian
fi
install -d -m 0755 "$base" "$base/releases"
install -d -m 0700 -o pojian -g pojian "$state_dir"
mkdir -m 0700 "$recovery"
created_recovery=true
if [[ -e "$release" || -e "$stage" ]]; then
    echo 'This release ID already exists.' >&2
    exit 1
fi
mkdir -m 0755 "$stage"
created_stage=true

# Reject traversal, links and credential files even if an archive was modified.
/usr/bin/python3 - "$bundle/server.tar.gz" "$stage" <<'PY'
import fnmatch
from pathlib import Path, PurePosixPath
import shutil
import sys
import tarfile

excluded = ('.env*', 'backend.env', '._*', '*.py[cod]', '*.sqlite*', '*.db', '*.pem',
            '*.key', '*.p12', '*.pfx', '*credential*', '*secret*')
root = Path(sys.argv[2])
with tarfile.open(sys.argv[1], 'r:gz') as archive:
    members = archive.getmembers()
    seen = set()
    for member in members:
        path = PurePosixPath(member.name)
        if (path.is_absolute() or '..' in path.parts or not path.parts or path.parts[0] != 'server'
                or not (member.isfile() or member.isdir())
                or any(part in {'.runtime', '__pycache__', '.git'} for part in path.parts)
                or any(fnmatch.fnmatch(part.lower(), pattern) for part in path.parts for pattern in excluded)
                or path in seen):
            raise SystemExit('The backend archive contains a forbidden entry.')
        seen.add(path)
    # The destination is a fresh root-owned directory. Never let tarfile create
    # links, restore archive ownership/modes, or overwrite an earlier member.
    for member in members:
        destination = root.joinpath(*PurePosixPath(member.name).parts)
        if member.isdir():
            destination.mkdir(parents=True, exist_ok=True)
        else:
            destination.parent.mkdir(parents=True, exist_ok=True)
            with archive.extractfile(member) as source, destination.open('xb') as target:
                shutil.copyfileobj(source, target)
PY
chown -R root:root "$stage"
find "$stage" -type d -exec chmod 0755 {} +
find "$stage" -type f -exec chmod 0644 {} +

cd "$stage"
runuser -u pojian -- /usr/bin/python3 -B - <<'PY'
import sys
import unittest

suite = unittest.defaultTestLoader.discover('server/tests')
count = suite.countTestCases()
if count < 77:
    raise SystemExit(f'Expected at least 77 backend tests; discovered {count}.')
result = unittest.TextTestRunner(verbosity=2).run(suite)
raise SystemExit(0 if result.wasSuccessful() else 1)
PY

# Let systemd load EnvironmentFile exactly as the final service does. The file is
# never sourced, read by the unprivileged user, or printed in command output.
validation_code="$(cat <<'PY'
import os
import sys
try:
    from emerge_server import Config
    from monitor_server import Options, runtime_file_has_text_key
    config = Config.from_env(require_upstream=os.getenv('EMERGE_ALLOW_UNCONFIGURED') != '1')
    Options.from_env(config)
    if (os.environ.get('EMERGE_HOST') != '127.0.0.1'
            or os.environ.get('EMERGE_PORT') != '28763'
            or os.environ.get('EMERGE_BUDGET_DB') != '/var/lib/pojian-backend/monitor-budget.sqlite3'):
        raise ValueError()
except Exception:
    raise SystemExit('Invalid backend configuration; check backend.env without exposing its values.') from None
print('Backend environment configuration is valid.')
PY
)"
systemd-run --quiet --wait --pipe --collect \
    --unit="pojian-validate-$release_id" \
    --property=User=pojian --property=Group=pojian \
    --property="WorkingDirectory=$stage/server" \
    --property="EnvironmentFile=$env_file" \
    --setenv=PYTHONDONTWRITEBYTECODE=1 \
    --setenv=EMERGE_HOST=127.0.0.1 --setenv=EMERGE_PORT=28763 \
    --setenv=EMERGE_BUDGET_DB=/var/lib/pojian-backend/monitor-budget.sqlite3 \
    /usr/bin/python3 -B -c "$validation_code"
systemd-analyze verify "$bundle/$unit"

if [[ -L "$base/current" ]]; then
    previous_release="$(readlink -f "$base/current")"
    if [[ "$previous_release" != "$base/releases/"* || ! -d "$previous_release/server" ]]; then
        echo 'The current release link does not point to a managed backend release.' >&2
        exit 1
    fi
elif [[ -e "$base/current" ]]; then
    echo 'The current release must be a symbolic link.' >&2
    exit 1
fi
if [[ -f "$unit_file" ]]; then
    cp -p "$unit_file" "$recovery/unit.previous"
    previous_unit=true
fi
if systemctl is-active --quiet "$unit"; then previous_active=true; fi
if systemctl is-enabled --quiet "$unit"; then previous_enabled=true; fi
if ! "$previous_active"; then
    /usr/bin/python3 - <<'PY'
import socket
with socket.socket() as listener:
    try:
        listener.bind(('127.0.0.1', 28763))
    except OSError:
        raise SystemExit('Backend port 28763 is occupied; deployment was not switched.') from None
PY
fi

cd "$base"
mv "$stage" "$release"
created_release=true
mutated=true
install -m 0644 "$bundle/$unit" "$unit_file"
ln -s "$release" "$base/.current-$release_id"
mv -Tf "$base/.current-$release_id" "$base/current"
systemctl daemon-reload
systemctl enable "$unit" >/dev/null
systemctl restart "$unit"

# Load the token through systemd for readiness; credentials never enter argv or
# output. Only unconfigured mode sends authenticated requests, returning 503
# before parsing the body, reserving budget or calling a model provider.
readiness_code="$(cat <<'PY'
import json
import os
import time
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen

try:
    from emerge_server import Config
    from monitor_server import runtime_file_has_text_key
    config = Config.from_env(require_upstream=os.getenv('EMERGE_ALLOW_UNCONFIGURED') != '1')
    state_dir = os.path.dirname(os.environ['EMERGE_BUDGET_DB'])
    effective_configured = config.model_configured or runtime_file_has_text_key(
        os.path.join(state_dir, 'runtime-config.json'))
except Exception:
    raise SystemExit('Backend configuration could not be loaded for readiness checks.') from None

base = 'http://127.0.0.1:28763'
for attempt in range(30):
    try:
        with urlopen(base + '/health', timeout=2) as response:
            health = json.load(response)
            if (response.status != 200 or not isinstance(health, dict)
                    or health.get('status') != 'ok' or health.get('version') != 1
                    or type(health.get('model_configured')) is not bool):
                raise ValueError('Invalid health response')
        with urlopen(base + '/capabilities', timeout=2) as response:
            capabilities = json.load(response)
            if (response.status != 200 or not isinstance(capabilities, dict)
                    or capabilities.get('protocol') != 1 or capabilities.get('monitor') is not True
                    or type(capabilities.get('model_configured')) is not bool
                    or capabilities['model_configured'] != health['model_configured']
                    or health['model_configured'] != effective_configured):
                raise ValueError('Invalid capabilities response')
        for endpoint in ('/monitor', '/analyze'):
            try:
                with urlopen(Request(base + endpoint, data=b'{}', headers={'Content-Type': 'application/json'}), timeout=2):
                    raise ValueError('Unauthenticated request was accepted')
            except HTTPError as error:
                status = error.code
                error.close()
                if status != 401:
                    raise ValueError('Unauthenticated request did not return 401')
        if not effective_configured:
            for endpoint in ('/monitor', '/analyze'):
                headers = {'Content-Type': 'application/json',
                           'Authorization': 'Bearer ' + config.service_token}
                try:
                    with urlopen(Request(base + endpoint, data=b'{}', headers=headers), timeout=2):
                        raise ValueError('Unconfigured model request was accepted')
                except HTTPError as error:
                    status = error.code
                    error.close()
                    if status != 503:
                        raise ValueError('Unconfigured model request did not return 503')
        else:
            # A configured service must pass the configuration gate: an authenticated
            # request with an empty body fails validation, never with 503.
            headers = {'Content-Type': 'application/json',
                       'Authorization': 'Bearer ' + config.service_token}
            try:
                with urlopen(Request(base + '/monitor', data=b'{}', headers=headers), timeout=2):
                    raise ValueError('Empty monitor body was accepted')
            except HTTPError as error:
                status = error.code
                error.close()
                if status in (401, 503):
                    raise ValueError('Configured model request hit the auth or configuration gate')
        break
    except (URLError, OSError, ValueError):
        if attempt == 29:
            raise SystemExit('Backend health/authentication checks failed.') from None
        time.sleep(1)
print('Health, monitor capabilities and both authentication checks passed.')
if effective_configured:
    print('Backend is ready; model configuration is present (environment or runtime file).')
else:
    print('Backend is ready; model API is not configured. Authenticated analysis returns 503.')
PY
)"
systemd-run --quiet --wait --pipe --collect \
    --unit="pojian-readiness-$release_id" \
    --property=User=pojian --property=Group=pojian \
    --property="WorkingDirectory=$release/server" \
    --property="EnvironmentFile=$env_file" \
    --setenv=PYTHONDONTWRITEBYTECODE=1 \
    --setenv=EMERGE_HOST=127.0.0.1 --setenv=EMERGE_PORT=28763 \
    --setenv=EMERGE_BUDGET_DB=/var/lib/pojian-backend/monitor-budget.sqlite3 \
    /usr/bin/python3 -B -c "$readiness_code"
systemctl is-active --quiet "$unit"
if [[ -n "$previous_release" ]]; then
    ln -s "$previous_release" "$base/.previous-$release_id"
    mv -Tf "$base/.previous-$release_id" "$base/previous"
fi
echo "Deployed $release_id, listening on 127.0.0.1:28763."
echo 'Logs: sudo journalctl -u pojian-backend.service'
