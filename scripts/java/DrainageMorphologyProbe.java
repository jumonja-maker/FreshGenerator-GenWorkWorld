import dev.freshgenerator.core.DrainageModel;
import static dev.freshgenerator.core.DrainageModel.*;
import java.util.Locale;
public class DrainageMorphologyProbe {
 static Field terrain(String s){return switch(s){
  case "coastal" -> (x,z)->61.2f+.003f*x+.002f*z;
  case "shore" -> (x,z)->62.1f+.014f*x+.4f*(float)Math.sin(z/43.0);
  case "plane" -> (x,z)->100-.06f*z;
  case "valley" -> (x,z)->105-.055f*z+Math.min(5f,.18f*Math.abs(x-64));
  case "steep" -> (x,z)->195-.65f*z+.22f*Math.abs(x-64);
  case "rugged" -> (x,z)->(float)(108+18*Math.sin(x/77.)*Math.cos(z/91.)+8*Math.sin(z/39.)+.007*x);
  default -> throw new IllegalArgumentException(s);
 };}
 public static void main(String[] args){
  for(String shape:new String[]{"coastal","shore","plane","valley","steep","rugged"})for(long seed:new long[]{42,1337,-20261111,2037157180504249802L,801086821803359396L}){
   Field h=terrain(shape);var model=new DrainageModel(seed,63,()->new Fields(h,(x,z)->.2f,(x,z)->.3f));
   long cols=0,wet=0,lakes=0,raised=0,underwater=0,changedUnderwater=0,raisedUnderwater=0;double cut=0,maxFill=0;long start=System.nanoTime();
   for(int tz=-1;tz<=1;tz++)for(int tx=-1;tx<=1;tx++){
    var t=model.tile(tx,tz);
    for(int z=tz*128;z<tz*128+128;z++)for(int x=tx*128;x<tx*128+128;x++){
     cols++;int w=t.waterAt(x,z);float c=t.cutAt(x,z),v=h.at(x,z);cut+=Math.abs(c);
     if(c<0)raised++;if(v<62){underwater++;if(c!=0)changedUnderwater++;if(c<0)raisedUnderwater++;}
     if(w!=DRY){wet++;if(isLake(w))lakes++;maxFill=Math.max(maxFill,waterTop(w)-v);}
    }
   }
   System.out.printf(Locale.ROOT,"{\"shape\":\"%s\",\"seed\":\"%d\",\"columns\":%d,\"wet\":%d,\"lake\":%d,\"raised\":%d,\"native_water_columns\":%d,\"changed_native_water\":%d,\"raised_native_water\":%d,\"mean_abs_cut\":%.5f,\"max_source_above_ground\":%.5f,\"ms\":%.2f}%n",shape,seed,cols,wet,lakes,raised,underwater,changedUnderwater,raisedUnderwater,cut/cols,maxFill,(System.nanoTime()-start)/1e6);
  }
 }
}
