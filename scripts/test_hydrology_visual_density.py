"""Compare actual Java 0.3.7 vs 0.3.8 stream/lake footprint on fixed regions.

Synthetic terrain gives measurable network density and a repeatable safety signal,
but it does not replace screenshots or gameplay-world generator observations.
"""
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

from test_hydrology_core import ROOT, CORE, java_tool

REFERENCE = ROOT / 'dist/FreshGenerator-Fabric-26.3-0.3.7-alpha.1.jar'
PROBE = ROOT / 'scripts/java/DrainageVisualDensityProbe.java'
SHAPES = ('valley', 'bowl', 'spill', 'rugged', 'rolling', 'flat')


def rows(text):
    values = [json.loads(line) for line in text.splitlines() if line.strip()]
    keyed = {(item['shape'], item['seed']): item for item in values}
    if len(values) != len(keyed) or len(values) != len(SHAPES) * 5:
        raise AssertionError('Incomplete fixed density observation')
    return keyed


class VisualHydrologyDensity(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        java, javac = java_tool('java'), java_tool('javac')
        if not REFERENCE.is_file():
            raise AssertionError('Published 0.3.7 JAR is required for density comparison')
        with tempfile.TemporaryDirectory(prefix='fresh-visual-density-') as folder:
            root = Path(folder)
            old = root / 'published'
            new = root / 'candidate'
            old.mkdir()
            new.mkdir()
            def run(command):
                return subprocess.run(command, check=True, capture_output=True, text=True, timeout=130)
            run([javac, '-encoding', 'UTF-8', '-cp', str(REFERENCE), '-d', str(old), str(PROBE)])
            run([javac, '-encoding', 'UTF-8', '-d', str(new),
                 str(CORE / 'DrainageModel.java'), str(CORE / 'DrainageBasin.java'), str(PROBE)])
            cls.before = rows(run([java, '-Xmx1G', '-cp', os.pathsep.join((str(old), str(REFERENCE))),
                                   'DrainageVisualDensityProbe']).stdout)
            cls.after = rows(run([java, '-Xmx1G', '-cp', str(new),
                                  'DrainageVisualDensityProbe']).stdout)
        destination = ROOT / 'build/reports/village-release/hydrology-visual-density.json'
        destination.parent.mkdir(parents=True, exist_ok=True)
        stats = []
        for key in sorted(cls.before):
            old, now = cls.before[key], cls.after[key]
            stats.append({'shape':key[0], 'seed':key[1], 'before':old, 'after':now})
        destination.write_text(json.dumps({'scope':'fixed synthetic 128m tiles sampled every 4 blocks; not real Minecraft FULL world',
                                            'reference':'0.3.7-alpha.1', 'candidate':'0.3.8-alpha.1',
                                            'observations':stats}, indent=2) + '\\n', encoding='utf8')

    def test_all_coordinates_are_identical(self):
        for key in self.before:
            with self.subTest(key=key):
                self.assertEqual(self.before[key]['samples'], self.after[key]['samples'])
                self.assertEqual(12 * 32 * 32, self.before[key]['samples'])

    def test_flat_terrain_does_not_gain_water(self):
        for key in self.before:
            if key[0] != 'flat':
                continue
            self.assertEqual(0, self.after[key]['river'])
            self.assertEqual(0, self.after[key]['lake'])

    def test_fewer_river_surface_columns_than_published(self):
        previous = sum(v['river'] for v in self.before.values())
        candidate = sum(v['river'] for v in self.after.values())
        self.assertGreater(previous, 0)
        self.assertLess(candidate, previous * .95,
                        f'River water density did not decline: {previous} -> {candidate}')

    def test_lakes_are_not_more_common_in_the_sample(self):
        previous = sum(v['lake'] for v in self.before.values())
        candidate = sum(v['lake'] for v in self.after.values())
        self.assertGreater(previous, 0)
        self.assertLessEqual(candidate, previous,
                             f'New lake footprint grew: {previous} -> {candidate}')

    def test_main_rivers_and_deep_lakes_remain_present(self):
        self.assertGreater(sum(v['river'] for k,v in self.after.items() if k[0] == 'valley'), 0)
        self.assertGreater(sum(v['lake'] for k,v in self.after.items() if k[0] in ('bowl', 'spill')), 0)


if __name__ == '__main__':
    unittest.main()
