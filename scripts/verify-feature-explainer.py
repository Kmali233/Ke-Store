"""Exercise report handoff and native hook contracts using isolated local fixtures."""
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("feature_explainer", ROOT / "scripts/feature-explainer.py")
workflow = importlib.util.module_from_spec(spec)
spec.loader.exec_module(workflow)


class FeatureExplainerTest(unittest.TestCase):
    def setUp(self):
        self.container = ROOT / "output/.feature-explainer/tests"
        self.container.mkdir(parents=True, exist_ok=True)
        self.temp = tempfile.TemporaryDirectory(prefix="isolated-", dir=self.container)
        self.root = Path(self.temp.name).resolve() / "repository"
        self.root.mkdir()
        self.assertTrue(self.root.is_relative_to(self.container.resolve()))
        (self.root / "source.java").write_text("class Example {}", encoding="utf-8")
        self.session = "isolated-session"

    def tearDown(self):
        self.assertTrue(self.root.is_relative_to(self.container.resolve()))
        self.temp.cleanup()

    def payload(self, event="Stop", **extra):
        return {"hook_event_name": event, "session_id": self.session,
                "cwd": str(self.root), "stop_hook_active": False, **extra}

    def require(self, name="测试功能"):
        stage = "01-测试功能"
        (self.root.parent / "基础模块后阶段计划" / stage).mkdir(parents=True, exist_ok=True)
        return workflow.require_report(self.root, self.session, name, stage)

    def write_report(self, state, extra="", with_document=True):
        if with_document and "stage" in state:
            document = workflow.implementation_document(self.root, state["stage"])
            document.write_text("# 测试功能实现说明\n\n实现内容、修改文件与实际验证记录。\n", encoding="utf-8")
        path = workflow.report_path(self.root, state)
        path.parent.mkdir(parents=True, exist_ok=True)
        source_href = Path(os.path.relpath(self.root / "source.java", path.parent)).as_posix()
        path.write_text(f'''<!doctype html><html lang="zh-CN"><head><meta charset="utf-8">
<title>{state['name']} · 实现讲解</title></head><body>
<h1>{state['name']}</h1><h2 id="flow">调用链</h2>
<svg viewBox="0 0 300 100" role="img"><title>请求到数据库</title>
<text x="10" y="30">请求 → 服务 → 数据库</text></svg>
<h2>数据变化</h2><p>示例业务值在同一事务中更新。</p>
<h2>验证与源码</h2><a href="{source_href}">Example.java</a>
<a href="#flow">回到调用链</a>{extra}</body></html>''', encoding="utf-8")
        return path

    def test_unrelated_turn_is_quiet_and_creates_no_state(self):
        self.assertEqual(workflow.hook(self.payload(), self.root), {})
        self.assertFalse(workflow.state_path(self.root, self.session).exists())

    def test_prompt_context_uses_current_session_without_saving_prompt(self):
        output = workflow.hook(self.payload("UserPromptSubmit", prompt="Synthetic-Private-Prompt"), self.root)
        context = output["hookSpecificOutput"]["additionalContext"]
        self.assertIn(self.session, context)
        self.assertIn("在标题下先用一句贴近用户操作的类比", context)
        self.assertIn("标出“面试重点”并注明具体主题", context)
        self.assertIn("可能的面试追问和基于源码的简短答法", context)
        self.assertIn("核心源码链接后加醒目的“重点”标签", context)
        self.assertIn("每个重点下方另起一行", context)
        self.assertNotIn("Synthetic-Private-Prompt", json.dumps(output))
        self.assertFalse(workflow.state_path(self.root, self.session).exists())

    def test_missing_report_requests_one_continuation_then_stops(self):
        self.require()
        first = workflow.hook(self.payload(), self.root)
        self.assertEqual(first["decision"], "block")
        self.assertIn("在标题下先用一句贴近用户操作的类比", first["reason"])
        self.assertIn("标出“面试重点”并注明具体主题", first["reason"])
        self.assertIn("可能的面试追问和基于源码的简短答法", first["reason"])
        self.assertIn("核心源码链接后加醒目的“重点”标签", first["reason"])
        self.assertIn("每个重点下方另起一行", first["reason"])
        second = workflow.hook(self.payload(stop_hook_active=True), self.root)
        self.assertNotIn("decision", second)
        self.assertEqual(workflow.load_state(self.root, self.session)["status"], "deferred")
        self.assertEqual(workflow.hook(self.payload(), self.root), {})

    def test_recovery_flag_also_prevents_loops_when_runtime_flag_is_absent(self):
        self.require()
        self.assertEqual(workflow.hook(self.payload(), self.root)["decision"], "block")
        self.assertNotIn("decision", workflow.hook(self.payload(), self.root))

    def test_completed_report_releases_stop_and_records_exact_content(self):
        state = self.require()
        path = self.write_report(state)
        complete = workflow.complete_report(self.root, self.session)
        self.assertEqual(complete["sha256"], workflow.digest(path))
        self.assertEqual(workflow.hook(self.payload(), self.root), {})

    def test_stop_accepts_valid_fresh_report_even_if_complete_command_was_missed(self):
        self.write_report(self.require())
        self.assertEqual(workflow.hook(self.payload(), self.root), {})
        self.assertEqual(workflow.load_state(self.root, self.session)["status"], "complete")

    def test_unchanged_old_report_is_rejected_and_backed_up(self):
        path = self.write_report(self.require())
        workflow.complete_report(self.root, self.session)
        before = path.read_bytes()
        state = self.require()
        self.assertEqual((self.root / state["backup"]).read_bytes(), before)
        with self.assertRaisesRegex(ValueError, "before this feature"):
            workflow.complete_report(self.root, self.session)
        self.write_report(state, "<p>本次验证记录已更新。</p>")
        self.assertEqual(workflow.complete_report(self.root, self.session)["status"], "complete")

    def test_repeated_registration_does_not_reset_freshness_or_other_sessions(self):
        state = self.require()
        self.write_report(state)
        self.assertEqual(self.require()["baseline_sha256"], None)
        other = self.payload(session_id="different-session")
        self.assertEqual(workflow.hook(other, self.root), {})
        self.assertEqual(workflow.load_state(self.root, self.session)["status"], "pending")
        with self.assertRaisesRegex(ValueError, "pending report"):
            self.require("另一个功能")

    def test_names_and_session_ids_cannot_escape_output(self):
        for name in ("../escape", "a/b", "CON", "name.", "NUL.html", "bad\nname"):
            with self.subTest(name=name), self.assertRaises(ValueError):
                self.require(name)
        with self.assertRaises(ValueError):
            workflow.state_path(self.root, "../session")
        with self.assertRaises(ValueError):
            workflow.report_path(self.root, {"html": "../escape.html"})

    def test_broken_links_and_non_embedded_resources_block_handoff(self):
        state = self.require()
        for extra in ('<a href="#missing">broken</a>', '<a href="missing.java">broken</a>',
                      '<script src="https://example.com/app.js"></script>',
                      '<img src="other.png">', '<h3 id="flow">duplicate</h3>'):
            with self.subTest(extra=extra):
                self.write_report(state, extra)
                with self.assertRaises(ValueError):
                    workflow.complete_report(self.root, self.session)

    def test_idea_links_are_validated_against_actual_project_files_and_lines(self):
        from idea_links import make_idea_url
        state = self.require()
        link = make_idea_url("source.java", 1, self.root)
        self.write_report(state, '<a href="' + link.replace('&', '&amp;') + '">IDEA</a>')
        self.assertEqual(workflow.complete_report(self.root, self.session)["status"], "complete")
        self.write_report(state, '<a href="xiaoke-idea://open?file=source.java&amp;line=999">IDEA</a>')
        with self.assertRaisesRegex(ValueError, "line no longer exists"):
            workflow.complete_report(self.root, self.session)

    def test_moved_stage_document_links_resolve_without_allowing_arbitrary_external_files(self):
        checkout = self.root / "repository"
        checkout.mkdir()
        (checkout / "source.java").write_text("class Example {}", encoding="utf-8")
        plans = self.root / "基础模块后阶段计划" / "06-管理操作审计模块"
        plans.mkdir(parents=True)
        (plans / "详细实现说明.md").write_text("# 实现说明", encoding="utf-8")
        (self.root / "unrelated.md").write_text("# Other", encoding="utf-8")
        self.root = checkout
        state = self.require()
        report = workflow.report_path(self.root, state)
        doc_href = Path(os.path.relpath(plans / "详细实现说明.md", report.parent)).as_posix()
        other_href = Path(os.path.relpath(self.root.parent / "unrelated.md", report.parent)).as_posix()
        self.write_report(state, '<a href="' + doc_href + '">说明</a>')
        self.assertEqual(workflow.complete_report(self.root, self.session)["status"], "complete")
        self.write_report(state, '<a href="' + other_href + '">Other</a>')
        with self.assertRaises(ValueError):
            workflow.complete_report(self.root, self.session)

    def test_report_and_document_are_registered_in_the_same_stage_directory(self):
        state = self.require()
        report = workflow.report_path(self.root, state)
        document = workflow.implementation_document(self.root, state["stage"])
        self.assertEqual(report.parent, document.parent)
        self.assertEqual(report.name, "测试功能.html")
        self.assertEqual((self.root / state["html"]).resolve(), report)
        self.write_report(state, '<a href="详细实现说明.md">实现说明</a>')
        self.assertEqual(workflow.complete_report(self.root, self.session)["status"], "complete")

    def test_report_in_old_output_folder_or_another_stage_cannot_complete(self):
        state = self.require()
        for html in ("output/功能实现讲解/测试功能.html", "../基础模块后阶段计划/02-other/测试功能.html"):
            with self.subTest(html=html), self.assertRaisesRegex(ValueError, "registered"):
                workflow.report_path(self.root, {**state, "html": html})

    def test_old_pending_registration_switches_destination_without_moving_history(self):
        state = self.require()
        legacy = self.root / "output/功能实现讲解/测试功能.html"
        legacy.parent.mkdir(parents=True)
        legacy.write_text("previous report", encoding="utf-8")
        state.update(html=legacy.relative_to(self.root).as_posix(), baseline_sha256=workflow.digest(legacy))
        workflow.save_state(self.root, self.session, state)
        updated = self.require()
        self.assertEqual(workflow.report_path(self.root, updated).parent,
                         workflow.implementation_document(self.root, updated["stage"]).parent)
        self.assertIsNone(updated["baseline_sha256"])
        self.assertEqual(legacy.read_text(encoding="utf-8"), "previous report")

    def test_missing_or_misplaced_document_blocks_delivery_even_when_html_is_ready(self):
        state = self.require()
        self.write_report(state, with_document=False)
        (self.root / "详细实现说明.md").write_text("# 放在了错误位置", encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "non-empty implementation document"):
            workflow.complete_report(self.root, self.session)
        result = workflow.hook(self.payload(), self.root)
        self.assertEqual(result["decision"], "block")
        self.assertIn("基础模块后阶段计划/01-测试功能/详细实现说明.md", result["reason"])

    def test_correct_document_is_required_nonempty_and_its_hash_is_recorded(self):
        state = self.require()
        self.write_report(state, with_document=False)
        doc = workflow.implementation_document(self.root, state["stage"])
        doc.write_text(" \n", encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "non-empty implementation document"):
            workflow.complete_report(self.root, self.session)
        doc.write_text("# 实现说明\n\n本次实现和验证结果。", encoding="utf-8")
        complete = workflow.complete_report(self.root, self.session)
        self.assertEqual(complete["implementation_document_sha256"], workflow.digest(doc))

    def test_stage_selection_is_explicit_or_unique_and_cannot_escape_plans(self):
        plans = self.root.parent / "基础模块后阶段计划"
        (plans / "06-管理操作审计模块").mkdir(parents=True)
        self.assertEqual(workflow.select_stage(self.root, "管理操作审计"), "06-管理操作审计模块")
        with self.assertRaises(ValueError):
            workflow.select_stage(self.root, "未知功能")
        for stage in ("../outside", "..", "06-不存在"):
            with self.subTest(stage=stage), self.assertRaises(ValueError):
                workflow.implementation_document(self.root, stage)

    def test_report_cannot_claim_an_implementation_document_from_a_different_stage(self):
        state = self.require()
        self.write_report(state)
        state["implementation_document"] = "基础模块后阶段计划/other/详细实现说明.md"
        with self.assertRaisesRegex(ValueError, "differs from the registered stage"):
            workflow.validate_document(self.root, state)

    def test_subdirectory_hook_works_and_outside_project_is_ignored(self):
        sub = self.root / "module"
        sub.mkdir()
        self.assertIn("hookSpecificOutput", workflow.hook(self.payload("UserPromptSubmit", cwd=str(sub)), self.root))
        self.assertEqual(workflow.hook(self.payload(cwd=str(self.root.parent)), self.root), {})

    def test_actual_cli_hook_accepts_utf8_and_emits_only_json(self):
        result = subprocess.run([sys.executable, str(ROOT / "scripts/feature-explainer.py"), "hook"],
                                input=json.dumps({"hook_event_name": "UserPromptSubmit", "session_id": "utf8-test",
                                                  "cwd": str(ROOT), "prompt": "中文测试内容"}, ensure_ascii=False),
                                text=True, encoding="utf-8", capture_output=True, cwd=ROOT, check=True)
        self.assertEqual(json.loads(result.stdout)["hookSpecificOutput"]["hookEventName"], "UserPromptSubmit")
        self.assertEqual(result.stderr, "")

    @unittest.skipUnless(sys.platform == "win32", "Windows hook launcher check")
    def test_installed_windows_commands_work_in_both_shells(self):
        config = json.loads((ROOT / ".codex/hooks.json").read_text(encoding="utf-8"))
        for event, groups in config["hooks"].items():
            command = groups[0]["hooks"][0]["commandWindows"]
            payload = json.dumps({"hook_event_name": event, "session_id": "launcher-smoke-only",
                                  "cwd": str(ROOT), "prompt": "只验证命令启动，不登记功能"}, ensure_ascii=False)
            shells = {"powershell": ["powershell.exe", "-NoProfile", "-NonInteractive", "-Command", command],
                      "cmd": 'cmd.exe /d /s /c "' + command + '"'}
            for name, args in shells.items():
                with self.subTest(event=event, shell=name):
                    result = subprocess.run(args, input=payload, text=True, encoding="utf-8",
                                            capture_output=True, cwd=ROOT, check=True)
                    output = json.loads(result.stdout)
                    self.assertEqual(result.stderr, "")
                    if event == "UserPromptSubmit":
                        self.assertEqual(output["hookSpecificOutput"]["hookEventName"], event)
                    else:
                        self.assertEqual(output, {})


if __name__ == "__main__":
    unittest.main(verbosity=2)
