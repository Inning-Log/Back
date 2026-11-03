"""Exercise the real shell rollout with deterministic Docker/HTTP stand-ins (no production access)."""
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

SCRIPT = Path(__file__).parents[1] / "deploy-application.sh"

DOCKER = r'''#!/usr/bin/env python3
import json, os, sys
from pathlib import Path
p = Path(os.environ['FAKE_STATE'])
s = json.loads(p.read_text())
a = sys.argv[1:]
s['calls'].append(a)
scenario = os.environ['SCENARIO']
def save(): p.write_text(json.dumps(s))
def fail(): save(); sys.exit(1)
if a[0:2] == ['container','inspect']:
    if a[-1] not in s['containers']: fail()
elif a[0:2] == ['image','inspect']:
    print('new-image')
elif a[0] == 'inspect':
    c=s['containers'].get(a[-1])
    if c is None: fail()
    fmt=a[a.index('--format')+1]
    if 'ExitCode' in fmt: print(c.get('exit',0))
    elif 'State.Running' in fmt:
        restarted=scenario == 'restart' and c['image']=='new-image'
        print(f"true {1 if restarted else 0} {c['image']}")
    elif 'RestartCount' in fmt: print(0)
    else: print(c['image'])
elif a[0] == 'create':
    n=a[a.index('--name')+1]
    s['mode']=a[-2]
    code=1 if (scenario=='preflight-fail' and s['mode']=='preflight') or (scenario=='snapshot-fail' and s['mode']=='snapshot') else 0
    s['containers'][n]={'image':'check','exit':code}
elif a[0] == 'cp':
    Path(a[-1]).write_text(json.dumps({'mode':s['mode']}))
elif a[0] == 'run':
    if scenario == 'run-fail': fail()
    s['containers'][a[a.index('--name')+1]]={'image':'new-image'}
elif a[0] == 'rename':
    s['containers'][a[2]]=s['containers'].pop(a[1])
elif a[0] == 'rm':
    s['containers'].pop(a[-1],None)
elif a[0] == 'stop':
    pass
elif a[0] == 'start':
    if scenario=='start-fail' and a[-1]=='app' and s['containers'].get('app',{}).get('image')=='new-image': fail()
save()
'''

CURL = r'''#!/usr/bin/env python3
import json, os, sys
from pathlib import Path
s=json.loads(Path(os.environ['FAKE_STATE']).read_text())
new=s['containers'].get('app',{}).get('image')=='new-image'
bad=new and os.environ['SCENARIO'] in ('health-down','malformed','db-down')
if bad and os.environ['SCENARIO']=='malformed': print('not JSON')
elif bad: print('{"status":"DOWN","components":{"something":{"status":"UP"}}}')
else: print('{"status":"UP"}')
'''


@unittest.skipIf(os.name == "nt", "Run shell process integration tests on Linux (CI)")
class RolloutTest(unittest.TestCase):
    def run_scenario(self, scenario):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            bindir = root / "bin"
            bindir.mkdir()
            for name, script in [("docker", DOCKER), ("curl", CURL)]:
                tool = bindir / name
                tool.write_text(script)
                tool.chmod(0o755)
            state = root / "state.json"
            state.write_text(json.dumps({"containers": {"app": {"image": "old-image"}}, "calls": []}))
            envfile = root / "app.env"
            envfile.write_text("FIREBASE_ENABLED=false\n")
            env = dict(os.environ, PATH=str(bindir) + os.pathsep + os.environ["PATH"],
                       FAKE_STATE=str(state), SCENARIO=scenario, DEPLOY_HEALTH_TIMEOUT_SECONDS="2",
                       DEPLOY_HEALTH_POLL_SECONDS="1", DEPLOY_HEALTH_STABLE_COUNT="1")
            result = subprocess.run(["bash", str(SCRIPT), "app", "test:new", "a" * 40, str(envfile),
                "unused", "127.0.0.1", "8080", "8080", "9090", "9090", "640m", "896m", "256", str(root / "out")],
                env=env, capture_output=True, text=True, timeout=20)
            return result, json.loads(state.read_text())

    def test_preflight_failure_leaves_old_container_untouched(self):
        result, state = self.run_scenario("preflight-fail")
        self.assertNotEqual(result.returncode, 0, result.stdout)
        self.assertEqual(state['containers']['app']['image'], 'old-image')
        self.assertFalse(any(call[0] in ('stop', 'rename', 'run') for call in state['calls']))

    def test_health_failure_restart_and_snapshot_failure_restore_previous(self):
        for scenario in ('run-fail', 'health-down', 'malformed', 'db-down', 'restart', 'snapshot-fail'):
            with self.subTest(scenario=scenario):
                result, state = self.run_scenario(scenario)
                self.assertNotEqual(result.returncode, 0, result.stdout)
                self.assertEqual(state['containers']['app']['image'], 'old-image', result.stderr)
                self.assertIn('restoring', result.stderr)

    def test_success_preserves_previous_and_records_snapshot(self):
        result, state = self.run_scenario("success")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(state['containers']['app']['image'], 'new-image')
        self.assertEqual(state['containers']['app-rollback']['image'], 'old-image')
        self.assertEqual(state['mode'], 'snapshot')


if __name__ == '__main__':
    unittest.main()
