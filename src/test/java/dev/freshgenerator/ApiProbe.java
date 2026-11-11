package dev.freshgenerator;
import java.io.*;
import java.lang.reflect.*;
import java.util.*;
public final class ApiProbe {
    public static void main(String[] args) throws Exception {
        for (String name : List.of("net.minecraft.world.level.levelgen.DensityFunction", "net.minecraft.world.level.levelgen.RandomState", "net.minecraft.world.level.levelgen.NoiseRouter", "net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator", "net.minecraft.server.MinecraftServer", "net.minecraft.server.level.ServerLevel")) {
            try {
                Class<?> type = Class.forName(name, false, ApiProbe.class.getClassLoader());
                System.out.println("API_PROBE " + name);
                for (Method m : type.getDeclaredMethods()) if (Modifier.isPublic(m.getModifiers())) System.out.println(m.toGenericString());
            } catch (Throwable error) { System.out.println("PROBE_ERROR " + name + " " + error); }
        }
        for (String name : List.of("version.json", "data/minecraft/worldgen/density_function/overworld/final_density.json", "data/minecraft/worldgen/density_function/overworld/chunk_surface_level.json", "data/minecraft/worldgen/density_function/overworld/preliminary_surface_level.json", "data/minecraft/worldgen/material_rule/overworld.json")) {
            try (InputStream in = ApiProbe.class.getResourceAsStream("/" + name)) {
                System.out.println("RESOURCE_PROBE " + name + " " + (in == null ? "MISSING" : new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)));
            }
        }
    }
}
