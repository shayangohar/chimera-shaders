// SPDX-License-Identifier: LGPL-3.0-only

package net.chimera.render.shader;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

/**
 * The SPIR-V of the stages compiled this session, keyed by shader kind, pack treatment and the source's
 * SHA-256. A module depends on those alone, so a stored one is never stale.
 *
 * A program drawn in several vertex layouts binds the same fragment source in each (Complementary's hand
 * fragment stage appears four times per load), and a dimension the session visited before compiles the same
 * sources again; Iris's TransformPatcher likewise keeps its transformed sources across pipeline rebuilds. The
 * least recently used modules go once the store passes its byte budget.
 */
public final class CompiledStages {
    /** About three of Complementary's dimension variants; each load reports the store's size. */
    static final long SESSION_BUDGET = 64L << 20;
    private static final CompiledStages SESSION = new CompiledStages(SESSION_BUDGET);

    private final long budget;
    private final Map<Key, byte[]> modules = new LinkedHashMap<>(256, 0.75F, true);
    private long bytes;
    private int compiled;
    private int reused;

    private record Key(int kind, boolean packModule, String sourceSha256) {}

    CompiledStages(long budget) {
        this.budget = budget;
    }

    /** The session's module of this stage, compiled by {@code compile} unless it was compiled before. */
    static byte[] module(int kind, boolean packModule, String source, Supplier<byte[]> compile) {
        return SESSION.moduleOf(kind, packModule, source, compile);
    }

    /** Ends a load, reporting how many stages it compiled and reused and what the store holds. */
    public static String endLoad() {
        return SESSION.report();
    }

    byte[] moduleOf(int kind, boolean packModule, String source, Supplier<byte[]> compile) {
        Key key = new Key(kind, packModule, sha256(source));
        synchronized (this) {
            byte[] known = modules.get(key);
            if (known != null) {
                reused++;
                return known;
            }
        }
        byte[] module = compile.get();
        synchronized (this) {
            byte[] replaced = modules.put(key, module);
            bytes += module.length - (replaced == null ? 0 : replaced.length);
            compiled++;
            var eldest = modules.entrySet().iterator();
            while (bytes > budget && eldest.hasNext()) {
                bytes -= eldest.next().getValue().length;
                eldest.remove();
            }
        }
        return module;
    }

    /** The counts since the last report, which resets them, and the store's size. */
    synchronized String report() {
        String counts = String.format(Locale.ROOT, "%d compiled, %d reused; the session keeps %d modules, %.1f MB",
                compiled, reused, modules.size(), bytes / (1024.0 * 1024.0));
        compiled = 0;
        reused = 0;
        return counts;
    }

    private static String sha256(String source) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(source.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException missing) {
            throw new IllegalStateException("SHA-256 is unavailable", missing);
        }
    }
}
