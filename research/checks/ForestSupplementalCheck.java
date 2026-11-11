import dev.freshgenerator.core.ForestEcology;
import java.util.*;
import java.util.stream.*;
import java.nio.file.*;
import java.security.MessageDigest;

/** Supplemental pure-model check; does not stand in for a Minecraft world test. */
public class ForestSupplementalCheck {
    static void require(boolean v,String what) { if(!v)throw new AssertionError(what); }
    public static void main(String[] args)throws Exception {
        var calm=new ForestEcology.Site(100,63,.15,0,0,.2,0,0);
        var steep=new ForestEcology.Site(100,63,1.8,0,0,.2,0,0);
        var high=new ForestEcology.Site(270,63,.15,0,0,.2,0,0);
        var lowBank=new ForestEcology.Site(64,63,.15,0,0,.2,1,0);
        var dryBank=new ForestEcology.Site(64,63,.15,0,0,.2,0,0);
        int perSeed=128*128;long count=0,open=0,kept=0,tropicalKept=0;
        double sum=0,trop=0,minMean=1,maxMean=0;
        long[] seeds=new long[64];
        seeds[0]=42;seeds[1]=1337;seeds[2]=-20261111;
        Random r=new Random(20261111);for(int i=3;i<64;i++)seeds[i]=r.nextLong();
        Set<String> openingMaps=new HashSet<>();
        for(long seed:seeds){
            double seedSum=0;
            MessageDigest digest=MessageDigest.getInstance("SHA-256");
            for(int ix=-64;ix<64;ix++)for(int iz=-64;iz<64;iz++){
                int x=ix*4,z=iz*4;
                var a=ForestEcology.sample(seed,x,z,ForestEcology.Family.TEMPERATE,calm);
                var b=ForestEcology.sample(seed,x,z,ForestEcology.Family.TROPICAL,calm);
                require(a.retention()>=0&&a.retention()<=1&&a.opening()>=0&&a.opening()<=1,"bounded fields");
                require(b.retention()+1e-15>=a.retention(),"tropical gaps stronger than temperate");
                count++;sum+=a.retention();trop+=b.retention();seedSum+=a.retention();
                if(a.opening()>.5)open++;
                digest.update((byte)(a.opening()>.5?1:0));
                if(ForestEcology.retain(seed,x,z,a))kept++;
                if(ForestEcology.retain(seed,x,z,b))tropicalKept++;
            }
            require(openingMaps.add(HexFormat.of().formatHex(digest.digest())),"repeated opening map among tested seeds");
            double mean=seedSum/perSeed;minMean=Math.min(minMean,mean);maxMean=Math.max(maxMean,mean);
            require(mean>.7&&mean<.99,"unreasonable mean on unstressed flat test site");
        }
        // Coordinate range, signed chunk boundaries, deterministic parallel execution.
        var points=IntStream.range(0,10000).mapToObj(i->new int[]{
            (i%2==0?1:-1)*(29_900_000+(i*17)%100000),
            (i%3==0?1:-1)*(29_900_000+(i*31)%100000)}).toList();
        var serial=points.stream().map(p->ForestEcology.sample(42,p[0],p[1],ForestEcology.Family.CONIFER,calm)).toList();
        var parallel=points.parallelStream().map(p->ForestEcology.sample(42,p[0],p[1],ForestEcology.Family.CONIFER,calm)).toList();
        require(serial.equals(parallel),"parallel results differ");
        int stressChecks=0;
        for(int x=-128;x<128;x++)for(int z=-128;z<128;z+=4){
            var a=ForestEcology.sample(42,x,z,ForestEcology.Family.CONIFER,calm);
            var b=ForestEcology.sample(42,x,z,ForestEcology.Family.CONIFER,steep);
            var c=ForestEcology.sample(42,x,z,ForestEcology.Family.CONIFER,high);
            require(b.retention()<=a.retention()&&c.retention()<=a.retention(),"stress increased density");
            require(ForestEcology.sample(42,x,z,ForestEcology.Family.CONIFER,lowBank).retention()<=
                ForestEcology.sample(42,x,z,ForestEcology.Family.CONIFER,dryBank).retention(),"low bank increased density");
            require(a.equals(ForestEcology.sample(42,Math.floorDiv(x,16)*16+Math.floorMod(x,16),
                Math.floorDiv(z,16)*16+Math.floorMod(z,16),ForestEcology.Family.CONIFER,calm)),"signed chunk reconstruction");
            stressChecks++;
        }
        String report=String.format(Locale.ROOT,"""
          {
            "status": "passed",
            "source_commit": "3aea3dacefdf23a3c5d2327d2b5a7685f53564d1",
            "core_git_blob_sha1": "374f9ab1f1181c608030f95e8bb987c5940c1a70",
            "scope": "Independent pure-Java model check; not Minecraft world, not ecological fit or visual validation",
            "runtime": "%s",
            "seed_count": 64,
            "distinct_opening_maps": 64,
            "sample_positions_per_family": %d,
            "flat_site_temperate_mean_retention": %.9f,
            "flat_site_tropical_mean_retention": %.9f,
            "flat_site_mean_retention_range_across_seeds": [%.9f, %.9f],
            "flat_site_opening_above_half_fraction": %.9f,
            "flat_site_kept_fraction": %.9f,
            "flat_site_tropical_kept_fraction": %.9f,
            "parallel_boundary_points_identical": 10000,
            "stress_and_signed_chunk_checks": %d,
            "assumptions": "Test environment height=100, slope=0.15, temperature=0, moisture=0.2; not measured forest data"
          }
          """,System.getProperty("java.version"),count,sum/count,trop/count,minMean,maxMean,
          open/(double)count,kept/(double)count,tropicalKept/(double)count,stressChecks);
        Path out=Path.of(args[0]);Files.writeString(out,report);System.out.print(report);
    }
}
