# matchers/tools — ruleset signing (#416)

Dev-side helpers for signing a canonical ruleset bundle so a running app instance
can verify it before compiling (the hard prerequisite for the CDN matchers split,
#192). The on-device gate is `RulesetVerifier` in `:core:pipeline`.

## Scheme

- **ECDSA P-256 (secp256r1 / prime256v1) with SHA-256** — Java `SHA256withECDSA`,
  available on every supported API level, no extra crypto dependency.
- **Detached** signature over the **exact canonical JSON bytes** (no reformatting).
- Signature transported **base64** (standard alphabet); it is an ASN.1 DER
  `SEQUENCE { r, s }` — exactly what `openssl dgst -sign` emits and what the Java
  verifier expects.
- Public key pinned in app config as **base64 DER X.509 SubjectPublicKeyInfo**.

## One-time key generation (private key is NEVER committed)

```bash
# Generate the signing keypair. Keep ruleset-signing-key.pem OFF the repo / in a secret store.
openssl ecparam -name prime256v1 -genkey -noout -out ruleset-signing-key.pem

# Derive the PINNED public key for app config (base64 DER SubjectPublicKeyInfo):
openssl ec -in ruleset-signing-key.pem -pubout -outform DER | base64 -w0
```

Pin that public-key string against the rule source id in `RuleSourceKey` config. To
rotate or fork, generate a new keypair and switch the pinned key for that source —
verification is always against the *configured source's* key; it is never skipped.

> `keytool -genkeypair -keyalg EC -groupname secp256r1 ...` into a keystore works
> too if you prefer a keystore-managed private key; export the public key as DER and
> base64-encode it the same way.

## Signing a bundle

```bash
matchers/tools/sign-ruleset.sh ruleset-signing-key.pem path/to/doordash.json
# → prints the base64 signature; ship it alongside the bundle for the CDN path.
```

## Verifying locally (sanity check, mirrors the app)

```bash
openssl ec -in ruleset-signing-key.pem -pubout -out pub.pem
matchers/tools/sign-ruleset.sh ruleset-signing-key.pem doordash.json | base64 -d > sig.der
openssl dgst -sha256 -verify pub.pem -signature sig.der doordash.json   # → "Verified OK"
```

Bundled `assets/rules/*` are **exempt** — the APK signature already covers them; only
remote / side-loaded bundles are signed and verified here.
