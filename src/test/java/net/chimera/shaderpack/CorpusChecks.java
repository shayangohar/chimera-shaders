// SPDX-License-Identifier: LGPL-3.0-only

package net.chimera.shaderpack;

import net.chimera.render.FlipSidesCorpus;
import net.chimera.testing.TestPacks;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Corpus pack checks: each pack is analysed once and goes through every per-pack check, the compile check
 * (PackCompileTest) last since it may change the report. Gradle forks a JVM per test class, so keep one
 * CorpusShardNTest class per {@link #SHARDS} shard.
 */
final class CorpusChecks {
    static final int SHARDS = 8;

    private CorpusChecks() {}

    static void run(int shard) throws Exception {
        List<Path> packs = TestPacks.shard(TestPacks.corpus(), shard, SHARDS);
        Path baselinePath = PackCompileTest.baselinePath();
        TreeMap<String, TreeSet<String>> baseline = PackCompileTest.readBaseline(baselinePath);
        Map<String, TreeSet<String>> recorded = new TreeMap<>();
        List<String> failures = new ArrayList<>();
        int paths = 0;
        int steps = 0;
        for (Path pack : packs) {
            PackProbe.Analysis analysis = PackProbe.analyze(pack);
            if (analysis.plan() == null) {
                failures.add(pack.getFileName() + ": no plan");
                continue;
            }
            GlslTreeCorpusTest.checkPack(pack, analysis, failures);
            paths += ClipDepthTest.checkPack(pack, analysis, failures);
            steps += FlipSidesCorpus.check(pack, analysis, failures);
            PackCompileTest.checkPack(pack, analysis, baseline, recorded, failures);
        }
        if (Boolean.getBoolean("chimera.packCompile.updateAdmission") && !recorded.isEmpty()) {
            PackCompileTest.writeBaseline(baselinePath, recorded);
        }
        System.out.printf("[chimera] corpus shard %d of %d: %d packs, %d rasterizer paths, %d flip steps %s%n",
                shard, SHARDS, packs.size(), paths, steps, packs.stream().map(Path::getFileName).toList());
        assertTrue(failures.isEmpty(), "corpus gate failed:\n  " + String.join("\n  ", failures));
    }
}

@Tag("packs")
final class CorpusShard0Test {
    @Test
    void checkCorpusPacks() throws Exception {
        CorpusChecks.run(0);
    }
}

@Tag("packs")
final class CorpusShard1Test {
    @Test
    void checkCorpusPacks() throws Exception {
        CorpusChecks.run(1);
    }
}

@Tag("packs")
final class CorpusShard2Test {
    @Test
    void checkCorpusPacks() throws Exception {
        CorpusChecks.run(2);
    }
}

@Tag("packs")
final class CorpusShard3Test {
    @Test
    void checkCorpusPacks() throws Exception {
        CorpusChecks.run(3);
    }
}

@Tag("packs")
final class CorpusShard4Test {
    @Test
    void checkCorpusPacks() throws Exception {
        CorpusChecks.run(4);
    }
}

@Tag("packs")
final class CorpusShard5Test {
    @Test
    void checkCorpusPacks() throws Exception {
        CorpusChecks.run(5);
    }
}

@Tag("packs")
final class CorpusShard6Test {
    @Test
    void checkCorpusPacks() throws Exception {
        CorpusChecks.run(6);
    }
}

@Tag("packs")
final class CorpusShard7Test {
    @Test
    void checkCorpusPacks() throws Exception {
        CorpusChecks.run(7);
    }
}
