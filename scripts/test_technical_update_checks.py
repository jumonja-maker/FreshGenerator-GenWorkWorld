"""Synthetic rejection tests for the observer comparison, not evidence of generated worlds."""
import copy,json,unittest
from pathlib import Path
from technical_update_checks import BASE_COMMIT,compare

SHA='a'*40
HASH='b'*64

def reports():
    a={'source_commit':BASE_COMMIT,'completed':True,'mode':'reference','pass':'reference',
       'seed':42,'settings':'minecraft:overworld','observer_sha256':HASH,'maps':[],
       'full_windows':[{'label':'normal','x':0,'z':0,'sample_columns':256,
         'base_geometry_water_sha256':HASH,'sampled_full_blocks_sha256':HASH,'biomes':{'minecraft:plains':256}}],
       'stored_biome_checks':256,'explicit_full_chunks':36,
       'forest_telemetry':{'TEMPERATE_attempts':0},'cave_telemetry':{'guard_instances':0}}
    b=copy.deepcopy(a);b.update(source_commit=SHA,mode='candidate');b['pass']='candidate'
    c=copy.deepcopy(b);c['pass']='candidate-reload'
    return a,b,c

class TechnicalChecksTest(unittest.TestCase):
    def check(self, rs):return compare(*rs,SHA,'normal',42)
    def test_equal_complete_normal_world(self):self.assertTrue(self.check(reports())['full_sample_equal'])
    def test_failed_run_rejected(self):
        r=list(reports());r[1]['completed']=False
        with self.assertRaises(ValueError):self.check(r)
    def test_stale_candidate_rejected(self):
        r=list(reports());r[1]['source_commit']='c'*40
        with self.assertRaises(ValueError):self.check(r)
    def test_wrong_reference_rejected(self):
        r=list(reports());r[0]['source_commit']='c'*40
        with self.assertRaises(ValueError):self.check(r)
    def test_wrong_seed_rejected(self):
        r=list(reports());r[1]['seed']=1337
        with self.assertRaises(ValueError):self.check(r)
    def test_wrong_phase_rejected(self):
        r=list(reports());r[2]['pass']='candidate'
        with self.assertRaises(ValueError):self.check(r)
    def test_changed_block_hash_rejected(self):
        r=list(reports());r[1]['full_windows'][0]['sampled_full_blocks_sha256']='c'*64
        with self.assertRaises(ValueError):self.check(r)
    def test_changed_water_rejected(self):
        r=list(reports());r[1]['full_windows'][0]['base_geometry_water_sha256']='c'*64
        with self.assertRaises(ValueError):self.check(r)
    def test_different_observers_rejected(self):
        r=list(reports());r[1]['observer_sha256']='c'*64
        with self.assertRaises(ValueError):self.check(r)
    def test_no_full_samples_rejected(self):
        r=list(reports())
        for a in r:a['full_windows']=[]
        with self.assertRaises(ValueError):self.check(r)
    def test_global_policy_leak_rejected(self):
        r=list(reports())
        for a in r:a['cave_telemetry']['guard_instances']=1
        with self.assertRaises(ValueError):self.check(r)
    def test_runtime_decision_difference_rejected(self):
        r=list(reports());r[1]['forest_telemetry']['TEMPERATE_attempts']=1
        with self.assertRaises(ValueError):self.check(r)
    def test_missing_snapshot_hash_rejected_even_if_equal(self):
        r=list(reports())
        for a in r:a['full_windows'][0]['sampled_full_blocks_sha256']=''
        with self.assertRaises(ValueError):self.check(r)
    def test_bad_stored_count_rejected(self):
        r=list(reports());r[1]['stored_biome_checks']=0
        with self.assertRaises(ValueError):self.check(r)
    def test_missing_maps_in_fresh_world_rejected(self):
        r=list(reports())
        for a in r:a['settings']='freshgenerator:highlands_v2'
        with self.assertRaises(ValueError):compare(*r,SHA,'fresh',42)

if __name__=='__main__':
    result=unittest.TextTestRunner(verbosity=2).run(unittest.defaultTestLoader.loadTestsFromTestCase(TechnicalChecksTest))
    p=Path('build/reports/technical-update-check-tests.json');p.parent.mkdir(parents=True,exist_ok=True)
    p.write_text(json.dumps({'tests':result.testsRun,'failures':len(result.failures),'errors':len(result.errors),'skipped':len(result.skipped)},indent=2)+'\n')
    raise SystemExit(not result.wasSuccessful())
