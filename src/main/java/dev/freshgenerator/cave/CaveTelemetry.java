package dev.freshgenerator.cave;
import java.util.*;
import java.util.concurrent.atomic.LongAdder;
/** Disabled in ordinary play. Counters only, no positions or references to worlds. */
public final class CaveTelemetry {
 private static final boolean ENABLED=Boolean.getBoolean("freshgenerator.caveAudit");
 private static final LongAdder GUARDS=new LongAdder();
 private static final LongAdder[][] C=new LongAdder[2][6];
 static{for(int i=0;i<2;i++)for(int j=0;j<6;j++)C[i][j]=new LongAdder();}
 private CaveTelemetry() { }
 static void guard(){if(ENABLED)GUARDS.increment();}
 static void carve(boolean canyon,boolean keep,float depth){
  if(!ENABLED)return;var c=C[canyon?1:0];c[keep?0:1].increment();
  if(depth>=42)c[keep?2:3].increment();
  if(depth>=0&&depth<14)c[keep?4:5].increment();
 }
 public static Map<String,Long> snapshot(){var r=new TreeMap<String,Long>();r.put("guard_instances",GUARDS.sum());
  var names=List.of("forwarded","blocked","deep_forwarded","deep_blocked","near_surface_forwarded","near_surface_blocked");
  for(int i=0;i<2;i++)for(int j=0;j<6;j++)r.put((i==0?"cave_":"canyon_")+names.get(j),C[i][j].sum());return r;}
}
