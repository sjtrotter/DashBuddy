#!/usr/bin/env bash
# sign-ruleset.sh — detached signature over a canonical ruleset bundle (#416).
#
# Produces the exact scheme the on-device RulesetVerifier checks:
#   - ECDSA P-256 with SHA-256  (Java "SHA256withECDSA")
#   - detached signature, base64-encoded (ASN.1 DER SEQUENCE { r, s })
#   - over the EXACT canonical JSON bytes (no re-formatting)
#
# The signing PRIVATE KEY is never committed. Generate one (once) with:
#   openssl ecparam -name prime256v1 -genkey -noout -out ruleset-signing-key.pem
# and pin the matching PUBLIC key (base64 DER SubjectPublicKeyInfo) in app config:
#   openssl ec -in ruleset-signing-key.pem -pubout -outform DER | base64 -w0
#
# Usage:
#   matchers/tools/sign-ruleset.sh <private-key.pem> <bundle.json>
# Prints the base64 signature to stdout (transport it alongside the bundle).
set -euo pipefail

if [[ $# -ne 2 ]]; then
  echo "usage: $0 <private-key.pem> <bundle.json>" >&2
  exit 2
fi

KEY="$1"
BUNDLE="$2"

for f in "$KEY" "$BUNDLE"; do
  [[ -f "$f" ]] || { echo "error: no such file: $f" >&2; exit 2; }
done

# -sha256 + an EC key => SHA256withECDSA, DER-encoded signature (Java-compatible).
# base64 -w0 keeps it single-line for transport; the verifier uses the standard alphabet.
openssl dgst -sha256 -sign "$KEY" "$BUNDLE" | base64 -w0
echo
