package dev.freshgenerator;

import net.fabricmc.api.ModInitializer;

/** Uses Minecraft's real registry-driven generation pipeline. No external generator mod. */
public final class FreshGenerator implements ModInitializer {
    @Override public void onInitialize() {
        System.out.println("[FreshGenerator] Generation core loaded. Existing world types are not replaced.");
    }
}
