package dev.freshgenerator;

import com.google.gson.Gson;
import dev.freshgenerator.pack.*;
import java.util.*;
import java.util.jar.JarFile;
import java.lang.reflect.*;
import java.nio.file.*;

/** Read the actual resolved game API. Never shipped in the game mod. */
public final class ApiProbe {
    public static void main(String[] args) throws Exception {
        var vanilla=new VanillaAssets(); var generated=WorldgenCatalog.compile(vanilla);var gson=new Gson();
        for(var e:generated.entrySet()) if(e.getKey().contains("/highlands_v2/") &&
                (e.getKey().contains("material_condition") || e.getKey().contains("material_rule")))
            System.out.println("MATERIAL_RESOURCE "+e.getKey()+" "+gson.toJson(e.getValue()));
        for(String path:List.of("data/minecraft/worldgen/noise_settings/overworld.json",
                "data/minecraft/worldgen/feature/freeze_top_layer.json",
                "data/minecraft/worldgen/placed_feature/freeze_top_layer.json"))
            if(vanilla.exists(path))System.out.println("NATIVE_RESOURCE "+path+" "+gson.toJson(vanilla.read(path)));
        Class<?> generator=Class.forName("net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator",false,ApiProbe.class.getClassLoader());
        Path jar=Path.of(generator.getProtectionDomain().getCodeSource().getLocation().toURI());
        List<String> types=new ArrayList<>(List.of("net.minecraft.core.registries.Registries",
            "net.minecraft.world.level.levelgen.densityfunction.DensitySampler",
            "net.minecraft.world.level.levelgen.densityfunction.SamplerContext",
            "net.minecraft.world.level.levelgen.RandomState",
            "net.minecraft.world.level.WorldGenLevel","net.minecraft.server.level.WorldGenRegion",
            "net.minecraft.world.level.levelgen.feature.Feature","net.minecraft.world.level.levelgen.feature.FeaturePlaceContext"));
        try(var z=new JarFile(jar.toFile())) {
            for(var e:Collections.list(z.entries())) {
                String p=e.getName();if(!p.endsWith(".class"))continue;
                if(p.startsWith("net/minecraft/world/level/levelgen/") &&
                        (p.contains("material") || p.contains("Snow") || p.contains("Freeze") || p.contains("SurfaceRules"))) {
                    String n=p.substring(0,p.length()-6).replace('/','.');
                    System.out.println("NATIVE_CLASS "+n);types.add(n);
                }
            }
        }
        for(String name:types)try{
            Class<?> t=Class.forName(name,false,ApiProbe.class.getClassLoader());
            System.out.println("API_PROBE "+name+" interfaces="+Arrays.toString(t.getInterfaces()));
            Arrays.stream(t.getDeclaredConstructors()).forEach(x->System.out.println(x.toGenericString()));
            Arrays.stream(t.getDeclaredMethods()).filter(m->!m.isSynthetic()).forEach(m->System.out.println(m.toGenericString()));
            Arrays.stream(t.getDeclaredFields()).filter(f->Modifier.isPublic(f.getModifiers()) || !name.endsWith("Registries"))
                .filter(f->!name.endsWith("Registries") || f.getName().contains("MATERIAL")||f.getName().contains("FEATURE"))
                .forEach(f->System.out.println(f.toGenericString()));
        }catch(ClassNotFoundException e){System.out.println("MISSING_CLASS "+name);}
    }
}
