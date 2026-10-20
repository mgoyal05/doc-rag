"""Regression checks for publication guards; no credentials or network required."""
import hashlib
import io
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest
from unittest.mock import patch
import urllib.error
import xml.etree.ElementTree as ET
import zipfile

import release


class ReleaseChecksTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory(prefix='docrag-release-test-')
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        for name in ('', *release.MODULES, 'examples/spring-boot'):
            target = self.root / name / 'pom.xml'
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(release.ROOT / name / 'pom.xml', target)
        self.version = release.project_version(self.root)

    def change(self, path, old, new):
        file = self.root / path
        file.write_text(file.read_text(encoding='utf-8').replace(old, new), encoding='utf-8')

    def bundle(self, changed=None, omitted=(), extra=None):
        files = {}
        for name in release.artifact_files(self.version):
            artifact = name.split('/')[-3]
            module = '' if artifact == 'doc-rag-parent' else artifact.removeprefix('doc-rag-')
            if name.endswith('.pom'):
                content = (self.root / module / 'pom.xml').read_bytes()
            else:
                jar = io.BytesIO()
                with zipfile.ZipFile(jar, 'w') as archive:
                    suffix = '.html' if name.endswith('-javadoc.jar') else (
                        '.js' if module == 'widget' else (
                            '.java' if name.endswith('-sources.jar') else '.class'))
                    archive.writestr('Content' + suffix, b'content')
                    archive.writestr('META-INF/LICENSE', b'license')
                    archive.writestr('META-INF/NOTICE', b'notice')
                content = jar.getvalue()
            files[name] = content
            files[name + '.asc'] = b'test-signature'
            for algorithm in ('md5', 'sha1', 'sha256', 'sha512'):
                files[name + '.' + algorithm] = hashlib.new(algorithm, content).hexdigest().encode()
        files.update(changed or {})
        files.update(extra or {})
        bundle = self.root / 'bundle.zip'
        with zipfile.ZipFile(bundle, 'w') as archive:
            for name, content in files.items():
                if name not in omitted:
                    archive.writestr(name, content)
        return bundle

    def valid_signatures(self):
        return patch('release.subprocess.run', return_value=subprocess.CompletedProcess(
            [], 0, '[GNUPG:] VALIDSIG ' + 'A' * 40 + ' rest ' + 'A' * 40, ''))

    def test_project_and_exact_tag(self):
        self.assertEqual(release.check_project(self.root, 'v' + self.version), self.version)

    def test_rejects_invalid_or_mismatching_tags(self):
        for tag in ('v9.8.7', 'v01.2.3', 'v1.0.0-SNAPSHOT', 'v1.2.3/extra', 'main'):
            with self.subTest(tag=tag), self.assertRaises(ValueError):
                release.check_project(self.root, tag)

    def test_rejects_parent_version_mismatch(self):
        self.change('core/pom.xml', '<version>' + self.version, '<version>9.8.7')
        with self.assertRaisesRegex(ValueError, 'Parent version mismatch'):
            release.check_project(self.root)

    def test_rejects_missing_metadata(self):
        self.change('pom.xml', '<name>Manish Goyal</name>', '<name/>')
        with self.assertRaisesRegex(ValueError, 'Missing publishing metadata'):
            release.check_project(self.root)

    def test_rejects_publishable_example(self):
        self.change('examples/spring-boot/pom.xml', '<docrag.skipPublishing>true', '<docrag.skipPublishing>false')
        with self.assertRaisesRegex(ValueError, 'Example must set'):
            release.check_project(self.root)

    def test_rejects_snapshot_dependency_on_release(self):
        self.change('core/pom.xml', '<version>2.0.17</version>', '<version>2.0.17-SNAPSHOT</version>')
        with self.assertRaisesRegex(ValueError, 'Snapshot reference'):
            release.check_project(self.root, 'v' + self.version)

    def test_future_release_version_is_derived(self):
        for file in self.root.rglob('pom.xml'):
            file.write_text(file.read_text(encoding='utf-8').replace(self.version, '1.2.3'), encoding='utf-8')
        self.assertEqual(release.check_project(self.root, 'v1.2.3'), '1.2.3')

    def test_all_coordinates_must_be_unpublished(self):
        with patch('release.urllib.request.urlopen', side_effect=urllib.error.HTTPError(
                'url', 404, 'Not Found', {}, None)) as request:
            release.check_available(self.version)
            self.assertEqual(request.call_count, 6)
        with patch('release.urllib.request.urlopen'), self.assertRaisesRegex(ValueError, 'Already published'):
            release.check_available(self.version)

    def test_network_errors_do_not_mean_available(self):
        for error in (urllib.error.URLError('offline'), urllib.error.HTTPError('url', 503, 'Unavailable', {}, None)):
            with patch('release.urllib.request.urlopen', side_effect=error), self.assertRaises(urllib.error.URLError):
                release.check_available(self.version)

    def test_published_check_requires_all_coordinates(self):
        with patch('release.urllib.request.urlopen', side_effect=urllib.error.HTTPError(
                'url', 404, 'Not Found', {}, None)), self.assertRaisesRegex(ValueError, 'Not yet available'):
            release.check_available(self.version, published=True)

    def test_complete_bundle_and_fingerprint(self):
        with self.valid_signatures() as signatures:
            release.check_bundle(self.bundle(), self.version, fingerprint='A' * 40)
            self.assertEqual(signatures.call_count, 21)

    def test_rejects_missing_sources_signature_and_checksum(self):
        main = release.artifact_files(self.version)[3]
        for missing in (main, main + '.asc', main + '.md5', main + '.sha1'):
            with self.subTest(missing=missing), self.valid_signatures(), self.assertRaises(ValueError):
                release.check_bundle(self.bundle(omitted=(missing,)), self.version)

    def test_rejects_corrupt_checksum(self):
        name = release.artifact_files(self.version)[0] + '.sha1'
        with self.valid_signatures(), self.assertRaisesRegex(ValueError, 'Invalid checksum'):
            release.check_bundle(self.bundle(changed={name: b'bad'}), self.version)

    def test_rejects_example_and_traversal_entries(self):
        for name in ('doc-rag-example-0.1.0.jar', '../outside'):
            with self.subTest(name=name), self.valid_signatures(), self.assertRaisesRegex(ValueError, 'Unexpected bundle'):
                release.check_bundle(self.bundle(extra={name: b'bad'}), self.version)

    def test_rejects_wrong_signing_key(self):
        with self.valid_signatures(), self.assertRaisesRegex(ValueError, 'Unexpected signing key'):
            release.check_bundle(self.bundle(), self.version, fingerprint='B' * 40)

    def test_rejects_invalid_signature(self):
        with patch('release.subprocess.run', side_effect=subprocess.CalledProcessError(1, 'gpg')):
            with self.assertRaises(subprocess.CalledProcessError):
                release.check_bundle(self.bundle(), self.version)

    def test_rejects_duplicate_entries(self):
        bundle = self.bundle()
        import warnings
        with warnings.catch_warnings(), zipfile.ZipFile(bundle, 'a') as archive:
            warnings.simplefilter('ignore', UserWarning)
            archive.writestr(release.artifact_files(self.version)[0], b'duplicate')
        with self.assertRaisesRegex(ValueError, 'Duplicate ZIP'):
            release.check_bundle(bundle, self.version)

    def test_rejects_jar_without_documentation(self):
        name = next(name for name in release.artifact_files(self.version) if name.endswith('-javadoc.jar'))
        empty_jar = io.BytesIO()
        with zipfile.ZipFile(empty_jar, 'w') as archive:
            archive.writestr('README.txt', b'No API documentation')
        content = empty_jar.getvalue()
        changed = {name: content}
        changed.update({name + '.' + algorithm: hashlib.new(algorithm, content).hexdigest().encode()
                        for algorithm in ('md5', 'sha1', 'sha256', 'sha512')})
        with self.valid_signatures(), self.assertRaisesRegex(ValueError, 'Missing .html content'):
            release.check_bundle(self.bundle(changed=changed), self.version)

    def test_consumers_use_empty_settings_and_fresh_repository(self):
        def check(command, **kwargs):
            settings = Path(command[command.index('-s') + 1])
            self.assertEqual(command[command.index('-gs') + 1], str(settings))
            self.assertEqual(ET.parse(settings).getroot().tag, '{http://maven.apache.org/SETTINGS/1.0.0}settings')
            self.assertTrue(any(argument.startswith('-Dmaven.repo.local=' + str(settings.parent)) for argument in command))
            consumer = Path(kwargs['cwd'])
            self.assertIn(self.version, (consumer / 'pom.xml').read_text())
            self.assertIn('docrag.ResearchClient', (consumer / 'src/main/java/Smoke.java').read_text())
        with patch('release.subprocess.run', side_effect=check) as maven:
            release.verify_consumers(self.version)
            self.assertEqual(maven.call_count, 2)


if __name__ == '__main__':
    unittest.main()
