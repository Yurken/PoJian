#!/usr/bin/env bash
# Install the project's HTTPS route without changing other site routes.
set -euo pipefail

if [[ "$#" -ne 3 ]]; then
    echo "Usage: $0 SSH_HOST DOMAIN SITE_FILE" >&2
    exit 2
fi
ssh_host="$1"
domain="$2"
site_file="$3"
if [[ ! "$ssh_host" =~ ^[a-zA-Z0-9][a-zA-Z0-9_.@-]*$ ||
      ! "$domain" =~ ^[a-zA-Z0-9][a-zA-Z0-9.-]*[a-zA-Z0-9]$ ||
      "$domain" == *..* ||
      ! "$site_file" =~ ^/[a-zA-Z0-9/._-]+$ ||
      "$site_file" == */../* || "$site_file" == */.. ]]; then
    echo 'Invalid SSH host, domain, or absolute site file path.' >&2
    exit 2
fi
project_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
remote_tmp=""
cleanup() {
    if [[ "$remote_tmp" == /tmp/pojian-nginx.* && "$remote_tmp" != *[!a-zA-Z0-9/._-]* ]]; then
        ssh -o BatchMode=yes "$ssh_host" "rm -rf -- '$remote_tmp'" || true
    fi
}
trap cleanup EXIT
ssh -o BatchMode=yes "$ssh_host" 'sudo -n true'
remote_tmp="$(ssh -o BatchMode=yes "$ssh_host" 'mktemp -d /tmp/pojian-nginx.XXXXXXXX')"
if [[ "$remote_tmp" != /tmp/pojian-nginx.* || "$remote_tmp" == *[!a-zA-Z0-9/._-]* ]]; then
    echo 'Invalid remote temporary directory.' >&2
    exit 1
fi
scp -o BatchMode=yes "$project_dir/deploy/linux/nginx-location.conf" "$ssh_host:$remote_tmp/route.conf"
ssh -o BatchMode=yes "$ssh_host" "sudo -n bash -s -- '$remote_tmp' '$domain' '$site_file'" <<'REMOTE'
set -euo pipefail
bundle="$1"
domain="$2"
site="$3"
snippet=/etc/nginx/snippets/pojian-location.conf
legacy_snippet=/etc/nginx/snippets/zhiheng-location.conf
backup_root=/var/backups/pojian-nginx
site_tmp=""
snippet_tmp=""
mutated=false
had_snippet=false
backup_dir=""

exec 9>/run/lock/pojian-nginx-config.lock
flock -n 9 || { echo 'Another HTTPS route update is running.' >&2; exit 1; }
for command in python3 nginx curl systemctl install; do
    command -v "$command" >/dev/null || { echo "Required command is missing: $command" >&2; exit 1; }
done
if [[ "$EUID" -ne 0 || ! -f "$site" || -L "$site" || -L "$snippet" ]]; then
    echo 'Expected root access and regular site/snippet files.' >&2
    exit 1
fi

finish() {
    status="$?"
    trap - EXIT
    set +e
    if [[ "$status" -ne 0 ]] && "$mutated"; then
        echo 'HTTPS route update failed; restoring the saved configuration.' >&2
        cp -p "$backup_dir/site.conf" "$site.restore-$$"
        mv -f "$site.restore-$$" "$site"
        if "$had_snippet"; then
            cp -p "$backup_dir/snippet.conf" "$snippet.restore-$$"
            mv -f "$snippet.restore-$$" "$snippet"
        else
            rm -f "$snippet"
        fi
        if nginx -t; then
            systemctl reload nginx || echo 'Restored files, but nginx reload failed; inspect service status.' >&2
        else
            echo 'Restored files, but nginx validation failed; inspect configuration.' >&2
        fi
    fi
    [[ -z "$site_tmp" ]] || rm -f "$site_tmp"
    [[ -z "$snippet_tmp" ]] || rm -f "$snippet_tmp"
    exit "$status"
}
trap finish EXIT

install -d -m 0700 "$backup_root"
backup_dir="$(mktemp -d "$backup_root/$(date -u +%Y%m%dT%H%M%SZ)-XXXXXXXX")"
cp -p "$site" "$backup_dir/site.conf"
if [[ -e "$snippet" ]]; then
    [[ -f "$snippet" ]] || { echo 'Existing project snippet is not a regular file.' >&2; exit 1; }
    cp -p "$snippet" "$backup_dir/snippet.conf"
    had_snippet=true
else
    touch "$backup_dir/snippet.absent"
fi
if [[ -f "$legacy_snippet" && ! -L "$legacy_snippet" ]]; then
    cp -p "$legacy_snippet" "$backup_dir/legacy-snippet.conf"
fi
install -d -m 0755 /etc/nginx/snippets
site_tmp="$(mktemp "$site.XXXXXXXX")"
cp -p "$site" "$site_tmp"
python3 - "$site_tmp" "$snippet" "$domain" <<'PY'
from pathlib import Path
import re
import sys

path = Path(sys.argv[1])
snippet = sys.argv[2]
domain = sys.argv[3]
text = path.read_text()

def closing_brace(start):
    depth = 0
    quote = None
    comment = False
    escaped = False
    for index in range(start, len(text)):
        char = text[index]
        if comment:
            if char == '\n':
                comment = False
            continue
        if escaped:
            escaped = False
            continue
        if char == '\\':
            escaped = True
            continue
        if quote:
            if char == quote:
                quote = None
            continue
        if char in '\"\'':
            quote = char
        elif char == '#':
            comment = True
        elif char == '{':
            depth += 1
        elif char == '}':
            depth -= 1
            if depth == 0:
                return index
    raise SystemExit('Could not identify the existing server block boundary.')

matches = []
include_pattern = re.compile(
    r'(?m)^[ \t]*include[ \t]+(?P<quote>[\"\']?)'
    r'(?P<path>/etc/nginx/snippets/(?:zhiheng|pojian)-location\.conf)'
    r'(?P=quote)[ \t]*;[ \t]*(?:#[^\n]*)?(?:\n|$)'
)
for match in re.finditer(r'(?m)^[ \t]*server[ \t]*\{', text):
    opening = text.index('{', match.start(), match.end())
    closing = closing_brace(opening)
    body = text[opening + 1:closing]
    listens = re.findall(r'(?m)^\s*listen\s+([^;]+);', body)
    names = re.findall(r'(?m)^\s*server_name\s+([^;]+);', body)
    if any(re.search(r'(?:^|:)443(?:\s|$)', value) and 'ssl' in value.split() for value in listens) and any(domain in value.split() for value in names):
        matches.append((opening, closing, body))
if len(matches) != 1:
    raise SystemExit('Expected exactly one HTTPS server block for the specified domain.')
opening, closing, body = matches[0]
existing = list(include_pattern.finditer(text))
legacy = [match for match in existing if match.group('path') != snippet]
current = [match for match in existing if match.group('path') == snippet]
if existing:
    if any(not opening < match.start() < closing for match in existing):
        raise SystemExit('The project route include occurs outside the expected HTTPS block.')
    if len(legacy) > 1 or len(current) > 1:
        raise SystemExit('Duplicate project route includes require manual review.')
    if legacy:
        old = legacy[0]
        if current:
            text = text[:old.start()] + text[old.end():]
        else:
            text = text[:old.start('path')] + snippet + text[old.end('path'):]
        path.write_text(text)
else:
    if re.search(r'(?m)^\s*location\s+(?:=\s+|\^~\s+)?/(?:zhiheng|pojian)(?:/|\s|\{)', body):
        raise SystemExit('An existing project route requires manual review.')
    first_location = re.search(r'(?m)^[ \t]*location\b', body)
    position = opening + 1 + first_location.start() if first_location else closing
    text = text[:position] + f'    include {snippet};\n\n' + text[position:]
    path.write_text(text)
print('Located the specified HTTPS block; project include is ready.')
PY
snippet_tmp="$(mktemp "$snippet.XXXXXXXX")"
install -m 0644 -o root -g root "$bundle/route.conf" "$snippet_tmp"
mutated=true
mv -f "$snippet_tmp" "$snippet"
snippet_tmp=""
mv -f "$site_tmp" "$site"
site_tmp=""
nginx -t
systemctl reload nginx

health_ok=false
for attempt in {1..20}; do
    if code="$(curl --silent --show-error --connect-timeout 3 --max-time 5 \
        --resolve "$domain:443:127.0.0.1" \
        --output "$bundle/backend-health.json" --write-out '%{http_code}' \
        "https://$domain/pojian/health")" && [[ "$code" == 200 ]] && \
        python3 - "$bundle/backend-health.json" <<'PY'
import json
import sys
from pathlib import Path
try:
    body = json.loads(Path(sys.argv[1]).read_text())
    assert body.get('status') == 'ok' and body.get('version') == 1
except (ValueError, OSError, AssertionError, AttributeError):
    raise SystemExit(1)
PY
    then
        health_ok=true
        break
    fi
    sleep 2
done
"$health_ok" || { echo 'Backend HTTPS health check failed.' >&2; exit 1; }
printf 'HTTPS backend health: HTTP 200\n'
cat "$bundle/backend-health.json"
printf '\nConfiguration backup: %s\n' "$backup_dir"
REMOTE
