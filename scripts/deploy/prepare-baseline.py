"""Select a verified source commit; never execute or extract arbitrary artifact paths."""
import json
from pathlib import Path
import re
import zipfile


def select_revision(root):
    archive = root / "verified-deployment.zip"
    if archive.exists():
        with zipfile.ZipFile(archive) as bundle:
            state = json.loads(bundle.read("migration-state.json"))
        revision = state["revision"]
        expected = (root / "verified-deployment.sha").read_text().strip()
        if state.get("schemaVersion") != 1 or state.get("mode") != "snapshot" or not state.get("applied"):
            raise ValueError("Not a verified migration snapshot")
        if revision != expected:
            raise ValueError("Snapshot revision differs from the workflow commit")
    else:
        revision = (root / ".github/deployment-baseline.sha").read_text().strip()
    if not re.fullmatch(r"[0-9a-f]{40}", revision):
        raise ValueError("Invalid baseline commit")
    return revision


if __name__ == "__main__":
    root = Path.cwd()
    revision = select_revision(root)
    (root / "migration-baseline.sha").write_text(revision + "\n")
    print("Migration upgrade baseline:", revision)
