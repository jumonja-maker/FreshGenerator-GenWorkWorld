"""Network-free checks of the real Java core; not a replacement for FULL chunk audits."""

import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
FIXTURE = ROOT / 'scripts/fixtures/hydrology-core-14254df.json'
PROBE = ROOT / 'scripts/java/DrainageCoreProbe.java'
CORE = ROOT / 'src/core/java/dev/freshgenerator/core'


def java_tool(name):
    home = os.environ.get('JAVA_HOME')
    executable = name + ('.exe' if os.name == 'nt' else '')
    if home:
        candidate = Path(home) / 'bin' / executable
        if candidate.is_file():
            return str(candidate)
    found = shutil.which(executable)
    if found:
        return found
    raise RuntimeError(f'{name} is required for the hydrology core checks (JDK 21+; project builds use 25)')


class HydrologyCoreRegression(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.fixture = json.loads(FIXTURE.read_text(encoding='utf-8'))
        cls.expected = {(r['seed'], r['shape']): r for r in cls.fixture['fixtures']}
        if len(cls.expected) != 25:
            raise AssertionError('Expected five terrains on five seeds; fixture coverage changed')
        java, javac = java_tool('java'), java_tool('javac')
        version = subprocess.run([java, '-version'], text=True, capture_output=True, check=True, timeout=15)
        cls.results = {}
        with tempfile.TemporaryDirectory(prefix='fresh-hydrology-core-') as folder:
            command = [javac, '-encoding', 'UTF-8', '-d', folder,
                       str(CORE / 'DrainageModel.java'), str(CORE / 'DrainageBasin.java'), str(PROBE)]
            subprocess.run(command, text=True, capture_output=True, check=True, timeout=45)
            for mode in ('forward', 'reverse', 'parallel'):
                result = subprocess.run([java, '-Xmx768m', '-cp', folder, 'DrainageCoreProbe', mode],
                                        text=True, capture_output=True, check=True, timeout=90)
                rows = [json.loads(line) for line in result.stdout.splitlines() if line.strip()]
                keys = [(r['seed'], r['shape']) for r in rows]
                if len(keys) != len(set(keys)) or set(keys) != set(cls.expected):
                    raise AssertionError(f'Incomplete or duplicate core observations in {mode}')
                cls.results[mode] = rows
        report = {'scope': 'Java core on synthetic terrain; no Minecraft/Fabric runtime',
                  'reference_commit': cls.fixture['reference_commit'],
                  'java': (version.stderr or version.stdout).strip(), 'observations': cls.results}
        destination = ROOT / 'build/reports/village-release/hydrology-core-observations.json'
        destination.parent.mkdir(parents=True, exist_ok=True)
        destination.write_text(json.dumps(report, indent=2) + '\n', encoding='utf-8')

    def compare_mode(self, mode):
        for actual in self.results[mode]:
            with self.subTest(mode=mode, seed=actual['seed'], terrain=actual['shape']):
                expected = self.expected[actual['seed'], actual['shape']]
                self.assertEqual(actual['order'], mode)
                for field in ('tiles', 'columns', 'sha256'):
                    self.assertEqual(actual[field], expected[field], field)

    def test_forward_matches_pre_optimization_geometry(self):
        self.compare_mode('forward')

    def test_reverse_matches_pre_optimization_geometry(self):
        self.compare_mode('reverse')

    def test_parallel_matches_pre_optimization_geometry(self):
        self.compare_mode('parallel')

    def test_collision_heavy_terrain_does_not_repeat_the_old_sampler_work(self):
        old = [r for r in self.expected.values() if r['shape'] == 'rugged']
        new = [r for r in self.results['forward'] if r['shape'] == 'rugged']
        # Count deterministic sampler work, not wall time (which depends on the runner).
        for field in ('height_calls', 'moisture_calls', 'contexts'):
            self.assertLess(sum(r[field] for r in new), sum(r[field] for r in old) // 4, field)


if __name__ == '__main__':
    unittest.main(verbosity=2)
