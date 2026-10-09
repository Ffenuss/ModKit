import sys
import tempfile
import unittest
import warnings
import zipfile
from unittest.mock import patch
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from upgrade_host import verify_predecessor, verify_preservation, verify_signature, HOST_CERTIFICATE, NEW_HOST_CERTIFICATE


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
