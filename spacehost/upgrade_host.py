"""Upgrade only the pinned, previously signed ModKit Space host.

The original-host builder remains strict. This route never patches its bootstrap,
manifest or virtual kernel again. Signing is a separate, password-dependent step.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import zipfile

from patch_host import (old_signature, verify_overlay_payload,
                        verify_engine_payload, ENGINE_ASSET, ENGINE_HASH)

PREDECESSOR_SHA256 = '6f9cd491a2bc344538a515408d3f9b7682f494eab9caabab7f7dda372d4d79a6'
HOST_CERTIFICATE = '03498720af5c326fc3a399d7c96aed5fdea2f378ec1799580bb119e1bcbc4b5f'
NEW_HOST_CERTIFICATE = 'b44a6c2b53689c16cb08da3ca22e4aba20d9d0d1ecbc26df13e15c04b6665073'
NEW_KEY_PREDECESSOR_SHA256 = 'ec3ffc685880b27ad23b3305334a992dad313ce67ff7690420ffc7d0e2f22870'
TRUSTED_PREDECESSORS = {PREDECESSOR_SHA256: HOST_CERTIFICATE,
                        NEW_KEY_PREDECESSOR_SHA256: NEW_HOST_CERTIFICATE}
REPLACEABLE = {'classes4.dex', ENGINE_ASSET, ENGINE_HASH}


def digest(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def predecessor_certificate(path):
    certificate = TRUSTED_PREDECESSORS.get(digest(path))
    if certificate is None:
        raise ValueError('Unsupported predecessor: exact pinned Space APK required')
    return certificate


def verify_predecessor(path):
    certificate = predecessor_certificate(path)
    with zipfile.ZipFile(path) as archive:
        unique_entries(archive)
        verify_overlay_payload(archive.read('classes4.dex'))
        engine = verify_engine_payload(archive.read(ENGINE_ASSET))
        if archive.read(ENGINE_HASH) != engine.encode('ascii'):
            raise ValueError('Predecessor engine hash mismatch')
    return certificate


def unique_entries(archive):
    names = archive.namelist()
    if len(names) != len(set(names)):
        raise ValueError('Duplicate ZIP entries')
    return {name for name in names if not old_signature(name)}


def verify_preservation(source, output):
    """Compare decompressed bytes, including all kernel DEX/native/resource files."""
    with zipfile.ZipFile(source) as before, zipfile.ZipFile(output) as after:
        original = unique_entries(before)
        if unique_entries(after) != original:
            raise ValueError('Unexpected output entry set')
        if not REPLACEABLE <= original:
            raise ValueError('Missing existing ModKit payloads')
        for name in original - REPLACEABLE:
            if before.read(name) != after.read(name):
                raise ValueError('Unrelated host entry changed: ' + name)


def verify_output(source, output):
    verify_predecessor(source)
    verify_preservation(source, output)
    with zipfile.ZipFile(output) as archive:
        verify_overlay_payload(archive.read('classes4.dex'))
        engine = verify_engine_payload(archive.read(ENGINE_ASSET))
        if archive.read(ENGINE_HASH) != engine.encode('ascii'):
            raise ValueError('Output engine hash mismatch')
    return dict(predecessor_sha256=digest(source), sha256=digest(output),
                replaced_entries=sorted(REPLACEABLE), engine_sha256=engine,
                manifest_and_resources_unchanged=True,
                bootstrap_and_virtual_kernel_unchanged=True,
                guest_apks_modified=0, device_validation='pending')


def sdk_tools():
    root = os.environ.get('MODKIT_SPACE_BUILD_TOOLS')
    if not root:
        root = str(Path(os.environ['ANDROID_SDK_ROOT']) / 'build-tools/35.0.0')
    return Path(root)


def signer(*arguments):
    java = os.environ.get('MODKIT_SPACE_JAVA', 'java')
    return subprocess.run([java, '-jar', str(sdk_tools() / 'lib/apksigner.jar'),
                           *map(str, arguments)], check=True, text=True,
                          stdout=subprocess.PIPE, stderr=subprocess.PIPE).stdout


def verify_signature(path, certificate=HOST_CERTIFICATE):
    report = signer('verify', '--print-certs', path)
    certificates = re.findall(r'^Signer #\d+ certificate SHA-256 digest: (\w+)$',
                              report, re.MULTILINE)
    if certificates != [certificate]:
        raise ValueError('Space signer differs from authenticated host certificate')


def verify_source(path):
    certificate = verify_predecessor(path)
    verify_signature(path, certificate)
    return certificate


def prepare(source, overlay, engine, output):
    output = Path(output)
    if output.resolve() in {Path(p).resolve() for p in (source, overlay, engine)}:
        raise ValueError('Input and output must differ')
    verify_source(source)
    payload = Path(overlay).read_bytes()
    verify_overlay_payload(payload)
    carrier = Path(engine).read_bytes()
    engine_hash = verify_engine_payload(carrier)
    replacements = {'classes4.dex': payload, ENGINE_ASSET: carrier,
                    ENGINE_HASH: engine_hash.encode('ascii')}
    output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(dir=output.parent) as directory:
        unsigned, aligned = (Path(directory) / n for n in ('unsigned.apk', 'aligned.apk'))
        with zipfile.ZipFile(source) as src, zipfile.ZipFile(unsigned, 'w') as dst:
            for info in src.infolist():
                if not old_signature(info.filename):
                    dst.writestr(info, replacements.get(info.filename, src.read(info.filename)))
        subprocess.run([str(sdk_tools() / 'zipalign'), '-f', '-p', '4',
                        str(unsigned), str(aligned)], check=True)
        report = verify_output(source, aligned)
        report['signed'] = False
        aligned.replace(output)
    return report


def sign(source, unsigned, output, new_key=False):
    source_certificate = verify_source(source)
    certificate = NEW_HOST_CERTIFICATE if new_key else source_certificate
    output = Path(output)
    if output.resolve() in {Path(source).resolve(), Path(unsigned).resolve(),
                             Path(os.environ['MODKIT_SPACE_KEYSTORE']).resolve()}:
        raise ValueError('Input and output must differ')
    verify_output(source, unsigned)
    # Validate required secrets without logging or passing them on the command line.
    for name in ('MODKIT_SPACE_STORE_PASSWORD', 'MODKIT_SPACE_ALIAS'):
        if not os.environ.get(name):
            raise ValueError('Set ' + name)
    output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(dir=output.parent) as directory:
        candidate = Path(directory) / 'signed.apk'
        args = ['sign', '--ks', os.environ['MODKIT_SPACE_KEYSTORE'],
                '--ks-key-alias', os.environ['MODKIT_SPACE_ALIAS'],
                '--ks-pass', 'env:MODKIT_SPACE_STORE_PASSWORD']
        if os.environ.get('MODKIT_SPACE_KEY_PASSWORD'):
            args += ['--key-pass', 'env:MODKIT_SPACE_KEY_PASSWORD']
        signer(*args, '--out', candidate, unsigned)
        verify_signature(candidate, certificate)
        subprocess.run([str(sdk_tools() / 'zipalign'), '-c', '-p', '4',
                        str(candidate)], check=True)
        report = verify_output(source, candidate)
        report['signed'] = True
        report['certificate_sha256'] = certificate
        report['same_signer_update'] = certificate == source_certificate
        candidate.replace(output)
    return report


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    actions = parser.add_subparsers(dest='action', required=True)
    command = actions.add_parser('prepare')
    for argument in ('source', 'overlay', 'engine', 'output'):
        command.add_argument(argument)
    command = actions.add_parser('sign')
    command.add_argument('--new-key', action='store_true', help='Fresh install with the explicitly pinned replacement key; cannot update the old signer')
    for argument in ('source', 'unsigned', 'output'):
        command.add_argument(argument)
    args = parser.parse_args()
    if args.action == 'prepare':
        result = prepare(args.source, args.overlay, args.engine, args.output)
    else:
        result = sign(args.source, args.unsigned, args.output, args.new_key)
    print(json.dumps(result, indent=2))
