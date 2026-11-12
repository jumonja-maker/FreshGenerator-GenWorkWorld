import java.util.*;
import dev.freshgenerator.core.ColumnFloatCache;
import dev.freshgenerator.audit.ForestHeightOracle;
public class CacheTraceProbe {
 public static void main(String[] args) {
  long comparisons=0;
  for(int cap:new int[]{4,16,64,1024,4096}) {
   var a=new ColumnFloatCache(cap);var b=new HashMap<Long,Float>();var r=new Random(7131);
   for(int i=0;i<400000;i++){
    long key=r.nextInt(cap*3)-cap;Float expected=b.get(key);float actual=a.get(key);
    if((expected==null)!=Float.isNaN(actual))throw new AssertionError("miss trace");
    if(expected!=null&&Float.floatToRawIntBits(expected)!=Float.floatToRawIntBits(actual))throw new AssertionError("value");
    if(expected==null){float v=key==0?-0f:(float)Math.sin(key)*280;if(b.size()>=cap)b.clear();b.put(key,v);a.put(key,v);}
    comparisons++;
   }
  }
  var a=new ColumnFloatCache(1024);var oracle=new ForestHeightOracle();var r=new Random(42);
  for(int i=0;i<250000;i++){
   int x=r.nextInt(64)-32,z=r.nextInt(64)-32;long k=((long)x<<32)|(z&0xffffffffL);
   oracle.begin(x,z);float h=a.get(k);
   if(Float.isNaN(h)){h=(float)Math.cos(k)*200;oracle.sampled(x,0,z,h);a.put(k,h);}oracle.finish(h);
  }
  System.out.println("PASS cache_hits_misses_values="+comparisons+" oracle="+ForestHeightOracle.snapshot());
 }
}
