"""Synthetic contract tests; these never count as native server runs."""
import copy,json,pathlib,unittest
from village_checks import validate,BASE
SHA='a'*40
class VillageEvidenceTests(unittest.TestCase):
 def fixtures(self):
  rows=[];terrain=[]
  for i,name in enumerate(('village_desert','village_taiga','village_savanna')):
   ident={'id':'minecraft:'+name,'chunkX':i,'chunkZ':0,'sites':[],'pieces':20}
   rows.append(dict(ident,full_sha256='b'*64,path_blocks=0 if i==0 else 100,farmland_blocks=0 if i==1 else 40,
    path_columns_with_void_within_8=0,farmland_columns_with_void_within_8=0,doors=3,door_exit_warnings=0))
   terrain.append(dict(ident,terrain_columns=1000,predecorated_columns_not_used=0,protected_roof_columns=100,roof_failure_columns=0,
    open_hole_columns=0,rigid_columns=30,rigid_grade_error_columns=0,deep_sha256='c'*64,outside_sha256='d'*64))
  base={'completed':True,'seed':42,'settings':'freshgenerator:highlands_v2','villages':rows,'terrain':terrain,'explicit_full_chunks':200}
  return [dict(copy.deepcopy(base),phase=p,source_commit=s) for p,s in [('baseline',BASE),('candidate',SHA),('reload',SHA)]]
 def check(self,r):return validate(*r,SHA,42)
 def fails(self,mutate):
  r=self.fixtures();mutate(r)
  with self.assertRaises((ValueError,KeyError)):self.check(r)
 def test_native_desert_paths_and_absent_taiga_farm_are_not_invented(self):self.assertEqual(3,self.check(self.fixtures())['villages'])
 def test_roof_improvement_is_measured(self):
  r=self.fixtures();r[0]['terrain'][0]['roof_failure_columns']=20;self.assertTrue(self.check(r)['saved_world_equal'])
 def test_disappearing_farm_fails(self):
  def f(r):
   for q in r[1:]:q['villages'][2]['farmland_blocks']=0
  self.fails(f)
 def test_disappearing_path_fails(self):
  def f(r):
   for q in r[1:]:q['villages'][2]['path_blocks']=0
  self.fails(f)
 def test_missing_terrain_rows_fail(self):self.fails(lambda r:r[1]['terrain'].clear())
 def test_missing_village_fails(self):self.fails(lambda r:r[1]['villages'].pop())
 def test_duplicate_village_fails(self):
  def f(r):r[1]['villages'][1]=copy.deepcopy(r[1]['villages'][0])
  self.fails(f)
 def test_deep_cave_change_fails(self):self.fails(lambda r:r[1]['terrain'][0].update(deep_sha256='e'*64))
 def test_outside_change_fails(self):self.fails(lambda r:r[1]['terrain'][0].update(outside_sha256='e'*64))
 def test_new_roof_void_fails(self):self.fails(lambda r:r[1]['terrain'][0].update(roof_failure_columns=1))
 def test_surface_hole_fails(self):self.fails(lambda r:r[1]['terrain'][0].update(open_hole_columns=1))
 def test_grade_regression_fails(self):self.fails(lambda r:r[1]['terrain'][0].update(rigid_grade_error_columns=1))
 def test_path_support_regression_fails(self):
  def f(r):
   for q in r[1:]:q['villages'][2]['path_columns_with_void_within_8']=1
  self.fails(f)
 def test_door_regression_fails(self):
  def f(r):
   for q in r[1:]:q['villages'][2]['door_exit_warnings']=1
  self.fails(f)
 def test_reload_change_fails(self):self.fails(lambda r:r[2]['villages'][1].update(full_sha256='e'*64))
 def test_predecorated_initial_chunks_fail(self):self.fails(lambda r:r[0]['terrain'][0].update(predecorated_columns_not_used=10))
 def test_stale_candidate_fails(self):self.fails(lambda r:r[1].update(source_commit=BASE))
 def test_missing_actual_farm_coverage_fails(self):
  def f(r):
   for q in r:
    for v in q['villages']:v['farmland_blocks']=0
  self.fails(f)
 def test_wrong_setting_fails(self):self.fails(lambda r:r[0].update(settings='minecraft:overworld'))
 def test_invalid_counter_fails(self):self.fails(lambda r:r[1]['terrain'][0].update(roof_failure_columns=False))
if __name__=='__main__':
 result=unittest.TextTestRunner(verbosity=2).run(unittest.defaultTestLoader.loadTestsFromTestCase(VillageEvidenceTests))
 p=pathlib.Path('build/reports/village-check-tests.json');p.parent.mkdir(parents=True,exist_ok=True)
 p.write_text(json.dumps({'tests':result.testsRun,'failures':len(result.failures),'errors':len(result.errors),'skipped':len(result.skipped)})+'\n')
 raise SystemExit(not result.wasSuccessful())
