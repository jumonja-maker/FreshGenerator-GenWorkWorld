"""Synthetic rejection tests, NOT claims about actual Minecraft world outcomes."""
import copy,hashlib,json,unittest
from pathlib import Path
from technical_reference_envelope import states,validate_full,validate_telemetry


def window(values=None):
    values = values or ['Block{minecraft:stone}'] * (256 * 384)
    palette=list(dict.fromkeys(values));indices={s:i for i,s in enumerate(palette)}
    return {'label':'control','x':-32,'z':-48,'sample_columns':256,
            'base_geometry_water_sha256':'a'*64,'biomes':{'minecraft:forest':256},
            'block_palette':palette,'sampled_states':[indices[v] for v in values],
            'sampled_full_blocks_sha256':hashlib.sha256(''.join(v+'\n' for v in values).encode()).hexdigest()}


def change(w,index,state):
    result=states(w);result[index]=state;return window(result)


class ReferenceEnvelopeTest(unittest.TestCase):
    def setUp(self):self.w=window();self.controls=[[self.w] for _ in range(3)]
    def test_exact_world(self):self.assertEqual(0,validate_full(self.controls,[self.w])['reference_variable_blocks'])
    def test_one_exact_observed_alternative_is_reported_not_hidden(self):
        other=change(self.w,6,'Block{minecraft:andesite}');self.controls[1]=[other]
        r=validate_full(self.controls,[other]);self.assertEqual(1,r['reference_variable_blocks']);self.assertEqual(1,r['candidate_changes_from_first_reference'])
    def test_unknown_candidate_block_fails(self):
        with self.assertRaises(ValueError):validate_full(self.controls,[change(self.w,6,'Block{minecraft:air}')])
    def test_known_alternative_at_wrong_coordinate_still_fails(self):
        self.controls[1]=[change(self.w,6,'Block{minecraft:leaf_litter}')]
        with self.assertRaises(ValueError):validate_full(self.controls,[change(self.w,7,'Block{minecraft:leaf_litter}')])
    def test_unknown_third_state_not_excused(self):
        self.controls[1]=[change(self.w,6,'Block{minecraft:andesite}')]
        with self.assertRaises(ValueError):validate_full(self.controls,[change(self.w,6,'Block{minecraft:granite}')])
    def test_water_geometry_hash_is_always_exact(self):
        c=copy.deepcopy(self.w);c['base_geometry_water_sha256']='b'*64
        with self.assertRaises(ValueError):validate_full(self.controls,[c])
    def test_stored_biome_change_is_always_rejected(self):
        c=copy.deepcopy(self.w);c['biomes']={'minecraft:desert':256}
        with self.assertRaises(ValueError):validate_full(self.controls,[c])
    def test_changed_coordinates_are_not_same_window(self):
        c=copy.deepcopy(self.w);c['x']+=4
        with self.assertRaises(ValueError):validate_full(self.controls,[c])
    def test_all_three_references_are_required(self):
        with self.assertRaises(ValueError):validate_full(self.controls[:2],[self.w])
    def test_incomplete_palette_array_rejected(self):
        w=copy.deepcopy(self.w);w['sampled_states'].pop()
        with self.assertRaises(ValueError):states(w)
    def test_invalid_palette_index_rejected(self):
        w=copy.deepcopy(self.w);w['sampled_states'][0]=-1
        with self.assertRaises(ValueError):states(w)
    def test_hash_must_match_actual_blocks(self):
        w=copy.deepcopy(self.w);w['sampled_full_blocks_sha256']='b'*64
        with self.assertRaises(ValueError):states(w)
    def test_large_control_variance_is_not_usable(self):
        values=states(self.w);values[:100]=['Block{minecraft:air}']*100;other=window(values);self.controls[1]=[other]
        with self.assertRaises(ValueError):validate_full(self.controls,[self.w])
    def test_telemetry_requires_complete_snapshot_not_ranges(self):
        refs=[{'forest_telemetry':{'a':1,'b':2},'cave_telemetry':{'c':3}},
              {'forest_telemetry':{'a':2,'b':1},'cave_telemetry':{'c':3}},
              {'forest_telemetry':{'a':1,'b':2},'cave_telemetry':{'c':3}}]
        with self.assertRaises(ValueError):validate_telemetry(refs,{'forest_telemetry':{'a':1,'b':1},'cave_telemetry':{'c':3}})
    def test_carving_difference_is_not_excused(self):
        a={'forest_telemetry':{'a':1},'cave_telemetry':{'c':3}};b=copy.deepcopy(a);b['cave_telemetry']['c']=4
        with self.assertRaises(ValueError):validate_telemetry([a,a,a],b)
    def test_complete_matching_forest_snapshot(self):
        a={'forest_telemetry':{'a':1},'cave_telemetry':{'c':3}};b=copy.deepcopy(a);b['forest_telemetry']['a']=2
        r=validate_telemetry([a,b,a],b);self.assertFalse(r['forest_counts_equal_first_reference'])

if __name__=='__main__':
 result=unittest.TextTestRunner(verbosity=2).run(unittest.defaultTestLoader.loadTestsFromTestCase(ReferenceEnvelopeTest))
 p=Path('build/reports/technical-reference-tests.json');p.parent.mkdir(parents=True,exist_ok=True)
 p.write_text(json.dumps({'tests':result.testsRun,'failures':len(result.failures),'errors':len(result.errors),'skipped':len(result.skipped)},indent=2)+'\n')
 raise SystemExit(not result.wasSuccessful())
