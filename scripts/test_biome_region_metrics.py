import json,pathlib,unittest
from biome_region_metrics import stats,decode,family
class RegionMetricsTest(unittest.TestCase):
 def test_uniform_has_no_crossings_and_boundary_component_is_not_an_island(self):
  r=stats(['minecraft:forest']*25,5,16);self.assertEqual(r['exact_id_crossings'],0);self.assertEqual(r['interior_components'],0);self.assertEqual(r['boundary_components_not_used_for_area'],1)
 def test_single_interior_patch_is_measured(self):
  m=['minecraft:forest']*25;m[12]='minecraft:plains';r=stats(m,5,16);self.assertEqual(r['common_small_components_le4_cells'],1);self.assertEqual(r['exact_id_crossings'],4);self.assertEqual(r['common_component_area_median_blocks2'],256)
 def test_no_wrapping_at_row_edges(self):
  m=['minecraft:forest']*25;m[9]=m[10]='minecraft:desert';r=stats(m,5,1);self.assertEqual(r['interior_components'],0);self.assertEqual(r['boundary_components_not_used_for_area'],3)
 def test_diagonals_do_not_merge_patches(self):
  m=['minecraft:forest']*25;m[6]=m[12]='minecraft:plains';self.assertEqual(stats(m,5,1)['interior_components'],2)
 def test_local_rivers_are_not_classified_as_bad_small_common_biomes(self):
  self.assertIsNone(family('minecraft:river'));self.assertIsNone(family('minecraft:cherry_grove'));self.assertIsNone(family('other:forest'))
 def test_species_variants_are_not_hidden_from_exact_id_metric(self):
  m=['minecraft:forest']*25;m[12]='minecraft:birch_forest';r=stats(m,5,1);self.assertEqual(r['common_id_crossings'],4);self.assertEqual(r['common_family_crossings'],0)
 def test_palette_validation(self):
  self.assertRaises(ValueError,decode,{'side':2,'step':16,'palette':['x'],'cells':[0,0,-1,0]})
 def test_decode_uses_palette_not_numerical_ids(self):
  self.assertEqual(decode({'side':1,'step':1,'palette':['minecraft:forest'],'cells':[0]}),['minecraft:forest'])
if __name__=='__main__':
 result=unittest.TextTestRunner(verbosity=2).run(unittest.defaultTestLoader.loadTestsFromTestCase(RegionMetricsTest));p=pathlib.Path('build/reports/biome-region-metric-tests.json');p.parent.mkdir(parents=True,exist_ok=True)
 p.write_text(json.dumps({'tests':result.testsRun,'failures':len(result.failures),'errors':len(result.errors),'skipped':len(result.skipped)}));raise SystemExit(not result.wasSuccessful())
