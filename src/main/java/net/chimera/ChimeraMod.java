package net.chimera;

import net.chimera.command.ChimeraCommands;
import net.chimera.gui.ChimeraKeybinds;
import net.chimera.gui.ChimeraSettingsPage;
import net.chimera.gui.ShaderPackScreen;
import net.chimera.render.ChimeraMaterialReloadListener;
import net.chimera.render.ChimeraRenderer;
import net.chimera.render.PackFailureNotices;
import net.chimera.shaderpack.PackTemporaryRoots;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.CustomizeGuiOverlayEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mod(value = ChimeraMod.MOD_ID, dist = Dist.CLIENT)
public final class ChimeraMod {
    public static final String MOD_ID = "chimera";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    private static String version = "dev";

    public ChimeraMod(IEventBus modBus, ModContainer container) {
        version = container.getModInfo().getVersion().toString();
        LOGGER.info("[chimera] Chimera {}", version);
        NeoForge.EVENT_BUS.addListener(RegisterClientCommandsEvent.class,
                event -> ChimeraCommands.register(event.getDispatcher()));
        // a pack failure found before the player is in a world waits for one, as Iris's notice does
        NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post.class, event -> PackFailureNotices.flush());
        // F3 lines as Iris places them: version, pack and profile on the right, shadow map on the left
        NeoForge.EVENT_BUS.addListener(CustomizeGuiOverlayEvent.DebugText.class, event -> {
            event.getLeft().addAll(ChimeraRenderer.shadowDebugLines());
            event.getRight().addAll(ChimeraRenderer.debugLines());
        });
        ChimeraKeybinds.register(modBus);
        ChimeraSettingsPage.register();
        PackTemporaryRoots.sweepInBackground();
        modBus.addListener(RegisterClientReloadListenersEvent.class,
                event -> event.registerReloadListener(ChimeraMaterialReloadListener.live()));
        // the mod list's Config button opens the pack selector, as Iris's does
        container.registerExtensionPoint(IConfigScreenFactory.class, (mod, parent) -> new ShaderPackScreen(parent));
    }

    /** The mod version; "dev" until NeoForge constructs the mod. */
    public static String version() {
        return version;
    }
}
