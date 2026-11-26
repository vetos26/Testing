#!/usr/bin/env python3
"""Сборка APK: zip + v1-подпись (JAR signing) на чистом python."""
import base64
import hashlib
import zipfile
import os
import sys

try:
    from cryptography import x509
    from cryptography.hazmat.primitives import hashes, serialization
    from cryptography.hazmat.primitives.asymmetric import rsa
    from cryptography.hazmat.primitives.serialization import pkcs7
    from cryptography.x509.oid import NameOID
    import datetime
except ImportError:
    print('нужен pip install cryptography'); sys.exit(1)

def sha256_b64(data: bytes) -> str:
    return base64.b64encode(hashlib.sha256(data).digest()).decode()

def split_sections(manifest: bytes):
    """Разбивает MANIFEST.MF на секции (байты, включая завершающую пустую строку)."""
    parts = manifest.split(b'\r\n\r\n')
    secs = []
    for i, p in enumerate(parts):
        if not p: continue
        secs.append(p + b'\r\n\r\n')
    return secs

def build_v1(entries):
    """entries: список (имя, байты). Возвращает байты трёх META-INF файлов."""
    lines = [b'Manifest-Version: 1.0', b'Created-By: 1.0 (RedRadar Build)']
    manifest = bytearray()
    manifest += b'\r\n'.join(lines) + b'\r\n\r\n'
    digests = {}
    for name, data in entries:
        d = sha256_b64(data)
        digests[name] = d
        manifest += f'Name: {name}\r\nSHA-256-Digest: {d}\r\n\r\n'.encode()
    manifest = bytes(manifest)

    # CERT.SF: главная секция с дайджестом всего манифеста + по секциям
    sf = bytearray()
    sf += b'Signature-Version: 1.0\r\n'
    sf += b'Created-By: 1.0 (RedRadar Build)\r\n'
    sf += f'SHA-256-Digest-Manifest: {sha256_b64(manifest)}\r\n'.encode()
    sf += b'\r\n'
    # дайджест главной секции манифеста
    main_sec = split_sections(manifest)[0]
    sf += b'Name: META-INF/MANIFEST.MF\r\n'
    sf += f'SHA-256-Digest: {sha256_b64(main_sec)}\r\n\r\n'.encode()
    for name, data in entries:
        # байты соответствующей секции манифеста
        sec = f'Name: {name}\r\nSHA-256-Digest: {digests[name]}\r\n\r\n'.encode()
        sf += f'Name: {name}\r\nSHA-256-Digest: {sha256_b64(sec)}\r\n\r\n'.encode()
    sf = bytes(sf)

    # ключ и самоподписанный сертификат
    key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    now = datetime.datetime.now(datetime.timezone.utc)
    subject = x509.Name([
        x509.NameAttribute(NameOID.COMMON_NAME, u'Red Radar'),
        x509.NameAttribute(NameOID.ORGANIZATION_NAME, u'RedRadar'),
    ])
    cert = (
        x509.CertificateBuilder()
        .subject_name(subject)
        .issuer_name(subject)
        .public_key(key.public_key())
        .serial_number(x509.random_serial_number())
        .not_valid_before(now - datetime.timedelta(days=1))
        .not_valid_after(now + datetime.timedelta(days=3650))
        .sign(key, hashes.SHA256())
    )

    sig = (
        pkcs7.PKCS7SignatureBuilder()
        .set_data(sf)
        .add_signer(cert, key, hashes.SHA256())
        .sign(serialization.Encoding.DER, [pkcs7.PKCS7Options.DetachedSignature, pkcs7.PKCS7Options.NoCapabilities])
    )
    return manifest, sf, sig

def main():
    manifest_bin = open('manifest.bin', 'rb').read()
    dex = open('classes.dex', 'rb').read()

    entries = [
        ('AndroidManifest.xml', manifest_bin),
        ('classes.dex', dex),
    ]
    mf, sf, rsa_sig = build_v1(entries)

    apk_path = sys.argv[1] if len(sys.argv) > 1 else 'RedRadar.apk'
    z = zipfile.ZipFile(apk_path, 'w', zipfile.ZIP_DEFLATED)
    z.writestr('AndroidManifest.xml', manifest_bin)
    z.writestr('classes.dex', dex)
    z.writestr('META-INF/MANIFEST.MF', mf)
    z.writestr('META-INF/CERT.SF', sf)
    z.writestr('META-INF/CERT.RSA', rsa_sig)
    z.close()
    print(f'APK: {apk_path} ({os.path.getsize(apk_path)} байт)')

if __name__ == '__main__':
    main()
