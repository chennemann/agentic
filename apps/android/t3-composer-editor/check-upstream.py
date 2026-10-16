"""Compare this port with its pinned T3 checkout; optionally regenerate the local patch."""

import argparse
import difflib
import hashlib
import json
from pathlib import Path
import subprocess

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("checkout", type=Path, help="Independent T3 Code checkout")
parser.add_argument("--write-patch", action="store_true")
args = parser.parse_args()
module = Path(__file__).resolve().parent
manifest = json.loads((module / "upstream.json").read_text(encoding="utf-8"))
revision = manifest["revision"]
subprocess.run(
    ["git", "-C", str(args.checkout), "cat-file", "-e", f"{revision}^{{commit}}"], check=True
)

patch = []
immutable = []
for local, entry in manifest["files"].items():
    original = subprocess.check_output(
        ["git", "-C", str(args.checkout), "show", f"{revision}:{entry['source']}"]
    )
    actual = (module / local).read_bytes()
    if not entry["binary"]:
        original = original.replace(b"\r\n", b"\n")
        actual = actual.replace(b"\r\n", b"\n")
    if hashlib.sha256(original).hexdigest() != entry["sha256"]:
        raise SystemExit(f"Upstream source differs from pin: {entry['source']}")
    if original == actual:
        immutable.append(f"{entry['sha256']}  {local}")
    else:
        # Binary changes must always come from a new recorded upstream baseline.
        if entry["binary"]:
            raise SystemExit(f"Binary artifact differs from the recorded upstream source: {local}")
        before = original.decode("utf-8").replace("\r\n", "\n").splitlines(keepends=True)
        after = actual.decode("utf-8").replace("\r\n", "\n").splitlines(keepends=True)
        patch.extend(difflib.unified_diff(before, after, fromfile=f"a/{local}", tofile=f"b/{local}"))

expected_patch = "".join(patch)
expected_hashes = "\n".join(immutable) + "\n"
if args.write_patch:
    (module / "upstream.patch").write_text(expected_patch, encoding="utf-8", newline="\n")
    (module / "upstream.sha256").write_text(expected_hashes, encoding="utf-8", newline="\n")
else:
    if (module / "upstream.patch").read_text(encoding="utf-8") != expected_patch:
        raise SystemExit("Local changes differ from upstream.patch; inspect and regenerate it")
    if (module / "upstream.sha256").read_text(encoding="utf-8") != expected_hashes:
        raise SystemExit("Unmodified artifact list differs from upstream.sha256")
print(f"Verified {len(manifest['files'])} source/artifact pins and the complete local patch")
