#!/usr/bin/env python3
"""Download pinned V1 models and stage Self's model in install-time asset packs."""

import argparse
import hashlib
import json
import re
import sys
import urllib.error
import urllib.request
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
MANIFEST = ROOT / "models" / "models.json"
CHUNK = 1024 * 1024


def digest(path):
    sha = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(CHUNK), b""):
            sha.update(chunk)
    return sha.hexdigest()


def matches(path, entry):
    return path.is_file() and path.stat().st_size == entry["bytes"] and digest(path) == entry["sha256"]


def validate_entry(entry):
    for key in ("repository", "revision", "file"):
        if not isinstance(entry.get(key), str) or not re.fullmatch(r"[A-Za-z0-9_.\-/]+", entry[key]):
            raise ValueError(f"invalid {key}")
    if not isinstance(entry.get("bytes"), int) or entry["bytes"] <= 0:
        raise ValueError("invalid byte count")
    if not re.fullmatch(r"[0-9a-f]{64}", entry.get("sha256", "")):
        raise ValueError("invalid SHA-256")


def load_manifest(path=MANIFEST):
    manifest = json.loads(path.read_text(encoding="utf-8"))
    if set(manifest) != {"self", "source"}:
        raise ValueError("manifest must contain self and source")
    for target in manifest.values():
        validate_entry(target)
    parts = manifest["self"].get("parts")
    if not isinstance(parts, list) or len(parts) != 3:
        raise ValueError("Self must have three asset-pack parts")
    if sum(part.get("bytes", 0) for part in parts) != manifest["self"]["bytes"]:
        raise ValueError("Self part sizes do not match complete model")
    for part in parts:
        if not isinstance(part.get("file"), str) or not re.fullmatch(r"[A-Za-z0-9_.-]+", part["file"]):
            raise ValueError("invalid Self part filename")
        if not isinstance(part.get("bytes"), int) or part["bytes"] <= 0:
            raise ValueError("invalid Self part size")
        if not re.fullmatch(r"[0-9a-f]{64}", part.get("sha256", "")):
            raise ValueError("invalid Self part SHA-256")
    return manifest


def download(entry, destination):
    destination.parent.mkdir(parents=True, exist_ok=True)
    if destination.exists():
        if matches(destination, entry):
            print(f"Verified existing model: {destination}")
            return destination
        raise ValueError(f"Existing model does not match manifest: {destination}")

    partial = destination.with_name(destination.name + ".download")
    offset = partial.stat().st_size if partial.exists() else 0
    if offset > entry["bytes"]:
        raise ValueError(f"Partial download is larger than expected: {partial}")
    if offset == entry["bytes"]:
        if matches(partial, entry):
            partial.replace(destination)
            print(f"Verified completed download: {destination}")
            return destination
        partial.unlink()
        offset = 0
    url = f'https://huggingface.co/{entry["repository"]}/resolve/{entry["revision"]}/{entry["file"]}'
    headers = {"Range": f"bytes={offset}-"} if offset else {}
    print(f"Downloading {entry['file']} ({entry['bytes']} bytes) to {destination}", flush=True)
    with urllib.request.urlopen(urllib.request.Request(url, headers=headers), timeout=60) as response:
        status = response.status
        if offset and status == 206:
            expected_range = f"bytes {offset}-"
            if not response.headers.get("Content-Range", "").startswith(expected_range):
                raise ValueError("Server returned an unexpected resume range")
            mode = "ab"
        elif status == 200:
            mode = "wb"
        else:
            raise ValueError(f"Unexpected download response: HTTP {status}")
        with partial.open(mode) as output:
            for chunk in iter(lambda: response.read(CHUNK), b""):
                output.write(chunk)
                if output.tell() > entry["bytes"]:
                    raise ValueError("Download exceeded expected size")

    if not matches(partial, entry):
        if partial.stat().st_size == entry["bytes"]:
            partial.unlink()
        raise ValueError(f"Downloaded model did not match size and SHA-256: {partial}")
    partial.replace(destination)
    print(f"Verified SHA-256: {destination}")
    return destination


def part_paths(parts):
    return [
        ROOT / "self" / "android" / f"model_pack_{index}" / "src" / "main" / "assets" / part["file"]
        for index, part in enumerate(parts, 1)
    ]


def provision_self(entry):
    parts = entry["parts"]
    destinations = part_paths(parts)
    if all(matches(path, part) for path, part in zip(destinations, parts)):
        print("Self model parts are already present and verified")
        return
    model = download(entry, ROOT / ".models" / "self" / entry["file"])
    try:
        with model.open("rb") as source:
            for part, destination in zip(parts, destinations):
                destination.parent.mkdir(parents=True, exist_ok=True)
                partial = destination.with_name(destination.name + ".download")
                sha = hashlib.sha256()
                remaining = part["bytes"]
                with partial.open("wb") as output:
                    while remaining:
                        chunk = source.read(min(CHUNK, remaining))
                        if not chunk:
                            raise ValueError("Self model ended before all parts were written")
                        output.write(chunk)
                        sha.update(chunk)
                        remaining -= len(chunk)
                if sha.hexdigest() != part["sha256"]:
                    partial.unlink()
                    raise ValueError(f"Self part SHA-256 mismatch: {destination}")
                partial.replace(destination)
                print(f"Verified asset-pack part: {destination}")
            if source.read(1):
                raise ValueError("Self model has trailing data")
    finally:
        model.unlink()


def verify(target, entry):
    if target == "source":
        paths = [(ROOT / "data" / "models" / entry["file"], entry)]
    else:
        paths = list(zip(part_paths(entry["parts"]), entry["parts"]))
    for path, expected in paths:
        if not matches(path, expected):
            raise ValueError(f"Missing or invalid model artifact: {path}")
        print(f"Verified: {path}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("target", choices=("self", "source", "all"), nargs="?", default="all")
    action = parser.add_mutually_exclusive_group()
    action.add_argument("--check", action="store_true", help="validate the pinned manifest only")
    action.add_argument("--verify", action="store_true", help="verify provisioned files without downloading")
    args = parser.parse_args()
    manifest = load_manifest()
    if args.check:
        print("Model manifest is valid")
        return
    targets = ("self", "source") if args.target == "all" else (args.target,)
    for target in targets:
        if args.verify:
            verify(target, manifest[target])
        elif target == "self":
            provision_self(manifest[target])
        else:
            download(manifest[target], ROOT / "data" / "models" / manifest[target]["file"])


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError, urllib.error.URLError) as error:
        print(f"Model provisioning failed: {error}", file=sys.stderr)
        sys.exit(1)
