#!/usr/bin/env python3
"""Verify a signed public APK against its source files and build reports.

Requires apksigner, aapt2 and Pillow. Use --artifacts-only to check an existing
release without local build reports. An optional source ZIP is checked file by file.
"""

import argparse
from collections import Counter
import hashlib
from io import BytesIO
import json
from pathlib import Path
import re
import shutil
import stat
import struct
import subprocess
import xml.etree.ElementTree as ET
import zipfile

from PIL import Image


ANDROID = Path(__file__).resolve().parents[1]
ROOT = ANDROID.parent
CERTIFICATE = "884b58562d8a7cdf46f630c5c65ced9135163336bbfffec3b4a9548f7ea32cbc"
GPL_SHA256 = "3972dc9744f6499f0f9b2dbf76696f2ae7ad8af9b23dde66d6af86c9dfb36986"
AGPL_SHA256 = "0d96a4ff68ad6d4b6f1f30f713b18d5184912ba8dd389f86aa7710db079abcb0"
REPOSITORIES = {
    "tingyu": "https://github.com/qiuyuxc/tingyu",
    "chunkseal": "https://github.com/kity-yolo/chunkseal-protocol",
}


def require(condition, message):
    if not condition:
        raise ValueError(message)


def digest(data):
    return hashlib.sha256(data).hexdigest()


def command(*arguments):
    require(shutil.which(arguments[0]) is not None, f"Missing tool: {arguments[0]}")
    return subprocess.run(arguments, check=True, capture_output=True, text=True).stdout


def setting(name, config):
    match = re.search(rf'\b{name}\s*=\s*(?:"([^"\n]+)"|(\d+))', config)
    require(match is not None, f"Missing build setting: {name}")
    return match[1] if match[1] is not None else int(match[2])


def build_reports(latest_source):
    reports = sorted((ANDROID / "build/test-results/testDebugUnitTest").glob("TEST-*.xml"))
    require(bool(reports), "Missing unit test reports")
    totals = Counter()
    storage_tests = 0
    for report in reports:
        suite = ET.parse(report).getroot()
        require(report.stat().st_mtime >= latest_source, "Unit tests precede source changes")
        for key in ("tests", "failures", "errors", "skipped"):
            totals[key] += int(suite.get(key, 0))
        if suite.get("name") == "com.qiuyuxc.tingyu.FileStorageTest":
            storage_tests = int(suite.get("tests", 0))
    require(totals["tests"] > 0, "No unit tests ran")
    require(not any(totals[key] for key in ("failures", "errors", "skipped")), f"Unit tests: {totals}")
    totals["file_storage_tests"] = storage_tests
    lint = {}
    for name, directory in (("app", ANDROID), ("lrcview", ANDROID / "vendor/lrcview")):
        report = directory / "build/reports/lint-results-debug.xml"
        require(report.is_file(), f"Missing {name} Lint report")
        relevant = latest_source if name == "app" else max(
            p.stat().st_mtime for p in (directory / "src/main").rglob("*") if p.is_file()
        )
        require(report.stat().st_mtime >= relevant, f"{name} Lint precedes source changes")
        issues = Counter(item.get("severity") for item in ET.parse(report).getroot().findall("issue"))
        require(not issues["Error"] and not issues["Fatal"], f"{name} Lint errors: {issues}")
        lint[name] = dict(issues)
    return dict(totals), lint


def launcher_files(resource_table):
    densities = {"mdpi", "hdpi", "xhdpi", "xxhdpi", "xxxhdpi"}
    files = {}
    icon = None
    for line in resource_table.splitlines():
        if "resource 0x" in line:
            name = re.search(r"\bmipmap/(ic_launcher(?:_foreground)?)\s*$", line)
            icon = name[1] if name else None
        if icon is None:
            continue
        value = re.fullmatch(r"\s*\(([^)]+)\) \(file\) (res/\S+) type=PNG\s*", line)
        if value is None:
            continue
        density = value[1].split("-", 1)[0]
        if density in densities:
            key = (density, icon)
            require(key not in files, f"Duplicate launcher resource: {key}")
            files[key] = value[2]
    expected = {(density, icon) for density in densities for icon in ("ic_launcher", "ic_launcher_foreground")}
    require(files.keys() == expected, "Missing density-specific launcher icons")
    return files


def verify_archive(path):
    names = command("git", "-C", str(ROOT), "ls-files", "--cached", "--others", "--exclude-standard", "-z")
    expected = {name for name in names.split("\0") if name and (ROOT / name).is_file()}
    with zipfile.ZipFile(path) as archive:
        require(archive.testzip() is None, "Source ZIP CRC failed")
        entries = [entry for entry in archive.namelist() if not entry.endswith("/")]
        require(len(entries) == len(set(entries)), "Duplicate source ZIP entries")
        roots = {entry.split("/", 1)[0] for entry in entries}
        require(len(roots) == 1, "Source ZIP needs one top-level directory")
        prefix = roots.pop() + "/"
        require({entry.removeprefix(prefix) for entry in entries} == expected, "Source ZIP file set differs from source")
        for name in expected:
            require(not (ROOT / name).is_symlink(), f"Source symlink: {name}")
            mode = archive.getinfo(prefix + name).external_attr >> 16
            require(not stat.S_ISLNK(mode), f"Source ZIP symlink: {name}")
            require(bool(mode & 0o111) == bool((ROOT / name).stat().st_mode & 0o111), f"Source ZIP executable flag differs: {name}")
            require(archive.read(prefix + name) == (ROOT / name).read_bytes(), f"Source ZIP differs: {name}")
    return {"file": path.name, "files": len(expected), "bytes": path.stat().st_size, "sha256": digest(path.read_bytes())}


def verify_apk(path, certificate, artifacts_only):
    require(path.is_file(), f"Missing APK: {path}")
    config = (ANDROID / "build.gradle.kts").read_text()
    license_dir = ANDROID / "src/main/assets/licenses"
    licenses = sorted(license_dir.glob("*.txt"))
    require(digest((ROOT / "LICENSE").read_bytes()) == GPL_SHA256, "GNU GPLv3 text changed")
    require(digest((license_dir / "AGPL-3.0.txt").read_bytes()) == AGPL_SHA256, "GNU AGPLv3 text changed")
    for source, name in (
        (ROOT / "LICENSE", "GPL-3.0.txt"),
        (ROOT / "NOTICE", "Project-NOTICE.txt"),
        (ANDROID / "THIRD_PARTY.md", "ThirdParty-NOTICE.txt"),
        (ROOT / "licenses/ChunkSeal-NOTICE.txt", "ChunkSeal-NOTICE.txt"),
        (ROOT / "licenses/ChunkSeal-GPL-Permission.md", "ChunkSeal-GPL-Permission.txt"),
    ):
        require(source.read_bytes() == (license_dir / name).read_bytes(), f"Bundled {name} differs from its source")
    about = (ANDROID / "src/main/kotlin/com/qiuyuxc/tingyu/AboutPage.kt").read_text()
    referenced = set(re.findall(r'"([A-Za-z0-9_.-]+\.txt)"', about))
    require(referenced == {p.name for p in licenses}, "About page does not cover every license asset")

    sources = list((ANDROID / "src").rglob("*.kt")) + list((ANDROID / "src").rglob("*.java"))
    sources += list((ANDROID / "vendor/lrcview/src").rglob("*.java"))
    latest_source = max(p.stat().st_mtime for p in sources)
    tests, lint = (None, None) if artifacts_only else build_reports(latest_source)
    if not artifacts_only:
        inputs = sources + list((ANDROID / "res").rglob("*")) + list((ANDROID / "src/main/assets").rglob("*"))
        inputs += [ANDROID / name for name in ("AndroidManifest.xml", "build.gradle.kts", "settings.gradle.kts", "gradle.properties")]
        require(path.stat().st_mtime >= max(p.stat().st_mtime for p in inputs if p.is_file()), "APK precedes build inputs")
    signatures = command("apksigner", "verify", "--print-certs", str(path))
    certificates = re.findall(r"certificate SHA-256 digest: ([0-9a-f]+)", signatures, re.I)
    require([value.lower() for value in certificates] == [certificate], "Signing certificate changed")
    badging = command("aapt2", "dump", "badging", str(path))
    fields = dict(re.findall(r"([a-zA-Z]+)='([^']*)'", badging.splitlines()[0]))
    require(fields["name"] == "com.qiuyuxc.tingyu.opensource", "Incorrect application ID")
    require("application-debuggable" not in badging, "APK is debuggable")
    for field, value in (("versionName", "versionName"), ("versionCode", "versionCode"), ("compileSdkVersion", "compileSdk")):
        require(fields[field] == str(setting(value, config)), f"Incorrect {field}")
    label = ET.parse(ANDROID / "res/values/strings.xml").find("string[@name='app_name']").text
    require(f"application-label:'{label}'" in badging, "Incorrect application label")
    for field in ("minSdk", "targetSdk"):
        pattern = r"(?:minSdkVersion|sdkVersion):'(\d+)'" if field == "minSdk" else r"targetSdkVersion:'(\d+)'"
        match = re.search(pattern, badging)
        require(match is not None and int(match[1]) == setting(field, config), f"Incorrect {field}")
    native = []
    with zipfile.ZipFile(path) as archive, path.open("rb") as raw:
        require(archive.testzip() is None, "APK ZIP CRC failed")
        actual = {entry for entry in archive.namelist() if entry.startswith("assets/licenses/") and not entry.endswith("/")}
        require(actual == {"assets/licenses/" + p.name for p in licenses}, "APK license inventory differs from source")
        for file in licenses:
            require(archive.read("assets/licenses/" + file.name) == file.read_bytes(), f"APK license differs: {file.name}")
        require(not any(entry.startswith("assets/keys/") for entry in archive.namelist()), "Unexpected key assets in public APK")
        dex = [archive.read(entry) for entry in archive.namelist() if entry.endswith(".dex")]
        for value in ["AboutPage", "FileUploader", "UploadDialog", "CloudFilesPage", "StorageIoKt", *REPOSITORIES.values()]:
            require(any(value.encode() in data for data in dex), f"Missing APK entry: {value}")
        for entry in archive.infolist():
            if entry.filename.startswith("lib/") and entry.filename.endswith(".so"):
                raw.seek(entry.header_offset)
                header = raw.read(30)
                name_size, extra_size = struct.unpack_from("<HH", header, 26)
                offset = entry.header_offset + 30 + name_size + extra_size
                require(entry.compress_type == zipfile.ZIP_STORED and offset % 16384 == 0, f"Native library alignment: {entry.filename}")
                native.append(entry.filename)
        icons = launcher_files(command("aapt2", "dump", "resources", str(path)))
        for (density, name), entry in icons.items():
            with Image.open(BytesIO(archive.read(entry))) as actual, Image.open(ANDROID / "res" / f"mipmap-{density}" / f"{name}.png") as source:
                actual, source = actual.convert("RGBA"), source.convert("RGBA")
                require(actual.size == source.size and actual.tobytes() == source.tobytes(), f"Launcher icon differs: {entry}")
    return {
        "version": fields["versionName"], "version_code": int(fields["versionCode"]),
        "application_id": fields["name"], "debuggable": False, "project_license": "GPL-3.0-only",
        "signature_verified": True, "certificate_sha256": certificate, "zip_crc_verified": True,
        "native_libraries_aligned_16kb": native, "launcher_pngs_verified": len(icons),
        "license_assets_verified": len(licenses), "license_assets_sha256": {p.name: digest(p.read_bytes()) for p in licenses},
        "repositories": REPOSITORIES, "file_upload_code_present": True,
        "apk": {"file": path.name, "bytes": path.stat().st_size, "sha256": digest(path.read_bytes())},
        "unit_tests": tests, "lint": lint,
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path)
    parser.add_argument("--certificate", default=CERTIFICATE)
    parser.add_argument("--artifacts-only", action="store_true")
    parser.add_argument("--source-archive", type=Path)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    certificate = args.certificate.lower().replace(":", "")
    require(re.fullmatch(r"[0-9a-f]{64}", certificate) is not None, "Invalid certificate SHA-256")
    result = verify_apk(args.apk, certificate, args.artifacts_only)
    if args.source_archive is not None:
        result["source_archive"] = verify_archive(args.source_archive)
    command("git", "-C", str(ROOT), "diff", "--check")
    encoded = json.dumps(result, ensure_ascii=False, indent=2) + "\n"
    if args.output is not None:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(encoded, encoding="utf-8")
    print(encoded, end="")


if __name__ == "__main__":
    main()
