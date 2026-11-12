"""Stage-barrier preparation for throwaway A/B worlds only.

Do not call this from production. The release observer samples an ungenerated
window, prepares its terrain halo, then advances native statuses in a fixed order.
Coordinates, palette values, hashes and block equality gates are not filtered.
"""

FULL_REQUESTS = '''  for(Window w:windows){int cx=Math.floorDiv(w.x(),16),cz=Math.floorDiv(w.z(),16);
   for(int x=cx-1;x<=cx+4;x++)for(int z=cz-1;z<=cz+4;z++){level.getChunk(x,z);chunks.add(((long)x<<32)|(z&0xffffffffL));}}'''
NORMAL_WINDOW = 'List.of(new Window("normal",0,0))'
REMOTE_NORMAL_WINDOW = 'List.of(new Window("normal",8192,8192))'
SCAN_METHOD = ' private JsonObject scan(Window window)throws Exception {'
REPORT_MARKER = 'out.addProperty("explicit_full_chunks",chunks.size());'
PROTOCOL = 'native-status-barriers-v1'

# Fully qualified engine types keep the instrumentation independent of imports.
# Native status reflection is confined to a test observer; missing/new contracts
# fail with an exception rather than silently skipping stages.
JAVA_PREPARATION = r'''
 private JsonObject technicalPreparation;
 private void prepareTechnicalChunks(List<Window> windows)throws Exception {
  var terrain=net.minecraft.world.level.chunk.status.ChunkStatus.TERRAIN;
  var full=net.minecraft.world.level.chunk.status.ChunkStatus.FULL;
  Set<net.minecraft.world.level.chunk.status.ChunkStatus> unique=new LinkedHashSet<>();
  for(var field:net.minecraft.world.level.chunk.status.ChunkStatus.class.getDeclaredFields()) {
   if(field.getType()!=net.minecraft.world.level.chunk.status.ChunkStatus.class
       || !java.lang.reflect.Modifier.isPublic(field.getModifiers())
       || !java.lang.reflect.Modifier.isStatic(field.getModifiers()))continue;
   var status=(net.minecraft.world.level.chunk.status.ChunkStatus)field.get(null);
   if(status.isOrAfter(terrain))unique.add(status);
  }
  var stages=new ArrayList<>(unique);
  stages.sort((a,b)->a==b?0:a.isOrAfter(b)?1:-1);
  if(stages.size()<3 || stages.getFirst()!=terrain || stages.getLast()!=full)
   throw new AssertionError("Unknown native chunk stage contract: "+stages);
  if(windows.isEmpty())throw new AssertionError("No observation windows");
  boolean reload="candidate-reload".equals(System.getenv("FG_REGION_PASS"));
  var plan=MessageDigest.getInstance("SHA-256");
  long terrainRequests=0,laterRequests=0;int predecoratedTargets=0;
  for(var status:stages) {
   // A wider TERRAIN ring prevents a request for one chunk's decorations from
   // implicitly deciding when its sampled neighbours first acquire terrain.
   int halo=status==terrain?3:status==full?1:2;
   for(Window w:windows) {
    int cx=Math.floorDiv(w.x(),16),cz=Math.floorDiv(w.z(),16);
    for(int x=cx-halo;x<cx+4+halo;x++)for(int z=cz-halo;z<cz+4+halo;z++) {
     var chunk=level.getChunk(x,z,status);
     if(chunk==null || !chunk.getPersistedStatus().isOrAfter(status))
      throw new AssertionError("Native chunk stage incomplete: "+x+","+z+" "+status);
     if(status==terrain) {
      terrainRequests++;
      if(x>=cx && x<cx+4 && z>=cz && z<cz+4 && chunk.getPersistedStatus()!=terrain) {
       predecoratedTargets++;
       if(!reload)throw new AssertionError("Observation window already decorated before stage barrier: "+x+","+z);
      }
     } else laterRequests++;
     if(status==full)chunks.add(((long)x<<32)|(z&0xffffffffL));
     plan.update((status+":"+x+","+z+"\n").getBytes(StandardCharsets.UTF_8));
    }
   }
   System.out.println("TECH_CHUNK_STAGE "+status+" windows="+windows.size());
  }
  technicalPreparation=new JsonObject();
  technicalPreparation.addProperty("protocol","native-status-barriers-v1");
  technicalPreparation.addProperty("plan_sha256",HexFormat.of().formatHex(plan.digest()));
  technicalPreparation.add("native_stages",G.toJsonTree(stages.stream().map(Object::toString).toList()));
  technicalPreparation.addProperty("terrain_chunk_requests",terrainRequests);
  technicalPreparation.addProperty("later_stage_requests",laterRequests);
  technicalPreparation.addProperty("predecorated_observation_chunks",predecoratedTargets);
  technicalPreparation.addProperty("explicit_full_chunks",chunks.size());
 }
'''


def replace_once(source, before, after):
    if not isinstance(source, str) or source.count(before) != 1:
        raise ValueError('Expected exactly one technical observer anchor: ' + before[:70])
    return source.replace(before, after)


def ordered_generation_observer(source):
    """Transform a private observer copy; never change checked-in game sources."""
    if not isinstance(source, str) or 'prepareTechnicalChunks' in source:
        raise ValueError('Invalid or already prepared technical observer')
    result = replace_once(source, FULL_REQUESTS, '  prepareTechnicalChunks(windows);')
    # Spawn preparation occurs before SERVER_STARTED. A fixed remote control is
    # selected by protocol, not by inspecting which coordinates pass a test.
    result = replace_once(result, NORMAL_WINDOW, REMOTE_NORMAL_WINDOW)
    result = replace_once(result, SCAN_METHOD, JAVA_PREPARATION + '\n' + SCAN_METHOD)
    result = replace_once(result, REPORT_MARKER,
                          REPORT_MARKER + 'out.add("technical_preparation",technicalPreparation);')
    return result
