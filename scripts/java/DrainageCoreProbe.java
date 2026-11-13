import dev.freshgenerator.core.DrainageModel;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.LongAdder;
import java.security.MessageDigest;
import java.nio.ByteBuffer;
import static dev.freshgenerator.core.DrainageModel.*;

public final class DrainageCoreProbe {
    static final long[] SEEDS={42,1337,-20261111,2037157180504249802L,801086821803359396L};
    static final int[][] TILES={{-2,-2},{-1,-2},{0,-2},{-2,-1},{-1,-1},{0,-1},{-2,0},{-1,0},{0,0},{1,0},{0,1},{1,1}};
    static Field field(String shape) {return switch(shape){
        case "valley" -> (x,z)->105-.055f*z+Math.min(5f,.18f*Math.abs(x-64));
        case "bowl" -> (x,z)->83+(float)StrictMath.hypot(.22*(x-64),.34*(z-64));
        case "spill" -> (x,z)->Math.min(83+(float)StrictMath.hypot(.18*(x-64),.32*(z-64)),110-.25f*x+.4f*Math.abs(z-64));
        case "rugged" -> (x,z)->(float)(108+18*StrictMath.sin(x/77.0)*StrictMath.cos(z/91.0)+8*StrictMath.sin(z/39.0)+.007*x);
        case "flat" -> (x,z)->100;
        default -> throw new IllegalArgumentException(shape);
    };}
    static String digest(Tile t,int tx,int tz)throws Exception {
        MessageDigest md=MessageDigest.getInstance("SHA-256");
        ByteBuffer b=ByteBuffer.allocate((TILE_SIZE+1)*(TILE_SIZE+1)*8);
        for(int z=tz*TILE_SIZE;z<=(tz+1)*TILE_SIZE;z++)for(int x=tx*TILE_SIZE;x<=(tx+1)*TILE_SIZE;x++){
            float cut=t.cutAt(x,z);int w=t.waterAt(x,z);
            if(!Float.isFinite(cut)||cut< -MAX_BANK_RAISE||cut>MAX_CUT)throw new AssertionError("bounds");
            if(w!=DRY&&floor(w)>=waterTop(w))throw new AssertionError("water below bed");
            b.putInt(Float.floatToRawIntBits(cut)).putInt(w);
        }
        return HexFormat.of().formatHex(md.digest(b.array()));
    }
    static void run(long seed,String shape,String order)throws Exception {
        LongAdder heights=new LongAdder(),climates=new LongAdder(),contexts=new LongAdder();
        Field terrain=field(shape);
        DrainageModel model=new DrainageModel(seed,63,()->{contexts.increment();return new Fields(
            (x,z)->{heights.increment();return terrain.at(x,z);},(x,z)->.2f,
            (x,z)->{climates.increment();return (float)(.4*StrictMath.sin(x/501.0+z/709.0));});});
        List<int[]> tiles=new ArrayList<>(Arrays.asList(TILES));if(order.equals("reverse"))Collections.reverse(tiles);
        Map<String,String> hashes=new TreeMap<>();long start=System.nanoTime();
        if(order.equals("parallel")){
            try(var pool=Executors.newFixedThreadPool(4)){
                List<Future<Map.Entry<String,String>>> jobs=new ArrayList<>();
                for(int[] p:tiles)jobs.add(pool.submit(()->Map.entry(p[0]+","+p[1],digest(model.tile(p[0],p[1]),p[0],p[1]))));
                for(var job:jobs){var e=job.get(90,TimeUnit.SECONDS);hashes.put(e.getKey(),e.getValue());}
            }
        }else for(int[] p:tiles)hashes.put(p[0]+","+p[1],digest(model.tile(p[0],p[1]),p[0],p[1]));
        var all=MessageDigest.getInstance("SHA-256");for(var e:hashes.entrySet())all.update((e.getKey()+":"+e.getValue()+"\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        System.out.printf(Locale.ROOT,"{\"seed\":\"%d\",\"shape\":\"%s\",\"order\":\"%s\",\"tiles\":%d,\"columns\":%d,\"ms\":%.3f,\"height_calls\":%d,\"moisture_calls\":%d,\"contexts\":%d,\"sha256\":\"%s\"}%n",seed,shape,order,tiles.size(),tiles.size()*129*129,(System.nanoTime()-start)/1e6,heights.sum(),climates.sum(),contexts.sum(),HexFormat.of().formatHex(all.digest()));
    }
    public static void main(String[] args)throws Exception {
        for(String shape:new String[]{"valley","bowl","spill","rugged","flat"})for(long seed:SEEDS)
            run(seed,shape,args.length==0?"forward":args[0]);
    }
}
