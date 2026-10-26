#!/usr/bin/env bash
# package.sh - build both apps and the release zip: dist/MonolithicOS-<version>.zip
# (installers, MonolithicOS.apk, MonolithicOS-Remote.apk, MonolithicOS-Safety.apk, START-HERE.txt).
set -euo pipefail
cd "$(dirname "$0")/.."
V="$(cat VERSION)"
(cd remote && ./build.sh)
(cd guard && ./build.sh)
cp remote/build/remote.apk launcher/res/raw/remote_app.apk
cp guard/build/guard.apk launcher/res/raw/guard.apk
(cd launcher && ./build.sh)
cp launcher/build/MonolithicOS.apk installer/files/MonolithicOS.apk
cp remote/build/remote.apk installer/files/MonolithicOS-Remote.apk
cp guard/build/guard.apk installer/files/MonolithicOS-Safety.apk
rm -rf dist/MonolithicOS
mkdir -p dist/MonolithicOS/files
cp installer/*.bat installer/*.command docs/START-HERE.txt dist/MonolithicOS/
cp installer/files/install.sh installer/files/install.ps1 installer/files/*.apk dist/MonolithicOS/files/
chmod +x dist/MonolithicOS/*.command dist/MonolithicOS/files/install.sh
rm -f dist/MonolithicOS.zip
(cd dist && zip -qr MonolithicOS.zip MonolithicOS -x "*.DS_Store")
echo "packed dist/MonolithicOS.zip, v$V ($(du -h dist/MonolithicOS.zip | cut -f1 | tr -d ' '))"
