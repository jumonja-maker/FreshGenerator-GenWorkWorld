"""Pre-feature geometry is exact; post-feature sculk changes remain explicit observations."""
import base64,copy,json,pathlib,struct,unittest
from cave_audit_metrics import compare_deep,geometric_kind,compare_terrain_stage
AIR='Block{minecraft:air}'
STONE='Block{minecraft:stone}'
WATER='Block{minecraft:water}[level=0]'
VEIN='Block{minecraft:sculk_vein}[down=true,east=false,north=false,south=false,up=false,waterlogged=false,west=false]'
SHRIEKER='Block{minecraft:sculk_shrieker}[can_summon=true,shrieking=false,waterlogged=false]'
WET=VEIN.replace('waterlogged=false','waterlogged=true')
def report(kind,state):
 return {'x':-3320,'z':1508,'side':4,'deep_column_stride':4,'deep_column_top_y':[16],'deep_min_y':16,
  'deep_occupancy_base64':base64.b64encode(bytes([kind])).decode(),
  'deep_state_indices_u16be':base64.b64encode(struct.pack('>H',0)).decode(),'deep_state_palette':[state]}
def stage():
 return [{'label':'sample','x':0,'z':0,'side':16,'chunks':[{'chunk_x':0,'chunk_z':0,'observed_status':'terrain','before_features':True,
  'samples':16,'air_samples':4,'fluid_samples':2,'exact_block_state_sha256':'a'*64}]}]
class CaveMetricsTest(unittest.TestCase):
 def test_identical_is_exact(self):
  self.assertEqual(compare_deep(report(1,STONE),report(1,STONE))['deep_block_state_changes'],0)
 def test_reported_sculk_air_difference_is_recorded_not_called_rock(self):
  r=compare_deep(report(1,VEIN),report(0,AIR))
  self.assertEqual((r['deep_terrain_occupancy_changes'],r['raw_deep_occupancy_changes'],r['deep_block_state_changes']),(0,1,1))
  self.assertEqual(r['first_deep_state_changes'][0]['x'],-3320)
 def test_actual_stone_loss_remains_a_regression(self):
  r=compare_deep(report(1,STONE),report(0,AIR));self.assertEqual(r['deep_unexplained_occupancy_changes'],1)
 def test_full_sculk_is_never_treated_as_thin_vein(self):
  self.assertEqual(geometric_kind(1,'Block{minecraft:sculk}'),1)
 def test_water_in_vein_is_not_discarded(self):
  self.assertEqual(compare_deep(report(2,WET),report(0,AIR))['deep_unexplained_occupancy_changes'],1)
  self.assertEqual(compare_deep(report(2,WET),report(2,WATER))['deep_terrain_occupancy_changes'],0)
 def test_unknown_mods_are_never_ignored(self):
  self.assertEqual(geometric_kind(1,'Block{another:sculk_vein}[waterlogged=false]'),1)
 def test_invalid_palette_is_rejected(self):
  r=report(0,AIR);r['deep_state_indices_u16be']=base64.b64encode(struct.pack('>H',4)).decode()
  with self.assertRaises(ValueError):compare_deep(r,report(0,AIR))
 def test_short_buffer_is_rejected(self):
  r=report(0,AIR);r['deep_state_indices_u16be']=''
  with self.assertRaises(ValueError):compare_deep(r,report(0,AIR))
 def test_shifted_coordinates_are_not_a_valid_pair(self):
  r=report(0,AIR);r['x']+=4
  with self.assertRaises(ValueError):compare_deep(r,report(0,AIR))
 def test_shrieker_is_recorded_not_erased(self):
  r=compare_deep(report(1,VEIN),report(1,SHRIEKER))
  self.assertEqual((r['deep_terrain_occupancy_changes'],r['deep_feature_only_occupancy_changes'],r['deep_unexplained_occupancy_changes']),(1,1,0))
  self.assertEqual(r['feature_occupancy_change_examples'][0]['after'],SHRIEKER)
 def test_stone_replaced_by_feature_is_not_excused(self):
  r=compare_deep(report(1,STONE),report(0,VEIN));self.assertEqual(r['deep_unexplained_occupancy_changes'],1)
 def test_full_sculk_replaced_by_vein_is_not_excused(self):
  r=compare_deep(report(1,'Block{minecraft:sculk}'),report(1,VEIN));self.assertEqual(r['deep_unexplained_occupancy_changes'],1)
 def test_waterlogged_shrieker_is_not_excused(self):
  r=compare_deep(report(0,AIR),report(2,SHRIEKER.replace('waterlogged=false','waterlogged=true')))
  self.assertEqual(r['deep_unexplained_occupancy_changes'],1)
 def test_exact_terrain_snapshot(self):
  r=compare_terrain_stage(stage(),stage());self.assertEqual(r['deep_block_samples'],16);self.assertTrue(r['exact_block_states_identical'])
 def test_terrain_change_rejected_even_for_decorated_world(self):
  a=stage();b=copy.deepcopy(a);b[0]['chunks'][0]['exact_block_state_sha256']='b'*64
  with self.assertRaises(ValueError):compare_terrain_stage(a,b)
 def test_unavailable_terrain_is_not_counted_as_verified(self):
  a=stage();a[0]['chunks'][0]={'chunk_x':0,'chunk_z':0,'observed_status':'full','before_features':False}
  r=compare_terrain_stage(a,a);self.assertEqual(r['deep_block_samples'],0);self.assertEqual(r['unavailable_already_decorated_chunks'],1)
if __name__=='__main__':
 result=unittest.TextTestRunner(verbosity=2).run(unittest.defaultTestLoader.loadTestsFromTestCase(CaveMetricsTest))
 p=pathlib.Path('build/reports/cave-metrics-tests.json');p.parent.mkdir(parents=True,exist_ok=True)
 p.write_text(json.dumps({'tests':result.testsRun,'failures':len(result.failures),'errors':len(result.errors),'skipped':len(result.skipped)},indent=2)+'\n')
 raise SystemExit(0 if result.wasSuccessful() else 1)
