#!/usr/bin/env python3
"""What changed in the script API between two engine jars.

The engine is closed source, but its public API is not a secret: the same signatures ship to script
authors in every script-api release, and a script that calls them is compiled against them. So a
changelog of public signatures tells people what they already hold a copy of - it just tells them
sooner, and says which of it moved.

Only the packages scripts actually import are read, and only public members of public types. Bodies,
private members and everything outside those packages never leave the jar.
"""

import argparse
import json
import re
import subprocess
import sys
import zipfile

try:
    sys.stdout.reconfigure(encoding="utf-8")
except Exception:
    pass
from collections import defaultdict
from pathlib import Path

# The surface a script compiles against. Everything else in the jar is the engine's own business.
API_PACKAGES = (
    "com/projectx/script/",
    "com/projectx/webwalker/",
    "com/projectx/game/interfaces/",
    "com/projectx/game/nxt/entity/",
    "com/projectx/ui/backend/dsl/",
)

# Members that are an implementation detail of how Kotlin compiles, not something anyone calls.
NOISE = re.compile(
    r"\b(access\$|\$\$|lambda\$|<clinit>|component\d+\(|copy\$default\(|"
    r"getDefaultImpls|DefaultImpls|\$annotations\(|\$lambda|synthetic)"
)


def api_classes(jar: Path) -> list[str]:
    with zipfile.ZipFile(jar) as z:
        names = [n for n in z.namelist() if n.endswith(".class")]
    out = []
    for n in names:
        if not any(n.startswith(p) for p in API_PACKAGES):
            continue
        stem = n[:-len(".class")]
        # Anonymous and synthetic classes carry no API.
        if re.search(r"\$\d+$", stem) or stem.endswith("Kt$WhenMappings"):
            continue
        out.append(stem.replace("/", "."))
    return sorted(out)


def signatures(jar: Path, classes: list[str]) -> dict[str, set[str]]:
    """Public signatures per class, via javap. Batched, because javap start-up dwarfs the work."""
    found: dict[str, set[str]] = defaultdict(set)
    for i in range(0, len(classes), 200):
        batch = classes[i:i + 200]
        proc = subprocess.run(
            ["javap", "-public", "-cp", str(jar), *batch],
            capture_output=True, text=True, errors="replace",
        )
        current = None
        for line in proc.stdout.splitlines():
            line = line.strip()
            if not line or line == "}":
                continue
            head = re.match(r"^(?:(public)\s+)?(?:final\s+|abstract\s+|static\s+)*"
                            r"(?:class|interface|enum|record)\s+([\w.$]+)", line)
            if head:
                # javap prints a type it was asked about even when it is not public. Only a public
                # type is something a script can name, so the rest are skipped along with their
                # members - that is what keeps a private helper like WebLinks.RawLink out of this.
                current = head.group(2) if head.group(1) else None
                continue
            if current and line.endswith(";") and not NOISE.search(line):
                found[current].add(line.rstrip(";").strip())
    return found


def member_name(sig: str) -> str:
    m = re.search(r"([\w$]+)\s*\(", sig)
    return m.group(1) if m else sig.split()[-1]


def member_params(sig: str) -> str:
    m = re.search(r"\((.*)\)", sig)
    return m.group(1) if m else ""


def short(cls: str) -> str:
    return cls.rsplit(".", 1)[-1].replace("$", ".")


def describe(old: dict[str, set[str]], new: dict[str, set[str]]) -> dict[str, list[str]]:
    added_types = sorted(set(new) - set(old))
    removed_types = sorted(set(old) - set(new))
    out = defaultdict(list)

    for t in added_types:
        out["added"].append(f"`{short(t)}` (new type)")
    for t in removed_types:
        out["removed"].append(f"`{short(t)}` (type removed)")

    for cls in sorted(set(old) & set(new)):
        gone = old[cls] - new[cls]
        fresh = new[cls] - old[cls]
        if not gone and not fresh:
            continue
        matched_gone, matched_fresh = set(), set()

        # A rename keeps the shape and changes the name; that reads better than an add and a remove.
        for g in sorted(gone):
            for f in sorted(fresh):
                if f in matched_fresh:
                    continue
                if member_name(g) != member_name(f) and member_params(g) == member_params(f) \
                        and g.split()[:-1] == f.split()[:-1]:
                    out["renamed"].append(
                        f"`{short(cls)}.{member_name(g)}` is now `{member_name(f)}`")
                    matched_gone.add(g); matched_fresh.add(f)
                    break

        # Same name, different parameters: the call has to change, so say so as one line.
        for g in sorted(gone - matched_gone):
            for f in sorted(fresh - matched_fresh):
                if member_name(g) == member_name(f):
                    out["changed"].append(
                        f"`{short(cls)}.{member_name(g)}({member_params(g)})` is now "
                        f"`({member_params(f)})`")
                    matched_gone.add(g); matched_fresh.add(f)
                    break

        for f in sorted(fresh - matched_fresh):
            out["added"].append(f"`{short(cls)}.{member_name(f)}`")
        for g in sorted(gone - matched_gone):
            out["removed"].append(f"`{short(cls)}.{member_name(g)}`")

    return out


def render(version: str, changes: dict[str, list[str]], limit: int) -> str:
    order = [("removed", "Removed"), ("renamed", "Renamed"),
             ("changed", "Signature changed"), ("added", "Added")]
    if not any(changes.get(k) for k, _ in order):
        return f"**Project X {version}** — no script API changes."

    lines = [f"**Project X {version}** — script API changes"]
    for key, title in order:
        items = changes.get(key) or []
        if not items:
            continue
        lines.append(f"\n**{title}**")
        for item in items[:limit]:
            lines.append(f"• {item}")
        if len(items) > limit:
            lines.append(f"• …and {len(items) - limit} more")
    return "\n".join(lines)


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--old", type=Path, help="previous engine jar; omitted means a first release")
    ap.add_argument("--new", type=Path, required=True)
    ap.add_argument("--version", required=True)
    ap.add_argument("--limit", type=int, default=12, help="most lines per section")
    ap.add_argument("--out", type=Path, help="write the message here as well as to stdout")
    args = ap.parse_args()

    new_sigs = signatures(args.new, api_classes(args.new))
    old_sigs = signatures(args.old, api_classes(args.old)) if args.old and args.old.exists() else {}
    if not old_sigs:
        text = (f"**Project X {args.version}** — script API baseline "
                f"({sum(len(v) for v in new_sigs.values())} public members across "
                f"{len(new_sigs)} types).")
    else:
        text = render(args.version, describe(old_sigs, new_sigs), args.limit)

    print(text)
    if args.out:
        args.out.write_text(text, encoding="utf-8")
    return 0


if __name__ == "__main__":
    sys.exit(main())
