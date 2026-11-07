package net.fit.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fit.client.gui.FitScreen;
import net.fit.client.module.Module;
import net.fit.client.module.ModuleManager;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;

public final class FitClient implements ClientModInitializer {
    public static final String NAME = "Fit Client";
    private static KeyMapping openMenu;

    @Override
    public void onInitializeClient() {
        ModuleManager.init();

        openMenu = KeyBindingHelper.registerKeyBinding(
                new KeyMapping("key.fitclient.gui", GLFW.GLFW_KEY_NUM_LOCK, KeyMapping.Category.MISC));

        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            while (openMenu.consumeClick()) {
                if (mc.screen == null) mc.setScreen(new FitScreen());
            }
            for (Module module : ModuleManager.all()) {
                if (module.isEnabled()) module.tick(mc);
            }
        });

        ClientChunkEvents.CHUNK_LOAD.register((level, chunk) -> ModuleManager.susChunkFinder.onChunkLoad(level, chunk));
        ClientChunkEvents.CHUNK_UNLOAD.register((level, chunk) -> ModuleManager.susChunkFinder.onChunkUnload(level, chunk));

        HudRenderCallback.EVENT.register((graphics, tickCounter) -> {
            Minecraft mc = Minecraft.getInstance();
            for (Module module : ModuleManager.all()) {
                if (module.isEnabled()) module.renderHud(graphics, mc);
            }
        });
    }
}
