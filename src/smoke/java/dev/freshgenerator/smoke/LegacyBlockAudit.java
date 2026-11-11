package dev.freshgenerator.smoke;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.levelgen.*;
import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.zip.GZIPOutputStream;

/** Test-only observer, copied unchanged into the historical source checkout for an A/B audit. */
public final class LegacyBlockAudit implements ModInitializer {
    @Override public void onInitialize() {
        if (!Boolean.getBoolean("freshgenerator.integration") || !"true".equals(System.getenv("FG_LEGACY_AUDIT"))) return;
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            try { write(server); }
            catch (Exception error) { throw new IllegalStateException("Legacy A/B audit failed",error); }
        });
    }
    private static void write(MinecraftServer server) throws Exception {
        var level=server.overworld();
        var generator=(NoiseBasedChunkGenerator)level.getChunkSource().getGenerator();
        var state=RandomState.create(server.registryAccess().lookupOrThrow(Registries.NOISE),level.getSeed(),generator.generatorSettings().value());
        int[][] chunks={{0,0},{1,0},{-1,0},{0,1},{0,-1}};
        // Complete the same neighboring feature work before observing either build.
        // Do not compare one target while the next target's decorations are still being generated.
        var surrounding=new LinkedHashSet<Long>();
        for(int[] pair:chunks) for(int dx=-1;dx<=1;dx++) for(int dz=-1;dz<=1;dz++)
            surrounding.add(((long)(pair[0]+dx)<<32)|((pair[1]+dz)&0xffffffffL));
        for(long key:surrounding) level.getChunk((int)(key>>32),(int)key);
        var heightDigest=MessageDigest.getInstance("SHA-256");
        var blockDigest=MessageDigest.getInstance("SHA-256");
        ByteBuffer bytes=ByteBuffer.allocate(4);
        BlockPos.MutableBlockPos pos=new BlockPos.MutableBlockPos();
        int count=0;
        try(var out=new BufferedWriter(new OutputStreamWriter(new GZIPOutputStream(Files.newOutputStream(
                Path.of("freshgenerator-legacy-blocks.txt.gz"))),StandardCharsets.UTF_8))) {
            for(int[] pair:chunks) {
                var chunk=level.getChunk(pair[0],pair[1]);
                for(int x=0;x<16;x++)for(int z=0;z<16;z++) {
                    int wx=pair[0]*16+x,wz=pair[1]*16+z;
                    int h=generator.getBaseHeight(wx,wz,Heightmap.Types.WORLD_SURFACE_WG,level,state);
                    bytes.clear();bytes.putInt(h);heightDigest.update(bytes.array());
                    for(int y=-64;y<320;y++) {
                        pos.set(wx,y,wz);String text=chunk.getBlockState(pos).toString()+"\n";
                        out.write(text);blockDigest.update(text.getBytes(StandardCharsets.UTF_8));count++;
                    }
                }
            }
        }
        JsonObject r=new JsonObject();r.addProperty("seed",level.getSeed());
        r.addProperty("settings",generator.generatorSettings().unwrapKey().orElseThrow().toString());
        r.addProperty("preloaded_full_chunks",surrounding.size());
        r.addProperty("base_height_columns",1280);r.addProperty("full_blocks",count);
        r.addProperty("base_height_sha256",HexFormat.of().formatHex(heightDigest.digest()));
        r.addProperty("block_sha256",HexFormat.of().formatHex(blockDigest.digest()));
        Files.writeString(Path.of("freshgenerator-legacy-audit.json"),new GsonBuilder().setPrettyPrinting().create().toJson(r));
        System.out.println("FRESHGENERATOR_LEGACY_AUDIT "+r);
    }
}
