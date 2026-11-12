"""Offline transport validation. No credentials or external API calls in these tests."""
import base64,copy,gzip,hashlib,json,tempfile,unittest
from pathlib import Path
from unittest.mock import patch
import ci_evidence as ci

IDENTITY={'GITHUB_SHA':'a'*40,'GITHUB_RUN_ID':'123','GITHUB_RUN_ATTEMPT':'1','GITHUB_REPOSITORY':'owner/repo'}

class EvidenceTest(unittest.TestCase):
 def setUp(self):
  self.tmp=tempfile.TemporaryDirectory();self.root=Path(self.tmp.name);self.input=self.root/'in';self.input.mkdir()
  (self.input/'sample.json').write_bytes(b'{"sample":1}\n')
  (self.input/'sample.jar').write_bytes(bytes(range(256)))
  self.data=ci.pack(self.input,['*.json','*.jar'],'technical-build',IDENTITY)
 def tearDown(self):self.tmp.cleanup()
 def extract(self,data=None,identity=None,name='technical-build'):
  return ci.unpack(self.data if data is None else data,name,IDENTITY if identity is None else identity,self.root/'out')
 def changed(self,mutate):
  d=json.loads(gzip.decompress(self.data));mutate(d);return gzip.compress(json.dumps(d).encode())
 def test_binary_and_text_roundtrip(self):
  r=self.extract();self.assertEqual(2,r['files'])
  for name in ['sample.json','sample.jar']:self.assertEqual((self.input/name).read_bytes(),(self.root/'out'/name).read_bytes())
 def test_repeated_pack_is_identical(self):self.assertEqual(self.data,ci.pack(self.input,['*.json','*.jar'],'technical-build',IDENTITY))
 def test_duplicate_pattern_does_not_duplicate_files(self):self.assertEqual(self.data,ci.pack(self.input,['*.json','*.jar','*.json'],'technical-build',IDENTITY))
 def test_wrong_commit_rejected(self):
  identity=dict(IDENTITY,GITHUB_SHA='b'*40)
  with self.assertRaises(ValueError):self.extract(identity=identity)
 def test_wrong_attempt_rejected(self):
  with self.assertRaises(ValueError):self.extract(identity=dict(IDENTITY,GITHUB_RUN_ATTEMPT='2'))
 def test_wrong_repository_rejected(self):
  with self.assertRaises(ValueError):self.extract(identity=dict(IDENTITY,GITHUB_REPOSITORY='other/repo'))
 def test_wrong_bundle_rejected(self):
  with self.assertRaises(ValueError):self.extract(name='technical-world-fresh-42')
 def test_tampered_file_rejected(self):
  data=self.changed(lambda d:d['files']['sample.json'].update(content=base64.b64encode(b'tampered').decode()))
  with self.assertRaises(ValueError):self.extract(data)
 def test_unsafe_names_rejected(self):
  for name in ['../file','/file','dir/../file','a//b','a\\b','./file','nul\x00']:
   with self.subTest(name=name):
    with self.assertRaises(ValueError):ci.valid_name(name)
 def test_path_escape_is_not_written(self):
  data=self.changed(lambda d:d['files'].update({'../escaped':d['files'].pop('sample.json')}))
  with self.assertRaises(ValueError):self.extract(data)
  self.assertFalse((self.root/'escaped').exists())
 def test_symlink_input_rejected(self):
  (self.input/'outside.json').symlink_to(self.input/'sample.json')
  with self.assertRaises(ValueError):ci.pack(self.input,['*.json'],'technical-build',IDENTITY)
 def test_no_files_rejected(self):
  with self.assertRaises(ValueError):ci.pack(self.input,['missing.*'],'technical-build',IDENTITY)
 def test_existing_destination_not_overwritten(self):
  self.extract()
  with self.assertRaises(ValueError):self.extract()
 def test_small_size_limit_fails_closed(self):
  with patch.object(ci,'MAX_RAW',16):
   with self.assertRaises(ValueError):self.extract()
 def test_blob_sha_is_git_fingerprint(self):self.assertEqual('ce013625030ba8dba906f756967f9e9ca394464a',ci.blob_sha(b'hello\n'))
 def test_bad_provenance_rejected(self):
  with self.assertRaises(ValueError):ci.provenance(dict(IDENTITY,GITHUB_SHA='not-a-sha'))
 def test_failed_upstream_cannot_publish(self):
  needs={n:{'result':'failure','outputs':{}} for n in ['build','worlds','structures']}
  with self.assertRaises(ValueError):ci.get_all(needs,self.root/'out')
 def test_missing_outputs_cannot_publish(self):
  needs={n:{'result':'success','outputs':{}} for n in ['build','worlds','structures']}
  with self.assertRaises(ValueError):ci.get_all(needs,self.root/'out')

if __name__=='__main__':
 result=unittest.TextTestRunner(verbosity=2).run(unittest.defaultTestLoader.loadTestsFromTestCase(EvidenceTest))
 p=Path('build/reports/ci-evidence-tests.json');p.parent.mkdir(parents=True,exist_ok=True)
 p.write_text(json.dumps({'tests':result.testsRun,'failures':len(result.failures),'errors':len(result.errors),'skipped':len(result.skipped)},indent=2)+'\n')
 raise SystemExit(not result.wasSuccessful())
