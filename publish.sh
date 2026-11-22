#!/usr/bin/env bash
# Publishes the library to Maven Central, through the Central Portal's bundle
# upload. There is no Gradle or Maven here (see build.sh), and the portal does
# not need one: it accepts a zip laid out as a Maven repository, with signatures
# and checksums beside the artifacts.
#
#   bash publish.sh                     # build, sign, checksum, zip; print how to upload
#   bash publish.sh --upload            # ... and upload it
#   bash publish.sh --status <id>       # how did deployment <id> go?
#   bash publish.sh --publish <id>      # release a validated, USER_MANAGED deployment
#   VERSION=0.2.0 PGP_KEY=0xABCD bash publish.sh
#
# Environment:
#   PGP_KEY            key to sign with (default: gpg's default secret key)
#   PGP_PASSPHRASE     the key's passphrase, for a key that has one; without it
#                      gpg tries to ask, which no piped shell can answer
#   CENTRAL_USERNAME   portal user token username   \  only for --upload and
#   CENTRAL_PASSWORD   portal user token password   /  --status; if unset, they
#                      are read from ~/.m2/settings.xml, server id "central"
#   SETTINGS           a different settings.xml to read that server from
#
# What Central checks, and where it comes from:
#   .aar, .pom, -sources.jar, -javadoc.jar   build.sh
#   .asc for every file above                gpg; the public key must be on a
#                                            keyserver Central can reach
#   .md5 .sha1 .sha256 .sha512 for each      sha*sum, over the file and its .asc
#   a verified namespace                     one-time, in the portal, for
#                                            io.github.mbanucu
#
# A version number is permanent on Central: once published, 0.1.0 belongs to
# this content forever. Publishing a fix means a new version.
set -euo pipefail

here=$(cd "$(dirname "$0")" && pwd)
version=${VERSION:-0.1.0}
group=io.github.mbanucu
artifact_id=screenstream
portal=${PORTAL:-https://central.sonatype.com}
out=$here/out
bundle=$out/$artifact_id-$version-bundle.zip
tree=$out/bundle
action=${1:-bundle}
settings=${SETTINGS:-$HOME/.m2/settings.xml}

# The token is a secret, and a secret on a command line is a secret in the shell
# history: read it from Maven's settings.xml, which is where a machine that runs
# `mvn deploy` keeps the same credential anyway.
read_credentials() {
    if [ -n "${CENTRAL_USERNAME:-}" ] && [ -n "${CENTRAL_PASSWORD:-}" ]; then
        echo "token: from the environment" >&2
        return 0
    fi
    [ -f "$settings" ] || return 1
    local pair
    pair=$(python3 - "$settings" <<'EOF'
import sys, xml.etree.ElementTree as ET
for server in ET.parse(sys.argv[1]).getroot().iter():
    if server.tag.split('}')[-1] != 'server':
        continue
    fields = {c.tag.split('}')[-1]: (c.text or '').strip() for c in server}
    if fields.get('id') == 'central' and fields.get('username') and fields.get('password'):
        print(fields['username'])
        print(fields['password'])
        break
EOF
)
    [ -n "$pair" ] || return 1
    CENTRAL_USERNAME=$(printf '%s\n' "$pair" | sed -n 1p)
    CENTRAL_PASSWORD=$(printf '%s\n' "$pair" | sed -n 2p)
    echo "token: $settings, server 'central'" >&2
}

if [ "$action" = --status ] || [ "$action" = --publish ]; then
    id=${2:?deployment id}
    read_credentials || {
        echo "no token: set CENTRAL_USERNAME/CENTRAL_PASSWORD, or add a server" >&2
        echo "named 'central' to $settings" >&2
        exit 1
    }
    auth=$(printf '%s:%s' "$CENTRAL_USERNAME" "$CENTRAL_PASSWORD" | base64 | tr -d '\n')
    if [ "$action" = --publish ]; then
        # A USER_MANAGED deployment that reached VALIDATED waits for exactly this:
        # a POST to the deployment itself. 204 means it is now PUBLISHING.
        curl --fail --silent --show-error -X POST -H "Authorization: Bearer $auth" \
            "$portal/api/v1/publisher/deployment/$id"
        echo "deployment $id is publishing"
        exit 0
    fi
    # /publisher/status answers 500 for deployments it has, so read the list:
    # the deployment's own GET is for the portal's UI, not for this question.
    listed=$(curl --fail --silent --show-error -H "Authorization: Bearer $auth" \
        "$portal/api/v1/publisher/deployments")
    # The program comes from -c, not stdin: stdin is the JSON being parsed.
    printf '%s' "$listed" | python3 -c "
import json, sys
want = sys.argv[1]
data = json.load(sys.stdin)
items = data if isinstance(data, list) else data.get('deployments') or []
for item in items:
    if item.get('deploymentId') == want:
        print(json.dumps({k: item.get(k) for k in
                          ('deploymentName', 'deploymentState', 'createTimestamp')}))
        break
else:
    sys.exit('no deployment ' + want)
" "$id"
    exit 0
fi

for tool in gpg md5sum sha1sum sha256sum sha512sum python3; do
    command -v "$tool" >/dev/null || { echo "missing $tool" >&2; exit 1; }
done

# --- the artifacts -------------------------------------------------------------
bash "$here/build.sh"

# --- sign, checksum, lay out as a repository -----------------------------------
# Central's validation reads this tree; the zip's first entry is io/, not a
# wrapper directory.
rm -rf "$tree"
dir=$tree/${group//.//}/$artifact_id/$version
mkdir -p "$dir"

# A passphrase in the environment means signing can be non-interactive. Without
# one, gpg must be left free to ask pinentry, so --batch is not passed: in a
# terminal that works, in a pipe it fails loudly rather than signing with nothing.
sign=(--yes)
[ -n "${PGP_KEY:-}" ] && sign+=(-u "$PGP_KEY")
if [ -n "${PGP_PASSPHRASE:-}" ]; then
    sign+=(--batch --pinentry-mode loopback --passphrase-fd 3)
fi
created=0
for name in "$artifact_id-$version.pom" "$artifact_id-$version.aar" \
        "$artifact_id-$version-sources.jar" "$artifact_id-$version-javadoc.jar"; do
    [ -f "$out/$name" ] || { echo "build.sh did not write $out/$name" >&2; exit 1; }
    cp "$out/$name" "$dir/$name"
    created=$((created + 1))
done

echo "== $artifact_id $version: signing $created files =="
for file in "$dir"/*; do
    gpg --armor --detach-sign "${sign[@]}" "$file" 3<<<"${PGP_PASSPHRASE:-}"
    # A signature nobody can verify is worse than none: check it here, while
    # the key is still in this machine's keyring.
    gpg --batch --verify "$file.asc" "$file" >/dev/null 2>&1 || {
        echo "the signature on $(basename "$file") does not verify" >&2
        exit 1
    }
done

echo "== $artifact_id $version: checksums =="
for file in "$dir"/*; do
    for algorithm in md5 sha1 sha256 sha512; do
        "${algorithm}sum" "$file" | cut -d' ' -f1 > "$file.$algorithm"
    done
done

python3 - "$tree" "$bundle" <<'EOF'
import os, sys, zipfile
root, bundle = sys.argv[1], sys.argv[2]
with zipfile.ZipFile(bundle, 'w', zipfile.ZIP_DEFLATED) as z:
    for base, _, files in os.walk(root):
        for name in sorted(files):
            path = os.path.join(base, name)
            z.write(path, os.path.relpath(path, root))
EOF

count=$(ls -1 "$dir" | wc -l)
echo "built $bundle ($count files, $(wc -c < "$bundle") bytes)"

if [ "$action" != --upload ]; then
    cat <<EOF

To publish this:
  1. Namespace: io.github.mbanucu verified at $portal (once, in the browser;
     signing in with GitHub usually verifies it automatically).
  2. Signing key: the key's public half on a keyserver, or Central rejects the
     signatures:
         gpg --keyserver keyserver.ubuntu.com --send-keys \$(gpg --list-secret-keys --with-colons | awk -F: '/^sec/ {print \$5; exit}')
  3. Upload, with the user token from $portal → Account:
         CENTRAL_USERNAME=... CENTRAL_PASSWORD=... bash $0 --upload
     Or upload $bundle by hand on the portal's Publish page.
     To keep the token off the command line, put it in $settings:
         <server><id>central</id><username>…</username><password>…</password></server>
  Publishing is manual by default (\`publishingType\` USER_MANAGED): the upload
  validates, then waits for you to press Publish.
EOF
    exit 0
fi

read_credentials || {
    echo "no token: set CENTRAL_USERNAME/CENTRAL_PASSWORD, or add a server" >&2
    echo "named 'central' to $settings; see the steps above" >&2
    exit 1
}
auth=$(printf '%s:%s' "$CENTRAL_USERNAME" "$CENTRAL_PASSWORD" | base64 | tr -d '\n')
echo "== $artifact_id $version: uploading =="
response=$(curl --fail --silent --show-error \
    -H "Authorization: Bearer $auth" \
    -F "bundle=@$bundle;filename=$artifact_id-$version-bundle.zip" \
    "$portal/api/v1/publisher/upload?name=$artifact_id-$version&publishingType=USER_MANAGED")
echo "deployment: $response"
echo
echo "validation takes a few minutes; check it with:"
echo "  CENTRAL_USERNAME=... CENTRAL_PASSWORD=... bash $0 --status $response"
echo "then press Publish on $portal, or say --publishingType AUTOMATIC next time"
echo "to have a passing deployment publish itself."
