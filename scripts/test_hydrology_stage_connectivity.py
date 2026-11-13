"""Verify actual shared water volume, including a one-step lake outlet."""
import unittest
from hydrology_checks import SIDE, spillway_metrics


class SpillwayConnectivityTest(unittest.TestCase):
    @staticmethod
    def column(stage, floor, lake=False):
        flags = 3 if lake else 0
        profile = (stage << 16) | ((floor & 0x3fff) << 2) | flags
        return [floor, floor, stage, floor, 1, profile]

    def measure(self, first, second, third=None):
        columns = [[0, 0, -999, -999, 0, 0] for _ in range(SIDE * SIDE)]
        i = 16 * SIDE + 16
        columns[i], columns[i + 1] = first, second
        selected = [i, i + 1]
        if third is not None:
            columns[i + 2] = third
            selected.append(i + 2)
        return spillway_metrics(columns, selected)

    def test_equal_stage_reach_then_drop_is_still_an_outlet(self):
        self.assertEqual(self.measure(self.column(68, 66, True),
                                      self.column(68, 65), self.column(67, 65)), (1, 1))

    def test_immediate_lower_stage_with_shared_water_is_an_outlet(self):
        # The upper column contains water at Y67 and Y68; the next at Y66/Y67.
        # Requiring an equal-height surface would falsely reject their shared Y67.
        self.assertEqual(self.measure(self.column(68, 66, True),
                                      self.column(67, 65)), (1, 1))

    def test_diagonal_water_above_a_solid_lip_is_not_connected(self):
        self.assertEqual(self.measure(self.column(68, 67, True),
                                      self.column(67, 66)), (0, 0))

    def test_uphill_contact_does_not_certify_a_downstream_outlet(self):
        self.assertEqual(self.measure(self.column(68, 66, True),
                                      self.column(69, 66), self.column(67, 65)), (0, 0))


if __name__ == '__main__':
    unittest.main(verbosity=2)
