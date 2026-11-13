"""Paired synthetic morphology measurements, not a claim about client rendering."""
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from test_hydrology_core import ROOT, CORE, BASELINE, java_tool

PROBE = ROOT / 'scripts/java/DrainageMorphologyProbe.java'


class HydrologyMorphologyRegression(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.rows = {}
        java, javac = java_tool('java'), java_tool('javac')
        with tempfile.TemporaryDirectory(prefix='fresh-morphology-') as folder:
            for mode in ('baseline', 'candidate'):
                classes = Path(folder) / mode
                classes.mkdir()
                inputs = (['-cp', str(BASELINE)] if mode == 'baseline' else
                          [str(CORE / 'DrainageModel.java'), str(CORE / 'DrainageBasin.java')])
                subprocess.run([javac, '-encoding', 'UTF-8', '-d', str(classes), *inputs, str(PROBE)],
                               check=True, capture_output=True, text=True, timeout=45)
                cp = os.pathsep.join((str(classes), str(BASELINE))) if mode == 'baseline' else str(classes)
                result = subprocess.run([java, '-Xmx1g', '-cp', cp, 'DrainageMorphologyProbe'],
                                        check=True, capture_output=True, text=True, timeout=120)
                cls.rows[mode] = [json.loads(line) for line in result.stdout.splitlines() if line.strip()]
        keys = lambda rows: {(r['shape'], r['seed']) for r in rows}
        if any(len(r) != 30 or len(keys(r)) != 30 for r in cls.rows.values()):
            raise AssertionError('Incomplete six-terrain/five-seed morphology matrix')
        if keys(cls.rows['baseline']) != keys(cls.rows['candidate']):
            raise AssertionError('Unpaired coordinates or seeds')
        report = {'scope': 'Synthetic core geometry: six terrains on five seeds, NOT Minecraft screenshots',
                  'baseline_jar': BASELINE.name,
                  'baseline_sha256': hashlib.sha256(BASELINE.read_bytes()).hexdigest(),
                  'source_sha256': {p.name: hashlib.sha256(p.read_bytes()).hexdigest()
                                    for p in (CORE / 'DrainageModel.java', CORE / 'DrainageBasin.java')},
                  'observations': cls.rows}
        out = ROOT / 'build/reports/village-release/morphology-observations.json'
        out.parent.mkdir(parents=True, exist_ok=True)
        out.write_text(json.dumps(report, indent=2) + '\n')

    def test_unconfined_sheet_flow_does_not_become_parallel_channels(self):
        for row in self.rows['candidate']:
            if row['shape'] == 'plane':
                self.assertEqual(row['wet'], 0)
                self.assertEqual(row['mean_abs_cut'], 0)

    def test_native_submerged_columns_never_become_new_dikes(self):
        checked = 0
        for row in self.rows['candidate']:
            if row['native_water_columns']:
                checked += row['native_water_columns']
                self.assertEqual(row['changed_native_water'], 0, row)
                self.assertEqual(row['raised_native_water'], 0, row)
        self.assertGreater(checked, 500_000)

    def test_controls_reproduce_the_reported_failure_classes(self):
        planes = [r for r in self.rows['baseline'] if r['shape'] == 'plane']
        self.assertTrue(all(r['wet'] > r['columns'] * 0.8 for r in planes))
        self.assertTrue(all(r['raised_native_water'] > 0 for r in self.rows['baseline']
                            if r['shape'] == 'coastal'))

    def test_density_reduction_does_not_erase_confined_rivers_or_lakes(self):
        for row in self.rows['candidate']:
            if row['shape'] == 'valley':
                self.assertGreater(row['wet'], 1_000)
                self.assertLess(row['wet'], row['columns'] * 0.15)
            if row['shape'] == 'rugged':
                self.assertGreater(row['lake'], 100)
                self.assertGreater(row['wet'] - row['lake'], 100)

    def test_rugged_terrain_has_fewer_but_not_zero_watercourses(self):
        old = {(r['shape'], r['seed']): r for r in self.rows['baseline']}
        for row in self.rows['candidate']:
            if row['shape'] == 'rugged':
                self.assertLess(row['wet'], old[row['shape'], row['seed']]['wet'] * 0.65)


if __name__ == '__main__':
    unittest.main(verbosity=2)
