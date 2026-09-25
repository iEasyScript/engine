#!/usr/bin/env python3
"""What changed in the script API between two engine jars, as a Discord announcement.

The engine is closed source, but its public API is not a secret: the same signatures ship to script
authors in every script-api release, and a script that calls them is compiled against them. So a
changelog of public signatures tells people what they already hold a copy of - it just tells them
sooner, and says which of it moved.

Only the packages scripts actually import are read, and only public members of public types. Bodies,
private members and everything outside those packages never leave the jar.

The announcement is two embeds: the release's highlights, written by hand in the tag message, and the
API changes worked out here, grouped by area with anything that breaks a script called out first.
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

# The surface a script compiles against, and what each part is called in the announcement. Everything
# else in the jar is the engine's own business. The first matching prefix wins.
AREAS = (
    ("com/projectx/ui/compose/components/", "Overlay UI"),
    ("com/projectx/ui/compose/theme/", "Overlay UI"),
    ("com/projectx/ui/compose/OverlayText", "Overlay UI"),
    ("com/projectx/ui/Setting", "Overlay UI"),
    ("com/projectx/script/ComposePanel", "Overlay UI"),
    ("com/projectx/ui/backend/dsl/", "Overlay DSL"),
    ("com/projectx/script/", "Scripts"),
    ("com/projectx/webwalker/", "Web walker"),
    ("com/projectx/game/interfaces/", "Interfaces"),
    ("com/projectx/game/nxt/entity/", "Entities"),
)
AREA_ORDER = ("Overlay UI", "Overlay DSL", "Scripts", "Web walker", "Interfaces", "Entities")

# Kotlin `internal` types compile to public classes, so javap cannot tell them from API; they are named
# here instead. Only needed for internal types that live in one of the packages above.
INTERNAL_TYPES = {
    "com.projectx.script.LiveValues",
    "com.projectx.script.api.NecromancyImprovise",
}

# Members that are an implementation detail of how Kotlin and the Compose compiler build a class, not
# something anyone calls.
NOISE = re.compile(
    r"\b(access\$|\$\$|lambda\$|<clinit>|component\d+\(|\$default\(|"
    r"getDefaultImpls|DefaultImpls|DefaultConstructorMarker|\$annotations\(|\$lambda|synthetic)"
    r"|\$stable\b|\sINSTANCE$|\$Companion Companion$|\bgetEntries\(\)|\bvalues\(\)|\bvalueOf\(java\.lang\.String\)"
)

# Types the compilers generate: interface default bodies, `when` tables, Compose's cached lambdas.
GENERATED_TYPE = re.compile(r"\$\d+$|\$WhenMappings$|\$DefaultImpls$|ComposableSingletons\$")

GREEN = 0x3FD48E
AMBER = 0xF2A93B
RED = 0xF0716C


def area_of(stem: str) -> str | None:
    return next((name for prefix, name in AREAS if stem.startswith(prefix)), None)


def api_classes(jar: Path) -> list[str]:
    with zipfile.ZipFile(jar) as z:
        names = [n for n in z.namelist() if n.endswith(".class")]
    out = []
    for n in names:
        stem = n[:-len(".class")]
        if area_of(stem) is None or GENERATED_TYPE.search(stem):
            continue
        name = stem.replace("/", ".")
        if name.split("$")[0] in INTERNAL_TYPES:
            continue
        out.append(name)
    return sorted(out)


def signatures(jar: Path, classes: list[str]) -> dict[str, set[str]]:
    """Public signatures per class, via javap. Batched, because javap start-up dwarfs the work.

    A companion's members are filed under the class that owns it, which is how a script calls them.
    """
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
                current = head.group(2).removesuffix("$Companion") if head.group(1) else None
                if current:
                    found.setdefault(current, set())
                continue
            if not line.endswith(";"):
                continue
            line = line.rstrip(";").strip()
            if current and not NOISE.search(line):
                found[current].add(line)
    return found


def member_name(sig: str) -> str:
    """The name as a script writes it: without the `-abc123` suffix Kotlin adds for inline-class parameters."""
    m = re.search(r"([\w$-]+)\s*\(", sig)
    name = m.group(1) if m else sig.split()[-1]
    return name.split("-")[0]


def member_params(sig: str) -> str:
    m = re.search(r"\((.*)\)", sig)
    return m.group(1) if m else ""


def short(cls: str) -> str:
    return cls.rsplit(".", 1)[-1].replace("$", ".")


def display(cls: str, sig: str) -> str:
    """How a member reads to a script author: `Script.live()`, `Palette.ground`, or `Stat()` for a top-level function."""
    name = member_name(sig)
    params = member_params(sig)
    top_level = cls.endswith("Kt") and " static " in f" {sig} "
    if "(" not in sig:
        target = name
    elif name == short(cls).rsplit(".", 1)[-1]:
        target = f"{name}()"  # a constructor
    elif re.fullmatch(r"(get|is)[A-Z]\w*", name) and not params:
        prop = re.sub(r"^(get|is)", "", name)
        # Compose names its CompositionLocals with a capital (LocalType), so those keep theirs.
        target = prop if "CompositionLocal" in sig.split(name)[0] else prop[0].lower() + prop[1:]
    elif re.fullmatch(r"set[A-Z]\w*", name) and params and "," not in params:
        target = name[3].lower() + name[4:]
    else:
        target = f"{name}()"
    if top_level or target.endswith(f"{short(cls)}()"):
        return f"`{target}`"
    return f"`{short(cls)}.{target}`"


def describe(old: dict[str, set[str]], new: dict[str, set[str]]) -> dict[str, dict[str, list[str]]]:
    """Changes per area, each split into removed, renamed, changed and added."""
    out: dict[str, dict[str, list[str]]] = defaultdict(lambda: defaultdict(list))

    def put(area: str, kind: str, text: str):
        if text not in out[area][kind]:
            out[area][kind].append(text)

    def area(cls: str) -> str:
        return area_of(cls.replace(".", "/")) or "Scripts"

    for t in sorted(set(new) - set(old)):
        if t.endswith("Kt"):
            # A Kotlin file's top-level functions: what a script calls is the functions, not the file.
            for f in sorted(new[t]):
                put(area(t), "added", display(t, f))
        else:
            put(area(t), "added", f"`{short(t)}` (new)")
    for t in sorted(set(old) - set(new)):
        put(area(t), "removed", f"`{short(t)}` (type removed)")

    for cls in sorted(set(old) & set(new)):
        gone = old[cls] - new[cls]
        fresh = new[cls] - old[cls]
        if not gone and not fresh:
            continue
        a = area(cls)
        matched_gone, matched_fresh = set(), set()

        # A rename keeps the shape and changes the name; that reads better than an add and a remove.
        for g in sorted(gone):
            for f in sorted(fresh):
                if f in matched_fresh:
                    continue
                if member_name(g) != member_name(f) and member_params(g) == member_params(f) \
                        and g.split()[:-1] == f.split()[:-1]:
                    put(a, "renamed", f"{display(cls, g)} is now {display(cls, f)}")
                    matched_gone.add(g); matched_fresh.add(f)
                    break

        # Same name, different parameters. An old overload still being there means the new one is only
        # an addition; otherwise the call has to change.
        still_there = {member_name(s) for s in new[cls]}
        for g in sorted(gone - matched_gone):
            for f in sorted(fresh - matched_fresh):
                if member_name(g) == member_name(f):
                    put(a, "changed", f"{display(cls, g)} takes `({simple_params(f)})`")
                    matched_gone.add(g); matched_fresh.add(f)
                    break

        for f in sorted(fresh - matched_fresh):
            put(a, "added", display(cls, f))
        for g in sorted(gone - matched_gone):
            if member_name(g) not in still_there:
                put(a, "removed", display(cls, g))
            else:
                put(a, "changed", f"{display(cls, g)} lost an overload `({simple_params(g)})`")

    return out


def simple_params(params_sig: str) -> str:
    """`java.lang.String, kotlin.jvm.functions.Function0<? extends T>` reads as `String, Function0`."""
    params = member_params(params_sig) if "(" in params_sig else params_sig
    parts = [re.sub(r"<.*>", "", p).strip().rsplit(".", 1)[-1] for p in split_params(params)]
    # The Compose compiler appends the composer and its change flags to every composable.
    while parts and parts[-1] == "int" and "Composer" in parts:
        parts.pop()
    return ", ".join(p for p in parts if p != "Composer")


def split_params(params: str) -> list[str]:
    depth, cur, out = 0, "", []
    for ch in params:
        if ch == "<":
            depth += 1
        elif ch == ">":
            depth -= 1
        if ch == "," and depth == 0:
            out.append(cur); cur = ""
        else:
            cur += ch
    if cur.strip():
        out.append(cur)
    return out


def unwrap(notes: str) -> str:
    """Tag messages are wrapped at 72-80 columns; Discord would keep every break. Join lines within a
    paragraph, but leave list items, headings and code blocks as they are."""
    out, para, fenced = [], [], False

    def flush():
        if para:
            out.append(" ".join(para))
            para.clear()

    for raw in notes.strip().splitlines():
        line = raw.rstrip()
        if line.startswith("```"):
            flush(); out.append(line); fenced = not fenced
            continue
        if fenced:
            out.append(line)
            continue
        stripped = line.strip()
        if not stripped:
            flush(); out.append("")
        elif re.fullmatch(r"\*\*[^*]+\*\*", stripped):
            flush(); out.append(stripped)  # a bold line is a heading
        elif re.match(r"^([-*•]|\d+[.)]|#)\s", stripped):
            flush(); para.append(stripped)
        elif para and raw.startswith((" ", "\t")) and re.match(r"^([-*•]|\d+[.)])\s", para[0]):
            para.append(stripped)  # a list item's continuation
        else:
            if para and re.match(r"^([-*•]|\d+[.)]|#)\s", para[0]):
                flush()
            para.append(stripped)
    flush()
    return re.sub(r"\n{3,}", "\n\n", "\n".join(out)).strip()


def listing(items: list[str], limit: int = 1000, sep: str = " · ") -> str:
    """As many items as fit a Discord field, then how many more there were."""
    text = ""
    for i, item in enumerate(items):
        piece = item if not text else sep + item
        rest = len(items) - i
        if len(text) + len(piece) > limit - len(f"{sep}…and {rest} more"):
            return text + f"{sep}…and {rest} more"
        text += piece
    return text


def announcement(version: str, previous: str | None, notes: str, changes, link: str | None,
                 title: str | None = None) -> dict:
    highlights = {
        "title": title or f"Project X {version}",
        "description": (unwrap(notes) or "A new engine build is out.")[:3800],
        "color": AMBER,
    }
    if link:
        highlights["url"] = link

    count = lambda kind: sum(len(k.get(kind, [])) for k in changes.values())
    added, removed, renamed, changed = count("added"), count("removed"), count("renamed"), count("changed")
    breaking = removed + renamed + changed

    if not previous:
        return {"embeds": [highlights | {"footer": {"text": "Script API baseline"}}]}
    if not (added or breaking):
        return {"embeds": [highlights | {"footer": {"text": f"No script API changes since {previous}"}}]}

    summary = [f"**{added}** added" if added else None,
               f"**{removed}** removed" if removed else None,
               f"**{renamed}** renamed" if renamed else None,
               f"**{changed}** changed" if changed else None]
    lines = [" · ".join(s for s in summary if s)]
    if breaking:
        lines.append("⚠️ Some calls were removed or changed. Scripts that use them need updating and rebuilding.")
    else:
        lines.append("✅ Nothing was removed or changed, so scripts built against the previous API keep working.")

    fields = []
    for kind, title in (("removed", "⚠️ Removed"), ("renamed", "✏️ Renamed"), ("changed", "⚠️ Changed")):
        for area in AREA_ORDER:
            items = changes.get(area, {}).get(kind, [])
            if items:
                fields.append({"name": f"{title} · {area}", "value": listing(items, sep="\n")})
    for area in AREA_ORDER:
        items = changes.get(area, {}).get("added", [])
        if items:
            # New types first: a new type is what a script author goes and reads about.
            items = sorted(items, key=lambda s: (not s.endswith("(new)"), s.lower()))
            fields.append({"name": f"✨ Added · {area}", "value": listing(items)})

    api = {
        "title": "Script API changes",
        "description": "\n".join(lines),
        "color": RED if breaking else GREEN,
        "fields": fields[:25],
        "footer": {"text": f"Public signatures compared with {previous}"},
    }
    embeds = [highlights, api]
    # Discord refuses a message whose embeds total more than 6000 characters; drop fields from the end.
    while embed_size(embeds) > 5900 and api["fields"]:
        api["fields"].pop()
    return {"embeds": embeds}


def embed_size(embeds: list[dict]) -> int:
    total = 0
    for e in embeds:
        total += len(e.get("title", "")) + len(e.get("description", "")) + len(e.get("footer", {}).get("text", ""))
        total += sum(len(f["name"]) + len(f["value"]) for f in e.get("fields", []))
    return total


def markdown(payload: dict) -> str:
    """The same announcement as plain Markdown, for the workflow's artifact and the log."""
    out = []
    for e in payload["embeds"]:
        out.append(f"## {e['title']}")
        out.append(e.get("description", ""))
        for f in e.get("fields", []):
            out.append(f"\n**{f['name']}**\n{f['value']}")
        if "footer" in e:
            out.append(f"\n_{e['footer']['text']}_")
        out.append("")
    return "\n".join(out)


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--old", type=Path, help="previous engine jar; omitted means a first release")
    ap.add_argument("--old-version", help="the previous release's version, for the footer")
    ap.add_argument("--new", type=Path, help="this release's engine jar; omitted posts the highlights alone")
    ap.add_argument("--version", help="the engine release; omitted posts the highlights alone")
    ap.add_argument("--title", help="the announcement's title, instead of Project X <version>")
    ap.add_argument("--notes", type=Path, help="the release's highlights, usually the tag message")
    ap.add_argument("--link", help="where the release can be downloaded")
    ap.add_argument("--out", type=Path, help="write the announcement as Markdown here")
    ap.add_argument("--discord", type=Path, help="write the Discord webhook payload here")
    args = ap.parse_args()

    notes = args.notes.read_text(encoding="utf-8") if args.notes and args.notes.exists() else ""
    if not (args.new and args.version):
        # An update without an engine release - a script, the launcher, a script-api doc - has no API
        # to compare, so it is announced with its highlights alone, in the same style.
        if not (args.title and notes.strip()):
            ap.error("without --new and --version, both --title and --notes are needed")
        payload = {"embeds": [{"title": args.title, "description": unwrap(notes)[:3800], "color": AMBER}]}
        if args.link:
            payload["embeds"][0]["url"] = args.link
    else:
        new_sigs = signatures(args.new, api_classes(args.new))
        has_old = bool(args.old and args.old.exists())
        old_sigs = signatures(args.old, api_classes(args.old)) if has_old else {}
        previous = (args.old_version or "the previous release") if has_old else None
        payload = announcement(args.version, previous, notes, describe(old_sigs, new_sigs), args.link, args.title)
    payload["allowed_mentions"] = {"parse": []}

    text = markdown(payload)
    print(text)
    if args.out:
        args.out.write_text(text, encoding="utf-8")
    if args.discord:
        args.discord.write_text(json.dumps(payload, ensure_ascii=False), encoding="utf-8")
    return 0


if __name__ == "__main__":
    sys.exit(main())
