package net.chimera.render.shader;

import net.chimera.shaderpack.PackProbe;
import net.chimera.shaderpack.PackRuntimeSettings;
import net.chimera.shaderpack.UniformRegistry;
import net.vulkanmod.vulkan.util.MappedBuffer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * RenderPearl's water reads waveState in every variant but declares it only with WAVES on. Installing a
 * session resolves the bindings again, so switching WAVES on serves the declared value in place of the
 * unset zero, and closing a session frees them.
 */
final class SessionUniformBindingTest {
    @Test
    void aNameAVariantDeclaresIsServedItsValue(@TempDir Path root) throws Exception {
        Path shaders = Files.createDirectories(root.resolve("shaders"));
        Files.writeString(shaders.resolve("shaders.properties"),
                "uniform.vec3.waveState=vec3(cameraPosition.x, cameraPosition.z, frameTimeCounter)\n");
        Files.writeString(shaders.resolve("composite.vsh"), """
                #version 120
                varying vec2 uv;
                void main() { uv = gl_MultiTexCoord0.xy; gl_Position = ftransform(); }
                """);
        Files.writeString(shaders.resolve("composite.fsh"), """
                #version 120
                uniform vec3 waveState;
                varying vec2 uv;
                void main() { gl_FragData[0] = vec4(waveState, uv.x); }
                """);
        PackRuntimeSettings waves = PackProbe.analyze(root).plan().runtimeSettings();
        PackUniformProvider provider = PackUniformProvider.shared();
        try {
            PackUniformProvider.installRuntimeSettings(null);
            MappedBuffer unset = provider.supplier("waveState", "vec3").get();
            assertEquals("unset:waveState", provider.boundDescriptor("waveState", "vec3").sourceKey(),
                    "an undeclared name is served Iris's unset zero");

            PackUniformProvider.installRuntimeSettings(waves);
            UniformRegistry.UniformDescriptor served = provider.boundDescriptor("waveState", "vec3");
            assertEquals("custom:waveState", served.sourceKey(), "the variant's own value is served");
            assertSame(unset, provider.supplier("waveState", "vec3").get(),
                    "a pipeline built earlier keeps reading the same buffer");

            PackUniformProvider.resetSession();
            assertNull(provider.boundDescriptor("waveState", "vec3"),
                    "a closed session frees its bindings with the pipelines that read them");
            provider.supplier("waveState", "vec3");
            assertEquals("unset:waveState", provider.boundDescriptor("waveState", "vec3").sourceKey(),
                    "a session without the declaration serves zero again");
        } finally {
            PackUniformProvider.resetSession();
        }
    }

    /**
     * Iris 1.21.1 never registers its HardcodedCustomUniforms, so a pack that only declares
     * blindFactor or velocity reads GL's unset zero, never a frame value of the same name.
     */
    @Test
    void anUnsetDeclarationNamesNoFrameValue() {
        for (String name : new String[] {"blindFactor", "velocity"}) {
            UniformRegistry.UniformDescriptor unset = UniformRegistry.resolve(name, "float", Map.of());
            assertEquals("unset:" + name, unset.sourceKey(), name);
            MappedBuffer buffer = new MappedBuffer(4);
            buffer.putFloat(0, 1.0f);
            new PackFrameState().write(unset, "float", buffer);
            assertEquals(0.0f, buffer.getFloat(0), name + " reads zero");
        }
    }
}
