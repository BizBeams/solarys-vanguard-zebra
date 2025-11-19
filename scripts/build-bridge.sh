#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BRIDGE_DIR="${ROOT_DIR}/rfid-bridge"
DIST_DIR="${ROOT_DIR}/dist"
ARTIFACT="${BRIDGE_DIR}/target/rfid-bridge-1.0.0.jar"
OUTPUT="${DIST_DIR}/rfid-bridge.jar"

if [[ ! -d "${BRIDGE_DIR}" ]]; then
  echo "rfid-bridge directory not found. Run from repo root." >&2
  exit 1
fi

if [[ ! -f "${BRIDGE_DIR}/lib/Symbol.RFID.API3.jar" ]]; then
  cat >&2 <<'EOF'
Missing Zebra SDK: rfid-bridge/lib/Symbol.RFID.API3.jar.
Copy it from /Users/nickolaigarces/Documents/BizBeams/RFID/bin after extracting the MSI.
EOF
  exit 1
fi

pushd "${BRIDGE_DIR}" >/dev/null
mvn --quiet clean package
popd >/dev/null

mkdir -p "${DIST_DIR}"
cp "${ARTIFACT}" "${OUTPUT}"
echo "Bridge jar copied to ${OUTPUT}"

# Embed the Zebra SDK classes so the jar runs standalone.
TMP_DIR="$(mktemp -d)"
(
  cd "${TMP_DIR}"
  jar xf "${BRIDGE_DIR}/lib/Symbol.RFID.API3.jar"
  rm -rf META-INF
  jar uf "${OUTPUT}" .
)
rm -rf "${TMP_DIR}"
