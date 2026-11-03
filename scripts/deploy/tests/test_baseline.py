import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
import zipfile

spec = importlib.util.spec_from_file_location("baseline", Path(__file__).parents[1] / "prepare-baseline.py")
baseline = importlib.util.module_from_spec(spec)
spec.loader.exec_module(baseline)


class BaselineTest(unittest.TestCase):
    def test_bootstrap_without_artifact(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / ".github").mkdir()
            (root / ".github/deployment-baseline.sha").write_text("a" * 40)
            self.assertEqual(baseline.select_revision(root), "a" * 40)

    def test_only_matching_post_readiness_artifact_is_accepted(self):
        for mode, revision, accepted in [("snapshot", "a" * 40, True), ("preflight", "a" * 40, False),
                                         ("snapshot", "b" * 40, False), ("snapshot", "../invalid", False)]:
            with self.subTest(mode=mode, revision=revision), tempfile.TemporaryDirectory() as tmp:
                root = Path(tmp)
                (root / "verified-deployment.sha").write_text("a" * 40)
                with zipfile.ZipFile(root / "verified-deployment.zip", "w") as bundle:
                    bundle.writestr("migration-state.json", json.dumps({"schemaVersion": 1, "mode": mode,
                        "revision": revision, "applied": [{"version": "25", "checksum": 123}]}))
                if accepted:
                    self.assertEqual(baseline.select_revision(root), revision)
                else:
                    with self.assertRaises(ValueError):
                        baseline.select_revision(root)
