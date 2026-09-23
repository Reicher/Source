import hashlib
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import provision_models


def entry(data):
    return {
        "repository": "example/model",
        "revision": "abc123",
        "file": "model.gguf",
        "bytes": len(data),
        "sha256": hashlib.sha256(data).hexdigest(),
    }


class Response:
    def __init__(self, data, status, content_range=None):
        self.data = data
        self.status = status
        self.headers = {"Content-Range": content_range} if content_range else {}

    def __enter__(self):
        return self

    def __exit__(self, *_):
        return False

    def read(self, size):
        chunk, self.data = self.data[:size], self.data[size:]
        return chunk


class ProvisionModelsTest(unittest.TestCase):
    def test_source_model_root_can_live_outside_checkout(self):
        with tempfile.TemporaryDirectory() as directory:
            with patch.dict("os.environ", {"SOURCE_MODEL_ROOT": directory}):
                self.assertEqual(
                    provision_models.source_model_path(entry(b"model")),
                    Path(directory) / "model.gguf",
                )

    def test_accepts_completed_partial_download(self):
        data = b"completed model"
        with tempfile.TemporaryDirectory() as directory:
            destination = Path(directory) / "model.gguf"
            destination.with_name("model.gguf.download").write_bytes(data)
            with patch.object(provision_models.urllib.request, "urlopen") as open_request:
                provision_models.download(entry(data), destination)
                open_request.assert_not_called()
            self.assertEqual(destination.read_bytes(), data)

    def test_resumes_download_and_verifies_checksum(self):
        data = b"a small test model"
        with tempfile.TemporaryDirectory() as directory:
            destination = Path(directory) / "model.gguf"
            destination.with_name("model.gguf.download").write_bytes(data[:5])

            def open_request(request, timeout):
                self.assertEqual(request.get_header("Range"), "bytes=5-")
                return Response(data[5:], 206, f"bytes 5-{len(data) - 1}/{len(data)}")

            with patch.object(provision_models.urllib.request, "urlopen", side_effect=open_request):
                provision_models.download(entry(data), destination)
            self.assertEqual(destination.read_bytes(), data)
            self.assertFalse(destination.with_name("model.gguf.download").exists())

    def test_splits_verified_self_model_into_asset_packs(self):
        data = b"abcdefghijkl"
        model = entry(data)
        model["parts"] = [
            {"file": f"part-{index}.gguf.part", "bytes": 4, "sha256": hashlib.sha256(data[index * 4:(index + 1) * 4]).hexdigest()}
            for index in range(3)
        ]
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            staged = root / ".models" / "self" / model["file"]
            staged.parent.mkdir(parents=True)
            staged.write_bytes(data)
            with patch.object(provision_models, "ROOT", root):
                provision_models.provision_self(model)
                provision_models.verify("self", model)
            self.assertFalse(staged.exists())


if __name__ == "__main__":
    unittest.main()
