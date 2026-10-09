#!/usr/bin/env python3
"""Refreshes the web walker's links.json from navpathService's worldReachableTiles.db.

    python3 .github/scripts/navpath_links.py <worldReachableTiles.db> [<links.json>]

The database comes from https://github.com/ShaggyHW/navpathService (used with its author's permission)
and is only ever opened read-only. Its nodes are chained (a node may name the next one), and the chains
are flattened the way navpathService flattens them: a node that nothing chains into starts a link, the
link's first node decides its kind, every later node becomes one of its "steps", and it lands where the
last node with a destination says. Costs are summed and converted from milliseconds to walked tiles.

Links that start somewhere in the world - an object, an NPC, a door, an item used on an object - carry
the area to stand in. Global ones - a lodestone, an item, a spell or interface button, the Passage of
the Abyss - can be used from anywhere and carry "global": true. A door also gets the way back through it
alone, as navpathService adds it. Fairy rings are listed on their own; every ring reaches every other.

Links we measured ourselves carry "measured": true and are always kept. Where upstream has the same
way through, ours takes upstream's requirements so it is not tried by an account that lacks the
unlock. Both copies stay, unless ours answers a prompt (a choice or steps): two different ways to
answer the same question would compete, and ours has been driven in-game, so upstream's is dropped.

A link whose area is inverted or wider than any real crossing is a typo upstream (one squeeze-through spans
646 tiles, which let the planner step from Yanille to Karamja), so it is skipped.

Upstream switches a node off with an unknown-key requirement ("Unkown", "UNKOWN") the engine cannot
evaluate, and the engine treats what it cannot evaluate as met, so those are left out.
"""
import datetime
import json
import pathlib
import sqlite3
import sys

TILE_MS = 600
DOOR_SEARCH_RADIUS = 4
NPC_SEARCH_RADIUS = 20
SWITCHED_OFF_KEYS = {"unkown", "unknown"}
SAME_WAY_SLACK = 3
MAX_AREA_SPAN = 32
DEFAULT_LINKS = pathlib.Path(__file__).resolve().parents[2] / "client-plugin-engine/src/main/resources/webwalker/links.json"

TABLES = {
    "door": "teleports_door_nodes",
    "object": "teleports_object_nodes",
    "npc": "teleports_npc_nodes",
    "useon": "teleports_useOn_nodes",
    "item": "teleports_item_nodes",
    "ifslot": "teleports_ifslot_nodes",
    "lodestone": "teleports_lodestone_nodes",
    "poa_item": "teleports_POA_nodes",
}
LOCAL_STARTS = ("object", "npc", "useon", "door")
GLOBAL_STARTS = ("lodestone", "item", "ifslot", "poa_item")
FIELD_ORDER = ["kind", "action", "objectId", "searchRadius", "costTiles", "global",
               "npcId", "npcName", "itemId", "itemName", "lodestone", "menu",
               "fromMinX", "fromMaxX", "fromMinY", "fromMaxY", "fromPlane",
               "toMinX", "toMaxX", "toMinY", "toMaxY", "toPlane", "requirements", "steps"]


def open_ro(path):
    db = sqlite3.connect(pathlib.Path(path).resolve().as_uri() + "?mode=ro", uri=True)
    db.row_factory = sqlite3.Row
    return db


def cost_tiles(ms):
    return max(1, round((ms or TILE_MS) / TILE_MS))


def requirement_ids(text):
    if text is None or str(text).strip() == "":
        return None
    return [int(p) for p in str(text).replace(",", ";").split(";") if p.strip()]


def node_kind(text):
    if text is None:
        return None
    text = text.strip().lower()
    return {"use_on": "useon", "poa": "poa_item"}.get(text, text)


def area(prefix, min_x, max_x, min_y, max_y, plane):
    return {f"{prefix}MinX": min_x, f"{prefix}MaxX": max_x, f"{prefix}MinY": min_y,
            f"{prefix}MaxY": max_y, f"{prefix}Plane": plane}


def has(row, column):
    return column in row.keys() and row[column] is not None


def destination(kind, row):
    if kind == "lodestone":
        return area("to", row["dest_x"], row["dest_x"], row["dest_y"], row["dest_y"], row["dest_plane"])
    if kind == "door":
        return area("to", row["tile_inside_x"], row["tile_inside_x"], row["tile_inside_y"], row["tile_inside_y"],
                    row["tile_inside_plane"])
    if not has(row, "dest_min_x"):
        return None
    max_x = row["dest_max_x"] if has(row, "dest_max_x") else row["dest_min_x"]
    max_y = row["dest_max_y"] if has(row, "dest_max_y") else row["dest_min_y"]
    return area("to", int(row["dest_min_x"]), int(max_x), int(row["dest_min_y"]), int(max_y), int(row["dest_plane"]))


def origin(kind, row):
    if kind == "door":
        x, y, p = row["tile_outside_x"], row["tile_outside_y"], row["tile_outside_plane"]
        return None if x is None else area("from", x, x, y, y, p)
    if not has(row, "orig_min_x"):
        return None
    max_x = row["orig_max_x"] if has(row, "orig_max_x") else row["orig_min_x"]
    max_y = row["orig_max_y"] if has(row, "orig_max_y") else row["orig_min_y"]
    return area("from", row["orig_min_x"], max_x, row["orig_min_y"], max_y, row["orig_plane"])


def first_step(kind, row):
    """The fields that say how a link starts, keyed by what its first node is."""
    if kind == "object":
        return {"kind": "OBJECT", "action": row["action"], "objectId": row["object_id"], "searchRadius": row["search_radius"]}
    if kind == "door":
        return {"kind": "DOOR", "action": row["open_action"], "objectId": row["real_id_closed"],
                "searchRadius": DOOR_SEARCH_RADIUS}
    if kind == "npc":
        link = {"kind": "NPC", "action": row["action"], "objectId": -1,
                "searchRadius": row["search_radius"] or NPC_SEARCH_RADIUS}
        if row["match_type"] == "name" or row["npc_id"] is None:
            link["npcName"] = row["npc_name"]
        else:
            link["npcId"] = row["npc_id"]
        return link
    if kind == "useon":
        return {"kind": "USE_ON", "action": "Use", "objectId": row["object_id"], "itemId": row["item_id"],
                "searchRadius": NPC_SEARCH_RADIUS}
    if kind == "item":
        link = {"kind": "ITEM", "action": row["action"], "objectId": -1, "searchRadius": 0}
        if row["match_type"] == "name" or row["item_id"] is None:
            link["itemName"] = row["name"]
        else:
            link["itemId"] = row["item_id"]
        return link
    if kind == "ifslot":
        return {"kind": "INTERFACE", "action": "", "objectId": -1, "searchRadius": 0,
                "steps": [[row["interface_id"], row["component_id"], row["slot_id"], row["click_id"]]]}
    if kind == "lodestone":
        return {"kind": "LODESTONE", "action": "Teleport", "objectId": -1, "searchRadius": 0, "lodestone": row["lodestone"]}
    if kind == "poa_item":
        return {"kind": "POA", "action": row["action"], "objectId": -1, "searchRadius": 0, "itemId": row["item_id"],
                "menu": [m for m in (row["action2"], row["action3"]) if m]}
    return None


def follow_up(kind, row):
    """A later node in a chain, as one of the link's steps."""
    if kind == "ifslot":
        return [row["interface_id"], row["component_id"], row["slot_id"], row["click_id"]]
    if kind == "object":
        return {"objectId": row["object_id"], "action": row["action"], "searchRadius": row["search_radius"]}
    return None


def next_of(row):
    if not has(row, "next_node_type") or not has(row, "next_node_id"):
        return None, None
    return node_kind(row["next_node_type"]), row["next_node_id"]


def flatten(db, kind, node_id):
    """One chain as a link, or None when it loops, breaks or never says where it lands."""
    seen, link, to, cost, requirements = set(), None, None, 0, []
    while kind:
        if (kind, node_id) in seen or kind not in TABLES:
            return None
        seen.add((kind, node_id))
        row = db.execute(f'select * from "{TABLES[kind]}" where id=?', (node_id,)).fetchone()
        if row is None:
            return None
        if link is None:
            link = first_step(kind, row)
        else:
            step = follow_up(kind, row)
            if step is None:
                return None
            link.setdefault("steps", []).append(step)
        if link is None:
            return None
        cost += row["cost"] or 0
        requirements += requirement_ids(row["requirements"]) or []
        to = destination(kind, row) or to
        kind, node_id = next_of(row)
    if to is None:
        return None
    link.update(to)
    link["costTiles"] = cost_tiles(cost)
    link["requirements"] = sorted(set(requirements)) or None
    return link


def sane(link):
    for end in ("from", "to") if "fromMinX" in link else ("to",):
        width = link[f"{end}MaxX"] - link[f"{end}MinX"]
        height = link[f"{end}MaxY"] - link[f"{end}MinY"]
        if not (0 <= width <= MAX_AREA_SPAN and 0 <= height <= MAX_AREA_SPAN):
            return False
    return True


def ordered(link):
    return {k: link[k] for k in FIELD_ORDER if k in link} | {k: v for k, v in link.items() if k not in FIELD_ORDER}


def convert(db):
    requirements = {
        str(r["id"]): {"about": r["metaInfo"], "key": r["key"], "value": r["value"], "comparison": r["comparison"]}
        for r in db.execute("select * from teleports_requirements order by id")
    }
    incoming = set()
    for table in TABLES.values():
        columns = [c[1] for c in db.execute(f'pragma table_info("{table}")')]
        if "next_node_type" in columns:
            for r in db.execute(f'select next_node_type, next_node_id from "{table}" where next_node_type is not null'):
                incoming.add((node_kind(r[0]), r[1]))

    links, dropped = [], 0
    for kind in LOCAL_STARTS:
        for row in db.execute(f'select * from "{TABLES[kind]}" order by id'):
            if (kind, row["id"]) in incoming:
                continue
            start = origin(kind, row)
            link = flatten(db, kind, row["id"])
            if start is None or link is None:
                dropped += 1
                continue
            link.update(start)
            links.append(link)
            if kind == "door" and row["tile_inside_x"] is not None:
                back = first_step("door", row)
                back.update(area("from", row["tile_inside_x"], row["tile_inside_x"], row["tile_inside_y"],
                                 row["tile_inside_y"], row["tile_inside_plane"]))
                back.update({key.replace("from", "to"): value for key, value in start.items()})
                back["costTiles"] = cost_tiles(row["cost"])
                back["requirements"] = requirement_ids(row["requirements"])
                links.append(back)
    for kind in GLOBAL_STARTS:
        for row in db.execute(f'select * from "{TABLES[kind]}" order by id'):
            if (kind, row["id"]) in incoming:
                continue
            link = flatten(db, kind, row["id"])
            if link is None:
                dropped += 1
                continue
            link["global"] = True
            links.append(link)

    rings = [{"objectId": row["object_id"], "x": row["x"], "y": row["y"], "plane": row["plane"],
              "code": (row["code"] or "").lower(), "action": row["action"] or "Configure",
              "costTiles": cost_tiles(row["cost"]), "requirements": requirement_ids(row["requirements"])}
             for row in db.execute("select * from teleports_fairy_rings_nodes order by id")]

    usable, seen = [], set()
    for link in links:
        identity = json.dumps(link, sort_keys=True)
        if sane(link) and identity not in seen:
            seen.add(identity)
            usable.append(ordered(link))
    return requirements, usable, rings, dropped + len(links) - len(usable)


def inside(link, prefix, other, other_prefix):
    x, y = other[f"{other_prefix}MinX"], other[f"{other_prefix}MinY"]
    return (link[f"{prefix}Plane"] == other[f"{other_prefix}Plane"]
            and link[f"{prefix}MinX"] - SAME_WAY_SLACK <= x <= link[f"{prefix}MaxX"] + SAME_WAY_SLACK
            and link[f"{prefix}MinY"] - SAME_WAY_SLACK <= y <= link[f"{prefix}MaxY"] + SAME_WAY_SLACK)


def same_way(measured, upstream):
    return ("fromMinX" in upstream and measured["objectId"] == upstream["objectId"]
            and measured["action"] == upstream["action"]
            and inside(measured, "from", upstream, "from") and inside(measured, "to", upstream, "to"))


def main():
    db_path = sys.argv[1]
    links_path = pathlib.Path(sys.argv[2]) if len(sys.argv) > 2 else DEFAULT_LINKS
    current = json.loads(links_path.read_text(encoding="utf-8"))
    measured = [link for link in current["links"] if link.get("measured")]

    requirements, upstream, rings, dropped = convert(open_ro(db_path))
    switched_off = {int(k) for k, r in requirements.items() if r["key"].strip().lower() in SWITCHED_OFF_KEYS}
    usable = [link for link in upstream if not switched_off.intersection(link["requirements"] or [])]
    rings = [ring for ring in rings if not switched_off.intersection(ring["requirements"] or [])]

    shared, kept = 0, []
    for link in usable:
        twins = [m for m in measured if same_way(m, link)]
        for twin in twins:
            if link["requirements"]:
                twin["requirements"] = sorted(set(twin["requirements"] or []) | set(link["requirements"]))
        shared += bool(twins)
        if not any(twin.get("choice") or twin.get("steps") for twin in twins):
            kept.append(link)

    document = {
        "source": "Converted from ShaggyHW/navpathService worldReachableTiles.db, used with the author's permission.",
        "upstream": "https://github.com/ShaggyHW/navpathService",
        "generated": datetime.date.today().isoformat(),
        "costUnit": "costTiles is in walked tiles; upstream costs are milliseconds at 600 ms per tile.",
        "requirements": requirements,
        "links": kept + measured,
        "fairyRings": rings,
    }
    links_path.write_text(json.dumps(document, indent=1, ensure_ascii=False), encoding="utf-8", newline="\n")

    kinds = {}
    for link in document["links"]:
        kinds[link["kind"]] = kinds.get(link["kind"], 0) + 1
    before = {json.dumps({k: v for k, v in l.items() if k != "measured"}, sort_keys=True) for l in current["links"]}
    after = {json.dumps({k: v for k, v in l.items() if k != "measured"}, sort_keys=True) for l in document["links"]}
    print(f"upstream: {len(upstream)} links ({dropped} incomplete or malformed skipped), "
          f"{len(upstream) - len(usable)} switched off upstream, {shared} also measured here, "
          f"{len(usable) - len(kept)} replaced by ours")
    print("by kind:", ", ".join(f"{k} {v}" for k, v in sorted(kinds.items())), f"| fairy rings {len(rings)}")
    print(f"links.json: {len(current['links'])} -> {len(document['links'])} "
          f"({len(after - before)} new or changed, {len(before - after)} gone), {len(measured)} measured kept")


if __name__ == "__main__":
    main()
