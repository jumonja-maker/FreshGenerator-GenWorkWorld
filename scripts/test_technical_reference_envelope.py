"""Synthetic rejection tests, NOT claims about actual Minecraft world outcomes."""
import copy,hashlib,json,unittest
from pathlib import Path
from technical_reference_envelope import states,validate_full,validate_telemetry,FAMILIES


def window(values=None):
    values = values or ['Block{minecraft:stone}'] * (256 * 384)
    palette=list(dict.fromkeys(values));indices={s:i for i,s in enumerate(palette)}
    return {'label':'control','x':-32,'z':-48,'sample_columns':256,
            'base_geometry_water_sha256':'a'*64,'biomes':{'minecraft:forest':256},
            'block_palette':palette,'sampled_states':[indices[v] for v in values],
            'sampled_full_blocks_sha256':hashlib.sha256(''.join(v+'\n' for v in values).encode()).hexdigest()}


def change(w,index,state):
    result=states(w);result[index]=state;return window(result)


def telemetry(attempts=8,kept=6):
    forest={f+s:0 for f in FAMILIES for s in ('_attempts','_kept','_opening_attempts')}
    forest.update(TEMPERATE_attempts=attempts,TEMPERATE_kept=kept,TEMPERATE_opening_attempts=1 if attempts else 0)
    return {'forest_telemetry':forest,'cave_telemetry':{'guard_instances':3},
            'forest_height_oracle':{'verified_calls':100 if attempts else 0,'hits':70 if attempts else 0,
                                   'misses':30 if attempts else 0,'mismatches':0}}


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
    def test_counter_difference_never_passes_without_native_oracle(self):
        a=telemetry();b=telemetry(9,7);del b['forest_height_oracle']
        with self.assertRaises(ValueError):validate_telemetry([a,a,a],b)
    def test_counter_difference_is_explicit_with_per_call_proof(self):
        a=telemetry();b=telemetry(9,7);r=validate_telemetry([a,a,a],b)
        self.assertFalse(r['forest_counts_equal_complete_observed_reference'])
        self.assertTrue(r['native_height_cache_protocol_verified'])
        self.assertEqual(b['forest_height_oracle'],r['candidate_height_oracle'])
    def test_carving_difference_is_not_excused(self):
        a=telemetry();b=copy.deepcopy(a);b['cave_telemetry']['guard_instances']=4
        with self.assertRaises(ValueError):validate_telemetry([a,a,a],b)
    def test_complete_matching_forest_snapshot_is_reported(self):
        a=telemetry();b=telemetry(9,7);r=validate_telemetry([a,b,a],b)
        self.assertFalse(r['forest_counts_equal_first_reference']);self.assertTrue(r['forest_counts_equal_complete_observed_reference'])
    def test_actual_cache_mismatch_fails_even_if_counters_equal(self):
        a=telemetry();b=copy.deepcopy(a);b['forest_height_oracle']['mismatches']=1
        with self.assertRaises(ValueError):validate_telemetry([a,a,a],b)
    def test_reference_oracle_failure_is_not_excused(self):
        a=telemetry();r=copy.deepcopy(a);r['forest_height_oracle']['mismatches']=1
        with self.assertRaises(ValueError):validate_telemetry([a,r,a],a)
    def test_uninstrumented_reference_fails(self):
        a=telemetry();r=copy.deepcopy(a);del r['forest_height_oracle']
        with self.assertRaises(ValueError):validate_telemetry([a,r,a],a)
    def test_oracle_arithmetic_must_match_completed_calls(self):
        a=telemetry();b=copy.deepcopy(a);b['forest_height_oracle']['hits']-=1
        with self.assertRaises(ValueError):validate_telemetry([a,a,a],b)
    def test_zero_oracle_cannot_cover_nonzero_attempts(self):
        a=telemetry();b=copy.deepcopy(a);b['forest_height_oracle']=telemetry(0,0)['forest_height_oracle']
        with self.assertRaises(ValueError):validate_telemetry([a,a,a],b)
    def test_counts_cannot_keep_more_than_attempted(self):
        a=telemetry();b=telemetry(8,9)
        with self.assertRaises(ValueError):validate_telemetry([a,a,a],b)
    def test_negative_counter_fails(self):
        a=telemetry();b=copy.deepcopy(a);b['forest_telemetry']['TEMPERATE_kept']=-1
        with self.assertRaises(ValueError):validate_telemetry([a,a,a],b)
    def test_bool_is_not_a_measured_counter(self):
        a=telemetry();b=copy.deepcopy(a);b['forest_height_oracle']['mismatches']=False
        with self.assertRaises(ValueError):validate_telemetry([a,a,a],b)
    def test_normal_world_may_have_no_forest_calls(self):
        a=telemetry(0,0);self.assertTrue(validate_telemetry([a,a,a],a)['native_height_cache_protocol_verified'])
    def test_incomplete_forest_counter_schema_fails(self):
        a=telemetry();del a['forest_telemetry']['TEMPERATE_kept']
        with self.assertRaises(ValueError):validate_telemetry([a,a,a],a)
    def test_no_sampling_misses_cannot_validate_a_forest(self):
        a=telemetry();b=copy.deepcopy(a);b['forest_height_oracle'].update(hits=100,misses=0)
        with self.assertRaises(ValueError):validate_telemetry([a,a,a],b)

if __name__=='__main__':
 result=unittest.TextTestRunner(verbosity=2).run(unittest.defaultTestLoader.loadTestsFromTestCase(ReferenceEnvelopeTest))
 p=Path('build/reports/technical-reference-tests.json');p.parent.mkdir(parents=True,exist_ok=True)
 p.write_text(json.dumps({'tests':result.testsRun,'failures':len(result.failures),'errors':len(result.errors),'skipped':len(result.skipped)},indent=2)+'\n')
 raise SystemExit(not result.wasSuccessful())
