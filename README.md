# jena-webfunction-plugin

Apache Jena binding for the WebAssembly Component Model. Registers ARQ filter
functions under `http://tegmentum.ai/ns/webfunction/` that load a WASM
component from a URL and invoke its `evaluate` export.

Part of a three-binding family that all share one component ABI:

| Binding | Repo |
|---|---|
| Stardog | [tegmentum/stardog-webfunction-plugin](https://github.com/tegmentum/stardog-webfunction-plugin) |
| Apache Jena | you are here |
| Eclipse RDF4J | [tegmentum/rdf4j-webfunction-plugin](https://github.com/tegmentum/rdf4j-webfunction-plugin) |

The WIT world at `src/main/wit/webfunction.wit` (package `stardog:webfunction@0.2.0`)
is byte-for-byte identical across the three repos, so a single Rust component
runs unmodified under any of the three SPARQL engines. WASM runtime is
[webassembly4j](https://github.com/tegmentum/webassembly4j) (wasmtime provider).

Component runtime: [webassembly4j](https://github.com/tegmentum/webassembly4j)
(wasmtime provider). Component ABI shared with the Stardog binding — the WIT
world at `src/main/wit/webfunction.wit` is package `stardog:webfunction@0.2.0`
and is loaded verbatim from the Stardog plugin for cross-framework
component reuse.

## SPARQL surfaces

The wf:call function is exposed through four different SPARQL surfaces; all
back onto the same component's `evaluate` / `aggregate-step` /
`aggregate-finish` exports.

| Shape | Syntax | When to reach for it |
|---|---|---|
| Filter | `BIND(wf:call(<url>, args...) AS ?x)` | one value out of one wasm call |
| Aggregate | `SELECT (<wf:call-agg>(<url>, ?v) AS ?sum)` | reduce query rows to one value |
| Property | `?x wf:call (<url> args...)` | multi-row output, single subject variable |
| SERVICE | `SERVICE <url> { BIND(...) }` | multi-row, multi-var output |

## Usage

Auto-registered via Jena's `JenaSubsystemLifecycle` SPI on classpath. Then in
SPARQL:

```sparql
PREFIX wf: <http://tegmentum.ai/ns/webfunction/>
SELECT ?result WHERE {
  BIND(wf:call(<file:/path/to/component.wasm>, "stardog") AS ?result)
}
```

## Performance

- Shared static `Engine` built once from `WebFunctionConfig` on first `wf:call`.
- `ConcurrentHashMap<URL, Component>` caches compiled components per URL. Repeat
  calls to the same wasm skip download + compile; only the per-call
  `ComponentInstance` is fresh.
- Cost: `webfunctions.*` system properties are read once at first use — changing
  them mid-run has no effect. Test-only `JenaWasmInstance.resetCache()` drops
  shared state for isolation.
- Bench (Darwin aarch64, `to_upper` component, warm cache):
  - `evaluate`: ~24 µs/op (42k ops/s)
  - `instantiate`: ~513 µs/op

## Config (system properties)

- `webfunctions.engine.provider` (default `wasmtime`)
- `webfunctions.engine.id`
- `webfunctions.fuel.limit`
- `webfunctions.memory.max.bytes`
- `webfunctions.timeout.millis`
- `webfunctions.exec.max.millis`
- `webfunctions.max.instances`
- `webfunctions.table.max.elements`

