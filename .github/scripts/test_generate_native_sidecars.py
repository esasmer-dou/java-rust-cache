import hashlib
import importlib.util
from pathlib import Path
import tempfile
import unittest


spec = importlib.util.spec_from_file_location("sidecars", Path(__file__).with_name("generate-native-sidecars.py"))
sidecars = importlib.util.module_from_spec(spec)
spec.loader.exec_module(sidecars)


class NativeSidecarGenerationTest(unittest.TestCase):
    REVISION = "3418671b5010afb5dc049897f2a7287dc6a58daf"
    FEATURES = "websocket,dubbo,redis,glowroot,edge-cache,edge-jwt,outbound-http,shared-slab"
    RUNTIME_FEATURES = "dubbo,edge_cache,edge_jwt,glowroot,outbound_http,redis,shared_slab,websocket"
    BINARIES = {"linux-arm64": "librust_hyper-linux-arm64.so",
                "macos-arm64": "librust_hyper-macos-arm64.dylib"}

    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.contract = self.root / "contract.properties"
        self.root_manifest = self.root / "native-provenance.properties"
        root = f"schema=2\nrest.abi=29\ndubbo.abi=7\nredis.abi=6\nglowroot.abi=6\ncrate.version=0.1.0\nsource.revision={self.REVISION}\n"
        contract = "schema=2\nrest.abi=29\ndubbo.abi=7\nredis.abi=6\nglowroot.abi=6\nlinux-arm64.glibc-minimum=2.17\n"
        for platform, name in self.BINARIES.items():
            (self.root / platform).mkdir()
            binary = ("fixture from source " + self.REVISION + " " + platform +
                      "\nfeatures=" + self.RUNTIME_FEATURES + "\n").encode("ascii")
            (self.root / platform / name).write_bytes(binary)
            root += f"{platform}.sha256={hashlib.sha256(binary).hexdigest()}\n"
            contract += f"binary.{platform}={name}\n"
        root += "linux-arm64.glibc-minimum=2.17\n"
        self.root_manifest.write_text(root, encoding="ascii")
        self.contract.write_text(contract, encoding="ascii")

    def render(self, revision=REVISION):
        return sidecars.render_sidecars(self.root, self.contract, revision, list(self.BINARIES))

    def test_derives_both_sidecars_from_actual_bytes_and_trusted_root(self):
        original_root = self.root_manifest.read_bytes()
        rendered = self.render()
        for platform, filename in self.BINARIES.items():
            path = self.root / platform / "native-provenance.properties"
            text = rendered[path]
            self.assertIn(f"features={self.FEATURES}\n", text)
            self.assertIn(f"source.revision={self.REVISION}\n", text)
            self.assertIn(f"{platform}.sha256={hashlib.sha256((self.root / platform / filename).read_bytes()).hexdigest()}\n", text)
            for field in ("schema=2", "redis.abi=6", "rest.abi=29", "dubbo.abi=7", "glowroot.abi=6",
                          "distribution.scope=current-source-compile-verified", "phase79.current-source=true",
                          "phase79.optional-features=known", "runtime.verified=false"):
                self.assertIn(field + "\n", text)
            self.assertEqual(platform == "linux-arm64", "glibc-minimum=2.17" in text)
            self.assertFalse(path.exists(), "Generation must not edit sidecars")
        self.assertEqual(original_root, self.root_manifest.read_bytes())

    def test_runtime_underscore_feature_names_render_identical_canonical_metadata(self):
        runtime = ",".join(reversed(self.FEATURES.replace("-", "_").split(",")))
        self.assertEqual(self.render(), sidecars.render_sidecars(
            self.root, self.contract, self.REVISION, list(self.BINARIES), runtime))

    def test_rejects_missing_extra_unknown_or_duplicate_features(self):
        for features in ("", self.FEATURES.replace("websocket,", ""), self.FEATURES + ",extra",
                         self.FEATURES.replace("websocket", "unknown"),
                         self.FEATURES.replace("websocket", "dubbo"),
                         self.FEATURES + ",edge_cache"):
            with self.subTest(features=features), self.assertRaisesRegex(ValueError, "feature set"):
                sidecars.render_sidecars(self.root, self.contract, self.REVISION,
                                         list(self.BINARIES), features)

    def test_rejects_head_or_any_revision_other_than_trusted_root(self):
        for revision in ("HEAD", "f" * 40, self.REVISION + "-dirty"):
            with self.subTest(revision=revision), self.assertRaisesRegex(ValueError, "Trusted source revision"):
                self.render(revision)

    def test_rejects_binary_bytes_that_do_not_match_trusted_root(self):
        path = self.root / "linux-arm64" / self.BINARIES["linux-arm64"]
        path.write_bytes(path.read_bytes() + b"tampered")
        with self.assertRaisesRegex(ValueError, "hash disagrees"):
            self.render()

    def test_rejects_hash_matched_binary_without_embedded_source_revision(self):
        path = self.root / "linux-arm64" / self.BINARIES["linux-arm64"]
        before = hashlib.sha256(path.read_bytes()).hexdigest()
        path.write_bytes(b"no source revision")
        self.root_manifest.write_text(self.root_manifest.read_text().replace(
            before, hashlib.sha256(path.read_bytes()).hexdigest()), encoding="ascii")
        with self.assertRaisesRegex(ValueError, "not embedded"):
            self.render()

    def test_rejects_changed_abi_schema_or_linux_glibc(self):
        original = self.root_manifest.read_text(encoding="ascii")
        for old, new in (("redis.abi=6", "redis.abi=5"), ("schema=2", "schema=1"),
                         ("glibc-minimum=2.17", "glibc-minimum=2.28")):
            self.root_manifest.write_text(original.replace(old, new), encoding="ascii")
            with self.subTest(field=old), self.assertRaises(ValueError):
                self.render()

    def test_emits_patch_without_writing_and_skips_identical_content(self):
        rendered = self.render()
        patch = sidecars.patch_for(rendered)
        self.assertTrue(patch.startswith("*** Begin Patch\n"))
        self.assertEqual(2, patch.count("*** Add File:"))
        for path, text in rendered.items():
            path.write_text("stale\n", encoding="ascii")
        patch = sidecars.patch_for(rendered)
        self.assertEqual(2, patch.count("*** Update File:"))
        for path, text in rendered.items():
            self.assertEqual("stale\n", path.read_text(encoding="ascii"))
            path.write_text(text, encoding="ascii")
        self.assertEqual("*** Begin Patch\n*** End Patch\n", sidecars.patch_for(rendered))

    def test_rejects_duplicate_manifest_fields(self):
        with self.root_manifest.open("a", encoding="ascii") as output:
            output.write("redis.abi=6\n")
        with self.assertRaisesRegex(ValueError, "duplicate"):
            self.render()

    def test_finds_embedded_revision_across_streaming_chunk_boundary(self):
        path = self.root / "linux-arm64" / self.BINARIES["linux-arm64"]
        binary = (b"x" * (64 * 1024 - 10) + self.REVISION.encode("ascii") +
                  ("\nfeatures=" + self.RUNTIME_FEATURES + "\n").encode("ascii"))
        path.write_bytes(binary)
        self.assertEqual(hashlib.sha256(binary).hexdigest(), sidecars.binary_evidence(path, self.REVISION))

    def test_rejects_hash_matched_binary_without_exact_runtime_features(self):
        path = self.root / "linux-arm64" / self.BINARIES["linux-arm64"]
        original = self.root_manifest.read_text(encoding="ascii")
        before = hashlib.sha256(path.read_bytes()).hexdigest()
        for features in ("", self.RUNTIME_FEATURES.replace("dubbo,", ""),
                         self.RUNTIME_FEATURES + ",extra", "extra," + self.RUNTIME_FEATURES,
                         self.RUNTIME_FEATURES.replace("redis", "unknown"),
                         self.RUNTIME_FEATURES.replace("_", "-")):
            binary = (self.REVISION + "\nfeatures=" + features + "\n").encode("ascii")
            path.write_bytes(binary)
            self.root_manifest.write_text(original.replace(
                before, hashlib.sha256(binary).hexdigest()), encoding="ascii")
            with self.subTest(features=features), self.assertRaisesRegex(ValueError, "runtime feature set"):
                self.render()

    def test_finds_runtime_features_across_streaming_chunk_boundary(self):
        path = self.root / "linux-arm64" / self.BINARIES["linux-arm64"]
        prefix = self.REVISION.encode("ascii")
        binary = (prefix + b"x" * (64 * 1024 - 10 - len(prefix)) +
                  ("\nfeatures=" + self.RUNTIME_FEATURES + "\n").encode("ascii"))
        path.write_bytes(binary)
        self.assertEqual(hashlib.sha256(binary).hexdigest(), sidecars.binary_evidence(path, self.REVISION))


if __name__ == "__main__":
    unittest.main()
