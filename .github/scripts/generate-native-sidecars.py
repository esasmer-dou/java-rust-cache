#!/usr/bin/env python3
"""Emit an apply_patch patch from trusted root provenance and actual native bytes; never write files."""
import argparse
import hashlib
from pathlib import Path
import re
import sys


REPOSITORY = Path(__file__).resolve().parents[2]
REQUIRED_FEATURES = ("websocket", "dubbo", "redis", "glowroot", "edge-cache", "edge-jwt",
                     "outbound-http", "shared-slab")


def canonical_features(value):
    # Cargo exposes hyphenated feature names as underscored CARGO_FEATURE_* names.
    features = [feature.strip().replace("_", "-") for feature in value.split(",")]
    if len(features) != len(REQUIRED_FEATURES) or set(features) != set(REQUIRED_FEATURES):
        raise ValueError("Native features must match the required cache distribution feature set")
    return ",".join(REQUIRED_FEATURES)


def properties(path):
    result = {}
    for line in path.read_text(encoding="ascii").splitlines():
        line = line.strip()
        if not line or line.startswith(("#", "!")):
            continue
        if "=" not in line or "\\" in line:
            raise ValueError(f"Expected unescaped key=value in {path}: {line}")
        key, value = (item.strip() for item in line.split("=", 1))
        if not key or not value or key in result:
            raise ValueError(f"Missing or duplicate provenance field in {path}: {key}")
        result[key] = value
    return result


def binary_evidence(path, revision):
    digest = hashlib.sha256()
    marker = revision.encode("ascii")
    feature_marker = ("\nfeatures=" + ",".join(sorted(
        feature.replace("-", "_") for feature in REQUIRED_FEATURES)) + "\n").encode("ascii")
    overlap = max(len(marker), len(feature_marker)) - 1
    tail = b""
    embedded = False
    features_embedded = False
    with path.open("rb") as binary:
        for chunk in iter(lambda: binary.read(64 * 1024), b""):
            digest.update(chunk)
            window = tail + chunk
            embedded = embedded or marker in window
            features_embedded = features_embedded or feature_marker in window
            tail = window[-overlap:]
    if not embedded:
        raise ValueError(f"Trusted source revision is not embedded in {path}")
    if not features_embedded:
        raise ValueError(f"Required runtime feature set is not embedded in {path}")
    return digest.hexdigest()


def render_sidecars(native_root, contract_path, revision, platforms, features=",".join(REQUIRED_FEATURES)):
    root = properties(native_root / "native-provenance.properties")
    contract = properties(contract_path)
    features = canonical_features(features)
    if not re.fullmatch(r"[0-9a-f]{40}", revision) or root.get("source.revision") != revision:
        raise ValueError("Trusted source revision must match root source.revision, never checkout HEAD")
    if contract.get("schema") != "2" or root.get("schema") != "2":
        raise ValueError("Native provenance schema must remain 2")
    abis = [key for key in root if key.endswith(".abi")]
    expected_abis = [key for key in contract if key.endswith(".abi")]
    if not expected_abis or any(root.get(key) != contract[key] for key in expected_abis):
        raise ValueError("Trusted root ABI contract mismatch")
    if not root.get("crate.version"):
        raise ValueError("Trusted root crate.version is required")
    result = {}
    for platform in platforms:
        filename = contract.get("binary." + platform, "")
        if not re.fullmatch(r"[a-z0-9]+(?:-[a-z0-9]+)*", platform) or not re.fullmatch(
                r"[A-Za-z0-9][A-Za-z0-9_.-]*", filename):
            raise ValueError(f"Invalid or missing native resource mapping: {platform}")
        digest = binary_evidence(native_root / platform / filename, revision)
        if digest != root.get(platform + ".sha256", "").lower():
            raise ValueError(f"Actual binary hash disagrees with trusted root for {platform}")
        fields = [("schema", "2")]
        fields.extend((key, root[key]) for key in abis)
        fields.extend([("crate.version", root["crate.version"]), ("source.revision", revision),
                       ("features", features),
                       (platform + ".sha256", digest),
                       ("distribution.scope", "current-source-compile-verified"),
                       ("phase79.current-source", "true"), ("phase79.optional-features", "known"),
                       ("runtime.verified", "false")])
        if platform.startswith("linux-"):
            key = platform + ".glibc-minimum"
            if contract.get(key) != "2.17" or root.get(key) != contract[key]:
                raise ValueError(f"Trusted Linux GLIBC minimum must remain 2.17 for {platform}")
            fields.append((key, root[key]))
        result[native_root / platform / "native-provenance.properties"] = "".join(
            f"{key}={value}\n" for key, value in fields)
    return result


def patch_for(sidecars):
    patch = ["*** Begin Patch"]
    for path, text in sidecars.items():
        old = path.read_text(encoding="ascii") if path.exists() else None
        if old == text:
            continue
        if old is None:
            patch.append(f"*** Add File: {path.as_posix()}")
        else:
            patch.extend([f"*** Update File: {path.as_posix()}", "@@"])
            patch.extend("-" + line for line in old.splitlines())
        patch.extend("+" + line for line in text.splitlines())
    patch.append("*** End Patch")
    return "\n".join(patch) + "\n"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--native-root", type=Path, default=REPOSITORY / "src/main/resources/native")
    parser.add_argument("--contract", type=Path, default=REPOSITORY / ".github/native-provenance-contract.properties")
    parser.add_argument("--trusted-source-revision", required=True)
    parser.add_argument("--platform", action="append", required=True)
    parser.add_argument("--features", default=",".join(REQUIRED_FEATURES),
                        help="Required feature list; Cargo runtime underscore aliases are accepted")
    parser.add_argument("--check", action="store_true", help="Only check that sidecars match generated content")
    args = parser.parse_args()
    sidecars = render_sidecars(args.native_root, args.contract, args.trusted_source_revision, args.platform,
                              args.features)
    if args.check:
        for path, text in sidecars.items():
            if not path.is_file() or path.read_text(encoding="ascii") != text:
                raise ValueError(f"Sidecar differs from trusted byte-derived generation: {path}")
        print(f"NATIVE_SIDECARS_MATCH count={len(sidecars)} source={args.trusted_source_revision}")
    else:
        print(patch_for(sidecars), end="")


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError) as error:
        print(error, file=sys.stderr)
        sys.exit(1)
