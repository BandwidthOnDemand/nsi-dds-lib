"""Exercise multi-release selection and archive boundaries with synthetic classes."""

import importlib.util
from pathlib import Path
import tempfile
import unittest
import zipfile

spec = importlib.util.spec_from_file_location("compatibility", Path(__file__).with_name("compatibility.py"))
compatibility = importlib.util.module_from_spec(spec)
spec.loader.exec_module(compatibility)


def class_header(java):
    return b"\xca\xfe\xba\xbe\x00\x00" + (java + 44).to_bytes(2, "big")


class BytecodeTest(unittest.TestCase):
    def jar(self, directory, multi_release, entries):
        path = Path(directory) / "fixture.jar"
        with zipfile.ZipFile(path, "w") as jar:
            jar.writestr("META-INF/MANIFEST.MF", "Manifest-Version: 1.0\r\nMulti-Release: "
                         + str(multi_release).lower() + "\r\n\r\n")
            for name, data in entries.items():
                jar.writestr(name, data)
        return path

    def test_newer_optional_entries_do_not_break_java17(self):
        with tempfile.TemporaryDirectory() as directory:
            jar = self.jar(directory, True, {"Example.class": class_header(17),
                "META-INF/versions/27/Example.class": class_header(27)})
            self.assertEqual(1, compatibility.check_jar(jar, 17))
            self.assertEqual(1, compatibility.check_jar(jar, 27))

    def test_selected_entry_must_be_loadable_on_its_runtime(self):
        with tempfile.TemporaryDirectory() as directory:
            jar = self.jar(directory, True, {"Example.class": class_header(17),
                "META-INF/versions/21/Example.class": class_header(25)})
            compatibility.check_jar(jar, 17)
            with self.assertRaisesRegex(ValueError, "incompatible with Java 21"):
                compatibility.check_jar(jar, 21)

    def test_versioned_classes_require_multi_release_manifest(self):
        with tempfile.TemporaryDirectory() as directory:
            jar = self.jar(directory, False, {"Example.class": class_header(17),
                "META-INF/versions/17/Example.class": class_header(27)})
            self.assertEqual(1, compatibility.check_jar(jar, 17))

    def test_named_manifest_section_does_not_enable_multi_release(self):
        with tempfile.TemporaryDirectory() as directory:
            jar = Path(directory) / "fixture.jar"
            with zipfile.ZipFile(jar, "w") as archive:
                archive.writestr("META-INF/MANIFEST.MF",
                                 "Manifest-Version: 1.0\r\n\r\nName: Example.class\r\n"
                                 "Multi-Release: true\r\n\r\n")
                archive.writestr("Example.class", class_header(17))
                archive.writestr("META-INF/versions/17/Example.class", class_header(27))
            self.assertEqual(1, compatibility.check_jar(jar, 17))

    def test_base_too_new_and_preview_bytecode_are_rejected(self):
        with self.assertRaises(ValueError):
            compatibility.check_class(class_header(21), "Example.class", 17)
        with self.assertRaises(ValueError):
            compatibility.check_class(b"\xca\xfe\xba\xbe\xff\xff\x00\x3d", "Example.class", 17)
        with self.assertRaises(ValueError):
            compatibility.check_class(b"invalid", "Example.class", 17)

    def test_archive_entries_cannot_escape_output_directory(self):
        with tempfile.TemporaryDirectory() as directory:
            for name in ("../outside", "/outside", "..\\outside"):
                with self.assertRaises(ValueError):
                    compatibility.write_entry(Path(directory), name, b"fixture")


if __name__ == "__main__":
    unittest.main()
