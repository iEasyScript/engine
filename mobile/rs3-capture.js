'use strict';

const CONFIG = {
    redirectHost: null,
    raw: false,
    rsaPatch: [],
    isolate: false,
    isolateAllow: [],
    libName: 'liblibs.hal.system.rs2client.so',
    rva: {
        packetReset: null,
        tcpIn: null,
        initOutgoing: null,
        clientStreamRead: null,
        clientStreamWrite: null,
        isaacInit: null,
        // direct-login (username+password, no OAuth) — see docs/mobile/net/login-handshake.md §3a
        loginManagerCtor: null,     // jag::LoginManager::LoginManager(this,client) @ 0x0099b2e4
        beginLobbyLogin: null,      // jag::LoginManager::BeginLobbyLogin() loginType=1 (op19 LOBBY) @ 0x009a2800
        beginDirectLogin: null,     // jag::LoginManager::BeginDirectLogin() loginType=2 (op16 WORLD) @ 0x009a3254
    },
    conn: {
        currentOpcode: 0x2c,
        resolvedSize: 0x30,
        bufData: 0x2d0,
        handleState: 0x8,
    },
    // Drive the client's own native direct-login (op19 username+password) against Project X, instead
    // of the OAuth "email/password" button which reaches account.jagex.com and exits on an isolated
    // network. jag::AttemptStoredLogin does NOT auto-tick on a fresh launch (verified on-device), so
    // we capture loginMgr from its ctor and call BeginDirectLogin directly. See §3a of the doc.
    directLogin: {
        enabled: false,
        username: '',
        password: '',
    },
};

const MAX_PAYLOAD = 0x20000;
const IDLE_OPCODE = -1;
const DEEPLINK_TEMPLATE = 'https://secure.runescape.com/playnow/rs?launchurl=';

const sockets = new Map();
let isaacSeen = 0;
let libcModule = null;

// frida 17 removed the static Module.getExportByName(mod, name); resolve through a
// module instance, with a global-lookup fallback for symbols libc re-exports.
function libcSym(name) {
    if (libcModule === null) libcModule = Process.getModuleByName('libc.so');
    const a = libcModule.findExportByName(name);
    if (a !== null) return a;
    return Module.getGlobalExportByName(name);
}

function globalSym(name) {
    return Module.findGlobalExportByName(name);
}

function findModule(name) {
    return Process.findModuleByName(name);
}

function emit(kind, payload, data) {
    send(Object.assign({ kind: kind }, payload), data);
}

function note(msg) {
    emit('log', { message: msg });
}

function parseSockaddr(sa) {
    if (sa.isNull()) return null;
    const family = sa.readU16();
    const port = (sa.add(2).readU8() << 8) | sa.add(3).readU8();
    if (family === 2) {
        const b = sa.add(4);
        return { ip: [0, 1, 2, 3].map(i => b.add(i).readU8()).join('.'), port: port };
    }
    if (family === 10) {
        const b = sa.add(8);
        const parts = [];
        for (let i = 0; i < 16; i += 2) parts.push(((b.add(i).readU8() << 8) | b.add(i + 1).readU8()).toString(16));
        return { ip: parts.join(':'), port: port };
    }
    return null;
}

function isAllowed(ip) {
    if (ip.indexOf('127.') === 0 || ip === '::1' || ip.indexOf(':ffff:7f') !== -1) return true;
    for (const a of CONFIG.isolateAllow) {
        if (ip === a || ip.indexOf(':ffff:' + a) !== -1) return true;
    }
    return false;
}

// Point a blocked connect at 127.0.0.1:1 (nothing listening → instant ECONNREFUSED) by rewriting its
// sockaddr in place, so isolation needs NO Interceptor.replace on connect() — the real syscall runs
// untouched for allowed hosts, and we never touch the app's errno / non-blocking-connect state.
function blackhole(sa) {
    const family = sa.readU16();
    sa.add(2).writeU8(0); sa.add(3).writeU8(1);                 // port 1 (big-endian)
    if (family === 2) {                                          // AF_INET → 127.0.0.1
        sa.add(4).writeU8(127); sa.add(5).writeU8(0); sa.add(6).writeU8(0); sa.add(7).writeU8(1);
    } else if (family === 10) {                                 // AF_INET6 → ::1
        for (let i = 0; i < 16; i++) sa.add(8 + i).writeU8(i === 15 ? 1 : 0);
    }
}

function installConnect() {
    // Observe-only: attaching (not replacing) leaves connect() and its EINPROGRESS/errno semantics
    // for the client's non-blocking sockets completely intact — replacing it broke every connect.
    Interceptor.attach(libcSym('connect'), {
        onEnter(args) {
            const fd = args[0].toInt32();
            const sa = args[1];
            const peer = parseSockaddr(sa);
            if (peer === null) return;
            if (CONFIG.isolate && !isAllowed(peer.ip)) {
                blackhole(sa);
                emit('blocked', { fd: fd, ip: peer.ip, port: peer.port });
                return;
            }
            sockets.set(fd, peer);
            emit('connect', { fd: fd, ip: peer.ip, port: peer.port });
        },
    });
}

function hookSocketLifecycle() {
    installConnect();

    Interceptor.attach(libcSym('close'), {
        onEnter(args) {
            const fd = args[0].toInt32();
            if (!sockets.has(fd)) return;
            emit('disconnect', { fd: fd });
            sockets.delete(fd);
        },
    });
    if (CONFIG.isolate) {
        note('ISOLATION ON — allow-list: ' + (CONFIG.isolateAllow.join(', ') || '(loopback only)'));
    }
}

function hookRawSockets() {
    const fn = libcSym;
    const wire = (dir, fd, buf, n) => {
        if (n <= 0 || !sockets.has(fd)) return;
        emit('wire', { dir: dir, fd: fd, len: n }, buf.readByteArray(n));
    };
    for (const name of ['recvfrom', 'recv', 'read']) {
        Interceptor.attach(fn(name), {
            onEnter(args) { this.fd = args[0].toInt32(); this.buf = args[1]; },
            onLeave(retval) { wire('s2c', this.fd, this.buf, retval.toInt32()); },
        });
    }
    for (const name of ['sendto', 'send', 'write']) {
        Interceptor.attach(fn(name), {
            onEnter(args) { this.fd = args[0].toInt32(); this.buf = args[1]; },
            onLeave(retval) { wire('c2s', this.fd, this.buf, retval.toInt32()); },
        });
    }
    note('raw socket hooks installed');
}

function hookRedirect() {
    if (CONFIG.redirectHost === null) return;
    const url = DEEPLINK_TEMPLATE + CONFIG.redirectHost;

    // frida 17 dropped the built-in Java bridge from the runtime. If it isn't present,
    // the in-process override is unavailable — but the `am start ... -d <deeplink>`
    // launch already drives GetDeepLinkString natively, so this is only a convenience.
    if (typeof Java === 'undefined' || !Java.available) {
        note('Java bridge unavailable — relying on the am-start deeplink for redirect ('
            + CONFIG.redirectHost + ')');
        return;
    }
    Java.perform(() => {
        Java.use('com.jagex.bootstrap.StartupArguments').GetDeepLinkString.implementation = function () {
            return url;
        };
        note('redirect armed (Java hook) -> ' + url);
    });
}

function hookPacketReset(base) {
    const off = CONFIG.conn;
    Interceptor.attach(base.add(CONFIG.rva.packetReset), {
        onEnter(args) {
            try {
                const conn = args[0];
                if (conn.isNull()) return;

                const opcode = conn.add(off.currentOpcode).readS32();
                if (opcode === IDLE_OPCODE) return;

                const size = conn.add(off.resolvedSize).readS32();
                if (size < 0) {
                    emit('desync', { dir: 's2c', opcode: opcode, size: size, reason: 'unresolved size' });
                    return;
                }
                if (size > MAX_PAYLOAD) {
                    emit('desync', { dir: 's2c', opcode: opcode, size: size, reason: 'implausible size' });
                    return;
                }
                if (size === 0) {
                    emit('packet', { dir: 's2c', opcode: opcode, size: 0, conn: conn.toString() }, null);
                    return;
                }

                const buf = conn.add(off.bufData).readPointer();
                if (buf.isNull()) {
                    emit('desync', { dir: 's2c', opcode: opcode, size: size, reason: 'null buffer' });
                    return;
                }
                emit('packet',
                    { dir: 's2c', opcode: opcode, size: size, conn: conn.toString() },
                    buf.readByteArray(size));
            } catch (e) {
                emit('desync', { dir: 's2c', opcode: -1, size: -1, reason: 'read failed: ' + e.message });
            }
        },
    });
    note('S->C hook installed (packet reset)');
}

function hookTcpIn(base) {
    Interceptor.attach(base.add(CONFIG.rva.tcpIn), {
        onEnter(args) {
            try {
                const conn = args[1].add(CONFIG.conn.handleState).readPointer();
                if (!conn.isNull()) emit('drain', { conn: conn.toString() });
            } catch (e) {
                /* handle not yet populated */
            }
        },
    });
    note('TcpIn hook installed (scoping)');
}

function hookInitOutgoing(base) {
    Interceptor.attach(base.add(CONFIG.rva.initOutgoing), {
        onEnter(args) {
            try {
                if (args[1].isNull()) return;
                emit('outop', {
                    opcode: args[1].readS32(),
                    size: args[2].toInt32(),
                    msg: args[0].toString(),
                    isaac: args[3].isNull() ? null : args[3].toString(),
                });
            } catch (e) {
                emit('desync', { dir: 'c2s', opcode: -1, size: -1, reason: 'read failed: ' + e.message });
            }
        },
    });
    note('C->S opcode hook installed');
}

function hookClientStream(base) {
    if (CONFIG.rva.clientStreamRead !== null) {
        Interceptor.attach(base.add(CONFIG.rva.clientStreamRead), {
            onEnter(args) { this.stream = args[0]; this.buf = args[1]; },
            onLeave(retval) {
                try {
                    const n = retval.toInt32();
                    if (n > 0) emit('stream', { dir: 's2c', stream: this.stream.toString(), len: n },
                        this.buf.readByteArray(n));
                } catch (e) {
                    note('stream read failed: ' + e.message);
                }
            },
        });
    }
    if (CONFIG.rva.clientStreamWrite !== null) {
        Interceptor.attach(base.add(CONFIG.rva.clientStreamWrite), {
            onEnter(args) {
                try {
                    const n = args[2].toInt32();
                    if (n > 0) emit('stream', { dir: 'c2s', stream: args[0].toString(), len: n },
                        args[1].readByteArray(n));
                } catch (e) {
                    note('stream write failed: ' + e.message);
                }
            },
        });
    }
    note('ClientStream hooks installed');
}

// Build an EASTL 24-byte string (SSO for <23 chars, else heap) the client can consume by const-ref.
// Layout: +0x00 data (inline when SSO), +0x08 size, +0x10 capacity|0x8000.. (heap flag = high bit of
// the byte at +0x17), +0x17 byte = 0x17-len when SSO. The callee copies synchronously, so the buffers
// only need to survive the call; refs are kept in a module array so Frida's GC won't reclaim them.
const dlKeep = [];
function makeEastlString(s) {
    const str = Memory.alloc(24);
    const bytes = asciiBytes(s);
    if (bytes.length < 0x17) {                        // SSO: room for data + NUL + size byte
        if (bytes.length) str.writeByteArray(bytes);
        str.add(bytes.length).writeU8(0);
        str.add(0x17).writeU8(0x17 - bytes.length);   // high bit clear => SSO
        dlKeep.push(str);
        return str;
    }
    const buf = Memory.alloc(bytes.length + 1);
    buf.writeByteArray(bytes); buf.add(bytes.length).writeU8(0);
    str.writePointer(buf);
    str.add(8).writeU64(bytes.length);
    str.add(0x10).writeU64(uint64(bytes.length).or(uint64('0x8000000000000000')));
    dlKeep.push(str); dlKeep.push(buf);
    return str;
}

// Read an EASTL string using the EXACT formula FUN_009a2990 uses (verified @ 0x009a2a4c): flag byte at
// ptr+0x17; flag>=0 => SSO (size = 0x17-flag, data inline at ptr); flag<0 => heap (size = *(ptr+8),
// data = *(ptr+0)). Returns {size, text} so a capture can PROVE what the client will read for size/data.
function readEastl(ptr) {
    try {
        if (ptr.isNull()) return { size: -1, text: '<null>' };
        const flag = ptr.add(0x17).readS8();
        let size, data;
        if (flag >= 0) { size = 0x17 - flag; data = ptr; }
        else { size = ptr.add(8).readU64().toNumber(); data = ptr.readPointer(); }
        let text = '';
        if (size > 0 && size < 256 && !data.isNull()) {
            const raw = new Uint8Array(data.readByteArray(Math.min(size, 32)));
            text = String.fromCharCode.apply(null, raw);
        }
        return { size: size, text: text };
    } catch (e) { return { size: -2, text: 'err:' + e.message }; }
}

// Client main-state lives at clientObj+0x19b40, where clientObj = *(loginMgr+0x18). Verified values:
//   10 (0xa)  = interactive LOGIN SCREEN  (jag::SetMainState(client,10); "only available on login screen")
//   0x1e (30) = LOGGED IN
// The login screen (state 10) is the ONLY state where the login-manager tick will pick up and drive a
// direct login to op19. Firing BeginDirectLogin before state 10 (e.g. during boot, when loginMgr is
// already idle) just sets +0x16c=3, which the boot sequence then resets — no op19. So we gate on 10.
const DL_LOGIN_SCREEN_STATE = 10;

function dlMainState(mgr) {
    try {
        const sess = mgr.add(0x18).readPointer();
        if (sess.isNull()) return -1;
        return sess.add(0x19b40).readS32();
    } catch (e) { return -1; }
}

// loginMgr step fields, for diagnostics: +0x10 state, +0x14 currentStep, +0x16c/+0x1b8 login sub-steps.
// StoreLoginCredentials sets the sub-step per loginType: +0x1b8 when loginType==1 (LOBBY, our path),
// +0x16c otherwise (verified @ 0x009a2ca8/0x009a2cf0). Per CRED CONTENT:
//   -3 (0xfffffffd) = BOTH username & password non-empty -> full login (resets conn, +0x10=10)
//    3             = a credential is EMPTY -> incomplete path (no connect)
// So for the LOBBY login watch sub1b8 == -3 (proves the creds landed).
function dlSteps(mgr) {
    try {
        return { state: mgr.add(0x10).readS32(), step: mgr.add(0x14).readS32(),
                 sub16c: mgr.add(0x16c).readS32(), sub1b8: mgr.add(0x1b8).readS32(),
                 main: dlMainState(mgr) };
    } catch (e) { return { err: e.message }; }
}

// All must hold or BeginDirectLogin no-ops / would deref null. Now REQUIRES main-state==10.
function directLoginReady(mgr) {
    if (mgr === null || mgr.isNull()) return false;
    try {
        if (dlMainState(mgr) !== DL_LOGIN_SCREEN_STATE) return false;  // must be at the login screen
        if (mgr.add(0x10).readS32() !== 0) return false;              // login-mgr idle
        const sess = mgr.add(0x18).readPointer();
        const connSub = sess.add(0x19420).readPointer();
        if (connSub.isNull()) return false;
        if (connSub.add(0x28).readS32() !== 0) return false;
        return true;
    } catch (e) {
        return false;
    }
}

let dlLoginMgr = null;   // captured from the LoginManager ctor
let dlDone = false;
let dlBegin = null;      // NativeFunction(BeginDirectLogin)

function tryDirectLogin(mgr, why) {
    const dl = CONFIG.directLogin;
    if (dlDone || !dl.enabled || dlBegin === null) return false;
    if (!directLoginReady(mgr)) return false;
    try {
        emit('directlogin', { status: 'pre', via: why, steps: dlSteps(mgr) });
        const u = makeEastlString(dl.username);
        const p = makeEastlString(dl.password);
        const out = makeEastlString('');                  // empty authenticator/2FA
        // Prove the args we built read back non-empty BEFORE the call (same formula the client uses).
        emit('directlogin', { status: 'built', builtUser: readEastl(u), builtPass: readEastl(p) });
        dlBegin(mgr, u, p, out, 0);                        // BeginLobbyLogin: loginType=1 -> op19 LOBBY
        dlDone = true;
        // Prove the creds LANDED: read them back out of the login-state store at loginMgr+0x70/+0x88.
        emit('directlogin', { status: 'login', user: dl.username, mgr: mgr.toString(), via: why,
                              steps: dlSteps(mgr),
                              storedUser: readEastl(mgr.add(0x70)), storedPass: readEastl(mgr.add(0x88)) });
        return true;
    } catch (e) {
        emit('directlogin', { status: 'error', reason: e.message + ' (' + why + ')' });
        return false;
    }
}

// Drive the client's native LOBBY direct login without OAuth. jag::AttemptStoredLogin does NOT auto-tick,
// and the "email/password" OAuth button is Java-driven (unhookable from native, and it exits on an
// isolated net). So: (1) capture loginMgr from its ctor; (2) POLL for the client to reach the login screen
// (main-state==10) and, the first time it does with the login manager idle, call BeginLobbyLogin
// (loginType=1) — which sets loginMgr+0x1b8=-3, and the client's per-frame login-manager tick then drives
// connect->op19 LOBBY; (3) keep emitting the loginMgr step fields so the handshake progress is visible.
// (BeginDirectLogin @0x009a3254 / loginType=2 is the WORLD variant — kept in rva.json for reference.)
function hookDirectLogin(base) {
    const dl = CONFIG.directLogin;
    if (!dl.enabled) return;
    const r = CONFIG.rva;
    const beginFn = r.beginLobbyLogin;   // loginType=1 (lobby-first); Project X is lobby-then-world
    if (r.loginManagerCtor === null || beginFn === null) {
        note('direct-login: need loginManagerCtor + beginLobbyLogin RVAs — skipping');
        return;
    }
    if (!dl.username || !dl.password) {
        note('direct-login: username/password not set — skipping');
        return;
    }

    dlBegin = new NativeFunction(base.add(beginFn), 'void',
        ['pointer', 'pointer', 'pointer', 'pointer', 'int']);   // (mgr,&user,&pass,&out,mode)

    Interceptor.attach(base.add(r.loginManagerCtor), {
        onEnter(args) {
            dlLoginMgr = args[0];
            emit('directlogin', { status: 'mgr-captured', mgr: dlLoginMgr.toString() });
        },
    });

    let lastState = null;
    let postWatch = 0;
    const timer = setInterval(() => {
        if (dlLoginMgr === null) return;
        const ms = dlMainState(dlLoginMgr);
        if (ms !== lastState) {
            emit('directlogin', { status: 'state', main: ms });
            lastState = ms;
        }
        if (!dlDone) {
            tryDirectLogin(dlLoginMgr, 'state-10');
            if (dlDone) postWatch = 12;   // ~6s of post-fire step snapshots
        } else if (postWatch > 0) {
            postWatch--;
            emit('directlogin', { status: 'post', steps: dlSteps(dlLoginMgr) });
            if (postWatch === 0) clearInterval(timer);
        }
    }, 500);
    note('direct-login armed (waiting for login screen, main-state=10) for ' + dl.username);
}

function hookIsaacInit(base) {
    Interceptor.attach(base.add(CONFIG.rva.isaacInit), {
        onEnter(args) {
            try {
                const keys = [];
                for (let i = 0; i < 4; i++) keys.push(args[1].add(i * 4).readU32());
                isaacSeen += 1;
                emit('isaac', {
                    keys: keys,
                    self: args[0].toString(),
                    which: isaacSeen === 1 ? 'c2s-raw' : (isaacSeen === 2 ? 's2c-plus50' : 'extra'),
                    seq: isaacSeen,
                });
            } catch (e) {
                note('isaac read failed: ' + e.message);
            }
        },
    });
    note('Isaac::Init hook installed');
}

function asciiPattern(s) {
    return Array.prototype.map.call(s, c => c.charCodeAt(0).toString(16).padStart(2, '0')).join(' ');
}

function asciiBytes(s) {
    const out = new Uint8Array(s.length);
    for (let i = 0; i < s.length; i++) out[i] = s.charCodeAt(i) & 0xff;
    return out;
}

function patchModuli(mod) {
    for (const p of CONFIG.rsaPatch) {
        if (p.find.length !== p.replace.length) {
            emit('rsa', { name: p.name, status: 'skip', reason: 'length mismatch' });
            continue;
        }
        let hits;
        try {
            hits = Memory.scanSync(mod.base, mod.size, asciiPattern(p.find));
        } catch (e) {
            emit('rsa', { name: p.name, status: 'error', reason: e.message });
            continue;
        }
        if (hits.length === 0) {
            emit('rsa', { name: p.name, status: 'not-found' });
            continue;
        }
        const bytes = asciiBytes(p.replace);
        let done = 0;
        for (const h of hits) {
            try {
                Memory.protect(h.address, bytes.length, 'rw-');
                h.address.writeByteArray(bytes);
                done += 1;
            } catch (e) {
                emit('rsa', { name: p.name, status: 'write-failed', reason: e.message, at: h.address.toString() });
            }
        }
        emit('rsa', { name: p.name, status: 'patched', count: done, at: hits[0].address.toString() });
    }
}

function hookInternals() {
    const m = findModule(CONFIG.libName);
    if (m === null) return false;
    emit('module', { name: CONFIG.libName, base: m.base.toString(), size: m.size });

    patchModuli(m);

    if (CONFIG.rva.packetReset !== null) hookPacketReset(m.base);
    if (CONFIG.rva.tcpIn !== null) hookTcpIn(m.base);
    if (CONFIG.rva.initOutgoing !== null) hookInitOutgoing(m.base);
    if (CONFIG.rva.clientStreamRead !== null || CONFIG.rva.clientStreamWrite !== null) hookClientStream(m.base);
    if (CONFIG.rva.isaacInit !== null) hookIsaacInit(m.base);
    hookDirectLogin(m.base);

    if (Object.values(CONFIG.rva).every(v => v === null)) note('no RVAs configured — raw sockets only');
    return true;
}

function awaitModule() {
    if (hookInternals()) return;
    note('waiting for ' + CONFIG.libName);
    const dlopen = globalSym('android_dlopen_ext') || globalSym('dlopen');
    if (dlopen === null) {
        note('dlopen not found — cannot wait for module load');
        return;
    }
    Interceptor.attach(dlopen, {
        onEnter(args) { this.path = args[0].isNull() ? '' : args[0].readCString(); },
        onLeave() {
            if (this.path.indexOf('rs2client') !== -1) hookInternals();
        },
    });
}

rpc.exports = {
    init(params) {
        params = params || {};
        // directLogin carries offset defaults in CONFIG; merge so a partial param object
        // (enabled/username/password) does not wipe cfgPtrOffset/credMapOffset.
        const dl = params.directLogin;
        delete params.directLogin;
        Object.assign(CONFIG, params);
        if (dl) Object.assign(CONFIG.directLogin, dl);
        hookSocketLifecycle();
        if (CONFIG.raw) hookRawSockets();
        hookRedirect();
        awaitModule();
        note('capture ready');
    },
};
