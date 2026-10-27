#!/usr/bin/env bash
# Install the fixed Maven distribution into the disposable GitHub runner directory.
set -euo pipefail
: "${RUNNER_TEMP:?GitHub runner temporary directory is required}"
: "${GITHUB_PATH:?GitHub path file is required}"
version=3.10.0
base="https://archive.apache.org/dist/maven/maven-3/${version}/binaries"
download_dir=$(mktemp -d "${RUNNER_TEMP}/maven-download.XXXXXX")
trap 'rm -rf "$download_dir"' EXIT
archive="apache-maven-${version}-bin.tar.gz"
curl --fail --silent --show-error --location --retry 3 --connect-timeout 30 --max-time 180 \
    --output "${download_dir}/${archive}" "${base}/${archive}"
curl --fail --silent --show-error --location --retry 3 --connect-timeout 30 --max-time 180 \
    --output "${download_dir}/${archive}.sha512" "${base}/${archive}.sha512"
checksum=$(tr -d '[:space:]' < "${download_dir}/${archive}.sha512")
if [[ ! "$checksum" =~ ^[[:xdigit:]]{128}$ ]]; then
    echo "Invalid Maven SHA-512 checksum" >&2
    exit 1
fi
python3 - "$checksum" "${download_dir}/${archive}" <<'PY'
import hashlib
import sys

digest = hashlib.sha512()
with open(sys.argv[2], "rb") as archive:
    for chunk in iter(lambda: archive.read(1024 * 1024), b""):
        digest.update(chunk)
if digest.hexdigest().lower() != sys.argv[1].lower():
    sys.exit("Maven SHA-512 checksum mismatch")
PY
tar -xzf "${download_dir}/${archive}" -C "$RUNNER_TEMP"
printf '%s\n' "${RUNNER_TEMP}/apache-maven-${version}/bin" >> "$GITHUB_PATH"
