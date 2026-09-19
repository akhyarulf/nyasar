#!/usr/bin/env python3
"""
Generate a committed Android DEBUG keystore (keystore/debug.keystore) so every
debug build — local or CI — signs with the SAME key. A debug key is not a
secret (it only ever signs debug APKs), unlike the release keystore which
stays untracked and arrives via CI secrets.

PKCS12 store, RSA 2048, 30-year validity, alias `androiddebugkey`,
password `android` — the Android SDK defaults, so `installDebug` and CI debug
builds work with zero extra configuration, and the SHA-256 fingerprint that
goes into web/.well-known/assetlinks.json stays valid forever.

Run from repo root:  python3 scripts/gen_debug_keystore.py
Prints the SHA-256 fingerprint for assetlinks.json at the end.
"""
import base64
import datetime
import hashlib
import ipaddress
import os
import sys

from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import rsa
from cryptography.hazmat.primitives.serialization import pkcs12
from cryptography.x509.oid import NameOID

STORE_PATH = os.path.join("keystore", "debug.keystore")
STORE_PASS = b"android"  # Android SDK debug convention
ALIAS = "androiddebugkey"

if os.path.exists(STORE_PATH):
    print(f"{STORE_PATH} already exists — leaving it untouched.")
    print("Delete it first if you want to rotate the debug key.")
    sys.exit(0)

key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
now = datetime.datetime.now(datetime.timezone.utc)
name = x509.Name([
    x509.NameAttribute(NameOID.COMMON_NAME, "Android Debug Nyasar"),
    x509.NameAttribute(NameOID.ORGANIZATION_NAME, "Nyasar"),
])
cert = (
    x509.CertificateBuilder()
    .subject_name(name)
    .issuer_name(name)  # self-signed, standard for debug certs
    .public_key(key.public_key())
    .serial_number(x509.random_serial_number())
    .not_valid_before(now - datetime.timedelta(days=1))
    .not_valid_after(now + datetime.timedelta(days=365 * 30))
    .add_extension(
        x509.SubjectAlternativeName([
            x509.DNSName("localhost"),
            x509.IPAddress(ipaddress.IPv4Address("127.0.0.1")),
        ]),
        critical=False,
    )
    .sign(key, hashes.SHA256())
)

os.makedirs(os.path.dirname(STORE_PATH), exist_ok=True)
data = pkcs12.serialize_key_and_certificates(
    name=ALIAS.encode(),
    key=key,
    cert=cert,
    cas=None,
    encryption_algorithm=serialization.BestAvailableEncryption(STORE_PASS),
)
with open(STORE_PATH, "wb") as f:
    f.write(data)

sha256 = hashlib.sha256(cert.public_bytes(serialization.Encoding.DER)).hexdigest().upper()
print(f"written: {STORE_PATH} (PKCS12, alias {ALIAS}, pass android)")
print()
print("SHA-256 debug cert (untuk web/.well-known/assetlinks.json):")
for i in range(0, len(sha256), 2):
    print(sha256[i:i + 2], end="")
    if i + 2 < len(sha256):
        print(":", end="")
print()
