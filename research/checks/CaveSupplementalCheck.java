import dev.freshgenerator.core.CaveAccessModel;
import java.util.SplittableRandom;
import java.util.stream.IntStream;

/** Additional pure-model regression checks. No Minecraft world/visual claims. */
public final class CaveSupplementalCheck {
    private static final int SAMPLES = 2_000_000;
    private static void require(boolean ok, String label) {
        if (!ok) throw new AssertionError(label);
    }
    private static double body(double surface, double cave, double weight) {
        return Math.min(surface, (1 - weight) * surface + weight * cave);
    }
    public static void main(String[] args) {
        var random = new SplittableRandom(20261111);
        long underWater=0, deep=0, open=0;
        double worstDensityLoss=0, maxThresholdJump=0;
        for (int i=0;i<SAMPLES;i++) {
            double h=random.nextDouble(-64,320), depth=random.nextDouble(-48,384);
            double g=random.nextDouble(), w=random.nextDouble();
            double roof=CaveAccessModel.roof(h,g), m=CaveAccessModel.multiplier(h,depth,g);
            require(Double.isFinite(roof)&&roof>=0&&roof<=14,"roof bounds");
            require(Double.isFinite(m)&&m>=0&&m<=1,"weight bounds");
            require(CaveAccessModel.multiplier(h,depth+.01,g)>=m-1e-12,"depth monotonicity");
            require(CaveAccessModel.multiplier(h,depth,Math.min(1,g+.001))>=m-1e-12,"entrance monotonicity");
            require(CaveAccessModel.permit(h,h-14,roof),"deep carving blocked");
            require(CaveAccessModel.permit(h,h-42,roof),"deep native output blocked");
            if (h<=63) {
                require(m==1&&roof==0,"underwater changed");
                require(CaveAccessModel.permit(h,h-depth,roof),"underwater mask blocked");
                underWater++;
            }
            if (depth>=42) { require(m==1,"deep noise changed"); deep++; }
            require(CaveAccessModel.multiplier(h,depth,1)==1,"open region noise changed");
            require(CaveAccessModel.roof(h,1)==0,"open region roof");open++;
            double surface=random.nextDouble(-1,1), cave=random.nextDouble(-1,1);
            double before=body(surface,cave,w), after=body(surface,cave,w*m);
            worstDensityLoss=Math.max(worstDensityLoss,before-after);
            require(after>=before-1e-12,"new density-created air");
            require(body(surface,cave,0)==body(surface,cave,0*m),"existing solid roof reopened");
        }
        for(double v:new double[]{63,69}) for(int d=-48;d<128;d++) for(double g:new double[]{0,.25,.5,.75,1}) {
            double jump=Math.abs(CaveAccessModel.multiplier(v-1e-7,d,g)-CaveAccessModel.multiplier(v+1e-7,d,g));
            maxThresholdJump=Math.max(maxThresholdJump,jump);require(jump<1e-8,"shoreline discontinuity");
        }
        for(double v:new double[]{12,42}) for(int h=-64;h<320;h++) for(double g:new double[]{0,.25,.5,.75,1}) {
            double jump=Math.abs(CaveAccessModel.multiplier(h,v-1e-7,g)-CaveAccessModel.multiplier(h,v+1e-7,g));
            maxThresholdJump=Math.max(maxThresholdJump,jump);require(jump<1e-8,"depth discontinuity");
        }
        double[] serial=IntStream.range(0,100000).mapToDouble(i->CaveAccessModel.multiplier(64+(i%256),i%96,CaveAccessModel.gate(Math.sin(i*.01)))).toArray();
        double[] parallel=IntStream.range(0,100000).parallel().mapToDouble(i->CaveAccessModel.multiplier(64+(i%256),i%96,CaveAccessModel.gate(Math.sin(i*.01)))).toArray();
        require(java.util.Arrays.equals(serial,parallel),"sampling order changed results");
        int invalid=0;
        for(double value:new double[]{Double.NaN,Double.POSITIVE_INFINITY,Double.NEGATIVE_INFINITY}) {
            try{CaveAccessModel.roof(value,.5);throw new AssertionError("invalid height accepted");}catch(IllegalArgumentException expected){invalid++;}
            try{CaveAccessModel.multiplier(80,value,.5);throw new AssertionError("invalid depth accepted");}catch(IllegalArgumentException expected){invalid++;}
            try{CaveAccessModel.gate(value);throw new AssertionError("invalid noise accepted");}catch(IllegalArgumentException expected){invalid++;}
            try{CaveAccessModel.permit(80,value,10);throw new AssertionError("invalid y accepted");}catch(IllegalArgumentException expected){invalid++;}
        }
        System.out.printf(java.util.Locale.ROOT,
            "{\"source_blob\":\"10a4eedc311f06406386ff561a17dc817634219f\",\"random_sites\":%d,\"underwater_sites\":%d,\"deep_sites\":%d,\"open_gate_sites\":%d,\"serial_parallel_samples\":100000,\"invalid_inputs_rejected\":%d,\"maximum_numeric_density_decrease\":%.17g,\"maximum_threshold_jump\":%.17g,\"status\":\"passed\",\"scope\":\"Pure CaveAccessModel invariants only, not native noise distribution, terrain generation or visual quality\"}%n",
            SAMPLES,underWater,deep,open,invalid,worstDensityLoss,maxThresholdJump);
    }
}
