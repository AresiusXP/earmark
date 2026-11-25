#!/usr/bin/env python3
"""Check that an APK has the same native libraries for every ABI it supports."""
import argparse
import re
import sys
import zipfile


REQUIRED_ABIS = {"armeabi-v7a", "arm64-v8a"}
REQUIRED_LIBS = {"libtorrent4j.so"}
NATIVE_LIB = re.compile(r"lib/([^/]+)/([^/]+\.so)\Z")


def verify(apk):
    libraries = {}
    with zipfile.ZipFile(apk) as archive:
        for entry in archive.infolist():
            match = NATIVE_LIB.fullmatch(entry.filename)
            if match:
                abi, name = match.groups()
                if entry.file_size == 0:
                    raise ValueError(f"empty native library: {entry.filename}")
                libraries.setdefault(abi, set()).add(name)

    missing_abis = REQUIRED_ABIS - libraries.keys()
    if missing_abis:
        raise ValueError(f"missing required ABIs: {', '.join(sorted(missing_abis))}")

    expected = REQUIRED_LIBS | set().union(*libraries.values())
    missing = [
        f"{abi}: {', '.join(sorted(expected - names))}"
        for abi, names in sorted(libraries.items()) if expected - names
    ]
    if missing:
        raise ValueError("missing native libraries: " + "; ".join(missing))
    print(f"Verified native libraries for {', '.join(sorted(libraries))}: {', '.join(sorted(expected))}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", help="path to the release APK")
    args = parser.parse_args()
    try:
        verify(args.apk)
    except (OSError, zipfile.BadZipFile, ValueError) as error:
        print(f"APK native library verification failed: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
