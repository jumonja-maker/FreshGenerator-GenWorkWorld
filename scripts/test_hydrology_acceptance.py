"""Mutation tests for the new evidence gates. No synthetic case is a server run."""
import copy
import hashlib
import struct
import unittest
from unittest.mock import patch
import hydrology_checks as water
import hydrology_village_checks as village
import test_village_checks as historical
SHA = historical.SHA
from village_checks import BASE, PUBLISHED


def bits(value):
    return struct.unpack('!i', struct.pack('!f', value))[0]


def snapshot(height=80, changed=None):
    palette = ['Block{minecraft:stone}', 'Block{minecraft:water}[level=0]']
    ids = [0] * 192
    if changed is not None:
        ids[(changed + 64) // 2] = 1
    row = [0, 0, bits(80), bits(height), 0, *ids]
    h = hashlib.sha256()
    for i, index in enumerate(ids):
        h.update(f'0,{-64+2*i},0={palette[index]}\n'.encode())
    return {'outside_palette': palette, 'outside_columns': [row], 'outside_sha256': h.hexdigest()}


class HydrologyAcceptanceTests(unittest.TestCase):
    def test_only_observed_local_excavation_changes_are_allowed(self):
        report = village.compare_outside(snapshot(), snapshot(75, 76))
        self.assertEqual(1, report['changed_blocks_inside_hydrology_envelope'])

    def test_an_unchanged_height_cannot_excuse_an_outside_block_change(self):
        with self.assertRaises(ValueError): village.compare_outside(snapshot(), snapshot(80, 76))

    def test_deep_outside_changes_are_rejected(self):
        with self.assertRaises(ValueError): village.compare_outside(snapshot(), snapshot(75, 40))

    def test_original_height_cannot_be_moved_to_hide_deep_changes(self):
        data = snapshot(75, 76); data['outside_columns'][0][2] = bits(90)
        with self.assertRaises(ValueError): village.compare_outside(snapshot(), data)

    def test_incomplete_columns_and_forged_hashes_are_rejected(self):
        data = snapshot(); data['outside_columns'][0].pop()
        with self.assertRaises(ValueError): village.outside_rows(data)
        data = snapshot(); data['outside_sha256'] = '0' * 64
        with self.assertRaises(ValueError): village.outside_rows(data)

    def village_fixture(self):
        a, b, c = historical.VillageEvidenceTests().fixtures()
        old = copy.deepcopy(b); old.update(phase='published', source_commit=PUBLISHED)
        for report in (a, old, b, c):
            report['hydrology_observer_schema'] = 1
            for terrain in report['terrain']: terrain.update(snapshot())
        return [a, old, b, c]

    def test_complete_safe_village_is_accepted(self):
        self.assertEqual(3, village.validate(*self.village_fixture(), SHA, 42)['villages'])

    def test_deep_hash_failure_remains_fatal(self):
        reports = self.village_fixture(); reports[2]['terrain'][0]['deep_sha256'] = 'f' * 64
        with self.assertRaises(ValueError): village.validate(*reports, SHA, 42)

    def test_roads_farms_doors_and_reload_are_not_waived(self):
        mutations = [('path_blocks', 0, 2), ('farmland_blocks', 0, 2), ('doors', 0, 2),
                     ('protected_path_air_or_lava_columns', 1, 0), ('protected_farm_air_or_lava_columns', 1, 0),
                     ('path_columns_with_void_within_8', 1, 0)]
        for field, value, row in mutations:
            with self.subTest(field=field):
                reports = self.village_fixture()
                for report in reports[2:]: report['villages'][row][field] = value
                with self.assertRaises(ValueError): village.validate(*reports, SHA, 42)
        reports = self.village_fixture(); reports[3]['villages'][0]['full_sha256'] = 'a' * 64
        with self.assertRaises(ValueError): village.validate(*reports, SHA, 42)

    def normal_fixture(self):
        window = {'site': {'kind': 'control', 'x': 0, 'z': 0}, 'surfaceBiomes': {'minecraft:plains': 4},
                  'nativeGeometrySha256': 'a' * 64, 'columns': [[80, 79, -999, -999, 0, 0] for _ in range(4)],
                  'blockPalette': ['Block{minecraft:stone}', 'Block{minecraft:oak_log}', 'Block{minecraft:lava}'],
                  'fullBlockStates': [0] * (4 * 384)}
        return [{'windows': [copy.deepcopy(window)]} for _ in range(4)]

    def test_all_stable_normal_blocks_must_match(self):
        data = self.normal_fixture()
        with patch.object(water, 'SIDE', 2):
            self.assertEqual(0, water.compare_normal(data[:3], data[3])['unobserved_candidate_blocks'])
            data[3]['windows'][0]['fullBlockStates'][50] = 2
            with self.assertRaises(ValueError): water.compare_normal(data[:3], data[3])

    def test_native_variants_are_allowed_only_at_the_same_coordinate(self):
        data = self.normal_fixture()
        data[1]['windows'][0]['fullBlockStates'][50] = 1
        data[3]['windows'][0]['fullBlockStates'][50] = 1
        with patch.object(water, 'SIDE', 2):
            self.assertEqual(1, water.compare_normal(data[:3], data[3])['native_variable_blocks'])
            data[3]['windows'][0]['fullBlockStates'][51] = 1
            with self.assertRaises(ValueError): water.compare_normal(data[:3], data[3])

    def test_geometry_and_biome_changes_are_never_native_variants(self):
        for field, value in [('nativeGeometrySha256', 'b' * 64), ('surfaceBiomes', {'minecraft:desert': 4})]:
            data = self.normal_fixture(); data[3]['windows'][0][field] = value
            with patch.object(water, 'SIDE', 2), self.assertRaises(ValueError): water.compare_normal(data[:3], data[3])


if __name__ == '__main__':
    unittest.main(verbosity=2)
