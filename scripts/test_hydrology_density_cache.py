"""Native terrain caches may reuse results, never change the density graph."""
import copy
import unittest
from validate_hydrology_patch import check_cached_density_graphs


class DensityCacheScopeTests(unittest.TestCase):
    def fixture(self):
        old_surface = {"type": "minecraft:add", "left": "terrain", "right": "detail"}
        old_caves = {"type": "minecraft:min", "left": "cave_noise", "right": "noodle_noise"}
        cap = {"type": "minecraft:range_choice", "input": "freshgenerator:highlands_v2/coherent/hydrology/cut",
               "min_inclusive": 0.25, "max_exclusive": 19,
               "when_in_range": {"type": "minecraft:min", "left": copy.deepcopy(old_surface),
                                 "right": "freshgenerator:highlands_v2/coherent/detail/original_surface_density"},
               "when_out_of_range": copy.deepcopy(old_surface)}
        return old_surface, old_caves, {"type": "minecraft:cache", "input": cap}, \
            {"type": "minecraft:cache", "input": copy.deepcopy(old_caves)}

    def test_exact_two_transparent_wrappers_are_accepted(self):
        check_cached_density_graphs(*self.fixture())

    def test_native_noise_cannot_be_replaced_under_a_cache_label(self):
        data = self.fixture()
        data[3]['input']['right'] = 0
        with self.assertRaisesRegex(ValueError, 'original density graph'):
            check_cached_density_graphs(*data)

    def test_cap_bounds_and_uncut_exterior_cannot_change(self):
        for key, value in [('min_inclusive', 0.0), ('max_exclusive', 300), ('when_out_of_range', 0.0)]:
            with self.subTest(key=key):
                data = self.fixture(); data[2]['input'][key] = value
                with self.assertRaisesRegex(ValueError, 'surface cap'):
                    check_cached_density_graphs(*data)

    def test_missing_cache_or_resampling_is_not_accepted(self):
        for index in (2, 3):
            for kind in (None, 'minecraft:interpolated'):
                data = list(self.fixture())
                if kind is None: data[index] = data[index]['input']
                else: data[index]['type'] = kind
                with self.subTest(index=index, kind=kind), self.assertRaises(ValueError):
                    check_cached_density_graphs(*data)

    def test_arithmetic_order_and_extra_fields_remain_strict(self):
        data = self.fixture(); data[3]['input']['left'], data[3]['input']['right'] = \
            data[3]['input']['right'], data[3]['input']['left']
        with self.assertRaises(ValueError): check_cached_density_graphs(*data)
        data = self.fixture(); data[2]['cell_size_xz'] = 32
        with self.assertRaises(ValueError): check_cached_density_graphs(*data)


if __name__ == '__main__':
    unittest.main(verbosity=2)
