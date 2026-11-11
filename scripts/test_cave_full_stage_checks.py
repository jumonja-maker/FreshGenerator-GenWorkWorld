"""Synthetic failures must still fail despite the separation of terrain and features."""
import base64,copy,json,pathlib,struct,unittest
from cave_full_stage_checks import compare_full_stage

def world(states):
    palette=list(dict.fromkeys(s for k,s in states)); ids={s:i for i,s in enumerate(palette)}
    return {'x':-16,'z':0,'side':4,'deep_column_stride':4,'deep_column_top_y':[len(states)-1],'deep_min_y':0,
            'deep_occupancy_base64':base64.b64encode(bytes(k for k,s in states)).decode(),
            'deep_state_indices_u16be':base64.b64encode(b''.join(struct.pack('>H',ids[s]) for k,s in states)).decode(),
            'deep_state_palette':palette}
def terrain():
    return {'label':'fixed','x':-16,'z':0,'side':16,
            'chunks':[{'chunk_x':-1,'chunk_z':0,'observed_status':'terrain','before_features':True,
                       'samples':100,'air_samples':0,'fluid_samples':0,'exact_block_state_sha256':'a'*64}]}
STONE=(1,'Block{minecraft:stone}');AIR=(0,'Block{minecraft:air}');WATER=(2,'Block{minecraft:water}[level=0]')
class FullStageTests(unittest.TestCase):
    def test_unchanged_full_is_exact(self):
        a=world([STONE]*100);r=compare_full_stage(a,a,terrain(),terrain());self.assertTrue(r['full_blocks_identical'])
    def test_small_actual_post_feature_fluid_change_is_not_hidden(self):
        a=world([STONE]*100);b=world([WATER]+[STONE]*99)
        r=compare_full_stage(a,b,terrain(),terrain())
        self.assertEqual(r['post_feature_fluid_occupancy_changes'],1);self.assertFalse(r['full_blocks_identical'])
    def test_large_cavity_change_fails(self):
        with self.assertRaises(ValueError):compare_full_stage(world([STONE]*100),world([AIR]*20+[STONE]*80),terrain(),terrain())
    def test_excessive_fluid_change_fails(self):
        with self.assertRaises(ValueError):compare_full_stage(world([STONE]*100),world([WATER]*5+[STONE]*95),terrain(),terrain())
    def test_one_pre_feature_block_change_still_fails(self):
        a=terrain();b=copy.deepcopy(a);b['chunks'][0]['exact_block_state_sha256']='b'*64
        with self.assertRaises(ValueError):compare_full_stage(world([STONE]*100),world([STONE]*100),a,b)
    def test_changed_full_sample_without_terrain_evidence_fails(self):
        a=terrain();a['chunks'][0]={'chunk_x':-1,'chunk_z':0,'observed_status':'full','before_features':False}
        with self.assertRaises(ValueError):compare_full_stage(world([STONE]*100),world([AIR]+[STONE]*99),a,a)
    def test_water_is_counted_as_water_not_a_decorative_exemption(self):
        a=world([WATER]*4+[STONE]*96);b=world([STONE]*100)
        r=compare_full_stage(a,b,terrain(),terrain());self.assertEqual(r['post_feature_net_fluid_change'],-4)
    def test_large_known_sculk_object_change_also_fails(self):
        shrieker=(1,'Block{minecraft:sculk_shrieker}[waterlogged=false]')
        with self.assertRaises(ValueError):compare_full_stage(world([AIR]*100),world([shrieker]*20+[AIR]*80),terrain(),terrain())
if __name__=='__main__':
    r=unittest.TextTestRunner(verbosity=2).run(unittest.defaultTestLoader.loadTestsFromTestCase(FullStageTests))
    p=pathlib.Path('build/reports/cave-full-stage-tests.json');p.parent.mkdir(parents=True,exist_ok=True)
    p.write_text(json.dumps({'tests':r.testsRun,'failures':len(r.failures),'errors':len(r.errors),'skipped':len(r.skipped)},indent=2)+'\n')
    raise SystemExit(0 if r.wasSuccessful() else 1)
