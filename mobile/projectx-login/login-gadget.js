'use strict';
// Standalone Project X-Mobile login gadget (script-mode, no PC). Reads username/password written by
// LoginActivity to the app-private file, then drives the client's native BeginLobbyLogin (loginType=1,
// op19 LOBBY) at main-state 10. Login-only: no capture/isolation/sniffing hooks.

const LIB = 'liblibs.hal.system.rs2client.so';
const RVA = { loginManagerCtor: 0x0099b2e4, beginLobbyLogin: 0x009a2800 };
const FILES = '/data/data/com.jagex.runescape.android/files/';
const CREDS = FILES + 'projectx-login.txt';
const STATUS = FILES + 'projectx-login-status.txt';
const LOGIN_STATE = 10;   // main-state at the login screen
const LOBBY_STATE = 0x14;  // main-state when logged into the lobby
const WORLD_STATE = 0x1e;  // main-state when in-world

function sym(n) {
    try { return Module.findGlobalExportByName(n); } catch (e) { return null; }
}

// Emit straight to logcat via liblog, independent of frida's console.log routing.
let _alog = null;
(function () {
    try {
        const w = sym('__android_log_write');
        if (w) {
            const f = new NativeFunction(w, 'int', ['int', 'pointer', 'pointer']);
            const tag = Memory.allocUtf8String('projectx-login');
            _alog = function (m) { try { f(4, tag, Memory.allocUtf8String(m)); } catch (e) {} };
        }
    } catch (e) {}
})();

function log(m) {
    if (_alog !== null) _alog(m);
    try { console.log('[projectx-login] ' + m); } catch (e) {}
}

log('script loaded');

function bytesToStr(ab) {
    const a = new Uint8Array(ab);
    let s = '';
    for (let i = 0; i < a.length; i++) s += String.fromCharCode(a[i]);
    return s;
}

function readFile(path) {
    try { return bytesToStr(File.readAllBytes(path)); } catch (e) {}
    try { const f = new File(path, 'rb'); const b = f.readBytes(); f.close(); return bytesToStr(b); } catch (e) {}
    return readFileLibc(path);
}

function readFileLibc(path) {
    const openP = sym('open'), readP = sym('read'), closeP = sym('close');
    if (openP === null || readP === null || closeP === null) return null;
    try {
        const open = new NativeFunction(openP, 'int', ['pointer', 'int']);
        const read = new NativeFunction(readP, 'long', ['int', 'pointer', 'long']);
        const close = new NativeFunction(closeP, 'int', ['int']);
        const fd = open(Memory.allocUtf8String(path), 0);
        if (fd < 0) return null;
        const buf = Memory.alloc(8192);
        const n = read(fd, buf, 8191).toInt32();
        close(fd);
        if (n <= 0) return null;
        const a = new Uint8Array(buf.readByteArray(n));
        let s = '';
        for (let i = 0; i < a.length; i++) s += String.fromCharCode(a[i]);
        return s;
    } catch (e) { return null; }
}

function readCreds() {
    const txt = readFile(CREDS);
    if (txt === null) return null;
    const nl = txt.indexOf('\n');
    if (nl < 0) return null;
    const user = txt.substring(0, nl).replace(/[\r\n]+$/, '');
    const pass = txt.substring(nl + 1).replace(/[\r\n]+$/, '');
    if (!user || !pass) return null;
    return { user: user, pass: pass };
}

function asciiBytes(s) {
    const o = new Uint8Array(s.length);
    for (let i = 0; i < s.length; i++) o[i] = s.charCodeAt(i) & 0xff;
    return o;
}

const keep = [];
function makeEastlString(s) {
    const str = Memory.alloc(24);
    const b = asciiBytes(s);
    if (b.length < 0x17) {
        if (b.length) str.writeByteArray(b);
        str.add(b.length).writeU8(0);
        str.add(0x17).writeU8(0x17 - b.length);
        keep.push(str);
        return str;
    }
    const buf = Memory.alloc(b.length + 1);
    buf.writeByteArray(b); buf.add(b.length).writeU8(0);
    str.writePointer(buf);
    str.add(8).writeU64(b.length);
    str.add(0x10).writeU64(uint64(b.length).or(uint64('0x8000000000000000')));
    keep.push(str); keep.push(buf);
    return str;
}

function mainState(mgr) {
    try {
        const sess = mgr.add(0x18).readPointer();
        if (sess.isNull()) return -1;
        return sess.add(0x19b40).readS32();
    } catch (e) { return -1; }
}

function ready(mgr) {
    if (mgr === null || mgr.isNull()) return false;
    try {
        if (mainState(mgr) !== LOGIN_STATE) return false;
        if (mgr.add(0x10).readS32() !== 0) return false;
        const sess = mgr.add(0x18).readPointer();
        const connSub = sess.add(0x19420).readPointer();
        if (connSub.isNull()) return false;
        if (connSub.add(0x28).readS32() !== 0) return false;
        return true;
    } catch (e) { return false; }
}

function writeStatus(s) {
    const openP = sym('open'), writeP = sym('write'), closeP = sym('close');
    if (openP === null || writeP === null || closeP === null) return;
    try {
        const open = new NativeFunction(openP, 'int', ['pointer', 'int', 'int']);
        const write = new NativeFunction(writeP, 'long', ['int', 'pointer', 'long']);
        const close = new NativeFunction(closeP, 'int', ['int']);
        const fd = open(Memory.allocUtf8String(STATUS), 1 | 0x40 | 0x200, 0x180);  // O_WRONLY|O_CREAT|O_TRUNC,0600
        if (fd < 0) return;
        const b = Memory.allocUtf8String(s);
        write(fd, b, s.length);
        close(fd);
        log('status -> ' + s);
    } catch (e) {}
}

// After firing, watch the login state machine and record ok/fail to the status file (read by the next
// LoginActivity launch). Success = main-state -> lobby(0x14)/world(0x1e). Failure = the login manager goes
// busy then returns to idle (+0x10==0) still on the login screen; result code at loginMgr+0x184 (2=success).
// Verified @ jag::LoginManager::LoginStepDealWithFirstResponse (0x0099ed00).
function watchResult(mgr) {
    let ticks = 0, seenBusy = false;
    const t = setInterval(() => {
        ticks++;
        try {
            const ms = mainState(mgr);
            if (ms === LOBBY_STATE || ms === WORLD_STATE) { writeStatus('ok'); clearInterval(t); return; }
            const step = mgr.add(0x10).readS32();
            if (step !== 0) seenBusy = true;
            if (seenBusy && step === 0 && ms === LOGIN_STATE) {
                writeStatus('fail:' + mgr.add(0x184).readS32());
                clearInterval(t); return;
            }
        } catch (e) {}
        if (ticks >= 75) { writeStatus('fail:timeout'); clearInterval(t); }  // ~30s
    }, 400);
}

let loginMgr = null, done = false, beginFn = null, creds = null;

function diag(mgr) {
    let ms = -1, l10 = -999, cs = '?', cs28 = -999;
    try { ms = mainState(mgr); } catch (e) {}
    try { l10 = mgr.add(0x10).readS32(); } catch (e) {}
    try {
        const connSub = mgr.add(0x18).readPointer().add(0x19420).readPointer();
        cs = connSub.isNull() ? 'null' : 'ok';
        if (!connSub.isNull()) cs28 = connSub.add(0x28).readS32();
    } catch (e) { cs = 'err'; }
    const raw = readFile(CREDS);
    let cred;
    if (raw === null) cred = 'null(open-failed/missing)';
    else { const nl = raw.indexOf('\n'); cred = 'rawlen=' + raw.length + ' nl=' + nl + ' user=[' + (nl < 0 ? raw : raw.substring(0, nl)) + ']'; }
    const scr = readFile('/data/data/com.jagex.runescape.android/files/loginscript.js');
    log('gate: mainState=' + ms + ' +0x10=' + l10 + ' connSub=' + cs + ' +0x28=' + cs28 +
        ' creds=' + cred + ' scriptFile=' + (scr === null ? 'null' : 'len=' + scr.length));
}

function tryLogin(mgr) {
    if (done || beginFn === null) return false;
    if (creds === null) { creds = readCreds(); if (creds === null) return false; }
    if (!ready(mgr)) return false;
    try {
        const u = makeEastlString(creds.user);
        const p = makeEastlString(creds.pass);
        const out = makeEastlString('');
        beginFn(mgr, u, p, out, 0);
        done = true;
        log("BeginLobbyLogin fired for '" + creds.user + "'");
        watchResult(mgr);
        return true;
    } catch (e) { log('login error: ' + e.message); return false; }
}

function install(base) {
    if (beginFn !== null) return;
    beginFn = new NativeFunction(base.add(RVA.beginLobbyLogin), 'void',
        ['pointer', 'pointer', 'pointer', 'pointer', 'int']);
    Interceptor.attach(base.add(RVA.loginManagerCtor), {
        onEnter(args) { loginMgr = args[0]; log('captured loginMgr ' + loginMgr); },
    });
    let tick = 0;
    const t = setInterval(() => {
        if (done) { clearInterval(t); return; }
        if (loginMgr !== null) {
            if ((tick++ % 4) === 0) diag(loginMgr);
            tryLogin(loginMgr);
        }
    }, 500);
    log('armed (waiting for login screen)');
}

function boot() {
    const m = Process.findModuleByName(LIB);
    if (m !== null) { install(m.base); return; }
    log('waiting for ' + LIB);
    const dl = sym('android_dlopen_ext') || sym('dlopen');
    if (dl === null) { log('no dlopen — cannot wait'); return; }
    Interceptor.attach(dl, {
        onEnter(args) { this.path = args[0].isNull() ? '' : args[0].readCString(); },
        onLeave() {
            if (this.path && this.path.indexOf('rs2client') !== -1) {
                const m = Process.findModuleByName(LIB);
                if (m !== null) install(m.base);
            }
        },
    });
}

boot();
