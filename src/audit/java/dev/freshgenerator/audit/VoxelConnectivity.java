package dev.freshgenerator.audit;

import java.util.*;

/** Read-only bounded six-neighbour component audit. NEVER removes game blocks.
 * Cells: bit 0 = occupied/support, bit 1 = natural terrain, bit 2 = structure volume.
 * Bottom-connected components are grounded; side/top boundaries are unknown, not floating.
 */
public final class VoxelConnectivity {
    public record Component(int blocks, int naturalBlocks, int minX, int minY, int minZ,
                            int maxX, int maxY, int maxZ, boolean skyExposed, boolean protectedStructure) { }
    public record Result(long occupiedBlocks, int groundedComponents, int boundaryUnknownComponents,
                         int enclosedComponents, List<Component> detached) { }
    private VoxelConnectivity() { }
    public static Result scan(byte[] cells, int width, int height, int depth) {
        Objects.requireNonNull(cells);
        long volume = (long) width * height * depth;
        if (width < 1 || height < 1 || depth < 1 || volume > 16_000_000L || volume != cells.length)
            throw new IllegalArgumentException("Invalid or excessive audit volume");
        int plane = width * depth;
        int[] top = new int[plane]; Arrays.fill(top, -1);
        for (int y=0;y<height;y++) for(int q=0;q<plane;q++) if((cells[y*plane+q]&1)!=0)top[q]=y;
        BitSet seen = new BitSet(cells.length);
        int[] queue = new int[cells.length];
        List<Component> detached = new ArrayList<>();
        int grounded=0,unknown=0,enclosed=0; long occupied=0;
        for(int first=0;first<cells.length;first++) {
            if((cells[first]&1)==0||seen.get(first))continue;
            int head=0,tail=1; queue[0]=first;seen.set(first);
            int natural=0,minX=width,minY=height,minZ=depth,maxX=-1,maxY=-1,maxZ=-1;
            boolean bottom=false,boundary=false,sky=false,structure=false;
            while(head<tail) {
                int i=queue[head++],y=i/plane,q=i%plane,z=q/width,x=q%width;
                occupied++; if((cells[i]&2)!=0)natural++;if((cells[i]&4)!=0)structure=true;
                minX=Math.min(minX,x);maxX=Math.max(maxX,x);minY=Math.min(minY,y);maxY=Math.max(maxY,y);
                minZ=Math.min(minZ,z);maxZ=Math.max(maxZ,z);
                bottom|=y==0;boundary|=x==0||z==0||x==width-1||z==depth-1||y==height-1;
                sky|=top[q]==y;
                if(x>0)tail=visit(i-1,cells,seen,queue,tail);
                if(x+1<width)tail=visit(i+1,cells,seen,queue,tail);
                if(z>0)tail=visit(i-width,cells,seen,queue,tail);
                if(z+1<depth)tail=visit(i+width,cells,seen,queue,tail);
                if(y>0)tail=visit(i-plane,cells,seen,queue,tail);
                if(y+1<height)tail=visit(i+plane,cells,seen,queue,tail);
            }
            if(bottom)grounded++;
            else if(boundary)unknown++;
            else {
                enclosed++;
                if(natural>0)detached.add(new Component(tail,natural,minX,minY,minZ,maxX,maxY,maxZ,sky,structure));
            }
        }
        return new Result(occupied,grounded,unknown,enclosed,List.copyOf(detached));
    }
    private static int visit(int i,byte[] cells,BitSet seen,int[] queue,int tail) {
        if((cells[i]&1)!=0&&!seen.get(i)){seen.set(i);queue[tail++]=i;}
        return tail;
    }
}
