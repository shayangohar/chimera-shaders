package net.chimera.mixin;

import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.ShaderInstance;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Optional;
import java.util.function.Supplier;

@Mixin(RenderStateShard.ShaderStateShard.class)
public interface ChimeraShaderStateShardAccessor {
    @Accessor("shader")
    Optional<Supplier<ShaderInstance>> chimera$shader();
}
