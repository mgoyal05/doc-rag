#!/usr/bin/env python3
"""Validate distributable sources; optionally create a reproducible, checksummed ZIP. Python 3.10+."""
import argparse
import hashlib
import json
import re
from pathlib import Path
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parents[1]
EXCLUDED = {'.git', '.idea', 'target', 'node_modules', 'data', 'research-data', 'research-demo', 'dist', '__pycache__'}
REQUIRED = ['LICENSE', 'NOTICE', '.env.example', '.gitattributes', 'mvnw', 'mvnw.cmd',
            '.mvn/wrapper/maven-wrapper.properties', 'core/src/main/resources/db/research/V1__research.sql',
            'core/src/main/java/docrag/SecureDownloads.java',
            'spring-boot/src/main/java/docrag/spring/ResearchProperties.java',
            'scripts/build.ps1', 'scripts/run-example.ps1', 'docs/mermaid-config.json',
            'examples/spring-boot/src/main/resources/templates/index.html']


def sources():
    return sorted(p for p in ROOT.rglob('*') if p.is_file()
                  and not any(part in EXCLUDED for part in p.relative_to(ROOT).parts)
                  and (not p.name.startswith('.env') or p.name == '.env.example')
                  and p.suffix not in {'.zip', '.log', '.ndjson', '.iml', '.pyc'}
                  and not p.name.endswith(('.zip.sha256', '.db', '.sqlite', '.binlog')))


def validate(files):
    for name in REQUIRED:
        assert (ROOT / name).is_file() and (ROOT / name).stat().st_size > 0, f'Missing/empty: {name}'
    for p in files:
        assert p.stat().st_size > 0 or p.name == '.gitkeep', f'Unexpected empty file: {p}'
        if p.suffix == '.xml':
            ET.parse(p)
        if p.suffix == '.json':
            json.loads(p.read_text(encoding='utf-8-sig'))
        if p.suffix == '.java':
            text = p.read_text(encoding='utf-8')
            assert 'dev.research' not in text and 'io.github.mgoyal05' not in text, f'Incorrect Java namespace: {p}'
            package = re.search(r'^package\s+([\w.]+);', text, re.M)
            assert package and p.parent.as_posix().endswith(package[1].replace('.', '/')), f'Package/path mismatch: {p}'
        if p.name == 'pom.xml':
            text = p.read_text()
            assert 'dev.research' not in text and '0.1.0-SNAPSHOT' not in text and 'document-research' not in text, p
        if p.suffix == '.md':
            text = p.read_text(encoding='utf-8')
            for target in re.findall(r'!?\[[^\]]*\]\(([^)]+)\)', text):
                target, _, anchor = target.partition('#')
                target = target.strip('<>')
                if target and '://' not in target and not target.startswith('mailto:'):
                    path = (p.parent / target).resolve()
                    assert path.is_relative_to(ROOT) and path.exists(), f'Broken local link: {p}: {target}'
                    if anchor and path.suffix == '.md':
                        headings = re.findall(r'^#+\s+(.+)$', path.read_text(encoding='utf-8'), re.M)
                        slugs = [re.sub(r'[^\w\- ]', '', h.lower()).replace(' ', '-') for h in headings]
                        assert anchor in slugs, f'Broken heading link: {p}: {target}#{anchor}'
    assert len(re.findall(r'^```mermaid$', (ROOT / 'README.md').read_text(encoding='utf-8'), re.M)) == 2, 'Keep exactly two README diagrams'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, help='Create ZIP only after running the documented build and release checks')
    args = parser.parse_args()
    files = sources()
    validate(files)
    print(f'Validated {len(files)} nonempty source/configuration/documentation files and local documentation links')
    if args.output:
        output = args.output.resolve()
        assert not output.exists(), 'Refusing to overwrite an existing archive; use a new output path'
        output.parent.mkdir(parents=True, exist_ok=True)
        prefix = 'doc-rag-0.1.0/'
        digests = {}
        with zipfile.ZipFile(output, 'w', compression=zipfile.ZIP_DEFLATED) as archive:
            for p in files:
                name = prefix + p.relative_to(ROOT).as_posix()
                info = zipfile.ZipInfo(name, (2026, 10, 20, 0, 0, 0))
                info.compress_type = zipfile.ZIP_DEFLATED
                info.external_attr = (0o100755 if p.name == 'mvnw' or p.suffix == '.sh' else 0o100644) << 16
                content = p.read_bytes()
                digests[name] = hashlib.sha256(content).hexdigest()
                archive.writestr(info, content)
        with zipfile.ZipFile(output) as archive:
            assert archive.testzip() is None, 'ZIP CRC failure'
            assert set(archive.namelist()) == set(digests)
            for name, digest in digests.items():
                assert hashlib.sha256(archive.read(name)).hexdigest() == digest, name
        checksum = hashlib.sha256(output.read_bytes()).hexdigest()
        output.with_suffix(output.suffix + '.sha256').write_text(f'{checksum}  {output.name}\n', encoding='ascii')
        print(f'ZIP CRC and all {len(digests)} content hashes verified: {output}\nSHA-256: {checksum}')


if __name__ == '__main__':
    main()
