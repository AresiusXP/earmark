#!/usr/bin/env python3
"""Checks of scripts/gen_strings.py: it must fail on a missing/orphan Spanish string or on format arguments that differ from English.
Run from app/: python3 scripts/test_gen_strings.py"""
import os
import shutil
import subprocess
import sys
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))


def run_in_copy(mutate):
    tmp = tempfile.mkdtemp()
    scripts = os.path.join(tmp, "scripts")
    shutil.copytree(HERE, scripts, ignore=shutil.ignore_patterns("__pycache__", "test_*"))
    mutate(scripts)
    p = subprocess.run([sys.executable, os.path.join(scripts, "gen_strings.py")], capture_output=True, text=True)
    return p.returncode, p.stdout + p.stderr, tmp


def edit(scripts, name, old, new):
    path = os.path.join(scripts, "strings_es", name)
    with open(path, encoding="utf-8") as f:
        s = f.read()
    assert old in s, old
    with open(path, "w", encoding="utf-8") as f:
        f.write(s.replace(old, new, 1))


class GenStringsTest(unittest.TestCase):
    def check(self, mutate, ok, needle=""):
        code, out, tmp = run_in_copy(mutate)
        shutil.rmtree(tmp, ignore_errors=True)
        if ok:
            self.assertEqual(code, 0, out)
        else:
            self.assertNotEqual(code, 0, out)
            self.assertIn(needle, out)

    def test_the_real_tables_are_complete(self):
        self.check(lambda s: None, True)

    def test_missing_translation_fails(self):
        self.check(lambda s: edit(s, "core.py", '    "common_back": "Volver",\n', ""), False, "missing Spanish for common_back")

    def test_orphan_translation_fails(self):
        self.check(lambda s: edit(s, "core.py", '    "common_back"', '    "common_nope": "x",\n    "common_back"'), False, "orphan translation common_nope")

    def test_translation_in_the_wrong_module_fails(self):
        self.check(lambda s: edit(s, "polish.py", "ES = {\n", 'ES = {\n    "common_back": "Volver",\n'), False, "orphan translation common_back")

    def test_missing_module_file_fails(self):
        self.check(lambda s: os.remove(os.path.join(s, "strings_es", "torrent.py")), False, "strings_es/torrent.py is missing")

    def test_changed_format_argument_fails(self):
        self.check(lambda s: edit(s, "core.py", "Demasiadas solicitudes. Probá de nuevo en %1$d s.", "Demasiadas solicitudes. Probá de nuevo en %1$s s."), False, "error_rate_limited_in: Spanish format arguments")

    def test_dropped_format_argument_fails(self):
        self.check(lambda s: edit(s, "core.py", '"Versión %1$s (%2$d)"', '"Versión %1$s"'), False, "settings_version: Spanish format arguments")

    def test_reordered_positional_arguments_are_fine(self):
        self.check(lambda s: edit(s, "core.py", '"Versión %1$s (%2$d)"', '"(%2$d) Versión %1$s"'), True)


if __name__ == "__main__":
    unittest.main()
