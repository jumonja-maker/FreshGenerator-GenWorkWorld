package dev.freshgenerator.audit;

import com.google.gson.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Test-only snapshot of actual TERRAIN chunks, before features and their RNG-dependent
 * sculk/ore/plant decoration. No exclusions or block normalization are used in this hash.
 * Already decorated spawn chunks are recorded as unavailable, never called terrain snapshots.
 */
public final class CaveTerrainSnapshot {
    @FunctionalInterface public interface Height { float at(int x,int z); }
    private CaveTerrainSnapshot() { }
    public static JsonObject capture(ServerLevel level,String label,int x,int z,int side,Height heights)throws Exception {
        int x0=Math.floorDiv(x,16)*16,z0=Math.floorDiv(z,16)*16;
        JsonArray chunks=new JsonArray();int total=0,available=0,skipped=0;
        for(int cx=Math.floorDiv(x0,16);cx<=Math.floorDiv(x0+side-1,16);cx++)
        for(int cz=Math.floorDiv(z0,16);cz<=Math.floorDiv(z0+side-1,16);cz++) {
            var chunk=level.getChunk(cx,cz,ChunkStatus.TERRAIN);
            if(chunk==null||!chunk.getPersistedStatus().isOrAfter(ChunkStatus.TERRAIN))
                throw new AssertionError("TERRAIN request did not complete: "+cx+","+cz);
            JsonObject row=new JsonObject();row.addProperty("chunk_x",cx);row.addProperty("chunk_z",cz);
            row.addProperty("observed_status",chunk.getPersistedStatus().toString());
            boolean exactStage=chunk.getPersistedStatus()==ChunkStatus.TERRAIN;
            row.addProperty("before_features",exactStage);
            if(!exactStage){skipped++;chunks.add(row);continue;}
            MessageDigest digest=MessageDigest.getInstance("SHA-256");
            Map<BlockState,byte[]> encodings=new IdentityHashMap<>();
            BlockPos.MutableBlockPos pos=new BlockPos.MutableBlockPos();int count=0,air=0,fluid=0;
            for(int dx=0;dx<16;dx+=4)for(int dz=0;dz<16;dz+=4){
                int wx=cx*16+dx,wz=cz*16+dz;
                int top=Math.min(48,(int)Math.floor(heights.at(wx,wz))-64);
                for(int y=-48;y<=top;y++){
                    pos.set(wx,y,wz);var block=chunk.getBlockState(pos);
                    digest.update((wx+","+y+","+wz+"=").getBytes(StandardCharsets.UTF_8));
                    digest.update(encodings.computeIfAbsent(block,s->(s.toString()+"\n").getBytes(StandardCharsets.UTF_8)));
                    count++;if(block.isAir())air++;if(!block.getFluidState().isEmpty())fluid++;
                }
            }
            row.addProperty("samples",count);row.addProperty("air_samples",air);row.addProperty("fluid_samples",fluid);
            row.addProperty("exact_block_state_sha256",HexFormat.of().formatHex(digest.digest()));
            available++;total+=count;chunks.add(row);
        }
        JsonObject out=new JsonObject();out.addProperty("label",label);out.addProperty("x",x0);out.addProperty("z",z0);
        out.addProperty("side",side);out.addProperty("terrain_chunks",available);out.addProperty("already_decorated_chunks",skipped);
        out.addProperty("deep_block_samples",total);out.add("chunks",chunks);
        out.addProperty("scope","Actual TERRAIN stage; exact block states at 4-block horizontal stride, Y=-48 through min(48,envelope-64); no feature exclusions");
        System.out.println("CAVE_TERRAIN_STAGE "+label+" chunks="+available+" skipped="+skipped+" samples="+total);
        return out;
    }
}
