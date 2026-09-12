# DumpProtTables.py — ServerProt/ClientProt opcode/size/handler/name dumper
#
# Run from Ghidra's Script Manager (Jython) against the CURRENT program (the
# rs2client binary, e.g. rs2client.948-2-2). Emits a CSV to the console and to
# <program_dir>/prot_tables_dump.csv with columns:
#
#     direction,opcode,size,entry_addr,handler_addr,handler_name,handler_source
#
# direction      = SERVER (ServerProt, server->client) or CLIENT (ClientProt).
# size           = -1 varByte, -2 varShort, >=0 fixed byte count.
# handler_source = BINDER     -> resolved via a BindHandlers store (SERVER) or a
#                               direct Send*/emitter xref to the entry base (CLIENT)
#                  CS2_TABLE  -> resolved by following a CS2 dispatch data table
#                               (0x013659xx) to the reader fn (IfButtonXInner family)
#                  UNBOUND    -> only RegisterAll references the entry (no handler/emitter)
#
# handler_name is the RAW Ghidra symbol at the resolved target (often FUN_xxxx).
# It is EVIDENCE, not a packet name — packet naming is a separate phase (source B).
#
# ----------------------------------------------------------------------------
# HOW IT WORKS  (read this before the next revision bump)
# ----------------------------------------------------------------------------
# The prot tables are built at static-init time. The two prot directions are
# built by SEPARATE RegisterAll functions in 948-2 (they were merged in 947-3):
#
#   ServerProt::RegisterAll @ 0x000c4700 — 218 InitEntry(entry, opcode, size)
#       calls + 18 InitSubEntry(...) zone sub-prot calls. The (opcode, size)
#       immediates are the last two scalar args; the entry object is the &DAT
#       loaded into the first arg register.
#
#   ClientProt::RegisterAll @ 0x000c45b0 — a THUNK that jmps to 0x000e6170.
#       The real body holds 130 ClientProt(&entry, opcode, size) ctor calls
#       (opcode 0x00..0x81). STOP walking at opcode 0x81 (129): the block that
#       follows is a FUN_00aa6d40/FUN_000c4600 init + a LoginProt::InitEntry
#       block (opcodes 0xe,0xf,0x10,0x13,0x17,0x18,0x1a..0x1f) that MUST NOT be
#       ingested as ClientProt rows.
#
# Two resolution layers map entry -> handler:
#
#   A) SERVER: BindHandlers (+ per-subsystem binders) store the manager/redirector
#      pointer at entry+0x20 and the real handler at entry+0x28. We collect, across
#      every binder fn, the map { stored_global_addr : handler_value }, then look up
#      entry+0x28 for each ServerProt entry. handler_source = BINDER. If entry+0x28
#      is never written (e.g. SERVER op 53 in 948-2), the row is UNBOUND.
#
#   B) CLIENT: ClientProt has NO +0x28 BindHandlers layer — these are packets the
#      client SENDS, emitted by Send*/encoder fns. We resolve each entry's emitter
#      via get_xrefs_to(entry_base): the one non-RegisterAll referrer is the emitter
#      (handler_source = BINDER). Entries referenced ONLY from a CS2 data table at
#      0x013659xx are the IF_BUTTON / OpTargetOption (OPLOC/OPOBJ/OPNPC) CS2 families;
#      follow the table to its reader fn jag::InterfaceManager::IfButtonXInner
#      (handler_source = CS2_TABLE; handler_addr = the table slot). Entries with only
#      a RegisterAll xref are UNBOUND.
#
# ----------------------------------------------------------------------------
# ANCHOR ADDRESSES  (948-2-2 — RE-POINT THESE WHEN THEY SHIFT NEXT REVISION)
# ----------------------------------------------------------------------------
#   ServerProt::RegisterAll                @ 0x000c4700   (218 InitEntry + 18 InitSubEntry)
#   ClientProt::RegisterAll                @ 0x000c45b0   (thunk -> 0x000e6170; 130 ClientProt ctor)
#       ClientProt::RegisterAll body       @ 0x000e6170
#   ServerProt::BindHandlers (catch-all)   @ 0x0007509a   (118 entries; 0x0139ff60..0x013a1d10 range)
#   ClientState::BindHandlers              @ 0x000aa85e   (18 VARP/VARC/zone, 0x015c12c0..0x015c1700)
#   ClientState::BindHandlers_extra        @ 0x000aaf4c   (12 rebuild/worldentity, 0x015c0fc0..0x015c1280)
#   ZoneUpdates::BindHandlers              @ 0x000ae1a8   (21 loc/obj/projanim, 0x015c1780..0x015c2080)
#   PlayerList::BindHandlers               @ 0x000ab3ea   (12 player/misc, 0x015c0cc0..0x015c0f80)
#   Interfaces::BindHandlers               @ 0x000ab888   (37 IF_*, 0x015c0380..0x015c0c80)
#   IfButtonXInner (CS2 table reader)      @ 0x002978d0   (reads PTR_DAT_01365920[button-1])
#   CS2 IF_BUTTON dispatch table base      @ 0x01365920   (10 slots x 8 bytes, .. 0x01365968)
#
# SERVER entry stride is 0x40 bytes; handler slot = entry+0x28.
# CLIENT entry stride is 0x10 bytes, descending from 0x015d3ea0 (op0); idx skips the
#   one outlier op 0x19 @ 0x015bfb40 (a relocated-out entry).
#
# RE-POINTING PROCEDURE (next revision bump, e.g. 949):
#   1) ServerProt::RegisterAll: getReferencesTo(getFunction("InitEntry").getEntryPoint());
#      the caller with ~218 refs is it.
#   2) ClientProt::RegisterAll: the sibling fn with ~130 ClientProt-ctor calls. The ctor
#      is the small fn called as `ClientProt(&entry, opcode, size)` reading the two trailing
#      immediates. Set CLIENTPROT_REGISTERALL to the thunk (or its body). Set
#      CLIENTPROT_LAST_OPCODE if the last real ClientProt row drifts from 0x81.
#   3) Binders: the fns that write paired pointer globals (redirector@+0x20, handler@+0x28)
#      into the ServerProt entry objects. List them in SERVERPROT_BINDERS.
#   4) CS2 table: re-find IfButtonXInner (references &PTR_DAT_<base>[idx]) and its table base.
# ----------------------------------------------------------------------------

from ghidra.program.model.symbol import RefType
from ghidra.program.model.lang import OperandType
import os

# ---- anchors (UPDATE on revision bump) ----
SERVERPROT_REGISTERALL = 0x000c4700
SERVERPROT_BINDERS = [
    0x0007509a,  # ServerProt::BindHandlers (catch-all)
    0x000aa85e,  # ClientState::BindHandlers
    0x000aaf4c,  # ClientState::BindHandlers_extra
    0x000ae1a8,  # ZoneUpdates::BindHandlers
    0x000ab3ea,  # PlayerList::BindHandlers
    0x000ab888,  # Interfaces::BindHandlers
]

# ClientProt::RegisterAll thunk (jmps to its body). The walker follows JMP thunks.
CLIENTPROT_REGISTERALL = 0x000c45b0
# Last real ClientProt opcode: stop before the LoginProt::InitEntry block that follows.
CLIENTPROT_LAST_OPCODE = 0x81  # 129
# Name substring that identifies the ClientProt ctor call target in RegisterAll.
# In 948-2 the ctor decompiles as a call named "ClientProt"; we also accept the
# generic InitEntry form in case the symbol differs.
CLIENTPROT_CTOR_NAMES = ("ClientProt", "InitEntry")

# CS2 IF_BUTTON dispatch table (CLIENT). Entries referenced ONLY from this .data
# region are routed through IfButtonXInner. Range is inclusive of base..base+stride*N.
CS2_TABLE_LO = 0x01365900
CS2_TABLE_HI = 0x01365970
CS2_TABLE_READER = 0x002978d0  # jag::InterfaceManager::IfButtonXInner

# ProtEntry layout: redirector pointer at entry+0x20, real handler at entry+0x28.
HANDLER_FIELD_OFFSET = 0x28

fp = currentProgram.getFunctionManager()
listing = currentProgram.getListing()
af = currentProgram.getAddressFactory()
st = currentProgram.getSymbolTable()
refmgr = currentProgram.getReferenceManager()


def addr(x):
    return af.getDefaultAddressSpace().getAddress(x)


def func_containing(x):
    return fp.getFunctionContaining(addr(x))


def sym_name(a):
    """Best symbol name at an address, else FUN_/DAT_ style fallback."""
    if a is None:
        return None
    s = st.getPrimarySymbol(a)
    if s is not None:
        return s.getName(True)  # include namespace path
    f = fp.getFunctionContaining(a)
    if f is not None and f.getEntryPoint().equals(a):
        return f.getName(True)
    return "0x%x" % a.getOffset()


def normalize_size(raw):
    """Normalize a raw size immediate to the prot convention.
       0xffffffff -> -1 (varByte); 0xfffffffe -> -2 (varShort); else fixed.
       Accepts already-signed -1/-2 too."""
    if raw in (0xffffffff, -1):
        return -1
    if raw in (0xfffffffe, -2):
        return -2
    # mask to 32 bits then sign-interpret the small-positive fixed sizes
    v = raw & 0xffffffff
    if v == 0xffffffff:
        return -1
    if v == 0xfffffffe:
        return -2
    return v


def resolve_thunk(a):
    """If the function at a is a single-instruction JMP thunk, follow it."""
    fn = fp.getFunctionContaining(addr(a))
    if fn is None:
        return addr(a)
    instrs = listing.getInstructions(fn.getBody(), True)
    first = None
    count = 0
    for ins in instrs:
        if count == 0:
            first = ins
        count += 1
        if count > 1:
            return fn.getEntryPoint()  # not a 1-insn thunk
    if first is not None and first.getMnemonicString() == "JMP":
        for r in first.getReferencesFrom():
            ta = r.getToAddress()
            if ta is not None and ta.isMemoryAddress():
                return ta
    return fn.getEntryPoint()


def walk_registerall(reg_addr, ctor_names, last_opcode=None):
    """Return list of (opcode, size, entry_addr) by scanning ctor/InitEntry calls.

    Tracks the most-recent &DAT entry pointer loaded into an arg register and the
    rolling list of recent integer immediates; snapshots them at each CALL whose
    target name contains one of ctor_names. Stops after last_opcode (inclusive)."""
    target = resolve_thunk(reg_addr)
    fn = fp.getFunctionContaining(target)
    if fn is None:
        print("[!] No function at RegisterAll anchor 0x%x" % reg_addr)
        return []
    rows = []
    last_entry = None
    last_imm = []
    for ins in listing.getInstructions(fn.getBody(), True):
        mnem = ins.getMnemonicString()
        if mnem in ("LEA", "MOV"):
            for i in range(ins.getNumOperands()):
                refs = ins.getOperandReferences(i)
                for r in refs:
                    ta = r.getToAddress()
                    if ta is not None and ta.isMemoryAddress():
                        last_entry = ta
            for i in range(ins.getNumOperands()):
                sc = ins.getScalar(i)
                if sc is not None:
                    last_imm.append(sc.getValue() & 0xffffffff)
                    if len(last_imm) > 8:
                        last_imm.pop(0)
        elif mnem == "PUSH":
            sc = ins.getScalar(0)
            if sc is not None:
                last_imm.append(sc.getValue() & 0xffffffff)
        elif mnem == "CALL":
            tgt = None
            for r in ins.getReferencesFrom():
                if r.getReferenceType().isCall():
                    tgt = r.getToAddress()
            name = sym_name(tgt) if tgt is not None else ""
            hit = name and any(c in name for c in ctor_names)
            if hit and last_entry is not None and len(last_imm) >= 2:
                size_raw = last_imm[-1]
                opcode_raw = last_imm[-2]
                opcode = opcode_raw & 0xffffffff
                size = normalize_size(size_raw)
                if 0 <= opcode <= 255 and -2 <= size <= 255:
                    rows.append((opcode, size, last_entry))
                    if last_opcode is not None and opcode == last_opcode:
                        break
                last_entry = None
                last_imm = []
    return rows


def collect_handler_stores(binder_addrs):
    """Across all binder fns, build { stored_global_addr : handler_value_addr }.

    A binding does `LEA reg,[handler]; MOV [DAT_x], reg` (handler at entry+0x28) and
    `LEA reg,[redirector]; MOV [DAT_y], reg` (redirector at entry+0x20). We pair each
    MOV-store to a global with the most-recent LEA target into the source register."""
    store_map = {}
    for ba in binder_addrs:
        fn = fp.getFunctionContaining(addr(ba))
        if fn is None:
            print("[!] No binder fn at 0x%x" % ba)
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
            elif mnem == "MOV":
                if ins.getNumOperands() == 2:
                    ot0 = ins.getOperandType(0)
                    if OperandType.isAddress(ot0):
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


def emitter_for_client_entry(entry):
    """CLIENT resolution: find the Send*/emitter fn that references entry (best-effort).

    Returns (handler_addr_or_None, handler_name, source). source is:
       BINDER    if a non-RegisterAll fn-body xref exists,
       CS2_TABLE if the only non-RegisterAll referrer is a CS2 data-table slot,
       UNBOUND   if only RegisterAll references it."""
    refs = refmgr.getReferencesTo(entry)
    emitter_fn = None
    cs2_slot = None
    for r in refs:
        frm = r.getFromAddress()
        f = fp.getFunctionContaining(frm)
        if f is not None:
            nm = f.getName(True)
            if "RegisterAll" in nm:
                continue
            emitter_fn = f.getEntryPoint()
        else:
            off = frm.getOffset()
            if CS2_TABLE_LO <= off < CS2_TABLE_HI:
                cs2_slot = frm
    if emitter_fn is not None:
        return (None, sym_name(emitter_fn), "BINDER")
    if cs2_slot is not None:
        # follow the CS2 table to its reader fn
        return (cs2_slot, "%s->%s" % (sym_name(cs2_slot) or "0x%x" % cs2_slot.getOffset(),
                                      sym_name(addr(CS2_TABLE_READER))), "CS2_TABLE")
    return (None, "UNBOUND", "UNBOUND")


def resolve_server(reg_addr, binder_addrs):
    rows = walk_registerall(reg_addr, ("InitEntry",))
    store_map = collect_handler_stores(binder_addrs) if binder_addrs else {}
    out = []
    for opcode, size, entry in rows:
        entry_off = entry.getOffset()
        handler_addr = store_map.get(entry_off + HANDLER_FIELD_OFFSET)
        if handler_addr is not None:
            hname = sym_name(handler_addr)
            haddr = "0x%08x" % (entry_off + HANDLER_FIELD_OFFSET)
            src = "BINDER"
        else:
            hname = "UNBOUND"
            haddr = ""
            src = "UNBOUND"
        out.append(("SERVER", opcode, size, "0x%08x" % entry_off, haddr, hname, src))
    out.sort(key=lambda r: r[1])
    return out


def resolve_client(reg_addr, last_opcode):
    rows = walk_registerall(reg_addr, CLIENTPROT_CTOR_NAMES, last_opcode)
    out = []
    seen = set()
    for opcode, size, entry in rows:
        if opcode in seen:
            continue
        seen.add(opcode)
        haddr_obj, hname, src = emitter_for_client_entry(entry)
        haddr = ("0x%08x" % haddr_obj.getOffset()) if haddr_obj is not None else ""
        out.append(("CLIENT", opcode, size, "0x%08x" % entry.getOffset(), haddr, hname, src))
    out.sort(key=lambda r: r[1])
    return out


def main():
    lines = ["direction,opcode,size,entry_addr,handler_addr,handler_name,handler_source"]
    server = resolve_server(SERVERPROT_REGISTERALL, SERVERPROT_BINDERS)
    for r in server:
        lines.append("%s,%d,%d,%s,%s,%s,%s" % r)
    if CLIENTPROT_REGISTERALL is not None:
        client = resolve_client(CLIENTPROT_REGISTERALL, CLIENTPROT_LAST_OPCODE)
        for r in client:
            lines.append("%s,%d,%d,%s,%s,%s,%s" % r)
    text = "\n".join(lines)
    print(text)
    try:
        outdir = os.path.dirname(currentProgram.getExecutablePath() or ".")
        if not outdir or not os.path.isdir(outdir):
            outdir = "."
        path = os.path.join(outdir, "prot_tables_dump.csv")
        f = open(path, "w")
        f.write(text + "\n")
        f.close()
        print("\n[+] Wrote %d rows to %s" % (len(lines) - 1, path))
    except Exception as e:
        print("[!] Could not write CSV file: %s" % e)


main()
