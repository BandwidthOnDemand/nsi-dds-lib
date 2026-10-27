#!/usr/bin/env python3
"""Check classpath bytecode, handwritten Javadoc, and Java 17 runtime bundles."""

import argparse
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import subprocess
import sys
import zipfile


TARGET = Path("target")
CLASSPATH = TARGET / "compatibility-classpath.txt"
SUPPORTED_JDKS = (17, 21, 25, 27)


def classpath():
    return [Path(entry) for entry in CLASSPATH.read_text().strip().split(os.pathsep) if entry]


def artifact():
    jars = sorted(TARGET.glob("dds-lib-*.jar"))
    if len(jars) != 1:
        raise ValueError(f"Expected one packaged DDS JAR, found {len(jars)}; run clean verify first")
    return jars[0]


def check_class(data, name, java):
    if len(data) < 8 or data[:4] != b"\xca\xfe\xba\xbe":
        raise ValueError(f"Invalid class header: {name}")
    minor = int.from_bytes(data[4:6], "big")
    major = int.from_bytes(data[6:8], "big")
    if major > java + 44 or minor == 65535:
        raise ValueError(f"{name}: class version {major}.{minor} is incompatible with Java {java}")


def check_jar(path, java):
    """Check the effective classpath view, not optional newer MR-JAR entries."""
    with zipfile.ZipFile(path) as jar:
        manifest = (jar.read("META-INF/MANIFEST.MF").decode("utf-8")
                    if "META-INF/MANIFEST.MF" in jar.namelist() else "")
        manifest = re.sub(r"\n ", "", manifest.replace("\r\n", "\n")).split("\n\n", 1)[0]
        multi_release = bool(re.search(r"(?im)^Multi-Release:\s*true\s*$", manifest))
        selected = {}
        for name in jar.namelist():
            if not name.endswith(".class"):
                continue
            version, logical = 0, name
            if name.startswith("META-INF/versions/"):
                parts = name.split("/", 3)
                if len(parts) != 4 or not parts[2].isdigit():
                    continue
                version, logical = int(parts[2]), parts[3]
                if not multi_release or not 9 <= version <= java:
                    continue
            # These tests use the classpath, where module descriptors are ignored.
            if logical == "module-info.class":
                continue
            if logical not in selected or version > selected[logical][0]:
                selected[logical] = (version, name)
        for _, name in selected.values():
            check_class(jar.read(name), f"{path}!/{name}", java)
        return len(selected)


def check_bytecode(java):
    count = 0
    jars = [artifact()] + classpath()
    for path in jars:
        count += check_jar(path, java)
    for path in (TARGET / "test-classes").rglob("*.class"):
        check_class(path.read_bytes(), str(path), java)
        count += 1
    print(f"Checked {count} effective classes in {len(jars)} JARs and compiled tests for Java {java}")


def javadoc():
    cp = os.pathsep.join(map(str, [TARGET / "test-classes", TARGET / "classes"] + classpath()))
    for kind in ("main", "test"):
        sources = sorted(Path("src", kind, "java").rglob("*.java"))
        subprocess.run(["javadoc", "-quiet", "-encoding", "UTF-8", "-source", "17",
                        "-Xdoclint:all,-missing", "-Werror", "-classpath", cp,
                        "-d", str(TARGET / f"apidocs-{kind}")] + list(map(str, sources)), check=True)


def bundle(destination):
    properties = subprocess.run(["java", "-XshowSettings:properties", "-version"],
                                capture_output=True, text=True, check=True).stderr
    if not re.search(r"(?m)^\s*java.specification.version = 17\s*$", properties):
        raise ValueError("Create the shared runtime bundle using Java 17")
    jar = artifact()
    tests = TARGET / "test-classes"
    if not any(tests.rglob("*Test.class")):
        raise ValueError("No compiled regression tests found")
    metadata = {"java": 17, "jar": jar.name,
                "sha256": hashlib.sha256(jar.read_bytes()).hexdigest()}
    with zipfile.ZipFile(destination, "w", compression=zipfile.ZIP_DEFLATED) as archive:
        archive.writestr("build.json", json.dumps(metadata))
        archive.write(jar, "artifact.jar")
        for path in sorted(tests.rglob("*")):
            if path.is_file():
                archive.write(path, "test-classes/" + path.relative_to(tests).as_posix())
    print(f"Saved Java 17 artifact and compiled tests to {destination}")


def write_entry(base, name, data):
    path = PurePosixPath(name)
    if path.is_absolute() or ".." in path.parts or "\\" in name:
        raise ValueError(f"Unsafe archive entry: {name}")
    output = base.joinpath(*path.parts)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_bytes(data)


def restore(source):
    classes, tests = TARGET / "classes", TARGET / "test-classes"
    for directory in (classes, tests):
        if directory.exists() and any(directory.iterdir()):
            raise ValueError(f"{directory} is not empty; restore into a clean checkout/build directory")
    with zipfile.ZipFile(source) as archive:
        metadata = json.loads(archive.read("build.json"))
        jar_bytes = archive.read("artifact.jar")
        if metadata["java"] != 17 or hashlib.sha256(jar_bytes).hexdigest() != metadata["sha256"]:
            raise ValueError("Invalid Java 17 artifact metadata or checksum")
        jar_name = metadata["jar"]
        if Path(jar_name).name != jar_name or not jar_name.startswith("dds-lib-"):
            raise ValueError("Invalid DDS artifact filename")
        TARGET.mkdir(exist_ok=True)
        jar = TARGET / jar_name
        jar.write_bytes(jar_bytes)
        with zipfile.ZipFile(jar) as packaged:
            for name in packaged.namelist():
                if not name.endswith("/"):
                    write_entry(classes, name, packaged.read(name))
        for name in archive.namelist():
            if name.startswith("test-classes/") and not name.endswith("/"):
                write_entry(tests, name.removeprefix("test-classes/"), archive.read(name))
    print("Restored Java 17 JAR classes/resources and compiled tests; no compilation performed")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    subcommands = parser.add_subparsers(dest="command", required=True)
    bytecode = subcommands.add_parser("bytecode")
    bytecode.add_argument("--java", type=int, choices=SUPPORTED_JDKS, required=True)
    subcommands.add_parser("javadoc")
    for command in ("bundle", "restore"):
        subcommands.add_parser(command).add_argument("archive", type=Path)
    args = parser.parse_args()
    try:
        if args.command == "bytecode":
            check_bytecode(args.java)
        elif args.command == "javadoc":
            javadoc()
        elif args.command == "bundle":
            bundle(args.archive)
        else:
            restore(args.archive)
    except (ValueError, OSError, KeyError, zipfile.BadZipFile, subprocess.CalledProcessError) as error:
        parser.exit(1, f"Compatibility verification failed: {error}\n")


if __name__ == "__main__":
    main()
