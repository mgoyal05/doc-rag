#!/usr/bin/env python3
"""Check release coordinates/bundles and verify consumers after Central publication. Python 3.10+."""
import argparse
import hashlib
import io
import os
from pathlib import Path
import re
import subprocess
import tempfile
import time
import urllib.error
import urllib.request
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parents[1]
NS = {'m': 'http://maven.apache.org/POM/4.0.0'}
GROUP = 'io.github.mgoyal05'
MODULES = ('core', 'docling', 'widget', 'observability', 'spring-boot')
ARTIFACTS = ('doc-rag-parent',) + tuple('doc-rag-' + name for name in MODULES)
CENTRAL = 'https://repo.maven.apache.org/maven2'


def require(condition, message):
    if not condition:
        raise ValueError(message)


def text(pom, path):
    return (pom.findtext('/'.join('m:' + part for part in path.split('/')), '', NS)).strip()


def project_version(root=ROOT):
    return text(ET.parse(root / 'pom.xml').getroot(), 'version')


def check_metadata(pom, version):
    for path in ('name', 'description', 'url', 'licenses/license/name', 'licenses/license/url',
                 'developers/developer/name', 'scm/connection', 'scm/developerConnection', 'scm/url'):
        require(text(pom, path), f'Missing publishing metadata: {path}')
    require(text(pom, 'scm/tag').replace('${project.version}', version) == 'v' + version,
            'SCM tag must match the release version')


def check_project(root=ROOT, tag=None):
    pom = ET.parse(root / 'pom.xml').getroot()
    version = text(pom, 'version')
    require(text(pom, 'groupId') == GROUP and text(pom, 'artifactId') == ARTIFACTS[0],
            'Unexpected parent coordinates')
    require(text(pom, 'packaging') == 'pom', 'Parent must use pom packaging')
    require(version, 'Missing project version')
    if tag is not None:
        require(re.fullmatch(r'v(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)', tag),
                'Release tag must have the form vMAJOR.MINOR.PATCH')
        require(tag == 'v' + version, f'Tag {tag} does not match version {version}')
    check_metadata(pom, version)
    modules = [node.text.strip() for node in pom.findall('m:modules/m:module', NS)]
    require(set(modules) == set(MODULES) | {'examples/spring-boot'} and len(modules) == 6,
            'Unexpected reactor module set; review the publication allowlist')
    for module in modules:
        child = ET.parse(root / module / 'pom.xml').getroot()
        expected = 'doc-rag-example' if module == 'examples/spring-boot' else 'doc-rag-' + module
        require(text(child, 'artifactId') == expected, f'Unexpected artifact in {module}')
        for name, value in (('groupId', GROUP), ('artifactId', ARTIFACTS[0]), ('version', version)):
            require(text(child, 'parent/' + name) == value, f'Parent {name} mismatch in {module}')
        require(not text(child, 'version') or text(child, 'version') == version,
                f'Module version mismatch in {module}')
        require(text(child, 'name') and text(child, 'description'), f'Missing metadata in {module}')
        if module == 'examples/spring-boot':
            for name in ('maven.deploy.skip', 'docrag.skipPublishing'):
                require(text(child, 'properties/' + name) == 'true', f'Example must set {name}=true')
        if tag is not None:
            require('SNAPSHOT' not in ET.tostring(child, encoding='unicode'),
                    f'Snapshot reference in {module}')
    if tag is not None:
        require('SNAPSHOT' not in ET.tostring(pom, encoding='unicode'), 'Snapshot reference in parent')
    return version


def artifact_files(version):
    files = []
    for artifact in ARTIFACTS:
        base = f'{GROUP.replace(".", "/")}/{artifact}/{version}/{artifact}-{version}'
        files.append(base + '.pom')
        if artifact != ARTIFACTS[0]:
            files.extend(base + suffix for suffix in ('.jar', '-sources.jar', '-javadoc.jar'))
    return files


def check_available(version, published=False):
    for artifact in ARTIFACTS:
        path = f'{GROUP.replace(".", "/")}/{artifact}/{version}/{artifact}-{version}.pom'
        request = urllib.request.Request(CENTRAL + '/' + path, method='HEAD')
        try:
            with urllib.request.urlopen(request, timeout=30):
                require(published, f'Already published: {GROUP}:{artifact}:{version}; use a new version')
        except urllib.error.HTTPError as error:
            if error.code != 404:
                raise
            require(not published, f'Not yet available on Central: {artifact}:{version}')


def check_bundle(bundle, version, gpg='gpg', fingerprint=None):
    expected = set(artifact_files(version))
    allowed = {name + suffix for name in expected
               for suffix in ('', '.asc', '.md5', '.sha1', '.sha256', '.sha512')}
    with zipfile.ZipFile(bundle) as archive, tempfile.TemporaryDirectory(prefix='docrag-signatures-') as temp:
        names = [name for name in archive.namelist() if not name.endswith('/')]
        require(len(names) == len(set(names)), 'Duplicate ZIP entries')
        require(set(names) <= allowed, f'Unexpected bundle entries: {sorted(set(names) - allowed)}')
        require(expected <= set(names), f'Missing release artifacts: {sorted(expected - set(names))}')
        require(archive.testzip() is None, 'Invalid ZIP CRC')
        for index, name in enumerate(sorted(expected)):
            content = archive.read(name)
            require(content, f'Empty release artifact: {name}')
            for algorithm in ('md5', 'sha1', 'sha256', 'sha512'):
                checksum = name + '.' + algorithm
                require(checksum in names or algorithm not in ('md5', 'sha1'), f'Missing {checksum}')
                if checksum in names:
                    require(archive.read(checksum).decode('ascii').strip().lower()
                            == hashlib.new(algorithm, content).hexdigest(), f'Invalid checksum: {checksum}')
            require(name + '.asc' in names, f'Missing signature: {name}')
            artifact = Path(temp) / str(index)
            signature = artifact.with_suffix('.asc')
            artifact.write_bytes(content)
            signature.write_bytes(archive.read(name + '.asc'))
            result = subprocess.run([gpg, '--batch', '--status-fd=1', '--verify', str(signature), str(artifact)],
                                    capture_output=True, text=True, check=True)
            valid = [line.split() for line in result.stdout.splitlines() if line.startswith('[GNUPG:] VALIDSIG ')]
            require(valid, f'No valid signature: {name}')
            if fingerprint:
                require(any(fingerprint.upper() in (line[2].upper(), line[-1].upper()) for line in valid),
                        f'Unexpected signing key: {name}')
            if name.endswith('.pom'):
                pom = ET.fromstring(content)
                artifact_id = name.split('/')[-3]
                require(text(pom, 'artifactId') == artifact_id, f'Wrong POM artifactId: {name}')
                if artifact_id == ARTIFACTS[0]:
                    require(text(pom, 'groupId') == GROUP and text(pom, 'version') == version,
                            'Wrong parent POM coordinates')
                    check_metadata(pom, version)
                else:
                    require(text(pom, 'parent/groupId') == GROUP
                            and text(pom, 'parent/artifactId') == ARTIFACTS[0]
                            and text(pom, 'parent/version') == version, f'Wrong POM parent: {name}')
            else:
                with zipfile.ZipFile(io.BytesIO(content)) as jar:
                    entries = jar.namelist()
                    require(jar.testzip() is None, f'Invalid JAR CRC: {name}')
                    suffix = '.html' if name.endswith('-javadoc.jar') else (
                        '.js' if '/doc-rag-widget/' in name else (
                            '.java' if name.endswith('-sources.jar') else '.class'))
                    require(any(entry.endswith(suffix) for entry in entries), f'Missing {suffix} content: {name}')
                    if not name.endswith(('-sources.jar', '-javadoc.jar')):
                        require({'META-INF/LICENSE', 'META-INF/NOTICE'} <= set(entries),
                                f'Missing license resources: {name}')
    print(f'Validated {len(expected)} artifacts, signatures, checksums and JAR/POM contents')


def verify_consumers(version, root=ROOT):
    """Never use the publisher's installed SDK artifacts or normal Maven settings/cache."""
    with tempfile.TemporaryDirectory(prefix='docrag-consumers-') as temp:
        base = Path(temp)
        settings = base / 'settings.xml'
        settings.write_text('<settings xmlns="http://maven.apache.org/SETTINGS/1.0.0"/>', encoding='utf-8')
        command = [str(root / 'mvnw.cmd')] if os.name == 'nt' else ['sh', str(root / 'mvnw')]
        for module in ('core', 'spring-boot'):
            consumer = base / module
            source = consumer / 'src/main/java'
            source.mkdir(parents=True)
            (consumer / 'pom.xml').write_text(f'''<project xmlns="http://maven.apache.org/POM/4.0.0">
  <modelVersion>4.0.0</modelVersion><groupId>consumer</groupId><artifactId>smoke-{module}</artifactId><version>1</version>
  <properties><maven.compiler.release>21</maven.compiler.release></properties>
  <dependencies><dependency><groupId>{GROUP}</groupId><artifactId>doc-rag-{module}</artifactId>
  <version>{version}</version></dependency></dependencies>
  <build><plugins><plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-compiler-plugin</artifactId>
  <version>3.14.1</version></plugin></plugins></build>
</project>''', encoding='utf-8')
            adapter = 'Class<?> adapter = docrag.spring.ResearchWebController.class;' if module == 'spring-boot' else ''
            (source / 'Smoke.java').write_text(
                'class Smoke { Class<?> client = docrag.ResearchClient.class; ' + adapter + ' }', encoding='utf-8')
            subprocess.run(command + ['-B', '--no-transfer-progress', '-U', '-s', str(settings),
                                      '-gs', str(settings), '-Dmaven.repo.local=' + str(base / 'repository'),
                                      '-f', str(consumer / 'pom.xml'), 'compile'], cwd=consumer, check=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--tag', help='Require an exact stable version tag')
    parser.add_argument('--check-central', action='store_true', help='Refuse any already-published coordinates')
    parser.add_argument('--bundle', type=Path, help='Validate a signed Central bundle without uploading')
    parser.add_argument('--gpg', default='gpg', help='GnuPG executable for signature verification')
    parser.add_argument('--fingerprint', help='Expected full signing-key fingerprint')
    parser.add_argument('--verify-published', action='store_true', help='Wait for Central and compile fresh consumers')
    args = parser.parse_args()
    version = check_project(tag=args.tag)
    if args.check_central:
        check_project(tag=args.tag or 'v' + version)
        check_available(version)
    if args.bundle:
        check_project(tag=args.tag or 'v' + version)
        check_bundle(args.bundle, version, args.gpg, args.fingerprint)
    if args.verify_published:
        check_project(tag='v' + version)
        for attempt in range(10):
            try:
                check_available(version, published=True)
                verify_consumers(version)
                break
            except (ValueError, urllib.error.URLError, subprocess.CalledProcessError):
                if attempt == 9:
                    raise
                print('Waiting 30 seconds for Central availability...', flush=True)
                time.sleep(30)
    print(f'Release checks passed for {GROUP}:doc-rag-*:{version}', flush=True)


if __name__ == '__main__':
    main()
