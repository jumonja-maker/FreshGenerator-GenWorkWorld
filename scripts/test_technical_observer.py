import unittest
from technical_observer import current_climate_observer, PHASE_SWITCH, CURRENT_SWITCH

class TechnicalObserverTest(unittest.TestCase):
    def test_exactly_the_historical_mode_switch_changes(self):
        source = "start;" + PHASE_SWITCH + "end;"
        self.assertEqual("start;" + CURRENT_SWITCH + "end;", current_climate_observer(source))
    def test_phase_metadata_is_retained(self):
        metadata = 'out.addProperty("mode",System.getenv("FG_REGION_MODE"));'
        self.assertEqual(CURRENT_SWITCH + metadata, current_climate_observer(PHASE_SWITCH + metadata))
    def test_missing_switch_fails_closed(self):
        with self.assertRaises(ValueError): current_climate_observer("unrelated")
    def test_duplicate_switch_fails_closed(self):
        with self.assertRaises(ValueError): current_climate_observer(PHASE_SWITCH * 2)
    def test_double_preparation_fails_closed(self):
        with self.assertRaises(ValueError): current_climate_observer(current_climate_observer(PHASE_SWITCH))
    def test_invalid_input_fails_closed(self):
        with self.assertRaises(ValueError): current_climate_observer(None)

if __name__ == "__main__":
    unittest.main(verbosity=2)
