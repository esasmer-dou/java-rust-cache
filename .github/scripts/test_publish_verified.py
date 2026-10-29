import copy
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest import mock
import subprocess
import zipfile

spec = importlib.util.spec_from_file_location('publisher', Path(__file__).with_name('publish-verified.py'))
publisher = importlib.util.module_from_spec(spec)
spec.loader.exec_module(publisher)
checked, digest, validate = publisher.checked, publisher.digest, publisher.validate


class ReleaseValidationTest(unittest.TestCase):
    def prepare_native_gate(self, directory):
        root = Path(directory)
        checkout = root / "checkout"
        verifier = checkout / "src/main/java/com/reactor/rust/cache/codegen/PackagedNativeProvenanceVerifier.java"
        contract = checkout / ".github/native-provenance-contract.properties"
        for path in (verifier, contract):
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text("fixture")
        runtime = root / "cache.jar"
        runtime.write_bytes(b"fixture jar")
        manifest = {"files": {"cache.jar": digest(runtime)},
                    "artifacts": [{"id": "java-rust-cache", "primary": "cache.jar",
                                   "downloads": [{"file": "cache.jar", "name": "java-rust-cache-1.0.0.jar"}]}]}
        return root, checkout, verifier, contract, runtime, manifest

    def test_prebuilt_publisher_invokes_repo_local_source_gate(self):
        with tempfile.TemporaryDirectory() as directory:
            root, checkout, verifier, contract, runtime, manifest = self.prepare_native_gate(directory)
            with mock.patch.object(publisher.subprocess, "run") as run:
                publisher.verify_packaged_native_artifacts(root, checkout, manifest)
            run.assert_called_once_with(["java", str(verifier), str(runtime), str(contract)],
                                        cwd=checkout, check=True, timeout=120)

    def test_prebuilt_publisher_propagates_gate_failure(self):
        with tempfile.TemporaryDirectory() as directory:
            root, checkout, _, _, _, manifest = self.prepare_native_gate(directory)
            with mock.patch.object(publisher.subprocess, "run", side_effect=subprocess.CalledProcessError(1, "java")):
                with self.assertRaises(subprocess.CalledProcessError):
                    publisher.verify_packaged_native_artifacts(root, checkout, manifest)

    def test_prebuilt_publisher_rejects_empty_downloads_or_missing_primary(self):
        with tempfile.TemporaryDirectory() as directory:
            root, checkout, _, _, _, manifest = self.prepare_native_gate(directory)
            for downloads in (None, [], {}, [{"file": "cache.pom", "name": "cache.pom"}]):
                candidate = copy.deepcopy(manifest)
                candidate["artifacts"][0]["downloads"] = downloads
                with self.subTest(downloads=downloads), \
                        mock.patch.object(publisher.subprocess, "run") as run:
                    with self.assertRaisesRegex(ValueError, "nonempty and include the primary JAR"):
                        publisher.verify_packaged_native_artifacts(root, checkout, candidate)
                    run.assert_not_called()

    def test_incomplete_downloads_precede_any_remote_publication_action(self):
        with tempfile.TemporaryDirectory() as directory:
            root, checkout, _, _, _, manifest = self.prepare_native_gate(directory)
            bundle = root / "distribution.zip"
            with zipfile.ZipFile(bundle, "w") as archive:
                archive.write(root / "cache.jar", "cache.jar")
            environment = {"GITHUB_REPOSITORY": "owner/java-rust-cache", "GITHUB_SHA": "commit",
                           "RELEASE_VERSION": "1.0.0", "RELEASE_BUNDLE": str(bundle),
                           "RELEASE_BUNDLE_SHA256": digest(bundle)}
            for downloads in ([], [{"file": "cache.pom", "name": "cache.pom"}]):
                candidate = copy.deepcopy(manifest)
                candidate["artifacts"][0]["downloads"] = downloads
                with self.subTest(downloads=downloads), \
                        mock.patch.dict(publisher.os.environ, environment, clear=True), \
                        mock.patch.object(publisher.Path, "cwd", return_value=checkout), \
                        mock.patch.object(publisher, "validate", return_value=candidate), \
                        mock.patch.object(publisher, "download") as download, \
                        mock.patch.object(publisher, "deploy_args") as deploy, \
                        mock.patch.object(publisher.subprocess, "run") as run:
                    with self.assertRaisesRegex(ValueError, "nonempty and include the primary JAR"):
                        publisher.main()
                    download.assert_not_called()
                    deploy.assert_not_called()
                    run.assert_not_called()

    def test_prebuilt_publisher_rejects_missing_runtime_or_checksum(self):
        with tempfile.TemporaryDirectory() as directory:
            root, checkout, _, _, _, manifest = self.prepare_native_gate(directory)
            for artifacts in ([], manifest["artifacts"] * 2, [{"id": "another-repo", "primary": "cache.jar"}]):
                with self.subTest(artifacts=artifacts), self.assertRaises(ValueError):
                    publisher.verify_packaged_native_artifacts(root, checkout, dict(manifest, artifacts=artifacts))
            with self.assertRaisesRegex(ValueError, "recorded checksum"):
                publisher.verify_packaged_native_artifacts(root, checkout, dict(manifest, files={}))

    def test_gate_failure_precedes_any_remote_publication_action(self):
        with tempfile.TemporaryDirectory() as directory:
            root, checkout, _, _, _, manifest = self.prepare_native_gate(directory)
            bundle = root / "distribution.zip"
            with zipfile.ZipFile(bundle, "w") as archive:
                archive.write(root / "cache.jar", "cache.jar")
            environment = {"GITHUB_REPOSITORY": "owner/java-rust-cache", "GITHUB_SHA": "commit",
                           "RELEASE_VERSION": "1.0.0", "RELEASE_BUNDLE": str(bundle),
                           "RELEASE_BUNDLE_SHA256": digest(bundle)}
            with mock.patch.dict(publisher.os.environ, environment, clear=True), \
                    mock.patch.object(publisher.Path, "cwd", return_value=checkout), \
                    mock.patch.object(publisher, "validate", return_value=manifest), \
                    mock.patch.object(publisher, "download") as download, \
                    mock.patch.object(publisher, "deploy_args") as deploy, \
                    mock.patch.object(publisher.subprocess, "run", side_effect=subprocess.CalledProcessError(1, "java")) as run:
                with self.assertRaises(subprocess.CalledProcessError):
                    publisher.main()
                download.assert_not_called()
                deploy.assert_not_called()
                self.assertEqual(1, run.call_count)
                self.assertEqual("java", run.call_args.args[0][0])

    def test_deploys_sources_and_all_attached_classifiers(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for name in ('a.pom', 'a.jar', 'a-sources.jar', 'a-codegen.jar', 'a-core-runtime.jar'):
                (root / name).write_text(name)
            artifact = dict(group='com.reactor', id='a', pom='a.pom', primary='a.jar', sources='a-sources.jar',
                            attached=[dict(file='a-codegen.jar', classifier='codegen'),
                                      dict(file='a-core-runtime.jar', classifier='core-runtime')])
            args = publisher.deploy_args(root, artifact, 'https://example.invalid', '1.0.0')
            self.assertIn('-Dclassifiers=codegen,core-runtime', args)
            self.assertIn('-Dtypes=jar,jar', args)
            self.assertIn(f'-Dsources={root / "a-sources.jar"}', args)
            self.assertIn(f'-Dfiles={root / "a-codegen.jar"},{root / "a-core-runtime.jar"}', args)

    def test_rejects_changed_or_unverified_inputs(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            checkout = root / 'checkout'
            checkout.mkdir()
            (checkout / 'pom.xml').write_text('source')
            (root / 'artifact.pom').write_text('artifact')
            receipt = {'execution_environment': 'EmaTech', 'test_failures': 0,
                       'abis': {'rest': 29, 'dubbo': 7, 'spring_dubbo': 3, 'redis': 6, 'glowroot': 6}}
            manifest = {'repository': 'owner/repo', 'commit': 'abc', 'version': '1.0.0',
                        'files': {'artifact.pom': digest(root / 'artifact.pom')},
                        'source_files': {'pom.xml': digest(checkout / 'pom.xml')},
                        'artifacts': [{'version': '1.0.0', 'pom': 'artifact.pom',
                                       'downloads': [{'file': 'artifact.pom'}]}]}

            def check(m=manifest, r=receipt):
                (root / 'manifest.json').write_text(json.dumps(m))
                (root / 'release-receipt.json').write_text(json.dumps(r))
                return validate(root, checkout, 'owner/repo', 'abc', '1.0.0')

            self.assertEqual(check()['version'], '1.0.0')
            for field, value in [('test_failures', 1), ('execution_environment', 'local'), ('abis', {})]:
                changed = dict(receipt, **{field: value})
                with self.assertRaises(ValueError):
                    check(r=changed)
            changed = copy.deepcopy(manifest)
            changed['commit'] = 'wrong'
            with self.assertRaises(ValueError):
                check(m=changed)
            (checkout / 'pom.xml').write_text('changed')
            with self.assertRaises(ValueError):
                check()
            (checkout / 'pom.xml').write_text('source')
            (root / 'artifact.pom').write_text('changed')
            with self.assertRaises(ValueError):
                check()
            with self.assertRaises(ValueError):
                checked(root, '../outside')


if __name__ == '__main__':
    unittest.main()
