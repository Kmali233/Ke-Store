"""Check browser-to-IDEA URI handling without opening an IDE or modifying the registry."""
import json
from pathlib import Path
import tempfile
import unittest
from urllib.parse import urlencode
from idea_links import CONFIG, ROOT, launch_args, make_idea_url, parse_idea_url


class IdeaLinksTest(unittest.TestCase):
    def setUp(self):
        self.container = ROOT / "output/.feature-explainer/idea-link-tests"
        self.container.mkdir(parents=True, exist_ok=True)
        self.temp = tempfile.TemporaryDirectory(prefix="isolated-", dir=self.container)
        self.root = Path(self.temp.name).resolve()
        self.assertTrue(self.root.is_relative_to(self.container.resolve()))
        self.file = self.root / "模块 空格" / "Demo.java"
        self.file.parent.mkdir()
        self.file.write_text("class Demo {\n    void run() {}\n}\n", encoding="utf-8")

    def tearDown(self):
        self.assertTrue(self.root.is_relative_to(self.container.resolve()))
        self.temp.cleanup()

    def uri(self, file="模块 空格/Demo.java", line="2", **extra):
        return "xiaoke-idea://open?" + urlencode({"file": file, "line": line, **extra})

    def test_unicode_and_spaces_round_trip_to_a_real_source_line(self):
        url = make_idea_url("模块 空格/Demo.java", 2, self.root)
        self.assertEqual(parse_idea_url(url, self.root), (self.file, 2))
        self.assertNotIn(" ", url)

    def test_outside_paths_extra_arguments_and_invalid_line_numbers_are_rejected(self):
        for uri in (self.uri(file="../outside.java"), self.uri(file="C:/outside.java"),
                    self.uri(file="//server/share.java"), self.uri(file="模块 空格/Demo.java:stream"),
                    self.uri(file="output/backup.sql"), self.uri(line="-1"), self.uri(line="1;whoami"),
                    self.uri(line="4"), self.uri(command="ignored"), self.uri()+"&line=1",
                    self.uri().replace("xiaoke-idea://open", "other://open")):
            with self.subTest(uri=uri), self.assertRaises(ValueError):
                parse_idea_url(uri, self.root)

    def test_launcher_uses_separate_arguments_without_a_shell(self):
        executable = self.root / "IDEA with spaces" / "idea64.exe"
        executable.parent.mkdir()
        executable.touch()
        config = self.root / CONFIG
        config.parent.mkdir(parents=True)
        config.write_text(json.dumps({"idea_executable": str(executable)}), encoding="utf-8")
        self.assertEqual(launch_args(self.uri(), self.root),
                         [str(executable), "--line", "2", str(self.file)])


if __name__ == "__main__":
    unittest.main(verbosity=2)
