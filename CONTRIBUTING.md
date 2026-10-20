# Contributing

Contributions are licensed under [Apache License 2.0](LICENSE). Never commit private reports, tokens, credentials,
database dumps, runtime indexes or conversation histories.

Run the reactor with `./mvnw clean verify` (`./mvnw.cmd` on Windows). Use isolated PostgreSQL with pgvector for SQL
tests and a local stub server for browser/provider tests. Supply RESEARCH_TEST_* values explicitly; never use production
application configuration for verification. Keep resource usage bounded and record changes to schema/model profiles.

For a new tool, implement `Research.Tool`, supply a precise description and argument contract, register selection
keywords, and provide a trusted read-only adapter. Include missing/ambiguous-source cases, scope violations, malicious
document/tool instructions, units/basis mismatches and expected citations in evaluations. Financial arithmetic belongs
in Java. A tool must not turn model strings into arbitrary SQL, filesystem paths or fetched URLs. Event tools such as
mergers/QIPs require deliberate scope-validator changes, sourced facts and reviewed domain questions.

Changes should include an API example, relevant tests, an operations-guide update for material tradeoffs and an honest
verification note. Distinguish implementation, mocked transport checks, real-model quality and production measurements.
Maintain exactly two Mermaid diagrams in the README and render-check them after edits.

## Regenerate diagrams

The SVG exports are generated from the two Mermaid blocks in the README using Mermaid CLI 12.0.0 and
[`docs/mermaid-config.json`](docs/mermaid-config.json). They use native SVG text rather than HTML `foreignObject`
labels,
so class names and labels remain visible in SVG viewers that do not support embedded HTML. Render both exports whenever
the source blocks change. For documentation contributors with Python 3, Node.js and Mermaid CLI available:

```sh
python -c "import pathlib,re; p=pathlib.Path('.'); out=p/'target/diagram-source'; out.mkdir(parents=True,exist_ok=True); fence=chr(96)*3; blocks=re.findall(fence+r'mermaid\n(.*?)'+fence, (p/'README.md').read_text(encoding='utf-8'), re.S); assert len(blocks)==2; [(out/(name+'.mmd')).write_text(text,encoding='utf-8') for name,text in zip(['architecture','user-flow'],blocks)]"
npx --yes --package=@mermaid-js/mermaid-cli@12.0.0 mmdc -i target/diagram-source/architecture.mmd -o docs/architecture.svg -c docs/mermaid-config.json -b white
npx --yes --package=@mermaid-js/mermaid-cli@12.0.0 mmdc -i target/diagram-source/user-flow.mmd -o docs/user-flow.svg -c docs/mermaid-config.json -b white
```
