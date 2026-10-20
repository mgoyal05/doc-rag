#!/usr/bin/env python3
"""Mirror an already-published, signature-verified Central release to GitHub Packages."""
import argparse
import base64
import os
from pathlib import Path
import re
import subprocess
import tempfile
import urllib.error
import urllib.request
import zipfile

from release import ARTIFACTS, CENTRAL, GROUP, ROOT, artifact_files, check_bundle, require

REGISTRY = 'https://maven.pkg.github.com/mgoyal05/doc-rag'


def release_version(tag):
    require(re.fullmatch(r'v(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)', tag),
            'Expected a stable release tag: vMAJOR.MINOR.PATCH')
    return tag[1:]


def fetch(url, authorization=None, missing_ok=False):
    headers = {'Authorization': authorization} if authorization else {}
    try:
        with urllib.request.urlopen(urllib.request.Request(url, headers=headers), timeout=60) as response:
            return response.read()
    except urllib.error.HTTPError as error:
        if missing_ok and error.code == 404:
            return None
        raise


def prepare(version, directory, fingerprint, gpg='gpg'):
    """Validate every artifact before attempting any registry write."""
    bundle = directory / 'central-bundle.zip'
    with zipfile.ZipFile(bundle, 'w') as archive:
        for name in artifact_files(version):
            if name.endswith('.pom'):
                print(f'Downloading published Central files: {name.split("/")[-3]}:{version}', flush=True)
            for suffix in ('', '.asc', '.md5', '.sha1'):
                content = fetch(CENTRAL + '/' + name + suffix)
                target = directory / (name + suffix)
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(content)
                archive.writestr(name + suffix, content)
    check_bundle(bundle, version, gpg, fingerprint)


def paths_for(artifact, version):
    return [name + suffix for name in artifact_files(version) if name.split('/')[-3] == artifact
            for suffix in ('', '.asc')]


def inventory(directory, version, authorization):
    """Skip identical complete versions; never overwrite conflicting or partial versions."""
    pending = []
    for artifact in ARTIFACTS:
        names = paths_for(artifact, version)
        present = 0
        for name in names:
            content = fetch(REGISTRY + '/' + name, authorization, missing_ok=True)
            if content is not None:
                require(content == (directory / name).read_bytes(), f'Conflicting GitHub artifact: {name}')
                present += 1
        require(present in (0, len(names)), f'Partial GitHub version requires maintainer review: {artifact}:{version}')
        if present == 0:
            pending.append(artifact)
        else:
            print(f'Already mirrored: {artifact}:{version}', flush=True)
    return pending


def deploy_command(directory, artifact, version, url=REGISTRY):
    base = directory / GROUP.replace('.', '/') / artifact / version / f'{artifact}-{version}'
    parent = artifact == ARTIFACTS[0]
    signatures = [str(base) + '.pom.asc']
    classifiers = ['']
    types = ['pom.asc']
    if not parent:
        signatures += [str(base) + suffix for suffix in ('.jar.asc', '-sources.jar.asc', '-javadoc.jar.asc')]
        classifiers += ['', 'sources', 'javadoc']
        types += ['jar.asc'] * 3
    command = [str(ROOT / 'mvnw.cmd')] if os.name == 'nt' else ['sh', str(ROOT / 'mvnw')]
    command += ['-B', '--no-transfer-progress', '-N',
                'org.apache.maven.plugins:maven-deploy-plugin:3.1.4:deploy-file',
                '-DrepositoryId=github', '-Durl=' + url, '-DgeneratePom=false',
                '-Dfile=' + str(base) + ('.pom' if parent else '.jar'), '-DpomFile=' + str(base) + '.pom',
                '-Dfiles=' + ','.join(signatures), '-Dtypes=' + ','.join(types),
                '-Dclassifiers=' + ','.join(classifiers)]
    if not parent:
        command += ['-Dsources=' + str(base) + '-sources.jar', '-Djavadoc=' + str(base) + '-javadoc.jar']
    return command


def publish(directory, version):
    actor, token = os.environ.get('GITHUB_ACTOR'), os.environ.get('GITHUB_TOKEN')
    require(actor and token, 'GITHUB_ACTOR and GITHUB_TOKEN are required for publishing')
    authorization = 'Basic ' + base64.b64encode(f'{actor}:{token}'.encode()).decode()
    for artifact in inventory(directory, version, authorization):
        subprocess.run(deploy_command(directory, artifact, version), cwd=directory, check=True)
    require(not inventory(directory, version, authorization), 'GitHub publication is incomplete')
    print(f'Verified all six GitHub packages and all 42 artifact/signature files for {version}', flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--tag', required=True)
    parser.add_argument('--fingerprint', required=True)
    parser.add_argument('--gpg', default='gpg')
    parser.add_argument('--publish', action='store_true', help='Upload to GitHub; default only validates Central files')
    args = parser.parse_args()
    version = release_version(args.tag)
    require(re.fullmatch(r'[A-Fa-f0-9]{40}', args.fingerprint), 'Expected a full signing-key fingerprint')
    with tempfile.TemporaryDirectory(prefix='docrag-github-') as temp:
        directory = Path(temp)
        prepare(version, directory, args.fingerprint, args.gpg)
        if args.publish:
            publish(directory, version)


if __name__ == '__main__':
    main()
