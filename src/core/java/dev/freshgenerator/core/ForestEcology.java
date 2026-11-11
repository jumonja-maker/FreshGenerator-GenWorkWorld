package dev.freshgenerator.core;

import java.util.Objects;

/** Spatial recruitment, not forest templates or a tree-age simulation.
 * Coefficients are conservative game-scale design values, NOT fitted stem densities.
 * Outputs depend only on seed, location and immutable environmental inputs.
 */
public final class ForestEcology {
    private ForestEcology() { }
    public enum Family { TEMPERATE, CONIFER, TROPICAL }
    public record Site(double height, double seaLevel, double slope, double convexity,
                       double temperature, double moisture, double shore, double edge) {
        public Site {
            for (double v : new double[]{height,seaLevel,slope,convexity,temperature,moisture,shore,edge})
                if (!Double.isFinite(v)) throw new IllegalArgumentException("Non-finite ecological input");
            if (slope < 0 || shore < 0 || shore > 1 || edge < 0 || edge > 1)
                throw new IllegalArgumentException("Invalid slope/proximity input");
        }
    }
    public record Fields(double retention, double opening, double stand, double stress) { }
    public static Fields sample(long seed,int x,int z,Family family,Site site) {
        Objects.requireNonNull(family);Objects.requireNonNull(site);
        double wx=x+18*noise(seed^0x12E5L,x/173.0,z/173.0);
        double wz=z+18*noise(seed^0x5B31L,x/173.0,z/173.0);
        double broad=noise(seed^0x87A1L,wx/191.0,wz/191.0);
        double group=noise(seed^0xDA35L,wx/31.0,wz/31.0);
        double opening=Math.max(gap(seed^0x7D11L,wx,wz,96,12,28,.42),
                                 .65*gap(seed^0xA72BL,wx,wz,40,4,10,.18));
        // One-species tropical absences must NOT become large canopy clearings.
        double strength=family==Family.TROPICAL?.72:.94;
        double stand=clamp(.94+.09*broad+.065*group,.76,1);
        double moisture=.94+.06*smooth(-.6,.35,site.moisture());
        double slope=1-.78*smooth(.55,1.65,site.slope());
        double wind=smooth(.3,3,site.convexity())*smooth(145,240,site.height());
        double treeline=(family==Family.CONIFER?215:family==Family.TROPICAL?240:205)
                +30*clamp(site.temperature(),-1,1)+9*broad;
        double altitude=1-.96*smooth(treeline-16,treeline+34,site.height());
        // Stable low-water-envelope proxy, not a physical flood model.
        double lowBank=site.shore()*(1-smooth(site.seaLevel()+1,site.seaLevel()+5,site.height()));
        double factor=moisture*slope*altitude*(1-.22*wind)*(1-.65*lowBank)*(1-.18*site.edge());
        return new Fields(clamp(stand*(1-strength*opening)*factor,0,1),opening,stand,1-factor);
    }
    public static boolean retain(long seed,int x,int z,Fields fields) {
        Objects.requireNonNull(fields);return unit(seed^0x98D7F43AL,x,z)<fields.retention();
    }
    private static double gap(long seed,double x,double z,int cell,double minRadius,double maxRadius,double frequency) {
        long gx=(long)Math.floor(x/cell),gz=(long)Math.floor(z/cell);double best=0;
        for(long a=gx-1;a<=gx+1;a++)for(long b=gz-1;b<=gz+1;b++) {
            if(unit(seed,a,b)>=frequency)continue;
            double cx=(a+.1+.8*unit(seed^0x51L,a,b))*cell,cz=(b+.1+.8*unit(seed^0x91L,a,b))*cell;
            double r=minRadius+(maxRadius-minRadius)*unit(seed^0xA1L,a,b);
            double ratio=.65+.4*unit(seed^0xC1L,a,b),angle=6.283185307179586*unit(seed^0xE1L,a,b);
            double dx=x-cx,dz=z-cz,co=Math.cos(angle),si=Math.sin(angle);
            double u=(dx*co+dz*si)/r,v=(-dx*si+dz*co)/(r*ratio);
            double distance=Math.sqrt(u*u+v*v)+.10*noise(seed^0xBC21L,x/11.0,z/11.0);
            best=Math.max(best,1-smooth(.32,1,distance));
        }
        return best;
    }
    public static double smooth(double lo,double hi,double v) {
        if(!Double.isFinite(lo)||!Double.isFinite(hi)||hi<=lo||!Double.isFinite(v))throw new IllegalArgumentException("Invalid interval");
        double t=clamp((v-lo)/(hi-lo),0,1);return t*t*t*(t*(6*t-15)+10);
    }
    private static double clamp(double v,double lo,double hi){return Math.max(lo,Math.min(hi,v));}
    public static double noise(long seed,double x,double z) {
        if(!Double.isFinite(x)||!Double.isFinite(z))throw new IllegalArgumentException("Non-finite coordinates");
        long a=(long)Math.floor(x),b=(long)Math.floor(z);double tx=smooth(0,1,x-a),tz=smooth(0,1,z-b);
        double p=unit(seed,a,b),q=unit(seed,a+1,b),r=unit(seed,a,b+1),s=unit(seed,a+1,b+1);
        return 2*((p+(q-p)*tx)*(1-tz)+(r+(s-r)*tx)*tz)-1;
    }
    private static double unit(long seed,long x,long z) {
        long n=seed+x*0x9E3779B97F4A7C15L+z*0xD1B54A32D192ED03L;
        n=(n^(n>>>30))*0xBF58476D1CE4E5B9L;n=(n^(n>>>27))*0x94D049BB133111EBL;n^=n>>>31;
        return(n>>>11)*0x1.0p-53;
    }
}
