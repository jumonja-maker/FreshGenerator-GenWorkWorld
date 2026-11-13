"""Plant telemetry is evidence, never a waiver for a changed water/bank profile."""
import copy
import unittest
from hydrology_checks import SIDE, hydrology_signature


class BankEvidenceTests(unittest.TestCase):
    @staticmethod
    def sample():
        cells = [[70, 70, -999, -999, 0, 0] for _ in range(SIDE * SIDE)]
        center = (SIDE // 2) * SIDE + SIDE // 2
        cells[center] = [70, 68, 70, 68, 1, (70 << 16) | (68 << 2)]
        return {'windows': [{'columns': cells, 'modelGround': [70] * len(cells),
                             'bankPlantStates': {}}]}, center

    def test_recorded_plant_is_not_itself_a_terrain_change(self):
        a, center = self.sample(); b = copy.deepcopy(a)
        b['windows'][0]['bankPlantStates'] = {'1,71,0': 'Block{minecraft:pumpkin}'}
        self.assertEqual(hydrology_signature(a), hydrology_signature(b))

    def test_recorded_melon_keeps_the_real_ground_and_water_signature(self):
        a, center = self.sample(); b = copy.deepcopy(a)
        b['windows'][0]['bankPlantStates'] = {'1,71,0': 'Block{minecraft:melon}'}
        self.assertEqual(hydrology_signature(a), hydrology_signature(b))

    def test_melon_record_cannot_waive_a_changed_bank_or_missing_source(self):
        a, center = self.sample()
        for kind in ('bank', 'source'):
            with self.subTest(kind=kind):
                b = copy.deepcopy(a)
                b['windows'][0]['bankPlantStates'] = {'1,71,0': 'Block{minecraft:melon}'}
                if kind == 'bank':
                    b['windows'][0]['modelGround'][center + 1] += 1
                else:
                    b['windows'][0]['columns'][center][2] = -999
                self.assertNotEqual(hydrology_signature(a), hydrology_signature(b))

    def test_plant_record_cannot_excuse_a_raised_or_missing_dry_bank(self):
        a, center = self.sample()
        for height in (71, 69, -999):
            with self.subTest(height=height):
                b = copy.deepcopy(a)
                b['windows'][0]['bankPlantStates'] = {'1,71,0': 'Block{minecraft:bamboo}'}
                b['windows'][0]['modelGround'][center + 1] = height
                self.assertNotEqual(hydrology_signature(a), hydrology_signature(b))

    def test_plant_record_cannot_excuse_water_bed_or_support_changes(self):
        a, center = self.sample()
        for component, value in ((2, 69), (3, 67), (4, 5)):
            with self.subTest(component=component):
                b = copy.deepcopy(a)
                b['windows'][0]['bankPlantStates'] = {'0,71,0': 'Block{minecraft:pumpkin}'}
                b['windows'][0]['columns'][center][component] = value
                self.assertNotEqual(hydrology_signature(a), hydrology_signature(b))


if __name__ == '__main__':
    unittest.main(verbosity=2)
