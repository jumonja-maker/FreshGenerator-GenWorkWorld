"""Fail-closed tests for technical-only observer transformations."""
import unittest
from technical_observer import PHASE_SWITCH, CURRENT_SWITCH, current_climate_observer
from technical_generation_order import (
    FULL_REQUESTS, NORMAL_WINDOW, REMOTE_NORMAL_WINDOW, SCAN_METHOD,
    REPORT_MARKER, JAVA_PREPARATION, ordered_generation_observer,
)


def fixture():
    return '\n'.join([PHASE_SWITCH, 'phase=System.getenv("FG_REGION_MODE");',
                      NORMAL_WINDOW, FULL_REQUESTS, REPORT_MARKER, SCAN_METHOD,
                      'return null;', '}'])


class TechnicalObserverTest(unittest.TestCase):
    def test_exactly_the_historical_mode_switch_changes_in_legacy_mode(self):
        source='prefix\n'+PHASE_SWITCH+'\nsuffix'
        self.assertEqual(current_climate_observer(source, ordered=False),
                         'prefix\n'+CURRENT_SWITCH+'\nsuffix')
    def test_phase_metadata_is_retained(self):
        source=fixture(); result=current_climate_observer(source)
        self.assertIn('phase=System.getenv("FG_REGION_MODE");',result)
        self.assertEqual(source,fixture())
    def test_missing_switch_fails_closed(self):
        with self.assertRaises(ValueError): current_climate_observer('no phase')
    def test_duplicate_switch_fails_closed(self):
        with self.assertRaises(ValueError): current_climate_observer(PHASE_SWITCH*2)
    def test_double_preparation_fails_closed(self):
        with self.assertRaises(ValueError): current_climate_observer(current_climate_observer(fixture()))
    def test_invalid_input_fails_closed(self):
        for value in [None,3,{},[]]:
            with self.subTest(value=value),self.assertRaises(ValueError): current_climate_observer(value)
    def test_generation_anchors_are_required(self):
        for anchor in [FULL_REQUESTS,NORMAL_WINDOW,SCAN_METHOD,REPORT_MARKER]:
            with self.subTest(anchor=anchor),self.assertRaises(ValueError):
                ordered_generation_observer(fixture().replace(anchor,''))
    def test_duplicate_generation_anchors_are_rejected(self):
        for anchor in [FULL_REQUESTS,NORMAL_WINDOW,SCAN_METHOD,REPORT_MARKER]:
            with self.subTest(anchor=anchor),self.assertRaises(ValueError):
                ordered_generation_observer(fixture()+'\n'+anchor)
    def test_double_generation_preparation_is_rejected(self):
        with self.assertRaises(ValueError): ordered_generation_observer(ordered_generation_observer(fixture()))
    def test_generation_transformation_changes_only_declared_anchors(self):
        expected=fixture().replace(FULL_REQUESTS,'  prepareTechnicalChunks(windows);')
        expected=expected.replace(NORMAL_WINDOW,REMOTE_NORMAL_WINDOW)
        expected=expected.replace(SCAN_METHOD,JAVA_PREPARATION+'\n'+SCAN_METHOD)
        expected=expected.replace(REPORT_MARKER,REPORT_MARKER+'out.add("technical_preparation",technicalPreparation);')
        self.assertEqual(ordered_generation_observer(fixture()),expected)
    def test_default_prepares_current_climate_and_native_barriers(self):
        result=current_climate_observer(fixture())
        self.assertIn(CURRENT_SWITCH,result)
        self.assertNotIn(PHASE_SWITCH,result)
        self.assertIn('  prepareTechnicalChunks(windows);',result)
        self.assertIn('native-status-barriers-v1',result)
    def test_generation_copy_is_repeatable_and_has_no_block_writes(self):
        self.assertEqual(current_climate_observer(fixture()),current_climate_observer(fixture()))
        for unsafe in ['setBlock(', 'removeBlock(', 'setBiome(', 'delete(', 'Random(']:
            self.assertNotIn(unsafe,JAVA_PREPARATION)
    def test_remote_normal_control_is_fixed_not_searched(self):
        self.assertIn('8192,8192',REMOTE_NORMAL_WINDOW)
        self.assertNotIn('findBiome',JAVA_PREPARATION)
    def test_initial_decorated_windows_fail_instead_of_skipping_samples(self):
        self.assertIn('if(!reload)throw new AssertionError',JAVA_PREPARATION)
        self.assertIn('chunk.getPersistedStatus()!=terrain',JAVA_PREPARATION)


if __name__=='__main__': unittest.main(verbosity=2)
