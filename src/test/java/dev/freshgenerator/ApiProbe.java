package dev.freshgenerator;

import com.google.gson.Gson;
import dev.freshgenerator.pack.*;
import java.util.*;

/** Inspect real dependency resources and the emitted graph, never visual guesses. */
public final class ApiProbe {
    public static void main(String[] args) throws Exception {
        var vanilla = new VanillaAssets();
        var generated = WorldgenCatalog.compile(vanilla);
        var gson = new Gson();
        for (String name : List.of("final_density", "preliminary_surface_level", "chunk_surface_level",
                "depth", "offset", "factor", "sloped_cheese", "base_3d_noise", "jaggedness",
                "caves/entrances", "caves/noodle", "caves/pillars")) {
            String path = "data/minecraft/worldgen/density_function/overworld/" + name + ".json";
            if (vanilla.exists(path)) System.out.println("NATIVE_RESOURCE " + name + " " + gson.toJson(vanilla.read(path)));
            String local = "data/freshgenerator/worldgen/density_function/highlands_v2/" + name + ".json";
            if (generated.containsKey(local)) System.out.println("CURRENT_RESOURCE " + name + " " + gson.toJson(generated.get(local)));
        }
        System.out.println("NATIVE_SETTINGS " + gson.toJson(vanilla.read("data/minecraft/worldgen/noise_settings/overworld.json")));
        System.out.println("NATIVE_MATERIAL " + gson.toJson(vanilla.read("data/minecraft/worldgen/material_rule/overworld.json")));
        for (String name : List.of("net.minecraft.world.level.levelgen.densityfunction.DensityFunction",
                "net.minecraft.world.level.levelgen.densityfunction.SamplerContext", "net.minecraft.world.level.NoiseColumn")) {
            Class<?> type = Class.forName(name, false, ApiProbe.class.getClassLoader());
            System.out.println("API_PROBE " + name);
            Arrays.stream(type.getDeclaredMethods()).filter(m -> java.lang.reflect.Modifier.isPublic(m.getModifiers()))
                    .forEach(m -> System.out.println(m.toGenericString()));
        }
    }
}
