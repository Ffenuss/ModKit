import sys
import os
import tempfile
import unittest
import warnings
import zipfile
from unittest.mock import patch
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from upgrade_host import verify_predecessor, verify_preservation, verify_signature, HOST_CERTIFICATE, NEW_HOST_CERTIFICATE, predecessor_certificate, verify_source, PREDECESSOR_SHA256, NEW_KEY_PREDECESSOR_SHA256, sign


class UpgradeTests(unittest.TestCase):
    def test_replacement_signer_is_explicit_and_does_not_change_predecessor_trust(self):
        replacement = 'Signer #1 certificate SHA-256 digest: ' + NEW_HOST_CERTIFICATE + '\n'
        with patch('upgrade_host.signer', return_value=replacement):
            with self.assertRaises(ValueError):
                verify_signature('old.apk')
            verify_signature('new.apk', NEW_HOST_CERTIFICATE)
        original = 'Signer #1 certificate SHA-256 digest: ' + HOST_CERTIFICATE + '\n'
        with patch('upgrade_host.signer', return_value=original):
            with self.assertRaises(ValueError):
                verify_signature('new.apk', NEW_HOST_CERTIFICATE)

    def test_signer_mismatch_or_multiple_signers_rejected(self):
        reports = ('Signer #1 certificate SHA-256 digest: incorrect\n', '',
                   'Signer #1 certificate SHA-256 digest: ' + HOST_CERTIFICATE + '\n' +
                   'Signer #2 certificate SHA-256 digest: ' + HOST_CERTIFICATE + '\n')
        for report in reports:
            with self.subTest(report=report), patch('upgrade_host.signer', return_value=report):
                with self.assertRaisesRegex(ValueError, 'signer differs'):
                    verify_signature('fixture.apk')
        with patch('upgrade_host.signer', return_value=
                   'Signer #1 certificate SHA-256 digest: ' + HOST_CERTIFICATE + '\n'):
            verify_signature('fixture.apk')

    def test_exact_predecessor_hash_selects_its_own_certificate(self):
        for sha, cert in ((PREDECESSOR_SHA256, HOST_CERTIFICATE),
                          (NEW_KEY_PREDECESSOR_SHA256, NEW_HOST_CERTIFICATE)):
            with self.subTest(sha=sha), patch('upgrade_host.digest', return_value=sha):
                self.assertEqual(cert, predecessor_certificate('space.apk'))
        with patch('upgrade_host.digest', return_value='0' * 64):
            with self.assertRaisesRegex(ValueError, 'Unsupported predecessor'):
                predecessor_certificate('space.apk')

    def test_predecessor_hash_cannot_authorize_a_different_signer(self):
        for expected, wrong in ((HOST_CERTIFICATE, NEW_HOST_CERTIFICATE),
                                (NEW_HOST_CERTIFICATE, HOST_CERTIFICATE)):
            with self.subTest(expected=expected), patch('upgrade_host.verify_predecessor', return_value=expected):
                with patch('upgrade_host.signer', return_value='Signer #1 certificate SHA-256 digest: ' + wrong + '\n'):
                    with self.assertRaisesRegex(ValueError, 'signer differs'):
                        verify_source('space.apk')
                with patch('upgrade_host.signer', return_value='Signer #1 certificate SHA-256 digest: ' + expected + '\n'):
                    self.assertEqual(expected, verify_source('space.apk'))

    def test_update_uses_source_signer_and_reports_migration_accurately(self):
        cases = ((HOST_CERTIFICATE, False, HOST_CERTIFICATE, True),
                 (HOST_CERTIFICATE, True, NEW_HOST_CERTIFICATE, False),
                 (NEW_HOST_CERTIFICATE, False, NEW_HOST_CERTIFICATE, True),
                 (NEW_HOST_CERTIFICATE, True, NEW_HOST_CERTIFICATE, True))
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for source_cert, new_key, expected, same_signer in cases:
                def fake_signer(*args):
                    if args[0] == 'sign':
                        Path(args[args.index('--out') + 1]).write_bytes(b'signed candidate')
                        return ''
                    return 'Signer #1 certificate SHA-256 digest: ' + expected + '\n'
                with self.subTest(source=source_cert, new_key=new_key), \
                        patch.dict(os.environ, {'MODKIT_SPACE_KEYSTORE': str(root / 'key.p12'),
                                                'MODKIT_SPACE_ALIAS': 'fixture',
                                                'MODKIT_SPACE_STORE_PASSWORD': 'fixture'}), \
                        patch('upgrade_host.verify_source', return_value=source_cert), \
                        patch('upgrade_host.verify_output', return_value={}), \
                        patch('upgrade_host.sdk_tools', return_value=root), \
                        patch('upgrade_host.subprocess.run'), \
                        patch('upgrade_host.signer', side_effect=fake_signer):
                    report = sign(root / 'source.apk', root / 'unsigned.apk', root / 'output.apk', new_key)
                    self.assertEqual(expected, report['certificate_sha256'])
                    self.assertEqual(same_signer, report['same_signer_update'])
                    self.assertTrue(report['signed'])

    def archive(self, path, changes=None):
        entries = {'AndroidManifest.xml': b'manifest', 'classes2.dex': b'bootstrap',
                   'lib/arm64-v8a/libCEZ.so': b'kernel', 'resources.arsc': b'resources',
                   'classes4.dex': b'old overlay', 'assets/modkit-space-engine.apk': b'engine',
                   'assets/modkit-space-engine.sha256': b'hash', 'META-INF/OLD.RSA': b'signer'}
        entries.update(changes or {})
        with zipfile.ZipFile(path, 'w') as archive:
            for name, content in entries.items():
                if content is not None:
                    archive.writestr(name, content)

    def test_unknown_host_rejected_before_archive_parsing(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'guest.apk'
            path.write_bytes(b'unknown host or guest')
            with self.assertRaisesRegex(ValueError, 'Unsupported predecessor'):
                verify_predecessor(path)

    def test_only_modkit_payloads_and_signature_may_change(self):
        with tempfile.TemporaryDirectory() as directory:
            before, after = (Path(directory) / n for n in ('before.apk', 'after.apk'))
            self.archive(before)
            self.archive(after, {'classes4.dex': b'new overlay',
                                'assets/modkit-space-engine.apk': b'new engine',
                                'assets/modkit-space-engine.sha256': b'new hash',
                                'META-INF/OLD.RSA': None, 'META-INF/NEW.RSA': b'new signer'})
            verify_preservation(before, after)

    def test_kernel_bootstrap_manifest_resources_and_extra_entries_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            before, after = (Path(directory) / n for n in ('before.apk', 'after.apk'))
            self.archive(before)
            for name in ('classes2.dex', 'lib/arm64-v8a/libCEZ.so', 'AndroidManifest.xml',
                         'resources.arsc', 'guest.apk'):
                with self.subTest(entry=name):
                    self.archive(after, {name: b'changed'})
                    with self.assertRaises(ValueError):
                        verify_preservation(before, after)

    def test_missing_or_duplicate_entry_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            before, after = (Path(directory) / n for n in ('before.apk', 'after.apk'))
            self.archive(before)
            self.archive(after, {'resources.arsc': None})
            with self.assertRaises(ValueError):
                verify_preservation(before, after)
            self.archive(after)
            with warnings.catch_warnings():
                warnings.simplefilter('ignore', UserWarning)
                with zipfile.ZipFile(after, 'a') as archive:
                    archive.writestr('classes4.dex', b'duplicate')
            with self.assertRaisesRegex(ValueError, 'Duplicate'):
                verify_preservation(before, after)
