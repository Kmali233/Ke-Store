"""Open project source links in the already-running IntelliJ IDEA via its launcher."""
import argparse
import json
from pathlib import Path, PureWindowsPath
import re
import subprocess
import sys
from urllib.parse import parse_qs, urlencode, urlsplit

ROOT = Path(__file__).resolve().parents[1]
SCHEME = "xiaoke-idea"
CONFIG = Path("output/.feature-explainer/idea-opener.json")
SUFFIXES = {".java", ".sql", ".md", ".xml", ".yml", ".yaml", ".properties", ".py",
            ".json", ".html", ".js", ".ts", ".tsx", ".jsx", ".vue", ".css"}


def parse_idea_url(value, root=ROOT):
    """Validate URI data before constructing a shell-free argument list."""
    url = urlsplit(value)
    if (url.scheme != SCHEME or url.netloc != "open" or url.path not in ("", "/")
            or url.fragment):
        raise ValueError("Invalid project IDEA link")
    params = parse_qs(url.query, keep_blank_values=True, strict_parsing=True)
    if set(params) != {"file", "line"} or any(len(v) != 1 for v in params.values()):
        raise ValueError("IDEA link must contain one file and one line")
    relative = params["file"][0].replace("\\", "/")
    parts = relative.split("/")
    if (not relative or PureWindowsPath(relative).drive or relative.startswith("/")
            or any(p in ("", ".", "..") for p in parts)
            or re.search(r'[<>:"|?*\x00-\x1f]', relative)
            or parts[0].lower() in (".git", ".workbuddy", "output", "outputs")):
        raise ValueError("IDEA link must refer to a project source file")
    path = (root / relative).resolve()
    if not path.is_relative_to(root.resolve()) or not path.is_file() or path.suffix.lower() not in SUFFIXES:
        raise ValueError("IDEA source file is missing, unsupported, or outside this project")
    line = params["line"][0]
    if not re.fullmatch(r"[1-9][0-9]{0,6}", line):
        raise ValueError("IDEA line must be a positive integer")
    number = int(line)
    if number > max(1, len(path.read_text(encoding="utf-8-sig").splitlines())):
        raise ValueError("IDEA line no longer exists; refresh the report source index")
    return path, number


def make_idea_url(relative, line, root=ROOT):
    value = SCHEME + "://open?" + urlencode({"file": relative, "line": line})
    parse_idea_url(value, root)
    return value


def launch_args(value, root=ROOT):
    path, line = parse_idea_url(value, root)
    config = json.loads((root / CONFIG).read_text(encoding="utf-8"))
    executable = Path(config["idea_executable"])
    if executable.name.lower() != "idea64.exe" or not executable.is_file():
        raise ValueError("Configured IDEA launcher is missing; run install again")
    return [str(executable), "--line", str(line), str(path)]


def install(executable, root=ROOT):
    if sys.platform != "win32":
        raise ValueError("This project URL handler is for Windows")
    import winreg
    executable = Path(executable).resolve()
    pythonw = Path(sys.executable).with_name("pythonw.exe")
    if executable.name.lower() != "idea64.exe" or not executable.is_file() or not pythonw.is_file():
        raise ValueError("Provide an existing idea64.exe; pythonw.exe must also be installed")
    script = (root / "scripts/idea_links.py").resolve()
    command = f'"{pythonw}" "{script}" open "%1"'
    key = "Software\\Classes\\" + SCHEME
    try:
        with winreg.OpenKey(winreg.HKEY_CURRENT_USER, key + "\\shell\\open\\command") as existing:
            previous = winreg.QueryValueEx(existing, "")[0]
            if previous != command:
                raise ValueError("This URL scheme already belongs to another handler; it was not overwritten")
    except FileNotFoundError:
        pass
    config = root / CONFIG
    config.parent.mkdir(parents=True, exist_ok=True)
    config.write_text(json.dumps({"idea_executable": str(executable), "scheme": SCHEME},
                                 ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    with winreg.CreateKey(winreg.HKEY_CURRENT_USER, key) as handle:
        winreg.SetValueEx(handle, "", 0, winreg.REG_SZ, "小Ke商城源码 · IntelliJ IDEA")
        winreg.SetValueEx(handle, "URL Protocol", 0, winreg.REG_SZ, "")
    with winreg.CreateKey(winreg.HKEY_CURRENT_USER, key + "\\DefaultIcon") as handle:
        winreg.SetValueEx(handle, "", 0, winreg.REG_SZ, f'"{executable}",0')
    with winreg.CreateKey(winreg.HKEY_CURRENT_USER, key + "\\shell\\open\\command") as handle:
        winreg.SetValueEx(handle, "", 0, winreg.REG_SZ, command)
    return {"scheme": SCHEME, "idea_executable": str(executable), "scope": "current-user"}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="action", required=True)
    setup = sub.add_parser("install")
    setup.add_argument("--idea", required=True)
    url = sub.add_parser("url")
    url.add_argument("--file", required=True)
    url.add_argument("--line", type=int, required=True)
    opener = sub.add_parser("open")
    opener.add_argument("uri")
    opener.add_argument("--dry-run", action="store_true")
    args = parser.parse_args()
    if args.action == "install":
        if Path.cwd().resolve() != ROOT:
            parser.error("Run setup from the repository root")
        print(json.dumps(install(args.idea), ensure_ascii=False))
    elif args.action == "url":
        print(make_idea_url(args.file, args.line))
    else:
        command = launch_args(args.uri)
        if args.dry_run:
            print(json.dumps(command, ensure_ascii=False))
        else:
            subprocess.Popen(command, cwd=ROOT, shell=False,
                             creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0),
                             stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)


if __name__ == "__main__":
    if sys.stdout is not None and hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    try:
        main()
    except (ValueError, KeyError, OSError) as error:
        message = "IDEA source link: " + str(error)
        if sys.stderr is not None:
            print(message, file=sys.stderr)
        else:
            error_path = ROOT / "output/.feature-explainer/idea-open-error.txt"
            error_path.parent.mkdir(parents=True, exist_ok=True)
            error_path.write_text(message, encoding="utf-8")
        sys.exit(1)
