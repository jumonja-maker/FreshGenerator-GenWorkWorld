"""Replay classifier regressions; normalizing coatings must not hide stone or water changes."""
import base64,json,pathlib,struct,unittest
from cave_audit_metrics import compare_deep, geometric_kind

AIR='Block{minecraft:air}'
STONE='Block{minecraft:stone}'
WATER='Block{minecraft:water}[level=0]'
VEIN='Block{minecraft:sculk_vein}[down=true,east=false,north=false,south=false,up=false,waterlogged=false,west=false]'
WET=VEIN.replace('waterlogged=false','waterlogged=true')
def report(kind,state):
    return {'x':-3320,'z':1508,'side':4,'deep_column_stride':4,'deep_column_top_y':[16],'deep_min_y':16,
            'deep_occupancy_base64':base64.b64encode(bytes([kind])).decode(),
            'deep_state_indices_u16be':base64.b64encode(struct.pack('>H',0)).decode(),'deep_state_palette':[state]}

class CaveMetricsTest(unittest.TestCase):
    def test_identical_is_exact(self):
        self.assertEqual(compare_deep(report(1,STONE),report(1,STONE))['deep_block_state_changes'],0)
    def test_reported_sculk_air_difference_is_recorded_not_called_rock(self):
        r=compare_deep(report(1,VEIN),report(0,AIR))
        self.assertEqual((r['deep_terrain_occupancy_changes'],r['raw_deep_occupancy_changes'],r['deep_block_state_changes']),(0,1,1))
        self.assertEqual(r['first_deep_state_changes'][0]['x'],-3320)
    def test_actual_stone_loss_remains_a_regression(self):
        self.assertEqual(compare_deep(report(1,STONE),report(0,AIR))['deep_terrain_occupancy_changes'],1)
    def test_full_sculk_is_never_treated_as_thin_vein(self):
        self.assertEqual(geometric_kind(1,'Block{minecraft:sculk}'),1)
    def test_water_in_vein_is_not_discarded(self):
        self.assertEqual(compare_deep(report(2,WET),report(0,AIR))['deep_terrain_occupancy_changes'],1)
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

if __name__=='__main__':
    result=unittest.TextTestRunner(verbosity=2).run(unittest.defaultTestLoader.loadTestsFromTestCase(CaveMetricsTest))
    p=pathlib.Path('build/reports/cave-metrics-tests.json');p.parent.mkdir(parents=True,exist_ok=True)
    p.write_text(json.dumps({'tests':result.testsRun,'failures':len(result.failures),'errors':len(result.errors),'skipped':len(result.skipped)},indent=2)+'\n')
    raise SystemExit(0 if result.wasSuccessful() else 1)
