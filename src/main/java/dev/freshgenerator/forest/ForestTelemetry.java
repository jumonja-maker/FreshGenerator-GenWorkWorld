package dev.freshgenerator.forest;
import dev.freshgenerator.core.ForestEcology;
import java.util.*;
import java.util.concurrent.atomic.LongAdder;
/** Disabled in ordinary play. Aggregates only; never stores positions or world references. */
public final class ForestTelemetry {
 private static final boolean ENABLED=Boolean.getBoolean("freshgenerator.forestAudit");
 private static final LongAdder[] ATTEMPTS=new LongAdder[3],KEPT=new LongAdder[3],OPENINGS=new LongAdder[3];
 static {for(int i=0;i<3;i++){ATTEMPTS[i]=new LongAdder();KEPT[i]=new LongAdder();OPENINGS[i]=new LongAdder();}}
 private ForestTelemetry(){}
 static void record(ForestEcology.Family family,boolean kept,double opening){if(!ENABLED)return;int n=family.ordinal();ATTEMPTS[n].increment();if(kept)KEPT[n].increment();if(opening>.5)OPENINGS[n].increment();}
 public static Map<String,Long> snapshot(){Map<String,Long> out=new TreeMap<>();for(var f:ForestEcology.Family.values()){int n=f.ordinal();out.put(f+"_attempts",ATTEMPTS[n].sum());out.put(f+"_kept",KEPT[n].sum());out.put(f+"_opening_attempts",OPENINGS[n].sum());}return out;}
}
