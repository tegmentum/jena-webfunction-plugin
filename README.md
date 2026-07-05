# jena-webfunction-plugin

Apache Jena binding for the WebAssembly Component Model. Registers ARQ filter
functions under `http://tegmentum.ai/ns/webfunction/` that load a WASM
component from a URL and invoke its `evaluate` export.

Component runtime: [webassembly4j](https://github.com/tegmentum/webassembly4j)
(wasmtime provider). Component ABI shared with the Stardog binding — the WIT
world at `src/main/wit/webfunction.wit` is package `stardog:webfunction@0.2.0`
and is loaded verbatim from the Stardog plugin for cross-framework
component reuse.

## Usage

Auto-registered via Jena's `JenaSubsystemLifecycle` SPI on classpath. Then in
SPARQL:

```sparql
PREFIX wf: <http://tegmentum.ai/ns/webfunction/>
SELECT ?result WHERE {
  BIND(wf:call(<file:/path/to/component.wasm>, "stardog") AS ?result)
}
```

## Config (system properties)

- `webfunctions.engine.provider` (default `wasmtime`)
- `webfunctions.engine.id`
- `webfunctions.fuel.limit`
- `webfunctions.memory.max.bytes`
- `webfunctions.timeout.millis`
- `webfunctions.exec.max.millis`
- `webfunctions.max.instances`
- `webfunctions.table.max.elements`

