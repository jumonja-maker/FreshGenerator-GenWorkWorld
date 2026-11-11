package dev.freshgenerator.core;

/** Near-surface access policy, not a new cave network. No state, random draws or chunk coordinates.
 * The native noise graph emits the same coefficients; deep caves regain their exact old weight.
 */
public final class CaveAccessModel {
    public static final float LAND_START=63, LAND_END=69, ROOF_DEPTH=14;
    public static final float FADE_START=12, FADE_END=42, GATE_START=.10f, GATE_END=.35f;
    public static final String SETTINGS="freshgenerator:highlands_v2";
    public static final String HEIGHT_LABEL="FG cave access: surface", ROOF_LABEL="FG cave access: roof";
    private CaveAccessModel() { }
    public static double smooth(double lo,double hi,double value) {
        if(!Double.isFinite(value)||!Double.isFinite(lo)||!Double.isFinite(hi)||hi<=lo)
            throw new IllegalArgumentException("Invalid cave transition input");
        double t=Math.max(0,Math.min(1,(value-lo)/(hi-lo)));
        return t*t*t*(t*(6*t-15)+10);
    }
    public static double gate(double noise){return smooth(GATE_START,GATE_END,noise);}
    public static double land(double height){return smooth(LAND_START,LAND_END,height);}
    public static double roof(double height,double entranceGate) {
        checkGate(entranceGate);return ROOF_DEPTH*land(height)*(1-entranceGate);
    }
    public static double multiplier(double height,double depth,double entranceGate) {
        checkGate(entranceGate);
        return 1-land(height)*(1-entranceGate)*(1-smooth(FADE_START,FADE_END,depth));
    }
    public static boolean permit(double height,double y,double roofDepth) {
        if(!Double.isFinite(height)||!Double.isFinite(y)||!Double.isFinite(roofDepth)||roofDepth<0||roofDepth>ROOF_DEPTH+.001)
            throw new IllegalArgumentException("Invalid cave roof");
        return roofDepth<=0||height-y>=roofDepth;
    }
    private static void checkGate(double value){if(!Double.isFinite(value)||value<0||value>1)throw new IllegalArgumentException("Invalid entrance gate");}
}
