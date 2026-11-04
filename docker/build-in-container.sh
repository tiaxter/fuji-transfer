#!/bin/bash
# Gira DENTRO il container. Il progetto è montato in sola lettura su /src: lo copio in /work così
# non vengono creati file di proprietà di root sul Mac e un eventuale local.properties del Mac non interferisce.
set -euo pipefail

rm -rf /work/* /work/.[!.]* 2>/dev/null || true
cd /src
tar --exclude=./build --exclude=./.gradle --exclude=./app/build --exclude=./core/build \
    --exclude=./dist --exclude=./local.properties --exclude=./apk --exclude=./.git -cf - . | tar -xf - -C /work
cd /work

echo "sdk.dir=${ANDROID_HOME}" > local.properties
bash ./gradlew --no-daemon --console=plain "$@"

mkdir -p /out
if ls app/build/outputs/apk/debug/*.apk > /dev/null 2>&1; then
    cp app/build/outputs/apk/debug/*.apk /out/
    echo "APK copiato in dist/: $(ls /out)"
fi
