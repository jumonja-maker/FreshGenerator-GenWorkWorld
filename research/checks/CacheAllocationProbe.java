import java.lang.management.ManagementFactory;
import java.util.*;
import dev.freshgenerator.core.ColumnFloatCache;
/** Standalone allocation observation, NOT an in-game FPS or generation benchmark. */
public final class CacheAllocationProbe {
 static volatile Object sink;
 static final com.sun.management.ThreadMXBean bean=(com.sun.management.ThreadMXBean)ManagementFactory.getThreadMXBean();
 static long key(long i){i^=i>>>30;i*=0xbf58476d1ce4e5b9L;i^=i>>>27;i*=0x94d049bb133111ebL;return i^(i>>>31);}
 static long allocated(){return bean.getThreadAllocatedBytes(Thread.currentThread().threadId());}
 static long measure(int count,boolean primitive) {
  long before=allocated();
  for(int j=0;j<2048;j++) {
   if(primitive){var cache=new ColumnFloatCache(1024);for(int i=0;i<count;i++)cache.put(key(i+10000),i+64f);sink=cache;}
   else{var cache=new HashMap<Long,Float>();for(int i=0;i<count;i++)cache.put(key(i+10000),i+64f);sink=cache;}
  }return (allocated()-before)/2048;
 }
 public static void main(String[] args){bean.setThreadAllocatedMemoryEnabled(true);measure(64,false);measure(64,true);
  for(int n:new int[]{0,8,32,128,512,1024})System.out.printf("entries=%d old_bytes=%d new_bytes=%d%n",n,measure(n,false),measure(n,true));
 }
}
