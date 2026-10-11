#!/bin/bash
# Publish the whole stack, as a release would (every native target, every JNI library, the Android AAR, openssl's
# release gate), from source checkouts into one plain Maven directory: a release candidate that consumers and the
# libraries' own JVM tests can then resolve instead of Maven Central. Used by .github/workflows/stack-candidate.yml.
# Usage: publish-stack-candidate.sh <dir holding the openssl-kotlin, io, http, websocket, tls and quic checkouts> <output dir>
# Needs ANDROID_NDK_HOME (NDK 29+), MinGW-w64 and the Kotlin/Native Linux sysroots (openssl-kotlin's README).
set -euo pipefail
here=$(cd "$(dirname "$0")/.." && pwd)
deps=$(cd "$1" && pwd)
mkdir -p "$2"
out=$(cd "$2" && pwd)
init=$(mktemp -d)/candidate.init.gradle.kts
printf '%s\n' \
    'allprojects {' \
    '    plugins.withId("maven-publish") {' \
    '        extensions.configure<PublishingExtension> {' \
    "            repositories { maven { name = \"candidate\"; url = uri(\"$out\") } }" \
    '        }' \
    '    }' \
    '}' > "$init"

inc() { for i in "$@"; do printf -- '--include-build %s ' "$deps/$i"; done; }
pub() { # checkout, then Gradle arguments
    local dir=$1; shift
    echo "== $(basename "$dir") $(git -C "$dir" rev-parse --short HEAD)"
    (cd "$dir" && ./gradlew --init-script "$init" -PnativeTargets=all --console=plain "$@")
}
task() { for p in "$@"; do printf '%s:publishAllPublicationsToCandidateRepository ' "$p"; done; }

pub "$deps/openssl-kotlin" -PjniPlatforms=all $(task :openssl)
pub "$deps/io" $(task :io :io-testkit)
pub "$deps/http" $(inc io) $(task :http)
pub "$deps/websocket" $(inc io http) $(task :websocket)
pub "$deps/tls" $(inc openssl-kotlin io http websocket) $(task :tls :tls-http :tls-websocket)
pub "$deps/quic" $(inc openssl-kotlin io) $(task :quic :quic-testkit)
# msgtrans's settings include ../io and ../quic themselves where those checkouts sit next to it.
siblings="openssl-kotlin http websocket tls"
[ -d "$here/../io" ] || siblings="$siblings io"
[ -d "$here/../quic" ] || siblings="$siblings quic"
pub "$here" $(inc $siblings) $(task :msgtrans :msgtrans-quic :msgtrans-websocket)
echo "candidate in $out"
