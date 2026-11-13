import copy
import unittest
from hydrology_settling_checks import check, SIDE


class SettlingEvidenceTest(unittest.TestCase):
    def setUp(self):
        p = (68 << 16) | (67 << 2) | 1
        profiles = [p] + [0] * (SIDE * SIDE - 1)
        site = {'kind': 'throughflow-lake', 'x': 3936, 'z': -640}
        self.plan = {'seed': 1337, 'windows': [{'site': site, 'profiles': profiles}]}
        sha = 'a' * 64
        self.meta = {'seed': 1337, 'jar_sha256': sha, 'completed_server_ticks': 1000,
                     'native_fluid_updates': True, 'halo_generated_before_snapshot': True,
                     'forced_chunks': 25, 'checkpoints': [
                         {'tick': t, 'planned_columns': 1, 'sources_or_ice_at_planned_stage': 1, 'missing': []}
                         for t in (0, 200, 1000)]}
        data = {'mode': 'candidate', 'seed': 1337, 'jar_sha256': sha, 'settings': 'freshgenerator:highlands_v2',
                'minecraft': '26.3', 'fabric_loader': '0.20.1', 'java': '25.0.1', 'freshgenerator_loaded': True,
                'full_chunks': 9, 'windows': [{'site': site, 'columns': [[68, 67, 68, 67, 1, p]] +
                    [[70, 70, -999, -999, 0, 0] for _ in range(SIDE * SIDE - 1)], 'modelGround': [67] + [70] * (SIDE * SIDE - 1)}]}
        self.snap = {t: copy.deepcopy(data) for t in (0, 200, 1000)}
    def result(self): return check(self.plan, self.snap, self.meta)
    def test_complete_stable_water_passes(self): self.assertTrue(self.result()['new_dry_land_flooding_pass'])
    def test_one_newly_flooded_dry_column_fails(self):
        self.snap[200]['windows'][0]['columns'][1][2] = 70
        self.assertFalse(self.result()['new_dry_land_flooding_pass'])
    def test_missing_source_cannot_hide_beneath_flowing_water(self):
        self.meta['checkpoints'][2]['sources_or_ice_at_planned_stage'] = 0
        with self.assertRaises(ValueError): self.result()
    def test_ground_height_change_still_fails(self):
        self.snap[1000]['windows'][0]['modelGround'][1] += 1
        self.assertFalse(self.result()['new_dry_land_flooding_pass'])
    def test_in_channel_transient_is_recorded_not_called_an_exact_surface(self):
        for t in (200,1000): self.snap[t]['windows'][0]['columns'][0][2] += 1
        report = self.result()
        self.assertTrue(report['new_dry_land_flooding_pass'])
        self.assertEqual(1, report['windows'][0]['changed_existing_water_tops'][0]['difference'])
    def test_multi_block_rise_cannot_hide_inside_already_wet_channel(self):
        for t in (200, 1000): self.snap[t]['windows'][0]['columns'][0][2] += 4
        report = self.result()
        self.assertFalse(report['new_dry_land_flooding_pass'])
        self.assertEqual(4, report['windows'][0]['large_water_stage_changes'][0]['difference'])
    def test_multi_block_drop_also_fails(self):
        for t in (200, 1000): self.snap[t]['windows'][0]['columns'][0][2] -= 2
        self.assertFalse(self.result()['new_dry_land_flooding_pass'])
    def test_continued_rise_at_1000_fails(self):
        self.snap[1000]['windows'][0]['columns'][0][2] += 1
        self.assertFalse(self.result()['new_dry_land_flooding_pass'])
    def test_missing_or_shortened_tick_experiment_fails(self):
        self.meta['completed_server_ticks'] = 200
        with self.assertRaises(ValueError): self.result()
    def test_mutated_runtime_or_sites_cannot_be_substituted(self):
        self.snap[200]['jar_sha256'] = 'b' * 64
        with self.assertRaises(ValueError): self.result()
    def test_unobserved_halo_cannot_pass(self):
        self.meta['halo_generated_before_snapshot'] = False
        with self.assertRaises(ValueError): self.result()
    def test_new_lava_or_unsupported_floor_still_fails(self):
        self.snap[200]['windows'][0]['columns'][0][4] |= 8
        self.assertFalse(self.result()['new_dry_land_flooding_pass'])

if __name__ == '__main__': unittest.main()
