# RS3ProtFinder.py — self-anchoring ServerProt/ClientProt + version finder
#
# Run from Ghidra's Script Manager (Jython 2.7) against the CURRENT program (an
# rs2client build, e.g. rs2client.948-5). Recreates the old RS3PacketFinder.java
# but with ZERO hardcoded addresses: every anchor (version, RegisterAll, the
# entry ctor, the handler binders, the CS2 dispatch table) is rediscovered from
# the binary's own structure on each run, so it survives a revision bump that
# shuffles opcodes, adds packets, and relinks .text.
#
# It answers the two questions the user asked of any new binary:
#   1. WHICH BUILD  — printed as "NXT vMMM-S" from the RS2Engine-MMM-NXT-S string.
#   2. ALL PROTS    — every ServerProt, ClientProt, AND the zone-update sub-opcode
#                     table: opcode, size, handler, entry address, and packet name,
#                     with newly-inserted packets flagged.
#
# NAMING AUTHORITY: re-resources/prot-names.json. Names there are either attested
# outside the binaries or INVENTED (listed under assigned_names) -- the binaries
# themselves carry no prot names. Never treat an assigned name as official.
# (override RS3PROT_NAMES). That file holds the build-agnostic name vocabulary plus one
# opcode->name map per build it has been derived for; a map is only ever applied to the build
# it is filed under, because a major revision re-scrambles every opcode. Where a map exists for
# the detected build those names WIN (src=KNOWN) over the cross-version carry; otherwise the
# scanner falls back to embedded-string names / oracle carry / UNKNOWN_<op> as before.
#
# Outputs (console + <program_dir>/):
#   prot_tables_dump.csv   direction,opcode,size,entry_addr,handler_addr,handler_name,handler_source,name,name_src
#   prot_names_<ver>.json  per-direction list in BINARY-ADDRESS (declaration) order — feed as the
#                          oracle for the NEXT build (see NAMING below).
#
# ----------------------------------------------------------------------------
# HOW TO RUN
# ----------------------------------------------------------------------------
# GUI: Script Manager -> run against the open rs2client program (PyGhidra on).
#
# Headless (PROVEN on 948-5; Ghidra 12.x uses PyGhidra, so a plain analyzeHeadless
# can't run .py — launch AnalyzeHeadless THROUGH pyghidra). One-time venv:
#   PYBIN=python3.11   # any 3.10-3.13 (matches a bundled jpype wheel)
#   $PYBIN -m venv /tmp/pgvenv
#   /tmp/pgvenv/bin/pip install --no-index \
#       --find-links "$GHIDRA/Ghidra/Features/PyGhidra/pypkg/dist" pyghidra
# Then (the program must NOT be open in the GUI — Ghidra takes an exclusive
# PROJECT lock; run against a copy of the .rep, or close the GUI first):
#   RS3PROT_ORACLE=re-resources/docs/net/prot_names_<prev>.json \
#   RS3PROT_OUTDIR=/some/out \
#   /tmp/pgvenv/bin/python -m pyghidra.ghidra_launch --install-dir "$GHIDRA" \
#       ghidra.app.util.headless.AnalyzeHeadless <projDir> <projName> \
#       -process rs2client.<ver> -noanalysis -readOnly \
#       -scriptPath ghidra_scripts -postScript RS3ProtFinder.py
#
# ----------------------------------------------------------------------------
# HOW IT WORKS  (read before trusting the output)
# ----------------------------------------------------------------------------
# The prot tables are built at static-init time by one or more RegisterAll
# functions that call a tiny entry-ctor once per packet:
#     ServerProt::InitEntry(&entry, opcode, size)   # ~218x in 948
#     ClientProt(&entry, opcode, size)              # ~130x in 948
# The &entry pointers are consecutive objects in .data; opcode/size are the
# trailing scalar immediates. Two facts make this mechanically findable with no
# names and no fixed addresses:
#
#  (a) The ctor is the ONE function in the program that a SINGLE caller invokes
#      dozens-to-hundreds of times. A linear census of every CALL finds it as
#      "callee whose calls are dominated by one caller, count in [MIN..400]".
#      That dominant caller IS a RegisterAll. (Robust whether the two directions
#      share one RegisterAll, as in 947-3, or split into two, as in 948.)
#
#  (b) Within a RegisterAll we read each ctor call's args by destination
#      register (SysV: arg0=RDI=&entry, arg1=ESI=opcode, arg2=EDX=size), so the
#      opcode/size mapping does not depend on instruction ordering. The opcode
#      column of a real prot table is a dense PERMUTATION of 0..N-1 — that gate
#      rejects non-prot "called-a-lot" helpers and auto-corrects an op/size swap.
#
# Entries are then grouped into tables by contiguous .data cluster (one cluster
# per direction; stride is derived, not assumed). SERVER vs CLIENT is decided
# structurally:
#   SERVER entries receive a bound handler — a code pointer WRITTEN into
#     entry+HANDLER_OFF by a separate BindHandlers function. We find the write
#     offset automatically (the offset that is a code-pointer store for most
#     entries) and follow the store back to its LEA to get the handler.
#   CLIENT entries are SENT, not dispatched: no handler-store layer. We resolve
#     each entry's emitter as its one non-RegisterAll referrer; entries reached
#     only through a .data dispatch table (the CS2 IF_BUTTON / OpTarget family)
#     are tagged CS2_TABLE and chased to the table's reader fn.
#
# ----------------------------------------------------------------------------
# NAMING  (cross-version, "addresses move, declaration order is preserved")
# ----------------------------------------------------------------------------
# Opcodes are reshuffled every revision, but the order packets are registered in
# (== the .data layout order of their entry objects) is stable, and per-packet
# SIZE is stable. So names are carried by aligning this build's entries, sorted
# by entry address, against the previous build's prot_names_<prev>.json, using a
# size-sequence diff to absorb inserted/removed packets. Unmatched new entries
# are flagged name_src=NEW (a packet that did not exist last build). If the entry
# struct embeds a name-string pointer (older unstripped builds, and the beta
# reference librs2client.so), those literal names are read directly and win.
#
# Bootstrap: with no oracle, sizes/handlers/opcodes are still 100% extracted;
# names come up UNKNOWN_<op>. Generate the first oracle from a build you have
# names for (e.g. by running this against librs2client.so, which is unstripped),
# then carry forward.
#
# ----------------------------------------------------------------------------
# CONFIG
# ----------------------------------------------------------------------------
import os
import re
import json

from ghidra.program.model.symbol import RefType
from ghidra.program.model.lang import OperandType

# Each setting takes an env override first (so a headless `analyzeHeadless
# -postScript RS3ProtFinder.py` run is configurable without editing this file),
# then the literal default.

# Path to the previous build's prot_names_<ver>.json (carry names by decl order).
# None = no carry (names come out UNKNOWN_<op> unless embedded in the binary).
# A committed bootstrap oracle ships at re-resources/docs/948-5/net/prot_names_948-5.json.
# Override: RS3PROT_ORACLE=/abs/path/prot_names_<prev>.json
NAME_ORACLE_IN = os.environ.get("RS3PROT_ORACLE") or None

# Opt-in: rename each resolved handler to jag::PacketHandlers::<PacketName> in
# the Ghidra DB (mirrors the old RS3PacketFinder). Off by default — dumping does
# not mutate the program. Override: RS3PROT_RENAME=1
RENAME_HANDLERS = os.environ.get("RS3PROT_RENAME", "") == "1"

# Where prot_tables_dump.csv / prot_names_<ver>.json are written. Default is the
# program's directory. Override: RS3PROT_OUTDIR=/abs/dir
OUT_DIR = os.environ.get("RS3PROT_OUTDIR") or None

WRITE_FILES = True

# A real prot table has at least this many entries. Filters "called-a-lot"
# helpers (refcount/string ctors) that lack a dominant single caller anyway.
MIN_TABLE_ENTRIES = 40
MAX_TABLE_ENTRIES = 400
# Fraction of a ctor's calls that must come from its top caller to be a RegisterAll.
DOMINANCE = 0.6
# Candidate handler-field offsets probed when auto-deriving HANDLER_OFF (SERVER).
HANDLER_OFF_CANDIDATES = [0x28, 0x20, 0x30, 0x18, 0x38, 0x10]
# The zone sub-opcode table (g_zoneSubProtVector) is too small for the RegisterAll
# census floor; this is the secondary-scan floor used to locate it (see find_small_tables).
MIN_ZONE_ENTRIES = 12

VERSION_RE = re.compile(r"RS2Engine-(\d+)-NXT-(\d+)")

# ----------------------------------------------------------------------------
# AUTHORITATIVE NAME TABLES  (source-of-truth override, by build version)
# ----------------------------------------------------------------------------
# When the scanned binary's detected version matches a key below, these opcode->name
# maps OVERRIDE the carry/embedded naming (src "KNOWN"). They are transcribed verbatim
# from a full jag::ServerProt / zone-sub-opcode registration dump of that exact client
# build, so they are the highest authority. This makes the scanner name a fresh
# (stripped) 948-5 binary correctly with ZERO dependence on an external oracle, and it
# seeds a correct cross-version carry oracle for the next build.
#
# CLIENT names are intentionally absent: the 948-5 dump did not recover official
# jag::ClientProt enum names (only opcodes+sizes), so CLIENT naming still flows through
# the oracle/carry path.
#
# Official Jagex prot names live in a build-agnostic data file, not in a literal here: the name
# vocabulary is stable across revisions while the opcode numbers are re-scrambled every major, so
# each opcode->name map is filed under the build it came from and is invalid for any other build.
# Override: RS3PROT_NAMES=/abs/path/prot-names.json
OFFICIAL_NAMES_FILE = os.environ.get("RS3PROT_NAMES") or os.path.join(
    os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "re-resources", "prot-names.json")


def _load_official_names():
    try:
        f = open(OFFICIAL_NAMES_FILE, "r")
        d = json.load(f)
        f.close()
    except Exception as e:
        print("[!] official name file unavailable (%s): %s" % (OFFICIAL_NAMES_FILE, e))
        return {}, {}, {}, []
    names_by_ver, zone_by_ver, zsizes_by_ver = {}, {}, {}
    for ver, tables in (d.get("per_build_opcodes") or {}).items():
        for direction in ("SERVER", "CLIENT"):
            if tables.get(direction):
                names_by_ver.setdefault(ver, {})[direction] = dict(
                    (int(k), v) for k, v in tables[direction].items())
        if tables.get("ZONE"):
            zone_by_ver[ver] = dict((int(k), v) for k, v in tables["ZONE"].items())
        if tables.get("ZONE_SIZES"):
            zsizes_by_ver[ver] = list(tables["ZONE_SIZES"])
    return names_by_ver, zone_by_ver, zsizes_by_ver, list(d.get("official_names") or [])


KNOWN_NAMES_BY_VER, KNOWN_ZONE_BY_VER, KNOWN_ZONE_SIZES_BY_VER, OFFICIAL_NAMES = _load_official_names()

# SysV AMD64 integer arg registers and their 32-bit aliases.
ARG_REGS = {
    0: ("RDI", "EDI"),
    1: ("RSI", "ESI"),
    2: ("RDX", "EDX"),
    3: ("RCX", "ECX"),
}

# ----------------------------------------------------------------------------
fp = currentProgram.getFunctionManager()
listing = currentProgram.getListing()
af = currentProgram.getAddressFactory()
st = currentProgram.getSymbolTable()
refmgr = currentProgram.getReferenceManager()
mem = currentProgram.getMemory()


def addr(x):
    return af.getDefaultAddressSpace().getAddress(x)


def sym_name(a):
    if a is None:
        return None
    s = st.getPrimarySymbol(a)
    if s is not None:
        return s.getName(True)
    f = fp.getFunctionContaining(a)
    if f is not None and f.getEntryPoint().equals(a):
        return f.getName(True)
    return "0x%x" % a.getOffset()


def normalize_size(raw):
    v = raw & 0xffffffff
    if v == 0xffffffff:
        return -1
    if v == 0xfffffffe:
        return -2
    return v


def call_target(ins):
    for r in ins.getReferencesFrom():
        if r.getReferenceType().isCall():
            return r.getToAddress()
    return None


def resolve_thunk(a):
    fn = fp.getFunctionContaining(a)
    if fn is None:
        return a
    it = listing.getInstructions(fn.getBody(), True)
    first = None
    count = 0
    for ins in it:
        if count == 0:
            first = ins
        count += 1
        if count > 1:
            return fn.getEntryPoint()
    if first is not None and first.getMnemonicString() == "JMP":
        for r in first.getReferencesFrom():
            ta = r.getToAddress()
            if ta is not None and ta.isMemoryAddress():
                return ta
    return fn.getEntryPoint()


# ---------------------------------------------------------------------------
# 1. VERSION
# ---------------------------------------------------------------------------
def detect_version():
    from ghidra.program.flatapi import FlatProgramAPI
    api = FlatProgramAPI(currentProgram)
    start = None
    for _ in range(64):
        hit = api.findBytes(start, "RS2Engine-")
        if hit is None:
            break
        s = read_cstring(hit, 64)
        if s:
            m = VERSION_RE.search(s)
            if m:
                return "%s-%s" % (m.group(1), m.group(2)), m.group(1)
        start = hit.add(1)
    return None, None


# ---------------------------------------------------------------------------
# 2. DISCOVER REGISTERALL FUNCTIONS  (call census, no anchors)
# ---------------------------------------------------------------------------
def census_calls():
    """callee_off -> {caller_off: count} over every direct CALL in the program."""
    census = {}
    for ins in listing.getInstructions(True):
        if ins.getMnemonicString() != "CALL":
            continue
        tgt = call_target(ins)
        if tgt is None:
            continue
        f = fp.getFunctionContaining(ins.getAddress())
        if f is None:
            continue
        d = census.setdefault(tgt.getOffset(), {})
        c = f.getEntryPoint().getOffset()
        d[c] = d.get(c, 0) + 1
    return census


def find_registerall_pairs(census):
    """(caller_off, ctor_off, count) for callees a single caller invokes en masse."""
    pairs = []
    for ctor_off, callers in census.items():
        best_caller = None
        best = 0
        total = 0
        for c, n in callers.items():
            total += n
            if n > best:
                best = n
                best_caller = c
        if best_caller is None:
            continue
        if MIN_TABLE_ENTRIES <= best <= MAX_TABLE_ENTRIES and best >= DOMINANCE * total:
            pairs.append((best_caller, ctor_off, best))
    return pairs


def find_small_tables(census, exclude_callers):
    """Like find_registerall_pairs but for SUB-tables below the main RegisterAll floor.

    The zone-update sub-opcode table (g_zoneSubProtVector) has only ~18 entries, so the
    MIN_TABLE_ENTRIES gate rejects it. This scans for a single caller that invokes a ctor
    MIN_ZONE_ENTRIES..MIN_TABLE_ENTRIES-1 times with single-caller dominance, excluding the
    already-found SERVER/CLIENT RegisterAll callers."""
    pairs = []
    for ctor_off, callers in census.items():
        best_caller = None
        best = 0
        total = 0
        for c, n in callers.items():
            total += n
            if n > best:
                best = n
                best_caller = c
        if best_caller is None or best_caller in exclude_callers:
            continue
        if MIN_ZONE_ENTRIES <= best < MIN_TABLE_ENTRIES and best >= DOMINANCE * total:
            pairs.append((best_caller, ctor_off, best))
    return pairs


def _mem_ref(ins):
    for i in range(ins.getNumOperands()):
        for r in ins.getOperandReferences(i):
            ta = r.getToAddress()
            if ta is not None and ta.isMemoryAddress():
                return ta
    return None


def extract_entries(caller_off, ctor_off):
    """Walk RegisterAll, reading (entry,opcode,size) per ctor call by arg register.

    Arg state is cleared on EVERY call (arg registers are caller-saved, so each
    ctor's args come only from the instructions since the previous call). This
    keeps the per-entry helper call (e.g. the descriptor register, also invoked
    once per entry) from polluting the next entry's args. `XOR reg,reg` is read
    as an immediate 0 — that idiom is how opcode 0 and size-0 packets are set."""
    fn = fp.getFunctionContaining(addr(caller_off))
    if fn is None:
        return []
    ctor_addr = addr(ctor_off)
    arg_ptr = {}   # regname -> LEA mem target
    arg_imm = {}   # regname -> immediate value
    rows = []
    for ins in listing.getInstructions(fn.getBody(), True):
        mnem = ins.getMnemonicString()
        if mnem == "LEA":
            dst = ins.getRegister(0)
            tgt = _mem_ref(ins)
            if dst is not None and tgt is not None:
                arg_ptr[dst.getName()] = tgt
                arg_imm.pop(dst.getName(), None)
        elif mnem == "MOV":
            dst = ins.getRegister(0)
            if dst is not None and ins.getNumOperands() >= 2:
                sc = ins.getScalar(1)
                if sc is not None and _mem_ref(ins) is None:
                    arg_imm[dst.getName()] = sc.getValue() & 0xffffffff
                    arg_ptr.pop(dst.getName(), None)
                else:
                    arg_imm.pop(dst.getName(), None)   # reg<-mem / reg<-reg: unknown
                    arg_ptr.pop(dst.getName(), None)
        elif mnem == "XOR":
            r0 = ins.getRegister(0)
            r1 = ins.getRegister(1)
            if r0 is not None and r1 is not None and r0.getName() == r1.getName():
                arg_imm[r0.getName()] = 0
                arg_ptr.pop(r0.getName(), None)
        elif mnem == "CALL":
            tgt = call_target(ins)
            if tgt is not None and tgt.equals(ctor_addr):
                entry = arg_ptr.get(ARG_REGS[0][0]) or arg_ptr.get(ARG_REGS[0][1])
                a1 = _reg_imm(arg_imm, 1)
                a2 = _reg_imm(arg_imm, 2)
                if entry is not None and a1 is not None and a2 is not None:
                    rows.append((a1, a2, entry))
            arg_ptr = {}
            arg_imm = {}
    return rows


def _reg_imm(arg_imm, idx):
    for rn in ARG_REGS[idx]:
        if rn in arg_imm:
            return arg_imm[rn]
    return None


def orient_opcode_size(rows):
    """rows are (a1,a2,entry); decide which of a1/a2 is opcode by which forms a
    dense permutation of 0..N-1. Returns normalized [(opcode,size,entry)]."""
    def score(getter):
        ops = [getter(r) for r in rows]
        uniq = set(ops)
        if not ops:
            return -1
        mx = max(ops)
        # reward: all unique AND max ~= count-1 (a clean permutation)
        dense = 1.0 - (abs((mx + 1) - len(ops)) / float(len(ops)))
        return (len(uniq) / float(len(ops))) + dense
    s_a1 = score(lambda r: r[0])
    s_a2 = score(lambda r: r[1])
    if s_a1 >= s_a2:
        return [(r[0], normalize_size(r[1]), r[2]) for r in rows]
    return [(r[1], normalize_size(r[0]), r[2]) for r in rows]


def is_prot_table(rows):
    if len(rows) < MIN_TABLE_ENTRIES:
        return False
    ops = [r[0] for r in rows]
    if len(set(ops)) < 0.9 * len(ops):       # opcodes nearly unique
        return False
    mx = max(ops)
    if mx + 1 > len(ops) * 1.5 + 8:           # dense-ish 0..N
        return False
    for op, size, _ in rows:
        if not (0 <= op <= 255 and -2 <= size <= 255):
            return False
    # A prot table carries varByte(-1)/varShort(-2) sizes; a dense-id table that
    # is NOT a prot (config-type registration, etc.) does not. Reject those.
    if sum(1 for op, size, _ in rows if size < 0) < 2:
        return False
    return True


# ---------------------------------------------------------------------------
# 3. CLUSTER into tables + classify SERVER/CLIENT
# ---------------------------------------------------------------------------
def get_stride(rows):
    """Most common small gap between entry addresses == the entry struct size.

    A table's entries can live in several .data regions (a packet's region is set
    by which subsystem binds it), so this ignores the large cross-region jumps and
    returns the dominant intra-region stride."""
    offs = sorted(e.getOffset() for op, size, e in rows)
    counts = {}
    for i in range(len(offs) - 1):
        g = offs[i + 1] - offs[i]
        if 0 < g <= 0x200:
            counts[g] = counts.get(g, 0) + 1
    if not counts:
        return 0x40
    return max(counts.items(), key=lambda kv: kv[1])[0]


def derive_handler_offset(rows):
    """Entry offset that is WRITTEN a code pointer for most entries (the handler
    slot, SERVER only). Samples across the whole table so a CLIENT table — whose
    entries get no handler store — is not misclassified off the first few rows."""
    n = len(rows)
    step = max(1, n // 32)
    sample = [rows[i] for i in range(0, n, step)]
    best_off = None
    best_hits = 0
    for off in HANDLER_OFF_CANDIDATES:
        hits = 0
        for op, size, entry in sample:
            slot = entry.getOffset() + off
            for r in refmgr.getReferencesTo(addr(slot)):
                if r.getReferenceType().isWrite():
                    f = fp.getFunctionContaining(r.getFromAddress())
                    if f is not None:
                        hits += 1
                        break
        if hits > best_hits:
            best_hits = hits
            best_off = off
    if best_hits >= max(6, (len(sample) * 3) // 5):
        return best_off, best_hits
    return None, 0


# ---------------------------------------------------------------------------
# 4. RESOLVE HANDLERS
# ---------------------------------------------------------------------------
def collect_stores(binder_fns):
    """{global_addr_off: lea_target_addr} across the auto-found binder fns."""
    store_map = {}
    for ba in binder_fns:
        fn = fp.getFunctionContaining(addr(ba))
        if fn is None:
            continue
        recent_lea = {}
        for ins in listing.getInstructions(fn.getBody(), True):
            mnem = ins.getMnemonicString()
            if mnem == "LEA":
                dst = ins.getRegister(0)
                tgt = None
                for r in ins.getReferencesFrom():
                    ta = r.getToAddress()
                    if ta is not None and ta.isMemoryAddress():
                        tgt = ta
                if dst is not None and tgt is not None:
                    recent_lea[dst.getName()] = tgt
            elif mnem == "MOV" and ins.getNumOperands() == 2:
                if OperandType.isAddress(ins.getOperandType(0)):
                    dstmem = None
                    for r in ins.getOperandReferences(0):
                        ta = r.getToAddress()
                        if ta is not None and ta.isMemoryAddress():
                            dstmem = ta
                    srcreg = ins.getRegister(1)
                    if dstmem is not None and srcreg is not None:
                        v = recent_lea.get(srcreg.getName())
                        if v is not None:
                            store_map[dstmem.getOffset()] = v
    return store_map


def binder_fns_for(rows, handler_off):
    """Functions that WRITE into any entry+handler_off -> the BindHandlers set."""
    fns = set()
    for op, size, entry in rows:
        slot = entry.getOffset() + handler_off
        for r in refmgr.getReferencesTo(addr(slot)):
            if r.getReferenceType().isWrite():
                f = fp.getFunctionContaining(r.getFromAddress())
                if f is not None:
                    fns.add(f.getEntryPoint().getOffset())
    return fns


def resolve_server(rows, handler_off):
    binders = binder_fns_for(rows, handler_off)
    store_map = collect_stores(binders)
    out = []
    for op, size, entry in rows:
        eoff = entry.getOffset()
        h = store_map.get(eoff + handler_off)
        if h is not None:
            out.append(("SERVER", op, size, eoff, h.getOffset(), sym_name(h), "BINDER"))
        else:
            out.append(("SERVER", op, size, eoff, None, "UNBOUND", "UNBOUND"))
    return sorted(out, key=lambda r: r[1])


def resolve_client(rows, reg_callers):
    out = []
    for op, size, entry in rows:
        emitter_fn = None
        data_slot = None
        for r in refmgr.getReferencesTo(entry):
            frm = r.getFromAddress()
            f = fp.getFunctionContaining(frm)
            if f is not None:
                if f.getEntryPoint().getOffset() in reg_callers:
                    continue
                emitter_fn = f.getEntryPoint()
            else:
                data_slot = frm
        if emitter_fn is not None:
            out.append(("CLIENT", op, size, entry.getOffset(), emitter_fn.getOffset(),
                        sym_name(emitter_fn), "BINDER"))
        elif data_slot is not None:
            reader = None
            for rr in refmgr.getReferencesTo(data_slot):
                f = fp.getFunctionContaining(rr.getFromAddress())
                if f is not None:
                    reader = f.getEntryPoint()
                    break
            nm = "%s->%s" % (sym_name(data_slot), sym_name(reader) if reader else "?")
            out.append(("CLIENT", op, size, entry.getOffset(),
                        data_slot.getOffset(), nm, "CS2_TABLE"))
        else:
            out.append(("CLIENT", op, size, entry.getOffset(), None, "UNBOUND", "UNBOUND"))
    return sorted(out, key=lambda r: r[1])


# ---------------------------------------------------------------------------
# 5. NAMING — embedded literal + cross-version carry by declaration order
# ---------------------------------------------------------------------------
def read_cstring(a, maxlen=48):
    try:
        bs = bytearray()
        cur = a
        for _ in range(maxlen):
            b = mem.getByte(cur) & 0xff
            if b == 0:
                break
            if b < 0x20 or b > 0x7e:
                return None
            bs.append(b)
            cur = cur.add(1)
        if len(bs) >= 3:
            return bs.decode("latin-1")
    except Exception:
        return None
    return None


def detect_embedded_names(rows, stride):
    """If entries embed a name-string pointer, return {entry_off: NAME}."""
    names = {}
    name_off = None
    for off in range(0, min(stride, 0x40), 8):
        hits = 0
        for op, size, entry in rows[:24]:
            try:
                ptr = mem.getLong(addr(entry.getOffset() + off))
            except Exception:
                continue
            if ptr and read_cstring(addr(ptr & 0xffffffffffff)) is not None:
                hits += 1
        if hits >= max(8, len(rows[:24]) * 3 // 4):
            name_off = off
            break
    if name_off is None:
        return {}
    for op, size, entry in rows:
        try:
            ptr = mem.getLong(addr(entry.getOffset() + name_off))
            s = read_cstring(addr(ptr & 0xffffffffffff))
        except Exception:
            s = None
        if s is not None:
            names[entry.getOffset()] = s
    return names


def carry_names(decl_rows, prev_list):
    """decl_rows: [(op,size,entry_off)] in DECLARATION (address) order for one
    direction. prev_list: [{size, name}] in the same order from the prior build.
    Size-sequence greedy diff; returns {op: (name, name_src)}."""
    out = {}
    if not prev_list:
        for op, size, eoff in decl_rows:
            out[op] = ("UNKNOWN_%d" % op, "NONE")
        return out
    prev = prev_list
    i = 0  # prev index
    j = 0  # new index
    LA = 6  # lookahead window for realignment
    while j < len(decl_rows):
        op, size, eoff = decl_rows[j]
        if i < len(prev) and prev[i].get("size") == size:
            out[op] = (prev[i].get("name") or ("UNKNOWN_%d" % op), "CARRY")
            i += 1
            j += 1
            continue
        # mismatch: is a packet inserted in new (skip j) or removed from prev (skip i)?
        ins_dist = _next_match(prev, i, decl_rows, j + 1, LA, mode="new")
        del_dist = _next_match(prev, i + 1, decl_rows, j, LA, mode="prev")
        if ins_dist is not None and (del_dist is None or ins_dist <= del_dist):
            out[op] = ("UNKNOWN_%d" % op, "NEW")          # inserted this build
            j += 1
        elif del_dist is not None:
            i += 1                                         # removed since last build
        else:
            out[op] = ("UNKNOWN_%d" % op, "NEW")
            i += 1
            j += 1
    return out


def _next_match(prev, i0, new, j0, window, mode):
    for d in range(window):
        if mode == "new":
            jj = j0 + d
            if jj < len(new) and i0 < len(prev) and prev[i0].get("size") == new[jj][1]:
                return d
        else:
            ii = i0 + d
            if ii < len(prev) and j0 < len(new) and prev[ii].get("size") == new[j0][1]:
                return d
    return None


# ---------------------------------------------------------------------------
# 6. MAIN
# ---------------------------------------------------------------------------
def load_oracle():
    if not NAME_ORACLE_IN:
        return {}
    try:
        f = open(NAME_ORACLE_IN, "r")
        data = json.load(f)
        f.close()
        return data
    except Exception as e:
        print("[!] could not read oracle %s: %s" % (NAME_ORACLE_IN, e))
        return {}


def main():
    ver, _major = detect_version()
    print("=" * 64)
    if ver:
        print("NXT v%s   (program: %s)" % (ver, currentProgram.getName()))
    else:
        ver = "unknown"
        print("NXT version: UNKNOWN (no RS2Engine-*-NXT-* string)  program: %s"
              % currentProgram.getName())
    print("=" * 64)

    print("[*] census of all CALLs ...")
    census = census_calls()
    pairs = find_registerall_pairs(census)
    reg_callers = set(p[0] for p in pairs)

    tables = []  # (caller_off, ctor_off, rows[(op,size,entryAddr)])
    for caller_off, ctor_off, cnt in pairs:
        raw = extract_entries(caller_off, ctor_off)
        if not raw:
            continue
        rows = orient_opcode_size(raw)
        if not is_prot_table(rows):
            continue
        tables.append((caller_off, ctor_off, rows))

    if not tables:
        print("[!] no prot tables discovered — lower MIN_TABLE_ENTRIES / inspect census")
        return

    for caller_off, ctor_off, rows in sorted(tables, key=lambda t: (t[0], t[1])):
        print("    RegisterAll @ 0x%08x -> ctor @ 0x%08x : %d entries"
              % (caller_off, ctor_off, len(rows)))

    results = []   # (direction, res7, decl_rows, stride, rows)
    for caller_off, ctor_off, rows in tables:
        stride = get_stride(rows)
        handler_off, hits = derive_handler_offset(rows)
        decl_rows = sorted([(op, size, e.getOffset()) for op, size, e in rows],
                           key=lambda r: r[2])
        if handler_off is not None:
            direction = "SERVER"
            res = resolve_server(rows, handler_off)
            print("[*] SERVER table: %d entries, stride 0x%x, handler@+0x%x (%d/%d bound-sample)"
                  % (len(rows), stride, handler_off, hits, min(24, len(rows))))
        else:
            direction = "CLIENT"
            res = resolve_client(rows, reg_callers)
            print("[*] CLIENT table: %d entries, stride 0x%x (no handler-store layer)"
                  % (len(rows), stride))
        results.append((direction, res, decl_rows, stride, rows))

    # ---- zone sub-opcode table (small; below the main RegisterAll floor) ----
    # The zone-update sub-opcode vector (g_zoneSubProtVector) is read inside
    # UPDATE_ZONE_PARTIAL_ENCLOSED. It is too small for find_registerall_pairs, so scan
    # for it separately and accept the candidate whose size-multiset matches the known
    # signature for this build (or, with no known signature, a small dense var-size table).
    zone_sizes_sig = KNOWN_ZONE_SIZES_BY_VER.get(ver)
    for caller_off, ctor_off, cnt in find_small_tables(census, reg_callers):
        raw = extract_entries(caller_off, ctor_off)
        if not raw:
            continue
        zrows = orient_opcode_size(raw)
        ops = [r[0] for r in zrows]
        if len(set(ops)) != len(ops):
            continue
        sig = sorted(s for _, s, _ in zrows)
        ok = (zone_sizes_sig is not None and sig == zone_sizes_sig)
        if not ok and zone_sizes_sig is None:
            ok = (max(ops) + 1 <= len(ops) + 4 and sum(1 for _, s, _ in zrows if s < 0) >= 1)
        if not ok:
            continue
        zdecl = sorted([(op, size, e.getOffset()) for op, size, e in zrows], key=lambda r: r[2])
        zres = [("ZONE", op, size, e.getOffset(), None, "ZONE_SUBOP", "ZONE")
                for op, size, e in sorted(zrows, key=lambda r: r[0])]
        results.append(("ZONE", zres, zdecl, get_stride(zrows), zrows))
        print("[*] ZONE table: %d entries (zone-update sub-opcode vector) @ RegisterAll 0x%08x"
              % (len(zrows), caller_off))
        break

    # ---- naming ----
    oracle = load_oracle()
    out_oracle = {}
    named = {}  # (direction, op) -> (name, src)
    for direction, res, decl_rows, stride, rows in results:
        embedded = detect_embedded_names(rows, stride)
        if embedded:
            print("    %s: read %d embedded packet names from the binary" % (direction, len(embedded)))
        prev_list = oracle.get(direction, [])
        carried = carry_names(decl_rows, prev_list)
        eoff_to_op = {}
        for op, size, eoff in decl_rows:
            eoff_to_op[eoff] = op
        # Authoritative per-version override (highest authority): a verbatim opcode->name
        # table dumped from this exact client build wins over carry/embedded. Lets the
        # scanner name a stripped build correctly with no oracle, and seeds a correct
        # carry oracle for the next build.
        known_dir = {}
        if direction == "ZONE":
            known_dir = KNOWN_ZONE_BY_VER.get(ver, {})
        else:
            known_dir = (KNOWN_NAMES_BY_VER.get(ver) or {}).get(direction, {})
        for op, size, eoff in decl_rows:
            if op in known_dir:
                named[(direction, op)] = (known_dir[op], "KNOWN")
            elif eoff in embedded:
                named[(direction, op)] = (embedded[eoff], "EMBEDDED")
            else:
                named[(direction, op)] = carried.get(op, ("UNKNOWN_%d" % op, "NONE"))
        # build outgoing oracle in declaration order
        out_oracle[direction] = [
            {"size": size, "name": named[(direction, op)][0], "opcode": op,
             "entry": "0x%08x" % eoff}
            for op, size, eoff in decl_rows
        ]

    # ---- print + files ----
    csv = ["direction,opcode,size,entry_addr,handler_addr,handler_name,handler_source,name,name_src"]
    for direction, res, decl_rows, stride, rows in results:
        print("-" * 64)
        for row in res:
            d, op, size, eoff, haddr, hname, hsrc = row
            name, nsrc = named.get((d, op), ("UNKNOWN_%d" % op, "NONE"))
            ha = ("0x%08x" % haddr) if haddr is not None else ""
            print("Found %s[opcode=%d, size=%d, name=%s (%s), entry=0x%08x, handler=%s %s]"
                  % (d, op, size, name, nsrc, eoff, hname, ha))
            csv.append("%s,%d,%d,0x%08x,%s,%s,%s,%s,%s"
                       % (d, op, size, eoff, ha, hname, hsrc, name, nsrc))
            if RENAME_HANDLERS and haddr is not None and not name.startswith("UNKNOWN_"):
                _rename_handler(haddr, name)

    if WRITE_FILES:
        _write_files(csv, out_oracle, ver)

    # ---- summary + new-packet alert ----
    print("=" * 64)
    for direction, res, decl_rows, stride, rows in results:
        new = [op for (d, op) in named if d == direction and named[(d, op)][1] == "NEW"]
        unb = [r[1] for r in res if r[6] == "UNBOUND"]
        print("%s: %d packets   NEW=%s   UNBOUND=%s"
              % (direction, len(res), sorted(new) if new else "none",
                 sorted(unb) if unb else "none"))
    print("=" * 64)


def _ns(parts):
    from ghidra.program.model.symbol import SourceType
    parent = currentProgram.getGlobalNamespace()
    for p in parts:
        child = st.getNamespace(p, parent)
        if child is None:
            child = st.createNameSpace(parent, p, SourceType.USER_DEFINED)
        parent = child
    return parent


def _rename_handler(haddr, name):
    try:
        from ghidra.program.model.symbol import SourceType
        a = addr(haddr)
        f = fp.getFunctionContaining(a)
        if f is not None and f.getEntryPoint().equals(a):
            f.setParentNamespace(_ns(["jag", "PacketHandlers"]))
            f.setName(_camel(name), SourceType.USER_DEFINED)
    except Exception as e:
        print("[!] rename failed @0x%08x: %s" % (haddr, e))


def _camel(name):
    return "".join(p.capitalize() for p in name.lower().split("_"))


def _write_files(csv, out_oracle, ver):
    outdir = OUT_DIR or os.path.dirname(currentProgram.getExecutablePath() or ".")
    if not outdir or not os.path.isdir(outdir):
        outdir = "."
    try:
        p = os.path.join(outdir, "prot_tables_dump.csv")
        f = open(p, "w")
        f.write("\n".join(csv) + "\n")
        f.close()
        print("[+] wrote %d rows -> %s" % (len(csv) - 1, p))
    except Exception as e:
        print("[!] csv write failed: %s" % e)
    try:
        p = os.path.join(outdir, "prot_names_%s.json" % ver)
        f = open(p, "w")
        json.dump(out_oracle, f, indent=2, sort_keys=True)
        f.close()
        print("[+] wrote name oracle -> %s  (feed as NAME_ORACLE_IN next build)" % p)
    except Exception as e:
        print("[!] oracle write failed: %s" % e)


main()
