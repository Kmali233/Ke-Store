"""Project-local Codex lifecycle hooks and HTML handoff checks. Standard library only."""
import argparse
import hashlib
from html.parser import HTMLParser
import json
import os
from pathlib import Path
import re
import sys
import tempfile
from urllib.parse import unquote, urlsplit

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT / "scripts") not in sys.path:
    sys.path.insert(0, str(ROOT / "scripts"))
from idea_links import SCHEME as IDEA_SCHEME, parse_idea_url
SKILL = ".agents/skills/feature-explainer/SKILL.md"
OUTPUT = Path("output/功能实现讲解")
SESSION_PATTERN = re.compile(r"[A-Za-z0-9_-]{1,128}\Z")
PLANS_DIRECTORY = "基础模块后阶段计划"
DOCUMENT_INSTRUCTION = (
    "功能实现说明必须写为基础模块后阶段计划/<对应阶段目录>/详细实现说明.md；"
    "登记时用 --stage 指定对应阶段目录，交付前同时检查该说明存在、非空且位置正确。"
)
IDEA_INSTRUCTION = (
    "源码索引默认点击后在已启动的 IDEA 中打开并定位到实际方法行："
    "核对当前源码行号，用 scripts/idea_links.py url 生成 xiaoke-idea:// 链接，"
    "旁边保留相对路径的“浏览器查看”入口，并检查目标文件与行号有效。"
)


def session_id(value=None):
    value = value or os.environ.get("CODEX_THREAD_ID") or os.environ.get("CODEX_SESSION_ID")
    if not isinstance(value, str) or not SESSION_PATTERN.fullmatch(value):
        raise ValueError("Pass --session-id from the UserPromptSubmit hook context.")
    return value


def state_path(root, session):
    key = hashlib.sha256(session_id(session).encode()).hexdigest()
    return root / "output/.feature-explainer" / (key + ".json")


def save_state(root, session, state):
    dest = state_path(root, session)
    dest.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile(mode="w", encoding="utf-8", dir=dest.parent,
                                     prefix="state-", suffix=".tmp", delete=False) as stream:
        tmp = Path(stream.name)
        json.dump(state, stream, ensure_ascii=False, indent=2)
    try:
        tmp.replace(dest)
    finally:
        tmp.unlink(missing_ok=True)


def load_state(root, session):
    path = state_path(root, session)
    return json.loads(path.read_text(encoding="utf-8")) if path.exists() else None


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest() if path.is_file() else None


def report_path(root, state):
    path = (root / state["html"]).resolve()
    allowed = (root / OUTPUT).resolve()
    if (not allowed.is_relative_to(root.resolve()) or not path.is_relative_to(allowed)
            or path.suffix.lower() != ".html"):
        raise ValueError("Report must stay inside output/功能实现讲解.")
    return path


def implementation_document(root, stage):
    if (not isinstance(stage, str) or not stage or stage != stage.strip()
            or stage in (".", "..") or re.search(r'[<>:"/\\|?*\x00-\x1f]', stage)):
        raise ValueError("Pass --stage with the name of the corresponding stage directory")
    plans = (root.parent / PLANS_DIRECTORY).resolve()
    directory = (plans / stage).resolve()
    if (not plans.is_relative_to(root.parent.resolve()) or directory.parent != plans
            or not directory.is_dir()):
        raise ValueError("Stage directory must exist directly inside 基础模块后阶段计划")
    document = (directory / "详细实现说明.md").resolve()
    if document.parent != directory:
        raise ValueError("Implementation document must stay in its corresponding stage directory")
    return document


def select_stage(root, name, explicit=None):
    if explicit:
        implementation_document(root, explicit)
        return explicit
    def normalized(value):
        value = re.sub(r"^\d+[-_、.\s]*", "", value)
        return re.sub(r"(?:模块|功能)$", "", value).strip()
    plans = root.parent / PLANS_DIRECTORY
    candidates = [p.name for p in plans.iterdir()
                  if p.is_dir() and normalized(p.name) == normalized(name)] if plans.is_dir() else []
    if len(candidates) != 1:
        raise ValueError("Choose the corresponding plan folder with --stage; it cannot be inferred uniquely")
    implementation_document(root, candidates[0])
    return candidates[0]


def validate_document(root, state):
    document = implementation_document(root, state.get("stage"))
    expected = document.relative_to(root.parent).as_posix()
    if state.get("implementation_document") != expected:
        raise ValueError("Implementation document location differs from the registered stage")
    if not document.is_file() or not document.read_text(encoding="utf-8-sig").strip():
        raise ValueError("Write a non-empty implementation document at " + expected)
    return digest(document)


class HtmlReport(HTMLParser):
    def __init__(self):
        super().__init__()
        self.ids = set()
        self.links = []
        self.title = ""
        self.in_title = False
        self.svg = 0
        self.headings = 0
        self.is_html = False
        self.has_language = False
        self.errors = []

    def handle_starttag(self, tag, attrs):
        attrs = dict(attrs)
        if tag == "html":
            self.is_html = True
            self.has_language = bool(attrs.get("lang"))
        if tag == "title":
            self.in_title = True
        if tag == "svg":
            self.svg += 1
        if tag == "h2":
            self.headings += 1
        if "id" in attrs:
            if attrs["id"] in self.ids:
                self.errors.append("Duplicate HTML id")
            self.ids.add(attrs["id"])
        if tag == "a" and attrs.get("href"):
            self.links.append(attrs["href"])
        if (tag == "script" and "src" in attrs
                or tag == "link" and "stylesheet" in attrs.get("rel", "").split()
                or tag in ("img", "video", "audio", "source", "iframe", "object", "embed")
                and any(attrs.get(a) and not attrs[a].startswith("data:") for a in ("src", "data", "poster"))
                or tag == "image" and any(attrs.get(a) and not attrs[a].startswith(("data:", "#"))
                                          for a in ("href", "xlink:href"))):
            self.errors.append("Keep report media, styles and scripts embedded for offline reading")

    def handle_endtag(self, tag):
        if tag == "title":
            self.in_title = False

    def handle_data(self, data):
        if self.in_title:
            self.title += data


def validate_report(root, state, fresh=True):
    path = report_path(root, state)
    if not path.is_file():
        raise ValueError("HTML report has not been generated")
    current = digest(path)
    if fresh and current == state.get("baseline_sha256"):
        raise ValueError("HTML still matches the report from before this feature handoff")
    parser = HtmlReport()
    parser.feed(path.read_text(encoding="utf-8"))
    parser.close()
    if not parser.is_html or not parser.has_language or state["name"] not in parser.title:
        raise ValueError("HTML needs its language and this feature name in the page title")
    if not parser.svg or parser.headings < 3:
        raise ValueError("HTML needs an inline diagram and developed explanation sections")
    source_links = 0
    for href in parser.links:
        url = urlsplit(href)
        if url.scheme == IDEA_SCHEME:
            parse_idea_url(href, root)
            source_links += 1
            continue
        if url.scheme in ("https", "http", "mailto"):
            continue
        if url.scheme or url.netloc:
            raise ValueError("Use relative project links or https source links")
        if not url.path:
            if unquote(url.fragment) not in parser.ids:
                raise ValueError("HTML contains a broken section anchor")
            continue
        target = (path.parent / unquote(url.path)).resolve()
        if not target.is_file():
            raise ValueError("HTML contains a missing or out-of-project local file link")
        if not target.is_relative_to(root.resolve()):
            plans = (root.parent / "基础模块后阶段计划").resolve()
            if target.is_relative_to(plans) and target.suffix.lower() == ".md":
                continue
            raise ValueError("HTML contains a missing or out-of-project local file link")
        if not target.is_relative_to((root / "output").resolve()):
            source_links += 1
    if not source_links:
        raise ValueError("Link at least one current project source or implementation document")
    if parser.errors:
        raise ValueError("; ".join(dict.fromkeys(parser.errors)))
    return current


def require_report(root, session, name, stage=None):
    if (not name or name != name.strip() or len(name) > 80
            or re.search(r'[<>:"/\\|?*\x00-\x1f]', name) or name.endswith(".")
            or name in (".", "..") or name.split(".")[0].upper()
            in {"CON", "PRN", "AUX", "NUL", *(f"COM{i}" for i in range(1, 10)),
                *(f"LPT{i}" for i in range(1, 10))}):
        raise ValueError("Use a short feature name without path or Windows filename characters")
    existing = load_state(root, session)
    if existing and existing["status"] == "pending":
        if existing["name"] == name:
            if stage is not None and existing.get("stage") not in (None, stage):
                raise ValueError("Finish the registered stage before switching document locations")
            if not existing.get("stage"):
                existing["stage"] = select_stage(root, name, stage)
                existing["implementation_document"] = implementation_document(root, existing["stage"]).relative_to(root.parent).as_posix()
                save_state(root, session, existing)
            return existing
        raise ValueError("Finish or explicitly defer the pending report before starting another")
    state = {"name": name, "html": (OUTPUT / (name + ".html")).as_posix(),
             "status": "pending", "recovery_requested": False}
    state["stage"] = select_stage(root, name, stage)
    state["implementation_document"] = implementation_document(root, state["stage"]).relative_to(root.parent).as_posix()
    state["baseline_sha256"] = digest(report_path(root, state))
    if state["baseline_sha256"]:
        backup = root / "output/.feature-explainer/backups" / (name + "-" + state["baseline_sha256"] + ".html")
        backup.parent.mkdir(parents=True, exist_ok=True)
        if backup.exists():
            if digest(backup) != state["baseline_sha256"]:
                raise ValueError("Existing report backup does not match its receipt")
        else:
            with backup.open("xb") as stream:
                stream.write(report_path(root, state).read_bytes())
        state["backup"] = backup.relative_to(root).as_posix()
    save_state(root, session, state)
    return state


def complete_report(root, session):
    state = load_state(root, session)
    if not state:
        raise ValueError("Register the completed feature with require first")
    current = validate_report(root, state)
    document_hash = validate_document(root, state)
    state.update(status="complete", sha256=current, implementation_document_sha256=document_hash)
    save_state(root, session, state)
    return state


def hook(payload, root=ROOT):
    # Hook input is data only: never execute prompt/message text or read transcripts.
    if not isinstance(payload, dict):
        raise ValueError("Hook input must be a JSON object")
    event = payload.get("hook_event_name")
    if event not in ("UserPromptSubmit", "Stop"):
        return {}
    cwd = Path(payload.get("cwd", str(root))).resolve()
    if not cwd.is_relative_to(root.resolve()):
        return {}
    session = session_id(payload.get("session_id"))
    if event == "UserPromptSubmit":
        context = (
            "本项目已配置功能完成后的 HTML 图文讲解流程。只在新功能/模块/阶段实现完成并确认实际验证状态后，"
            f"读取 {SKILL}，执行 scripts/feature-explainer.py 的 require、生成图文 HTML、验证并 complete。"
            "用一条真实操作讲实现，复用技能内参考页的图文风格；有实际问题再写解决过程。"
            f"{IDEA_INSTRUCTION}"
            f"{DOCUMENT_INSTRUCTION}"
            "普通问答、计划、代码审查、提交推送及本流程自身维护不触发；用户本次明确不需要 HTML 时遵从用户。"
            f"本次 hook session_id={session}，登记和交付检查请显式使用此 ID。"
        )
        return {"hookSpecificOutput": {"hookEventName": event, "additionalContext": context}}
    state = load_state(root, session)
    if not state or state["status"] != "pending":
        return {}
    try:
        complete_report(root, session)
        return {}
    except ValueError as error:
        if payload.get("stop_hook_active") or state.get("recovery_requested"):
            state.update(status="deferred", error=str(error))
            save_state(root, session, state)
            return {"systemMessage": "实现说明或 HTML 交付尚未完成，已停止自动续跑；请如实报告未完成项。"}
        state["recovery_requested"] = True
        save_state(root, session, state)
        return {"decision": "block", "reason": (
            f"已登记完成的功能“{state['name']}”的实现说明或 HTML 交付尚未齐全：{error}。"
            f"请读取 {SKILL}，基于当前源码生成 {state['html']}，检查图文与交互后执行 "
            f"python scripts/feature-explainer.py complete --session-id {session}，再交付文件链接。"
            f"{IDEA_INSTRUCTION}"
            f"{DOCUMENT_INSTRUCTION}"
            "只补齐已授权的讲解产物；如确实无法生成或用户已取消，请使用 defer 并如实说明。"
            "这是一次性的补漏检查，不重复执行已通过的业务测试。"
        )}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("require", "complete", "status", "defer", "hook"))
    parser.add_argument("--session-id")
    parser.add_argument("--name")
    parser.add_argument("--stage", help="Corresponding directory directly inside 基础模块后阶段计划")
    parser.add_argument("--reason")
    args = parser.parse_args()
    if args.action == "hook":
        print(json.dumps(hook(json.load(sys.stdin)), ensure_ascii=False))
        return
    if Path.cwd().resolve() != ROOT:
        parser.error("Run from the repository root")
    session = session_id(args.session_id)
    if args.action == "require":
        if not args.name:
            parser.error("require needs --name")
        result = require_report(ROOT, session, args.name, args.stage)
    elif args.action == "complete":
        result = complete_report(ROOT, session)
    elif args.action == "defer":
        if not args.reason or not args.reason.strip():
            parser.error("defer needs the actual --reason")
        result = load_state(ROOT, session)
        if not result:
            parser.error("No registered report")
        result.update(status="deferred", error=args.reason)
        save_state(ROOT, session, result)
    else:
        result = load_state(ROOT, session)
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    if hasattr(sys.stdin, "reconfigure"):
        sys.stdin.reconfigure(encoding="utf-8")
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    try:
        main()
    except (ValueError, KeyError, OSError) as error:
        print("Feature explainer: " + str(error), file=sys.stderr)
        sys.exit(1)
