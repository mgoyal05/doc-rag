"""Offline checks for immutable GitHub mirrors and registry failure handling."""
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch
import urllib.error

import github_packages as mirror


class GitHubMirrorTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='docrag-mirror-test-')
        self.addCleanup(self.temp.cleanup)
        self.directory = Path(self.temp.name)
        self.version = '0.1.1'
        for artifact in mirror.ARTIFACTS:
            for name in mirror.paths_for(artifact, self.version):
                file = self.directory / name
                file.parent.mkdir(parents=True, exist_ok=True)
                file.write_bytes(b'original Central artifact')

    def test_only_stable_tags_can_select_download_paths(self):
        self.assertEqual(mirror.release_version('v0.1.1'), '0.1.1')
        for tag in ('main', 'v01.1.1', 'v1.2.3/../../other', 'v1.0.0-SNAPSHOT', 'v1.2.3\n'):
            with self.subTest(tag=tag), self.assertRaises(ValueError):
                mirror.release_version(tag)

    def test_only_404_counts_as_missing(self):
        for status in (401, 403, 429, 500):
            with self.subTest(status=status), patch('github_packages.urllib.request.urlopen',
                    side_effect=urllib.error.HTTPError('url', status, 'error', {}, None)):
                with self.assertRaises(urllib.error.HTTPError):
                    mirror.fetch('https://example.com', 'Basic test', missing_ok=True)
        with patch('github_packages.urllib.request.urlopen',
                side_effect=urllib.error.HTTPError('url', 404, 'missing', {}, None)):
            self.assertIsNone(mirror.fetch('https://example.com', missing_ok=True))

    def test_empty_registry_needs_all_six_packages(self):
        with patch('github_packages.fetch', return_value=None) as fetch:
            self.assertEqual(mirror.inventory(self.directory, self.version, 'Basic test'), list(mirror.ARTIFACTS))
            self.assertEqual(fetch.call_count, 42)

    def test_identical_registry_never_redeploys(self):
        with patch.dict('os.environ', {'GITHUB_ACTOR': 'fixture', 'GITHUB_TOKEN': 'fixture'}), \
                patch('github_packages.fetch', return_value=b'original Central artifact'), \
                patch('github_packages.subprocess.run') as maven:
            mirror.publish(self.directory, self.version)
            maven.assert_not_called()

    def test_conflicting_or_partial_versions_block_all_writes(self):
        for values in ([b'changed'], [b'original Central artifact', None]):
            with self.subTest(values=values), \
                    patch.dict('os.environ', {'GITHUB_ACTOR': 'fixture', 'GITHUB_TOKEN': 'fixture'}), \
                    patch('github_packages.fetch', side_effect=values), \
                    patch('github_packages.subprocess.run') as maven:
                with self.assertRaises(ValueError):
                    mirror.publish(self.directory, self.version)
                maven.assert_not_called()

    def test_checks_the_complete_inventory_before_deploying(self):
        # Even a conflict in the last package must prevent the first package being uploaded.
        responses = [None] * 34 + [b'conflict']
        with patch.dict('os.environ', {'GITHUB_ACTOR': 'fixture', 'GITHUB_TOKEN': 'fixture'}), \
                patch('github_packages.fetch', side_effect=responses), \
                patch('github_packages.subprocess.run') as maven:
            with self.assertRaisesRegex(ValueError, 'Conflicting'):
                mirror.publish(self.directory, self.version)
            maven.assert_not_called()

    def test_failed_deployment_stops_without_claiming_success(self):
        with patch.dict('os.environ', {'GITHUB_ACTOR': 'fixture', 'GITHUB_TOKEN': 'fixture'}), \
                patch('github_packages.fetch', return_value=None), \
                patch('github_packages.subprocess.run', side_effect=subprocess.CalledProcessError(1, 'mvn')) as maven:
            with self.assertRaises(subprocess.CalledProcessError):
                mirror.publish(self.directory, self.version)
            self.assertEqual(maven.call_count, 1)


if __name__ == '__main__':
    unittest.main()
