package dev.freshgenerator.core;

/** Independent, seeded surface model. It NEVER changes terrain density or chooses a biome.
 * Broad warped patches establish regions; smaller detail only decorates their edges.
 * The same snow mask is used by the material rule and snowfall placement.
 */
public final class EcotoneModel {
    private EcotoneModel() { }
    public record Fields(boolean active, boolean snow, double snowCover, double patch, double rockPatch) { }
    public static double smooth(double low,double high,double value) {
        if(!Double.isFinite(low)||!Double.isFinite(high)||high<=low||!Double.isFinite(value))
            throw new IllegalArgumentException("Invalid transition interval/value");
        double t=Math.max(0,Math.min(1,(value-low)/(high-low)));
        return t*t*t*(t*(t*6-15)+10);
    }
    public static Fields sample(long seed,int x,int z,double height,double temperature) {
        if(!Double.isFinite(height)||!Double.isFinite(temperature))throw new IllegalArgumentException("Non-finite climate");
        double wx=x+18*noise(seed^0x41A7L,x/160.0,z/160.0);
        double wz=z+18*noise(seed^0x79B9L,x/160.0,z/160.0);
        double broad=noise(seed^0x3F2DL,wx/96.0,wz/96.0);
        double detail=noise(seed^0x933DL,wx/24.0,wz/24.0);
        double fine=noise(seed^0xCC29L,wx/8.0,wz/8.0);
        double patch=0.65*broad+0.25*detail+0.10*fine;
        double activation=(noise(seed^0x51AFL,wx/48.0,wz/48.0)+1)*0.5;
        boolean active=smooth(104,144,height)>activation;
        double snowline=170+115*Math.max(-1,Math.min(1,temperature));
        double margin=height-snowline-24*patch;
        double cover=smooth(-24,24,height-snowline);
        double rockPatch=noise(seed^0x7CB3L,wx/32.0,wz/32.0);
        return new Fields(active,margin>=0,cover,patch,rockPatch);
    }
    public static boolean rock(Fields fields,double height,double slope,double erosion) {
        if(!Double.isFinite(height)||!Double.isFinite(slope)||!Double.isFinite(erosion))
            throw new IllegalArgumentException("Non-finite surface inputs");
        double slopeBand=smooth(0.45,1.25,Math.max(0,slope));
        double alpine=smooth(176,240,height)*(1-smooth(-0.6,0.3,erosion))*0.45;
        double threshold=(fields.rockPatch()+1)*0.5;
        return Math.max(slopeBand,alpine)>threshold;
    }
    /** Near-surface biome depth follows the real envelope, allowing for biome zoom offsets.
     * Deep underground, the exact original climate depth is retained.
     */
    public static double biomeDepth(double height,double y,double original) {
        if(!Double.isFinite(height)||!Double.isFinite(y)||!Double.isFinite(original))
            throw new IllegalArgumentException("Non-finite depth");
        double d=height-y;
        double surface=(Math.min(d,0)+Math.max(d-24,0))/128.0;
        double w=smooth(40,88,d);
        return surface*(1-w)+original*w;
    }
    public static double noise(long seed,double x,double z) {
        long ix=(long)Math.floor(x),iz=(long)Math.floor(z);
        double tx=x-ix,tz=z-iz;
        tx=tx*tx*tx*(tx*(tx*6-15)+10);tz=tz*tz*tz*(tz*(tz*6-15)+10);
        double a=unit(seed,ix,iz),b=unit(seed,ix+1,iz),c=unit(seed,ix,iz+1),d=unit(seed,ix+1,iz+1);
        return ((a+(b-a)*tx)*(1-tz)+(c+(d-c)*tx)*tz)*2-1;
    }
    private static double unit(long seed,long x,long z) {
        long n=seed+x*0x9E3779B97F4A7C15L+z*0xD1B54A32D192ED03L;
        n=(n^(n>>>30))*0xBF58476D1CE4E5B9L;n=(n^(n>>>27))*0x94D049BB133111EBL;n^=n>>>31;
        return (n>>>11)*0x1.0p-53;
    }
}
