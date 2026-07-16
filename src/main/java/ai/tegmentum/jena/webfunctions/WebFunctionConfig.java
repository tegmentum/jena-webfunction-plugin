package ai.tegmentum.jena.webfunctions;

import ai.tegmentum.webassembly4j.api.config.ResourceLimits;
import ai.tegmentum.webassembly4j.api.config.WebAssemblyConfig;
import ai.tegmentum.webassembly4j.api.WebAssemblyBuilder;
import ai.tegmentum.webassembly4j.provider.wasmtime.config.WasmtimeConfig;

import java.util.Optional;
import java.util.OptionalLong;

/**
 * System-property configuration for the Jena webfunction plugin. Every
 * property is optional; unset means "provider default" or "no cap".
 *
 * <p>Component-model-only in v0 — no core-module ABI fallback.
 */
public final class WebFunctionConfig {

    public static final String PROP_ENGINE_PROVIDER   = "webfunctions.engine.provider";
    public static final String PROP_ENGINE_ID         = "webfunctions.engine.id";
    public static final String PROP_FUEL_LIMIT        = "webfunctions.fuel.limit";
    public static final String PROP_MAX_MEMORY_BYTES  = "webfunctions.memory.max.bytes";
    public static final String PROP_TIMEOUT_MILLIS    = "webfunctions.timeout.millis";
    public static final String PROP_MAX_EXEC_MILLIS   = "webfunctions.exec.max.millis";
    public static final String PROP_MAX_INSTANCES     = "webfunctions.max.instances";
    public static final String PROP_MAX_TABLE_ELEMS   = "webfunctions.table.max.elements";

    // v0.3.0 host-callback config.
    public static final String PROP_CALLBACK_MAX_DEPTH = "webfunctions.callback.max.depth";
    public static final String PROP_CALLBACK_MAX_ROWS  = "webfunctions.callback.max.rows";
    public static final String PROP_CALLBACK_ENABLED   = "webfunctions.callback.enabled";
    public static final String PROP_WASI_NN_ENABLED    = "webfunctions.wasinn.enabled";

    public static final int DEFAULT_CALLBACK_MAX_DEPTH = 100;
    public static final int DEFAULT_CALLBACK_MAX_ROWS  = 100_000;

    public static final String DEFAULT_ENGINE_PROVIDER = "wasmtime";

    private WebFunctionConfig() {}

    public static String engineProvider() {
        final String raw = System.getProperty(PROP_ENGINE_PROVIDER);
        return (raw == null || raw.isEmpty()) ? DEFAULT_ENGINE_PROVIDER : raw.trim();
    }

    public static Optional<String> engineId() {
        final String raw = System.getProperty(PROP_ENGINE_ID);
        return (raw == null || raw.isEmpty()) ? Optional.empty() : Optional.of(raw.trim());
    }

    public static WebAssemblyConfig fromSystemProperties() {
        final ai.tegmentum.webassembly4j.api.config.WebAssemblyConfigBuilder builder =
                WebAssemblyConfig.builder()
                        .resourceLimits(resourceLimitsFromSystemProperties())
                        .fuelLimit(getLong(PROP_FUEL_LIMIT).orElse(0L))
                        .timeoutMillis(getLong(PROP_TIMEOUT_MILLIS).orElse(0L));

        engineId().ifPresent(builder::engine);

        if ("wasmtime".equalsIgnoreCase(engineProvider())) {
            builder.engineConfig(WasmtimeConfig.builder()
                    // Component mode always on for the Jena binding.
                    .wasmComponentModel(true)
                    .build());
        }
        return builder.build();
    }

    static ResourceLimits resourceLimitsFromSystemProperties() {
        final OptionalLong maxMemory   = getLong(PROP_MAX_MEMORY_BYTES);
        final OptionalLong maxExecMs   = getLong(PROP_MAX_EXEC_MILLIS);
        final OptionalLong maxInst     = getLong(PROP_MAX_INSTANCES);
        final OptionalLong maxTableEls = getLong(PROP_MAX_TABLE_ELEMS);

        return new ResourceLimits() {
            @Override public OptionalLong maxMemoryBytes()         { return maxMemory; }
            @Override public OptionalLong maxTableElements()       { return maxTableEls; }
            @Override public OptionalLong maxInstances()           { return maxInst; }
            @Override public OptionalLong maxExecutionTimeMillis() { return maxExecMs; }
        };
    }

    public static int callbackMaxDepth() {
        return (int) getLong(PROP_CALLBACK_MAX_DEPTH).orElse(DEFAULT_CALLBACK_MAX_DEPTH);
    }

    public static int callbackMaxRows() {
        return (int) getLong(PROP_CALLBACK_MAX_ROWS).orElse(DEFAULT_CALLBACK_MAX_ROWS);
    }

    public static boolean callbackEnabled() {
        final String raw = System.getProperty(PROP_CALLBACK_ENABLED);
        return raw == null || raw.isEmpty() || Boolean.parseBoolean(raw.trim());
    }

    /**
     * Opt-in gate for wiring wasi:nn on the linker via
     * {@link ai.tegmentum.webassembly4j.api.DefaultLinkingContext.Builder#enableWasiNn}.
     * Off by default: wasmtime4j-native 46.0.1-1.4.1 exposes the JNI
     * shim (JniComponentLinker#nativeEnableWasiNn), but the shipped
     * wasmtime native library is not yet built with the cargo
     * {@code wasi-nn} feature, so an unconditional call errors every
     * component instantiate with "WASI-NN support not compiled in".
     * Guests that don't import wasi:nn are unaffected either way;
     * flipping this to true is only useful once a wasi-nn-enabled
     * wasmtime4j-native ships (tracked upstream).
     */
    public static boolean wasiNnEnabled() {
        final String raw = System.getProperty(PROP_WASI_NN_ENABLED);
        return raw != null && !raw.isEmpty() && Boolean.parseBoolean(raw.trim());
    }

    /**
     * Convenience: build a fully-configured Engine.
     */
    public static ai.tegmentum.webassembly4j.api.Engine buildEngine() {
        final WebAssemblyBuilder eb = ai.tegmentum.webassembly4j.api.WebAssembly.builder()
                .provider(engineProvider())
                .config(fromSystemProperties());
        engineId().ifPresent(eb::engine);
        return eb.build();
    }

    private static OptionalLong getLong(final String key) {
        final String raw = System.getProperty(key);
        if (raw == null || raw.isEmpty()) return OptionalLong.empty();
        try {
            return OptionalLong.of(Long.parseLong(raw.trim()));
        } catch (NumberFormatException e) {
            return OptionalLong.empty();
        }
    }
}
