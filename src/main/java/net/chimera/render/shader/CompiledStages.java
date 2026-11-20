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
 * The session's compiled SPIR-V, keyed by shader kind, pack treatment and source SHA-256, so a stored module is
 * never stale. Least recently used modules go once the store passes its byte budget.
 */
public final class CompiledStages {
    /** About three of Complementary's dimension variants. */
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

    static byte[] module(int kind, boolean packModule, String source, Supplier<byte[]> compile) {
        return SESSION.moduleOf(kind, packModule, source, compile);
    }

    /** Reports the load's compiled and reused counts and resets them. */
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
