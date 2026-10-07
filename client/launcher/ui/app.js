// Project X Launcher - Frontend
(function () {
  "use strict";

  const CLIENTS_POLL_MS = 3000;
  // A client mid-startup flips to Active on its own, with no result event to
  // drive a redraw — only a poll reveals it, so poll harder while one is pending.
  const CLIENTS_STARTING_POLL_MS = 1000;
  // Off the Clients view the header and sidebar still show how many clients run,
  // so keep a slow poll going rather than letting that count go stale.
  const CLIENTS_BACKGROUND_POLL_MS = 15000;
  const MAX_LOG_ENTRIES = 500;
  // A discovery sweep can legitimately take seconds (a /proc scan plus a control
  // socket round trip per injected client), so a poll never overlaps the one
  // before it — without this the requests pile up faster than they drain.
  const CLIENTS_REQUEST_TIMEOUT_MS = 15000;
  // Backstop for an action whose result never arrives, so a row can't stay
  // disabled forever. Generous: an inject can sit on a sudo prompt.
  const BUSY_TIMEOUT_MS = 120000;
  const EXPANDED_KEY = "projectx.expandedAccounts";

  const state = {
    config: null,
    sessions: [], // [{user_id, display_name, accounts, session_id, last_account_id}]
    // accountId -> character_info event, null while one is in flight.
    characters: {},
    launchingAccountId: null,
    view: "accounts",
    expanded: loadExpanded(),
    clients: null, // null until the first poll answers
    clientsPollTimer: null,
    clientsPollMs: 0,
    clientsRequestAt: 0,
    busyPids: {}, // pid -> timestamp of the action in flight
    renderedClients: null,
    pluginsSnapshot: null,
    busyPlugins: {}, // channel id -> timestamp of the action in flight
    store: null, // last store_status event
    storeScripts: null, // null until the first listing answers
    storeError: null,
    busyStore: {}, // internalName -> timestamp of the install in flight
    signInDismissed: false, // "Not now" lasts for this run of the launcher only
  };

  const $ = (s) => document.querySelector(s);
  const $$ = (s) => [...document.querySelectorAll(s)];

  const views = {
    accounts: $("#accounts-view"),
    clients: $("#clients-view"),
    scheduler: $("#scheduler-view"),
    plugins: $("#plugins-view"),
    store: $("#store-view"),
    logs: $("#logs-view"),
    settings: $("#settings-view"),
  };
  const navItems = $$(".nav-item[data-view]");

  const topbarVersion = $("#topbar-version");
  const topbarStats = $("#topbar-stats");
  const sidebarVersion = $("#sidebar-version");
  const statusbar = $("#statusbar");

  const accountsList = $("#accounts-list");
  const accountsEmpty = $("#accounts-empty");
  const btnLogin = $("#btn-login");
  const btnExpandAll = $("#btn-expand-all");

  const clientsList = $("#clients-list");
  const clientsEmpty = $("#clients-empty");
  const navClientsCount = $("#nav-clients-count");
  const btnRefreshClients = $("#btn-refresh-clients");

  const pluginsList = $("#plugins-list");
  const pluginsDir = $("#plugins-dir");
  const pluginsOrigin = $("#plugins-origin");
  const pluginsError = $("#plugins-error");
  const btnRefreshPlugins = $("#btn-refresh-plugins");
  const btnOpenScripts = $("#btn-open-scripts");
  const downloadsSection = $("#downloads-section");
  const downloadsList = $("#downloads-list");
  const btnReleasePage = $("#btn-release-page");
  const launcherUpdate = $("#launcher-update");
  const launcherUpdateDetail = $("#launcher-update-detail");
  const btnLauncherUpdate = $("#btn-launcher-update");
  const btnDonate = $("#btn-donate");

  const DONATE_URL = "https://www.paypal.com/donate/?business=Leightkenno8%40icloud.com&item_name=Project+X";

  const storeAccount = $("#store-account");
  const storeOrigin = $("#store-origin");
  const storePairing = $("#store-pairing");
  const storeCode = $("#store-code");
  const storePairingLink = $("#store-pairing-link");
  const storeError = $("#store-error");
  const storeList = $("#store-list");
  const storeEmpty = $("#store-empty");
  const btnStoreSignIn = $("#btn-store-sign-in");
  const btnStoreSignOut = $("#btn-store-sign-out");
  const btnStoreRefresh = $("#btn-store-refresh");
  const btnStorePage = $("#btn-store-page");

  const signInDialog = $("#sign-in-dialog");
  const signInGo = $("#sign-in-go");
  const signInWaiting = $("#sign-in-waiting");
  const signInError = $("#sign-in-error");

  const statusLog = $("#status-log");
  const btnClearLogs = $("#btn-clear-logs");

  const chkCloseAfter = $("#chk-close-after");
  const chkAutoInject = $("#chk-auto-inject");
  const chkDebugLogging = $("#chk-debug-logging");
  const inpLaunchCmd = $("#inp-launch-cmd");
  const btnSaveSettings = $("#btn-save-settings");
  const btnResetSettings = $("#btn-reset-settings");

  function send(msg) {
    window.ipc.postMessage(JSON.stringify(msg));
  }

  // Receive events from Rust
  window.__projectx_callback = function (event) {
    switch (event.type) {
      case "init":
        state.config = event.config;
        state.sessions = event.sessions;
        if (state.sessions.length === 1) state.expanded.add(state.sessions[0].user_id);
        restoreConfigToUI();
        renderAccounts();
        logStatus("Launcher ready");
        // The header shows the launcher version and the update strip must not
        // depend on the Plugins view having been opened.
        requestPlugins(false);
        requestClients(true);
        startClientsPolling();
        break;

      case "sessions_updated":
        state.sessions = event.sessions;
        renderAccounts();
        break;

      case "login_complete":
        state.sessions.push(event.session);
        state.expanded.add(event.session.user_id);
        saveExpanded();
        renderAccounts();
        logStatus("Logged in as " + event.session.display_name, "success");
        break;

      case "login_error":
        logStatus(event.message, "error");
        break;

      case "logout_complete":
        state.sessions = state.sessions.filter((s) => s.user_id !== event.user_id);
        state.expanded.delete(event.user_id);
        saveExpanded();
        renderAccounts();
        logStatus("Logged out");
        break;

      case "character_info":
        state.characters[event.account_id] = event;
        paintKnownFaces(event);
        break;

      case "scheduler_status":
        onSchedulerStatus(event);
        break;

      case "launch_status":
        logStatus(event.message);
        break;

      case "launch_complete":
        state.launchingAccountId = null;
        renderAccounts();
        logStatus("Game launched", "success");
        requestClients(true);
        break;

      case "launch_error":
        state.launchingAccountId = null;
        renderAccounts();
        logStatus(event.message, "error");
        break;

      case "config_saved":
        logStatus("Settings saved", "success");
        break;

      case "clients_list":
        state.clientsRequestAt = 0;
        state.clients = event.clients || [];
        pruneBusyPids();
        renderClients();
        renderStats();
        startClientsPolling();
        break;

      case "plugins_status":
        state.pluginsSnapshot = event.snapshot;
        renderPlugins();
        renderVersion();
        break;

      case "plugin_result":
        delete state.busyPlugins[event.id];
        logStatus(event.message, event.ok ? "success" : "error");
        renderPlugins();
        break;

      case "store_status":
        if (event.signed_in && state.store && !state.store.signed_in) {
          logStatus("Signed in to the store as " + (event.name || "your account"), "success");
        }
        state.store = event;
        if (event.error) logStatus(event.error, "error");
        renderStore();
        renderSignInDialog();
        break;

      case "store_scripts":
        state.storeScripts = event.scripts || [];
        state.storeError = event.error || null;
        renderStore();
        break;

      case "store_result":
        delete state.busyStore[event.internal_name];
        logStatus(event.message, event.ok ? "success" : "error");
        renderStore();
        break;

      case "client_control_result":
        delete state.busyPids[event.pid];
        logStatus("pid " + event.pid + ": " + event.message, event.ok ? "success" : "error");
        // A clients_list event follows from the backend; re-render now so the
        // row's buttons come back without waiting for it.
        state.renderedClients = null;
        renderClients();
        break;
    }
  };

  // ---- Shared helpers ----

  function el(tag, className, text) {
    const node = document.createElement(tag);
    if (className) node.className = className;
    if (text !== undefined) node.textContent = text;
    return node;
  }

  function icon(id, className) {
    const svg = document.createElementNS("http://www.w3.org/2000/svg", "svg");
    svg.setAttribute("class", className || "btn-icon");
    const use = document.createElementNS("http://www.w3.org/2000/svg", "use");
    use.setAttribute("href", "#" + id);
    svg.appendChild(use);
    return svg;
  }

  function button(label, variant, disabled, onClick) {
    const b = el("button", "btn btn-sm" + (variant ? " " + variant : ""), label);
    b.type = "button";
    b.disabled = !!disabled;
    b.addEventListener("click", (e) => {
      e.stopPropagation();
      onClick();
    });
    return b;
  }

  function badge(label, cls) {
    return el("span", "badge" + (cls ? " badge-" + cls : ""), label);
  }

  function plural(n, word) {
    return n + " " + word + (n === 1 ? "" : "s");
  }

  function formatSize(bytes) {
    if (!bytes) return null;
    const mb = bytes / (1024 * 1024);
    return mb >= 1 ? mb.toFixed(1) + " MB" : Math.max(1, Math.round(bytes / 1024)) + " KB";
  }

  function formatClock(date, withSeconds) {
    const parts = [date.getHours(), date.getMinutes()];
    if (withSeconds) parts.push(date.getSeconds());
    return parts.map((n) => String(n).padStart(2, "0")).join(":");
  }

  // Storage can be unavailable in the webview; expansion is only a convenience.
  function loadExpanded() {
    try {
      return new Set(JSON.parse(localStorage.getItem(EXPANDED_KEY) || "[]"));
    } catch (_) {
      return new Set();
    }
  }

  function saveExpanded() {
    try {
      localStorage.setItem(EXPANDED_KEY, JSON.stringify([...state.expanded]));
    } catch (_) {
      // ignored
    }
  }

  /** The chathead when RuneScape has one, otherwise the character's initial. */
  function paintFace(portrait, displayName, known) {
    portrait.innerHTML = "";
    if (known && known.avatar) {
      const img = document.createElement("img");
      img.src = known.avatar;
      img.alt = "";
      portrait.appendChild(img);
      portrait.classList.remove("is-blank");
      return;
    }
    portrait.textContent = (displayName || "?").charAt(0).toUpperCase();
    portrait.classList.add("is-blank");
  }

  function paintKnownFaces(event) {
    $$('.char-face[data-account-id="' + event.account_id + '"]').forEach((portrait) => {
      const row = portrait.parentElement;
      const name = row && row.querySelector(".char-name");
      paintFace(portrait, name && name.textContent, event);
      if (event.total && row && !row.querySelector(".char-stat")) {
        const stat = el("span", "char-stat", "Total " + event.total.toLocaleString("en-GB"));
        row.insertBefore(stat, row.querySelector(".char-name").nextSibling);
      }
    });
  }

  // ---- Scheduler ----

  const schedulerChosen = new Set();
  let schedulerRunning = false;

  function everyCharacter() {
    return state.sessions.flatMap((session) =>
      session.accounts
        .filter((account) => account.displayName)
        .map((account) => ({
          account_id: account.accountId,
          display_name: account.displayName,
          session_name: session.display_name,
        })),
    );
  }

  function renderScheduler() {
    const list = $("#scheduler-list");
    const empty = $("#scheduler-empty");
    const characters = everyCharacter();
    list.innerHTML = "";
    empty.hidden = characters.length > 0;

    characters.forEach((character) => {
      const row = document.createElement("label");
      row.className = "setting sched-row";
      const text = document.createElement("span");
      text.className = "setting-text";
      const name = document.createElement("span");
      name.textContent = character.display_name;
      const under = document.createElement("small");
      under.className = "muted";
      under.textContent = character.session_name || "";
      text.append(name, under);
      const tick = document.createElement("input");
      tick.type = "checkbox";
      tick.className = "switch";
      tick.checked = schedulerChosen.has(character.account_id);
      tick.disabled = schedulerRunning;
      tick.addEventListener("change", () => {
        if (tick.checked) schedulerChosen.add(character.account_id);
        else schedulerChosen.delete(character.account_id);
      });
      row.append(text, tick);
      list.appendChild(row);
    });
  }

  function onSchedulerStatus(event) {
    schedulerRunning = event.running;
    const box = $("#scheduler-status");
    const position =
      event.index === undefined || event.index === null ? "" : ` (${event.index + 1}/${event.total})`;
    box.hidden = false;
    box.textContent = event.message + position;
    box.classList.toggle("notice-warn", event.running);
    $("#btn-scheduler-start").disabled = event.running;
    $("#btn-scheduler-stop").disabled = !event.running;
    if (!event.running) renderScheduler();
    logStatus("Scheduler: " + event.message);
  }

  function startScheduler() {
    const chosen = everyCharacter().filter((c) => schedulerChosen.has(c.account_id));
    if (!chosen.length) {
      logStatus("Scheduler: tick at least one character first", "error");
      return;
    }
    send({
      type: "scheduler_start",
      accounts: chosen.map((c) => ({ account_id: c.account_id, display_name: c.display_name })),
      delay_seconds: Math.max(0, Number($("#sched-delay").value) || 0),
      repeat: $("#sched-repeat").checked,
    });
  }

  // ---- Frame ----

  function showView(name) {
    state.view = name;
    Object.entries(views).forEach(([key, section]) => {
      section.hidden = key !== name;
    });
    navItems.forEach((item) => item.classList.toggle("is-active", item.dataset.view === name));

    if (name === "scheduler") renderScheduler();
    if (name === "clients") requestClients(true);
    if (name === "plugins") requestPlugins(false);
    if (name === "store") requestStore();
    if (name === "settings") restoreConfigToUI();
    if (name === "logs") statusLog.scrollTop = statusLog.scrollHeight;
    startClientsPolling();
  }

  function renderStats() {
    const accounts = state.sessions.length;
    const characters = state.sessions.reduce((n, s) => n + s.accounts.length, 0);
    const parts = [plural(accounts, "account"), plural(characters, "character")];
    if (state.clients) parts.push(state.clients.length + " running");

    topbarStats.textContent = "";
    parts.forEach((part, i) => {
      if (i) topbarStats.appendChild(el("span", "sep", "|"));
      topbarStats.appendChild(document.createTextNode(part));
    });
  }

  function renderVersion() {
    const version = state.pluginsSnapshot && state.pluginsSnapshot.launcher_version;
    if (!version) return;
    topbarVersion.textContent = "Launcher v" + version;
    sidebarVersion.textContent = "v" + version;
  }

  function logStatus(msg, cls) {
    const entry = el("div", "log-entry" + (cls ? " " + cls : ""));
    entry.appendChild(el("span", "log-time", formatClock(new Date(), true)));
    entry.appendChild(el("span", "log-text", msg));

    const pinned = statusLog.scrollHeight - statusLog.scrollTop - statusLog.clientHeight < 24;
    statusLog.appendChild(entry);
    while (statusLog.childElementCount > MAX_LOG_ENTRIES) {
      statusLog.removeChild(statusLog.firstElementChild);
    }
    if (pinned) statusLog.scrollTop = statusLog.scrollHeight;

    statusbar.textContent = msg;
    statusbar.className = "statusbar muted" + (cls ? " " + cls : "");
  }

  // ---- Accounts ----

  function renderAccounts() {
    renderStats();
    accountsList.textContent = "";
    accountsEmpty.hidden = state.sessions.length > 0;

    const allOpen =
      state.sessions.length > 0 && state.sessions.every((s) => state.expanded.has(s.user_id));
    btnExpandAll.textContent = allOpen ? "Collapse all" : "Expand all";
    btnExpandAll.hidden = state.sessions.length === 0;
    btnLogin.textContent = state.sessions.length ? "Add account" : "Login with Jagex";

    state.sessions.forEach((session) => accountsList.appendChild(renderAccount(session)));
  }

  function renderAccount(session) {
    const open = state.expanded.has(session.user_id);
    const wrap = el("div", "acct" + (open ? " is-open" : ""));

    const head = el("div", "acct-head");
    head.setAttribute("role", "button");
    head.setAttribute("tabindex", "0");
    head.setAttribute("aria-expanded", String(open));
    head.appendChild(icon("i-chevron", "chev"));
    head.appendChild(el("span", "acct-name", session.display_name || "Jagex account"));
    head.appendChild(el("span", "acct-meta", plural(session.accounts.length, "character")));

    const actions = el("div", "acct-actions");
    actions.appendChild(
      button("Log out", "btn-danger", !!state.launchingAccountId, () =>
        send({ type: "logout", user_id: session.user_id })
      )
    );
    head.appendChild(actions);

    const toggle = () => {
      if (state.expanded.has(session.user_id)) state.expanded.delete(session.user_id);
      else state.expanded.add(session.user_id);
      saveExpanded();
      renderAccounts();
    };
    head.addEventListener("click", toggle);
    head.addEventListener("keydown", (e) => {
      if (e.target !== head || (e.key !== "Enter" && e.key !== " ")) return;
      e.preventDefault();
      toggle();
    });
    wrap.appendChild(head);

    if (open) wrap.appendChild(renderCharacters(session));
    return wrap;
  }

  function renderCharacters(session) {
    const list = el("div", "chars");
    if (!session.accounts.length) {
      list.appendChild(el("div", "char-empty", "No characters on this account yet."));
      return list;
    }

    session.accounts.forEach((account) => {
      const row = el("div", "char");
      const portrait = el("span", "char-face");
      portrait.dataset.accountId = account.accountId;
      const known = state.characters[account.accountId];
      paintFace(portrait, account.displayName, known);
      row.appendChild(portrait);
      row.appendChild(el("span", "char-name", account.displayName || "Unnamed character"));
      if (known && known.total) {
        row.appendChild(el("span", "char-stat", "Total " + known.total.toLocaleString("en-GB")));
      }
      if (account.displayName && known === undefined) {
        state.characters[account.accountId] = null;
        send({ type: "character_info", account_id: account.accountId, display_name: account.displayName });
      }
      if (session.last_account_id === account.accountId) {
        row.appendChild(badge("Last played", "last"));
      }

      const launchingThis = state.launchingAccountId === account.accountId;
      const play = button(
        launchingThis ? "Launching…" : "Play",
        "btn-accent btn-play",
        !!state.launchingAccountId,
        () => launch(session, account)
      );
      if (!launchingThis) play.prepend(icon("i-play"));
      row.appendChild(play);
      list.appendChild(row);
    });
    return list;
  }

  function launch(session, account) {
    if (state.launchingAccountId) return;
    state.launchingAccountId = account.accountId;
    session.last_account_id = account.accountId;
    renderAccounts();
    logStatus("Launching " + (account.displayName || "character") + "…");
    send({
      type: "launch",
      account_id: account.accountId,
      display_name: account.displayName || "",
    });
  }

  // ---- Clients (Project X engine hot-reload control) ----

  function requestClients(force) {
    const now = Date.now();
    const inFlight =
      state.clientsRequestAt && now - state.clientsRequestAt < CLIENTS_REQUEST_TIMEOUT_MS;
    if (inFlight && !force) return;
    state.clientsRequestAt = now;
    send({ type: "list_clients" });
  }

  function desiredClientsPollMs() {
    if (state.view !== "clients") return CLIENTS_BACKGROUND_POLL_MS;
    return (state.clients || []).some((c) => c.state === "starting")
      ? CLIENTS_STARTING_POLL_MS
      : CLIENTS_POLL_MS;
  }

  function startClientsPolling() {
    const interval = desiredClientsPollMs();
    if (state.clientsPollTimer !== null && state.clientsPollMs === interval) return;
    if (state.clientsPollTimer !== null) clearInterval(state.clientsPollTimer);
    state.clientsPollMs = interval;
    state.clientsPollTimer = setInterval(() => requestClients(false), interval);
  }

  function clientStateMeta(s) {
    switch (s) {
      case "active":
        return { label: "Active", cls: "active" };
      case "unloaded":
        return { label: "Disabled", cls: "" };
      case "not-injected":
        return { label: "Not injected", cls: "" };
      case "starting":
        return { label: "Injecting", cls: "starting" };
      default:
        return { label: "Error", cls: "error" };
    }
  }

  function isBusy(map, key) {
    const startedAt = map[key];
    if (!startedAt) return false;
    if (Date.now() - startedAt > BUSY_TIMEOUT_MS) {
      delete map[key];
      return false;
    }
    return true;
  }

  function pruneBusyPids() {
    const live = new Set((state.clients || []).map((c) => String(c.pid)));
    Object.keys(state.busyPids).forEach((pid) => {
      if (!live.has(pid)) delete state.busyPids[pid];
    });
  }

  function renderClients() {
    const clients = state.clients || [];
    navClientsCount.textContent = String(clients.length);
    navClientsCount.hidden = clients.length === 0;

    // Polls arrive every few seconds; rebuilding identical rows would steal focus
    // from a button and restart the badge animation, so redraw only on change.
    const signature = JSON.stringify(
      clients.map((c) => [
        c.pid,
        c.state,
        c.version,
        c.reloads,
        c.detail,
        isBusy(state.busyPids, c.pid),
      ])
    );
    if (signature === state.renderedClients) return;
    state.renderedClients = signature;

    clientsList.textContent = "";
    clientsEmpty.hidden = clients.length > 0;

    clients.forEach((c) => {
      const meta = clientStateMeta(c.state);
      const busy = isBusy(state.busyPids, c.pid);

      const row = el("div", "row");
      const main = el("div", "row-main");
      const title = el("div", "row-title");
      title.appendChild(el("span", "row-name", "Client " + c.pid));
      title.appendChild(badge(meta.label, meta.cls));
      main.appendChild(title);

      const parts = [];
      if (c.version) parts.push("engine v" + c.version);
      if (c.state === "active" && typeof c.reloads === "number") {
        parts.push(plural(c.reloads, "reload"));
      }
      if (parts.length) main.appendChild(el("div", "row-sub", parts.join(" · ")));
      // A client that never answers its control socket has no version or reload count to show, and
      // the badge alone gives the user nothing to act on — so show what the bootstrap said instead.
      if (c.detail) main.appendChild(el("div", "row-sub row-sub-error", c.detail));
      row.appendChild(main);

      const actions = el("div", "row-actions");
      if (c.state === "not-injected" || c.state === "unloaded") {
        actions.appendChild(
          button(busy ? "…" : "Inject", "btn-accent", busy, () => clientAction(c.pid, "inject_client"))
        );
      } else if (c.state === "active") {
        actions.appendChild(
          button(busy ? "…" : "Reinject", "", busy, () => clientAction(c.pid, "reinject_client"))
        );
        actions.appendChild(
          button("Uninject", "btn-danger", busy, () => clientAction(c.pid, "uninject_client"))
        );
      }
      // "starting" and "error" rows expose no actions: there is no socket to
      // command yet, and re-injecting an already-mapped bootstrap is never right.
      row.appendChild(actions);
      clientsList.appendChild(row);
    });
  }

  function clientAction(pid, type) {
    state.busyPids[pid] = Date.now();
    state.renderedClients = null;
    renderClients();
    send({ type: type, pid: pid });
  }

  // ---- Plugins (managed script jars) ----

  function requestPlugins(force) {
    send({ type: "plugins_status", force: !!force });
  }

  function pluginAction(id, type) {
    state.busyPlugins[id] = Date.now();
    renderPlugins();
    send({ type: type, id: id });
  }

  function pluginStateMeta(p) {
    if (p.installed_version && p.update_available) return { label: "Update ready", cls: "update" };
    if (p.installed_version) return { label: "Installed", cls: "managed" };
    if (p.local_jar) return { label: "Your build", cls: "local" };
    return { label: "Not installed", cls: "" };
  }

  function metaItem(label, value) {
    const span = el("span", "", label + " ");
    span.appendChild(el("b", "", value));
    return span;
  }

  function renderPluginRow(p) {
    const meta = pluginStateMeta(p);
    const busy = isBusy(state.busyPlugins, p.id);

    const row = el("div", "plugin");
    const title = el("div", "row-title");
    title.appendChild(el("span", "row-name", p.name));
    title.appendChild(badge(meta.label, meta.cls));
    row.appendChild(title);
    if (p.description) row.appendChild(el("p", "plugin-desc", p.description));

    const metaLine = el("div", "plugin-meta");
    if (p.installed_version) metaLine.appendChild(metaItem("installed", "v" + p.installed_version));
    metaLine.appendChild(metaItem("latest", p.available_version ? "v" + p.available_version : "unknown"));
    const size = formatSize(p.available_size);
    if (size) metaLine.appendChild(metaItem("size", size));
    row.appendChild(metaLine);

    // A jar the launcher did not put there is the user's own build. Two jars for
    // one channel put every script class on the engine's scan path twice.
    if (p.local_jar) {
      row.appendChild(
        el(
          "div",
          "notice notice-warn",
          p.installed_version
            ? "Also in the folder: " + p.local_jar + " — remove it, or the engine loads every script in this channel twice."
            : "Currently using your own build: " + p.local_jar + ". Installing replaces it."
        )
      );
    }

    const foot = el("div", "plugin-foot");
    const auto = el("label", "inline-check");
    const toggle = document.createElement("input");
    toggle.type = "checkbox";
    toggle.className = "switch";
    toggle.checked = !!p.auto_update;
    toggle.disabled = busy;
    toggle.addEventListener("change", () => {
      if (toggle.checked) state.busyPlugins[p.id] = Date.now();
      send({ type: "plugin_set_auto", id: p.id, enabled: toggle.checked });
    });
    auto.appendChild(toggle);
    auto.appendChild(el("span", "", "Keep updated"));
    foot.appendChild(auto);

    const actions = el("div", "row-actions");
    const canInstall = !!p.available_version;
    if (p.update_available || !p.installed_version) {
      actions.appendChild(
        button(busy ? "…" : p.update_available ? "Update" : "Install", "btn-accent", busy || !canInstall, () =>
          pluginAction(p.id, "plugin_install")
        )
      );
    }
    if (p.installed_version) {
      actions.appendChild(button("Remove", "btn-danger", busy, () => pluginAction(p.id, "plugin_remove")));
    }
    foot.appendChild(actions);
    row.appendChild(foot);
    return row;
  }

  // Clipboard access in the webview is not guaranteed, so fall back to the
  // selection-based copy rather than leaving the button silently dead.
  function copyText(text) {
    const done = () => logStatus("Link copied", "success");
    if (navigator.clipboard && navigator.clipboard.writeText) {
      navigator.clipboard.writeText(text).then(done, () => legacyCopy(text, done));
    } else {
      legacyCopy(text, done);
    }
  }

  function legacyCopy(text, done) {
    const field = document.createElement("textarea");
    field.value = text;
    field.setAttribute("readonly", "");
    field.style.position = "fixed";
    field.style.opacity = "0";
    document.body.appendChild(field);
    field.select();
    const copied = document.execCommand && document.execCommand("copy");
    document.body.removeChild(field);
    if (copied) done();
    else logStatus("Could not copy — the link is " + text, "error");
  }

  function renderDownload(d) {
    const row = el("div", "row");
    const main = el("div", "row-main");
    main.appendChild(el("span", "row-name", d.label || d.platform));
    const size = formatSize(d.size);
    main.appendChild(el("span", "row-sub", size ? d.file + " · " + size : d.file));
    row.appendChild(main);

    const actions = el("div", "row-actions");
    actions.appendChild(button("Copy link", "", false, () => copyText(d.url)));
    actions.appendChild(button("Download", "btn-accent", false, () => send({ type: "open_url", url: d.url })));
    row.appendChild(actions);
    return row;
  }

  // The launcher cannot replace itself while it is running, so this is a prompt
  // and a link rather than an install.
  function renderLauncherUpdate(snap) {
    const update = snap.launcher_update;
    launcherUpdate.hidden = !update;
    if (!update) return;
    const size = formatSize(update.size);
    launcherUpdateDetail.textContent =
      "You have " + snap.launcher_version + " · " +
      (snap.release_tag || update.version || "latest") +
      (size ? " · " + size : "");
  }

  function renderPlugins() {
    const snap = state.pluginsSnapshot;
    if (!snap) return;

    pluginsDir.textContent = snap.scripts_dir;

    pluginsOrigin.textContent = "";
    pluginsOrigin.appendChild(el("b", "", snap.source_url.replace(/^https?:\/\//, "")));
    const details = [];
    if (snap.release_tag) details.push(snap.release_tag);
    // With an error in hand the notice says what went wrong; claiming a check is
    // still running would contradict it.
    if (snap.checked_at) details.push("checked " + formatClock(new Date(snap.checked_at * 1000)));
    else if (!snap.error) details.push("checking…");
    if (details.length) pluginsOrigin.appendChild(document.createTextNode(" · " + details.join(" · ")));

    pluginsError.hidden = !snap.error;
    if (snap.error) pluginsError.textContent = snap.error;

    pluginsList.textContent = "";
    (snap.plugins || []).forEach((p) => pluginsList.appendChild(renderPluginRow(p)));

    // Nothing is published until a release exists; an empty section would just
    // read as broken.
    downloadsSection.hidden = !snap.release_url;
    downloadsList.textContent = "";
    (snap.launcher || []).forEach((d) => downloadsList.appendChild(renderDownload(d)));

    renderLauncherUpdate(snap);
  }

  // ---- Store ----

  function requestStore() {
    send({ type: "store_status" });
    send({ type: "store_scripts" });
  }

  function renderStore() {
    const status = state.store;
    const signedIn = !!(status && status.signed_in);

    storeAccount.textContent = signedIn ? status.name || "Signed in" : "Not signed in";
    storeOrigin.textContent = status ? status.store.replace(/^https?:\/\//, "") : "…";
    btnStoreSignIn.hidden = signedIn;
    btnStoreSignOut.hidden = !signedIn;

    const pairing = status && status.pairing;
    storePairing.hidden = !pairing;
    if (pairing) {
      storeCode.textContent = pairing.code;
      storePairingLink.dataset.url = pairing.url;
    }
    btnStoreSignIn.disabled = !!pairing;
    $("#store-sign-in-label").textContent = pairing ? "Waiting for approval…" : "Sign in with Discord";

    storeError.hidden = !state.storeError;
    if (state.storeError) storeError.textContent = state.storeError;

    storeList.textContent = "";
    const scripts = state.storeScripts || [];
    scripts.forEach((s) => storeList.appendChild(renderStoreRow(s)));
    // An empty list only means "nothing to buy" once the store has answered.
    storeEmpty.hidden = !(state.storeScripts && scripts.length === 0 && !state.storeError);
  }

  function renderStoreRow(s) {
    const busy = isBusy(state.busyStore, s.internal_name);
    const installed = s.install_state === "current";
    const outdated = s.install_state === "outdated";
    const row = el("div", "plugin");

    const title = el("div", "row-title");
    title.appendChild(el("span", "row-name", s.name));
    if (s.pending) title.appendChild(badge("Trial ready", "ok"));
    else if (s.owned) title.appendChild(badge(s.trial ? "Trial" : "Owned", "ok"));
    else title.appendChild(badge("Not owned", "warn"));
    if (installed) title.appendChild(badge("Installed", "ok"));
    if (outdated) title.appendChild(badge("Update available", "warn"));
    row.appendChild(title);

    const metaLine = el("div", "plugin-meta");
    metaLine.appendChild(metaItem("version", "v" + s.version));
    if (s.owned && s.expires_at && !s.pending) {
      metaLine.appendChild(metaItem(s.trial ? "trial ends" : "until", formatClock(new Date(s.expires_at))));
    }
    row.appendChild(metaLine);

    // The point of installing before the game: the hour has not started yet.
    if (s.pending) {
      row.appendChild(
        el(
          "p",
          "plugin-desc",
          "Your trial hour starts when you launch the game with Project X loaded, not now. Install it first."
        )
      );
    }

    const actions = el("div", "row-actions");
    if (s.owned) {
      const label = busy ? "…" : outdated ? "Update" : installed ? "Reinstall" : "Install";
      actions.appendChild(
        button(label, "btn-accent", busy, () => {
          state.busyStore[s.internal_name] = Date.now();
          send({ type: "store_install", internal_name: s.internal_name });
          renderStore();
        })
      );
    }
    if (s.store_url) {
      actions.appendChild(button(s.owned ? "View" : "Buy", "", false, () => send({ type: "open_url", url: s.store_url })));
    }
    const foot = el("div", "plugin-foot");
    foot.appendChild(actions);
    row.appendChild(foot);
    return row;
  }

  // Asked on every launch while signed out: linking the account is what makes bought scripts arrive.
  function renderSignInDialog() {
    const status = state.store;
    if (!status) return;

    if (status.signed_in) {
      if (signInDialog.open) signInDialog.close();
      return;
    }
    if (state.signInDismissed) return;

    const pairing = status.pairing;
    signInWaiting.hidden = !pairing;
    if (pairing) {
      $("#sign-in-code").textContent = pairing.code;
      $("#sign-in-reopen").dataset.url = pairing.url;
    }
    signInGo.disabled = !!pairing;
    $("#sign-in-go-label").textContent = pairing ? "Waiting for approval…" : "Sign in with Discord";

    signInError.hidden = !status.error;
    if (status.error) signInError.textContent = status.error;

    if (!signInDialog.open) signInDialog.showModal();
  }

  function dismissSignIn() {
    state.signInDismissed = true;
    if (signInDialog.open) signInDialog.close();
  }

  // ---- Settings ----

  function restoreConfigToUI() {
    if (!state.config) return;
    chkCloseAfter.checked = !!state.config.close_after_launch;
    chkAutoInject.checked = !!state.config.auto_inject_projectx;
    chkDebugLogging.checked = !!state.config.debug_logging;
    inpLaunchCmd.value = state.config.custom_launch_command || "";
  }

  function saveSettings() {
    if (!state.config) return;
    state.config.close_after_launch = chkCloseAfter.checked;
    state.config.auto_inject_projectx = chkAutoInject.checked;
    state.config.debug_logging = chkDebugLogging.checked;
    state.config.custom_launch_command = inpLaunchCmd.value || null;
    send({ type: "save_config", config: state.config });
  }

  // ---- Wiring ----

  navItems.forEach((item) => item.addEventListener("click", () => showView(item.dataset.view)));
  $("#btn-scheduler-start").addEventListener("click", startScheduler);
  $("#btn-scheduler-stop").addEventListener("click", () => send({ type: "scheduler_stop" }));

  btnLogin.addEventListener("click", () => send({ type: "login" }));
  btnExpandAll.addEventListener("click", () => {
    const allOpen = state.sessions.every((s) => state.expanded.has(s.user_id));
    state.sessions.forEach((s) => {
      if (allOpen) state.expanded.delete(s.user_id);
      else state.expanded.add(s.user_id);
    });
    saveExpanded();
    renderAccounts();
  });

  btnRefreshClients.addEventListener("click", () => requestClients(true));

  btnStoreSignIn.addEventListener("click", () => send({ type: "store_sign_in" }));
  signInGo.addEventListener("click", () => send({ type: "store_sign_in" }));
  $("#sign-in-later").addEventListener("click", dismissSignIn);
  signInDialog.addEventListener("cancel", (e) => {
    e.preventDefault();
    dismissSignIn();
  });
  $("#sign-in-reopen").addEventListener("click", (e) => {
    e.preventDefault();
    const url = e.currentTarget.dataset.url;
    if (url) send({ type: "open_url", url: url });
  });
  btnStoreSignOut.addEventListener("click", () => {
    // Signing out on purpose is an answer; the prompt waits for the next launch.
    state.signInDismissed = true;
    send({ type: "store_sign_out" });
  });
  btnStoreRefresh.addEventListener("click", requestStore);
  btnStorePage.addEventListener("click", () => {
    const base = state.store && state.store.store;
    if (base) send({ type: "open_url", url: base + "/rs3/store" });
  });
  storePairingLink.addEventListener("click", (e) => {
    e.preventDefault();
    if (storePairingLink.dataset.url) send({ type: "open_url", url: storePairingLink.dataset.url });
  });

  btnRefreshPlugins.addEventListener("click", () => requestPlugins(true));
  btnOpenScripts.addEventListener("click", () => send({ type: "plugins_open_dir" }));
  btnReleasePage.addEventListener("click", () => {
    const url = state.pluginsSnapshot && state.pluginsSnapshot.release_url;
    if (url) send({ type: "open_url", url: url });
  });
  btnLauncherUpdate.addEventListener("click", () => {
    const update = state.pluginsSnapshot && state.pluginsSnapshot.launcher_update;
    if (update) send({ type: "open_url", url: update.url });
  });
  btnDonate.addEventListener("click", () => send({ type: "open_url", url: DONATE_URL }));

  btnClearLogs.addEventListener("click", () => {
    statusLog.textContent = "";
    statusbar.textContent = "";
  });

  btnSaveSettings.addEventListener("click", saveSettings);
  btnResetSettings.addEventListener("click", restoreConfigToUI);

  document.addEventListener("keydown", (e) => {
    if (e.key === "Escape" && state.view !== "accounts") showView("accounts");
  });

  // Signal the Rust backend that the frontend is ready. The backend replies with
  // the "init" event (config + saved sessions), so the payload always lands
  // after __projectx_callback is installed.
  send({ type: "ready" });
})();
