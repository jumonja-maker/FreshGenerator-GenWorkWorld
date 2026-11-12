"""Exercise repeated structure audit invocations with a fake Gradle server."""
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


FAKE_GRADLE = """#!/usr/bin/env python3
import json
import os
from pathlib import Path

run = Path(os.environ.get('FG_AUDIT_RUN_DIR', 'run'))
props = dict(line.split('=', 1) for line in (run / 'server.properties').read_text().splitlines())
world = run / props['level-name']
reused = world.exists()
world.mkdir(exist_ok=True)
label = os.environ['FG_AUDIT_CASE']
ok = os.environ.get('FG_TEST_FAIL_CASE') != label
(run / 'freshgenerator-audit.json').write_text(json.dumps({
    'completed': ok, 'case': label, 'seed': int(props['level-seed']), 'reused': reused
}))
if ok and label.startswith('fg-'):
    (run / 'freshgenerator-envelope.json').write_text('{}')
    print('FRESHGENERATOR_ENVELOPE_PASS')
print('FRESHGENERATOR_AUDIT_PASS' if ok else 'FRESHGENERATOR_AUDIT_FAIL')
"""


class TechnicalStructureAuditTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        (self.root / 'scripts').mkdir()
        shutil.copyfile(Path(__file__).with_name('technical_structure_audit.sh'),
                        self.root / 'scripts' / 'technical_structure_audit.sh')
        (self.root / 'run').mkdir()
        (self.root / 'run' / 'server.properties').write_text('existing configuration\n')
        (self.root / 'bin').mkdir()
        fake = self.root / 'bin' / 'gradle'
        fake.write_text(FAKE_GRADLE)
        fake.chmod(0o755)
        subprocess.run(['git', 'init', '-q'], cwd=self.root, check=True)
        subprocess.run(['git', '-c', 'user.name=Audit Test', '-c', 'user.email=audit@example.invalid',
                        '-c', 'commit.gpgsign=false', 'commit', '--allow-empty', '-qm', 'Fixture'],
                       cwd=self.root, check=True)
        self.source = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=self.root, text=True).strip()

    def audit(self, seed=42, fail_case=''):
        output = self.root / 'step-output'
        output.write_text('')
        env = dict(os.environ, PATH=str(self.root / 'bin') + os.pathsep + os.environ['PATH'],
                   GITHUB_OUTPUT=str(output), FG_TEST_FAIL_CASE=fail_case)
        env.pop('GITHUB_SHA', None)
        result = subprocess.run(['bash', 'scripts/technical_structure_audit.sh', str(seed)],
                                cwd=self.root, env=env, capture_output=True, text=True)
        field, relative_path = output.read_text().strip().split('=', 1)
        self.assertEqual('report_dir', field)
        return result, self.root / relative_path

    def test_repeated_and_different_seeds_keep_fresh_worlds_and_exact_manifests(self):
        paths = []
        for seed in (42, 1337, 42):
            result, report = self.audit(seed)
            self.assertEqual(0, result.returncode, result.stderr)
            paths.append(report)
            meta = json.loads((report / f'meta-{seed}.json').read_text())
            self.assertEqual(self.source, meta['source_commit'])
            self.assertEqual(2, meta['actual_server_runs'])
            expected = {f'{mode}-{seed}.{suffix}' for mode in ('fg', 'vanilla')
                        for suffix in ('log', 'json', 'properties')} | {f'fg-{seed}-envelope.json'}
            self.assertEqual(expected, set(meta['files_sha256']))
            for name, digest in meta['files_sha256'].items():
                self.assertEqual(digest, hashlib.sha256((report / name).read_bytes()).hexdigest())
            for mode in ('fg', 'vanilla'):
                observation = json.loads((report / f'{mode}-{seed}.json').read_text())
                self.assertFalse(observation['reused'])
        self.assertEqual(3, len(set(paths)))
        self.assertEqual('existing configuration\n', (self.root / 'run' / 'server.properties').read_text())

    def test_failed_run_keeps_observation_without_success_manifest(self):
        result, report = self.audit(fail_case='vanilla-42')
        self.assertNotEqual(0, result.returncode)
        self.assertFalse((report / 'meta-42.json').exists())
        failed = json.loads((report / 'vanilla-42.json').read_text())
        self.assertFalse(failed['completed'])
        self.assertIn('FRESHGENERATOR_AUDIT_FAIL', (report / 'vanilla-42.log').read_text())


if __name__ == '__main__':
    unittest.main()
