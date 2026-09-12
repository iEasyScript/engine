// Project X Launcher - Frontend
(function () {
  "use strict";

  const CLIENTS_POLL_MS = 3000;
  // A client mid-startup flips to Active on its own, with no result event to
  // drive a redraw — only a poll reveals it, so poll harder while one is pending.
  const CLIENTS_STARTING_POLL_MS = 1000;
  const MAX_LOG_ENTRIES = 200;
  const DEFAULT_CONFIG_PORT = 8829;
  // A discovery sweep can legitimately take seconds (a /proc scan plus a control
  // socket round trip per injected client), so a poll never overlaps the one
  // before it — without this the requests pile up faster than they drain.
  const CLIENTS_REQUEST_TIMEOUT_MS = 15000;
  // Backstop for a control action whose result never arrives, so a row can't
  // stay disabled forever. Generous: an inject can sit on a sudo prompt.
  const BUSY_TIMEOUT_MS = 120000;

  let state = {
    config: null,
    sessions: [], // [{user_id, display_name, accounts, session_id, last_account_id}]
    mode: "Live",
    launching: false,
    view: "play", // "play" | "clients" | "plugins"
    clients: [], // [{pid, state, version, reloads}]
    clientsPollTimer: null,
    clientsPollMs: 0, // interval the running poll timer was created with
    clientsRequestAt: 0, // timestamp of the in-flight list request, 0 = none
    busyPids: {}, // pid -> timestamp of the action in flight
    renderedClients: null, // signature of what the clients list currently shows
    pluginsSnapshot: null, // last plugins_status event
    busyPlugins: {}, // channel id -> timestamp of the action in flight
  };

  const $ = (s) => document.querySelector(s);
  const $$ = (s) => [...document.querySelectorAll(s)];

  const noAccounts = $("#no-accounts");
  const accountPanel = $("#account-panel");
  const selAccount = $("#sel-account");
  const selCharacter = $("#sel-character");
  const btnPlay = $("#btn-play");
  const playLabel = $(".play-label");
  const btnLogin = $("#btn-login");
  const btnAddAccount = $("#btn-add-account");
  const btnLogout = $("#btn-logout");
  const btnSettings = $("#btn-settings");
  const btnCloseSettings = $("#btn-close-settings");
  const btnCancelSettings = $("#btn-cancel-settings");
  const btnSaveSettings = $("#btn-save-settings");
  const settingsModal = $("#settings-modal");
  const statusLog = $("#status-log");
  const chkCloseAfter = $("#chk-close-after");
  const chkAutoInject = $("#chk-auto-inject");
  const chkDebugLogging = $("#chk-debug-logging");
  const inpLaunchCmd = $("#inp-launch-cmd");
  const btnClients = $("#btn-clients");
  const navItems = $$(".nav-item[data-view]");
  const btnRefreshClients = $("#btn-refresh-clients");
  const playView = $("#play-view");
  const clientsView = $("#clients-view");
  const clientsList = $("#clients-list");
  const clientsEmpty = $("#clients-empty");
  const navClientsCount = $("#nav-clients-count");
  const btnPlugins = $("#btn-plugins");
  const btnRefreshPlugins = $("#btn-refresh-plugins");
  const btnOpenScripts = $("#btn-open-scripts");
  const pluginsView = $("#plugins-view");
  const pluginsList = $("#plugins-list");
  const pluginsDir = $("#plugins-dir");
  const pluginsOrigin = $("#plugins-origin");
  const pluginsError = $("#plugins-error");
  const downloadsSection = $("#downloads-section");
  const downloadsList = $("#downloads-list");
  const btnReleasePage = $("#btn-release-page");
  const launcherUpdate = $("#launcher-update");
  const launcherUpdateDetail = $("#launcher-update-detail");
  const btnLauncherUpdate = $("#btn-launcher-update");

  function send(msg) {
    window.ipc.postMessage(JSON.stringify(msg));
  }


  // Receive events from Rust
  window.__projectx_callback = function (event) {
    switch (event.type) {
      case "init":
        state.config = event.config;
        state.sessions = event.sessions;
        restoreConfigToUI();
        updateUI();
        logStatus("Launcher ready");
        break;

      case "sessions_updated": {
        const prevCharacter = selCharacter.value;
        state.sessions = event.sessions;
        updateUI();
        // Keep an in-flight manual character pick that survived the refresh.
        if (
          prevCharacter &&
          [...selCharacter.options].some((o) => o.value === prevCharacter)
        ) {
          selCharacter.value = prevCharacter;
        }
        break;
      }

      case "login_complete":
        state.sessions.push(event.session);
        updateUI();
        logStatus("Login successful", "success");
        break;

      case "login_error":
        logStatus(event.message, "error");
        break;

      case "logout_complete":
        state.sessions = state.sessions.filter(
          (s) => s.user_id !== event.user_id
        );
        updateUI();
        logStatus("Logged out");
        break;

      case "launch_status":
        logStatus(event.message);
        break;

      case "launch_complete":
        state.launching = false;
        updatePlayButton();
        logStatus("Game launched", "success");
        break;

      case "launch_error":
        state.launching = false;
        updatePlayButton();
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
        if (state.view === "clients") startClientsPolling();
        break;

      case "plugins_status":
        state.pluginsSnapshot = event.snapshot;
        renderPlugins();
        break;

      case "plugin_result":
        delete state.busyPlugins[event.id];
        logStatus(event.message, event.ok ? "success" : "error");
        renderPlugins();
        break;

      case "client_control_result":
        // The action finished; clear its busy flag and report.
        delete state.busyPids[event.pid];
        logStatus(
          "pid " + event.pid + ": " + event.message,
          event.ok ? "success" : "error"
        );
        // A ClientsList event follows from the backend; re-render to drop the
        // spinner immediately even before it arrives.
        renderClients();
        break;
    }
  };

  function restoreConfigToUI() {
    if (!state.config) return;

    chkCloseAfter.checked = state.config.close_after_launch;
    chkAutoInject.checked = !!state.config.auto_inject_projectx;
    chkDebugLogging.checked = !!state.config.debug_logging;
    inpLaunchCmd.value = state.config.custom_launch_command || "";
  }

  function updateUI() {
    const signedIn = state.sessions.length > 0;

    noAccounts.hidden = signedIn;
    accountPanel.hidden = !signedIn;
    btnPlay.hidden = !signedIn;

    if (!accountPanel.hidden) {
      const prevAccount = selAccount.value;
      selAccount.innerHTML = "";
      state.sessions.forEach((s) => {
        const opt = document.createElement("option");
        opt.value = s.user_id;
        opt.textContent = s.display_name;
        selAccount.appendChild(opt);
      });
      if (prevAccount && [...selAccount.options].some((o) => o.value === prevAccount)) {
        selAccount.value = prevAccount;
      }
      updateCharacters();
    }

    if (state.config) {
      chkCloseAfter.checked = state.config.close_after_launch;
      chkAutoInject.checked = !!state.config.auto_inject_projectx;
      chkDebugLogging.checked = !!state.config.debug_logging;
      inpLaunchCmd.value = state.config.custom_launch_command || "";
    }
  }

  function updateCharacters() {
    const session = state.sessions.find((s) => s.user_id === selAccount.value);
    selCharacter.innerHTML = "";
    if (session) {
      session.accounts.forEach((a) => {
        const opt = document.createElement("option");
        opt.value = a.accountId;
        opt.textContent = a.displayName || "Unnamed";
        selCharacter.appendChild(opt);
      });
      const last = session.last_account_id;
      if (last && [...selCharacter.options].some((o) => o.value === last)) {
        selCharacter.value = last;
      }
    }
  }

  function logStatus(msg, cls) {
    const entry = document.createElement("div");
    entry.className = "log-entry" + (cls ? " " + cls : "");

    const time = document.createElement("span");
    time.className = "log-time";
    const now = new Date();
    time.textContent = [now.getHours(), now.getMinutes(), now.getSeconds()]
      .map((n) => String(n).padStart(2, "0"))
      .join(":");

    const text = document.createElement("span");
    text.className = "log-text";
    text.textContent = msg;

    entry.appendChild(time);
    entry.appendChild(text);
    statusLog.appendChild(entry);

    while (statusLog.childElementCount > MAX_LOG_ENTRIES) {
      statusLog.removeChild(statusLog.firstElementChild);
    }
    statusLog.scrollTop = statusLog.scrollHeight;
  }

  function saveServerModeToConfig() {
    if (!state.config) return;
    state.config.server_mode = state.mode;
    state.config.custom_server_host = null;
    state.config.custom_server_port = null;
    state.config.custom_config_uri = null;
    send({ type: "save_config", config: state.config });
  }

  // ---- Clients panel (Project X engine hot-reload control) ----

  // The banner buttons toggle between full-screen views rather than stacking
  // panels, so exactly one is ever visible and only the visible one polls.
  function showView(name) {
    state.view = name;
    playView.hidden = name !== "play";
    clientsView.hidden = name !== "clients";
    pluginsView.hidden = name !== "plugins";
    navItems.forEach((item) => {
      item.classList.toggle("is-active", item.dataset.view === name);
    });

    if (name === "clients") {
      requestClients();
      startClientsPolling();
    } else {
      stopClientsPolling();
    }
    if (name === "plugins") {
      requestPlugins(false);
    }
  }


  function requestClients(force) {
    const now = Date.now();
    const inFlight =
      state.clientsRequestAt && now - state.clientsRequestAt < CLIENTS_REQUEST_TIMEOUT_MS;
    if (inFlight && !force) return;
    state.clientsRequestAt = now;
    send({ type: "list_clients" });
  }

  function startClientsPolling() {
    const interval = state.clients.some((c) => c.state === "starting")
      ? CLIENTS_STARTING_POLL_MS
      : CLIENTS_POLL_MS;
    if (state.clientsPollTimer !== null && state.clientsPollMs === interval) return;

    stopClientsPolling();
    state.clientsPollMs = interval;
    state.clientsPollTimer = setInterval(() => {
      if (state.view === "clients") requestClients();
    }, interval);
  }

  function stopClientsPolling() {
    if (state.clientsPollTimer !== null) {
      clearInterval(state.clientsPollTimer);
      state.clientsPollTimer = null;
    }
    state.clientsPollMs = 0;
  }

  // Map an engine state to a human label + CSS modifier class.
  function clientStateMeta(s) {
    switch (s) {
      case "active":
        return { label: "Active", cls: "active" };
      case "unloaded":
        return { label: "Disabled", cls: "unloaded" };
      case "not-injected":
        return { label: "Not injected", cls: "not-injected" };
      case "starting":
        return { label: "Injecting", cls: "starting" };
      default:
        return { label: "Error", cls: "error" };
    }
  }

  // An action is busy until its result arrives, or until it times out — a lost
  // result must not disable a row permanently.
  function isBusy(pid) {
    const startedAt = state.busyPids[pid];
    if (!startedAt) return false;
    if (Date.now() - startedAt > BUSY_TIMEOUT_MS) {
      delete state.busyPids[pid];
      return false;
    }
    return true;
  }

  function pruneBusyPids() {
    const live = new Set(state.clients.map((c) => String(c.pid)));
    Object.keys(state.busyPids).forEach((pid) => {
      if (!live.has(pid)) delete state.busyPids[pid];
    });
  }

  function clientsSignature() {
    return JSON.stringify(
      state.clients.map((c) => [c.pid, c.state, c.version, c.reloads, isBusy(c.pid)])
    );
  }

  function renderClients() {
    // The panel re-polls every few seconds; rebuilding identical rows would
    // steal keyboard focus from an action button and restart the badge
    // animation each time, so only redraw when something actually changed.
    const signature = clientsSignature();
    if (signature === state.renderedClients) return;
    state.renderedClients = signature;

    clientsList.innerHTML = "";

    // The rail badge is the only clients signal visible from the other views.
    navClientsCount.textContent = String(state.clients.length);
    navClientsCount.hidden = state.clients.length === 0;

    if (!state.clients.length) {
      clientsEmpty.hidden = false;
      return;
    }
    clientsEmpty.hidden = true;

    state.clients.forEach((c) => {
      const meta = clientStateMeta(c.state);
      const busy = isBusy(c.pid);

      const row = document.createElement("div");
      row.className = "client";

      const info = document.createElement("div");
      info.className = "client-info";

      const top = document.createElement("div");
      top.className = "client-top";
      const badge = document.createElement("span");
      badge.className = "badge badge-" + meta.cls;
      badge.textContent = meta.label;
      const pidLabel = document.createElement("span");
      pidLabel.className = "client-pid";
      pidLabel.textContent = "pid " + c.pid;
      top.appendChild(badge);
      top.appendChild(pidLabel);

      const parts = [];
      if (c.version) parts.push("v" + c.version);
      if (c.state === "active" && typeof c.reloads === "number") {
        parts.push(c.reloads + " reload" + (c.reloads === 1 ? "" : "s"));
      }

      info.appendChild(top);
      if (parts.length) {
        const sub = document.createElement("div");
        sub.className = "client-sub";
        sub.textContent = parts.join(" · ");
        info.appendChild(sub);
      }

      const actions = document.createElement("div");
      actions.className = "client-actions";

      if (c.state === "not-injected" || c.state === "unloaded") {
        actions.appendChild(
          clientButton("Inject", "btn-brass", busy, () =>
            clientAction(c.pid, "inject_client")
          )
        );
      } else if (c.state === "active") {
        actions.appendChild(
          clientButton("Reinject", "btn-iron", busy, () =>
            clientAction(c.pid, "reinject_client")
          )
        );
        actions.appendChild(
          clientButton("Uninject", "btn-ghost-danger", busy, () =>
            clientAction(c.pid, "uninject_client")
          )
        );
      }
      // "starting" and "error" rows expose no actions (no socket to command yet,
      // and re-injecting an already-mapped bootstrap is never the right move).

      row.appendChild(info);
      row.appendChild(actions);
      clientsList.appendChild(row);
    });
  }

  function clientButton(label, variant, busy, onClick) {
    const b = document.createElement("button");
    b.type = "button";
    b.className = "btn btn-sm " + variant;
    b.textContent = busy ? "…" : label;
    b.disabled = busy;
    b.addEventListener("click", onClick);
    return b;
  }

  function clientAction(pid, type) {
    state.busyPids[pid] = Date.now();
    renderClients();
    send({ type: type, pid: pid });
  }

  // ---- Plugins panel (managed script jars) ----

  function requestPlugins(force) {
    send({ type: "plugins_status", force: !!force });
  }

  function isPluginBusy(id) {
    const startedAt = state.busyPlugins[id];
    if (!startedAt) return false;
    if (Date.now() - startedAt > BUSY_TIMEOUT_MS) {
      delete state.busyPlugins[id];
      return false;
    }
    return true;
  }

  function pluginAction(id, type) {
    state.busyPlugins[id] = Date.now();
    renderPlugins();
    send({ type: type, id: id });
  }

  function formatSize(bytes) {
    if (!bytes) return null;
    const mb = bytes / (1024 * 1024);
    return mb >= 1 ? mb.toFixed(1) + " MB" : Math.max(1, Math.round(bytes / 1024)) + " KB";
  }

  function formatClock(unixSeconds) {
    const d = new Date(unixSeconds * 1000);
    return [d.getHours(), d.getMinutes()]
      .map((n) => String(n).padStart(2, "0"))
      .join(":");
  }

  // Badge + action wording for a row, derived from what is on disk versus what
  // the tracked release offers.
  function pluginStateMeta(p) {
    if (p.installed_version && p.update_available) {
      return { label: "Update ready", cls: "update" };
    }
    if (p.installed_version) return { label: "Managed", cls: "managed" };
    if (p.local_jar) return { label: "Your build", cls: "local" };
    return { label: "Not installed", cls: "absent" };
  }

  function metaItem(label, value) {
    const span = document.createElement("span");
    span.appendChild(document.createTextNode(label + " "));
    const strong = document.createElement("b");
    strong.textContent = value;
    span.appendChild(strong);
    return span;
  }

  function pluginToggle(p, busy) {
    const label = document.createElement("label");
    label.className = "check";

    const input = document.createElement("input");
    input.type = "checkbox";
    input.checked = !!p.auto_update;
    input.disabled = busy;
    input.addEventListener("change", () => {
      if (input.checked) state.busyPlugins[p.id] = Date.now();
      send({ type: "plugin_set_auto", id: p.id, enabled: input.checked });
    });

    const box = document.createElement("span");
    box.className = "check-box";

    const text = document.createElement("span");
    text.className = "check-text";
    text.textContent = "Keep updated";

    label.appendChild(input);
    label.appendChild(box);
    label.appendChild(text);
    return label;
  }

  function pluginButton(label, variant, disabled, onClick) {
    const b = document.createElement("button");
    b.type = "button";
    b.className = "btn btn-sm " + variant;
    b.textContent = label;
    b.disabled = disabled;
    b.addEventListener("click", onClick);
    return b;
  }

  function renderPluginRow(p) {
    const meta = pluginStateMeta(p);
    const busy = isPluginBusy(p.id);

    const row = document.createElement("div");
    row.className = "plugin";

    const head = document.createElement("div");
    head.className = "plugin-head";
    const name = document.createElement("span");
    name.className = "plugin-name";
    name.textContent = p.name;
    const badge = document.createElement("span");
    badge.className = "badge badge-" + meta.cls;
    badge.textContent = meta.label;
    head.appendChild(name);
    head.appendChild(badge);
    row.appendChild(head);

    const desc = document.createElement("p");
    desc.className = "plugin-desc";
    desc.textContent = p.description;
    row.appendChild(desc);

    const metaLine = document.createElement("div");
    metaLine.className = "plugin-meta";
    if (p.installed_version) {
      metaLine.appendChild(metaItem("installed", "v" + p.installed_version));
    }
    metaLine.appendChild(
      metaItem("latest", p.available_version ? "v" + p.available_version : "unknown")
    );
    const size = formatSize(p.available_size);
    if (size) metaLine.appendChild(metaItem("size", size));
    row.appendChild(metaLine);

    // A jar the launcher did not put there is the user's own build. Two jars for
    // one channel means every script class is on the engine's scan path twice,
    // so this is worth saying out loud rather than silently deleting.
    if (p.local_jar) {
      const warn = document.createElement("div");
      warn.className = "plugin-warn";
      warn.textContent = p.installed_version
        ? "Also in the folder: " + p.local_jar +
          " — remove it, or the engine loads every script in this channel twice."
        : "Currently using your own build: " + p.local_jar +
          ". Installing replaces it.";
      row.appendChild(warn);
    }

    const foot = document.createElement("div");
    foot.className = "plugin-foot";
    foot.appendChild(pluginToggle(p, busy));

    const actions = document.createElement("div");
    actions.className = "plugin-actions";
    const canInstall = !!p.available_version;
    if (p.update_available) {
      actions.appendChild(
        pluginButton(busy ? "…" : "Update", "btn-brass", busy || !canInstall, () =>
          pluginAction(p.id, "plugin_install")
        )
      );
    } else if (!p.installed_version) {
      actions.appendChild(
        pluginButton(busy ? "…" : "Install", "btn-brass", busy || !canInstall, () =>
          pluginAction(p.id, "plugin_install")
        )
      );
    }
    if (p.installed_version) {
      actions.appendChild(
        pluginButton("Remove", "btn-ghost-danger", busy, () =>
          pluginAction(p.id, "plugin_remove")
        )
      );
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
    const row = document.createElement("div");
    row.className = "download";

    const info = document.createElement("div");
    info.className = "download-info";
    const label = document.createElement("div");
    label.className = "download-label";
    label.textContent = d.label || d.platform;
    const sub = document.createElement("div");
    sub.className = "download-sub";
    const size = formatSize(d.size);
    sub.textContent = size ? d.file + " · " + size : d.file;
    info.appendChild(label);
    info.appendChild(sub);

    const actions = document.createElement("div");
    actions.className = "download-actions";
    actions.appendChild(
      pluginButton("Copy link", "btn-iron", false, () => copyText(d.url))
    );
    actions.appendChild(
      pluginButton("Download", "btn-brass", false, () =>
        send({ type: "open_url", url: d.url })
      )
    );

    row.appendChild(info);
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

  function renderDownloads(snap) {
    const items = snap.launcher || [];
    // Nothing is published until a release exists; an empty section would just
    // read as a broken tab.
    downloadsSection.hidden = !snap.release_url;
    btnReleasePage.hidden = !snap.release_url;

    downloadsList.innerHTML = "";
    items.forEach((d) => downloadsList.appendChild(renderDownload(d)));
  }

  function renderPlugins() {
    const snap = state.pluginsSnapshot;
    if (!snap) return;

    pluginsDir.textContent = snap.scripts_dir;

    pluginsOrigin.textContent = "";
    const origin = document.createElement("b");
    origin.textContent = snap.source_url.replace(/^https?:\/\//, "");
    pluginsOrigin.appendChild(origin);
    if (snap.release_tag) {
      pluginsOrigin.appendChild(document.createTextNode(" · " + snap.release_tag));
    }
    pluginsOrigin.appendChild(
      document.createTextNode(" · launcher " + snap.launcher_version)
    );
    // With an error in hand the banner below says what went wrong; claiming a
    // check is still running would contradict it.
    if (snap.checked_at) {
      pluginsOrigin.appendChild(
        document.createTextNode(" · checked " + formatClock(snap.checked_at))
      );
    } else if (!snap.error) {
      pluginsOrigin.appendChild(document.createTextNode(" · checking…"));
    }

    pluginsError.hidden = !snap.error;
    if (snap.error) pluginsError.textContent = snap.error;

    pluginsList.innerHTML = "";
    (snap.plugins || []).forEach((p) => pluginsList.appendChild(renderPluginRow(p)));

    renderDownloads(snap);
    renderLauncherUpdate(snap);
  }

  function updatePlayButton() {
    btnPlay.disabled = state.launching;
    btnPlay.classList.toggle("is-launching", state.launching);
    playLabel.textContent = state.launching ? "Launching…" : "Play";
  }

  function openSettings() {
    settingsModal.hidden = false;
    btnSettings.classList.add("is-active");
  }

  function closeSettings() {
    settingsModal.hidden = true;
    btnSettings.classList.remove("is-active");
    // Drop unsaved edits so a reopen shows what is actually persisted.
    restoreConfigToUI();
  }

  // Event handlers
  btnLogin.addEventListener("click", () => send({ type: "login" }));
  btnAddAccount.addEventListener("click", () => send({ type: "login" }));

  btnLogout.addEventListener("click", () => {
    const userId = selAccount.value;
    if (userId) {
      send({ type: "logout", user_id: userId });
    }
  });

  btnPlay.addEventListener("click", () => {
    const accountId = selCharacter.value;
    const session = state.sessions.find((s) => s.user_id === selAccount.value);
    if (!accountId || !session) return;

    const account = session.accounts.find((a) => a.accountId === accountId);
    if (!account) return;

    state.launching = true;
    updatePlayButton();
    send({
      type: "launch",
      account_id: accountId,
      display_name: account.displayName || "",
    });
  });

  selAccount.addEventListener("change", updateCharacters);

  selCharacter.addEventListener("change", () => {
    const userId = selAccount.value;
    const accountId = selCharacter.value;
    if (!userId || !accountId) return;
    const session = state.sessions.find((s) => s.user_id === userId);
    if (session) session.last_account_id = accountId;
    send({ type: "select_character", user_id: userId, account_id: accountId });
  });

  // Clients panel
  btnRefreshClients.addEventListener("click", () => requestClients(true));

  // Plugins panel
  btnRefreshPlugins.addEventListener("click", () => requestPlugins(true));
  btnOpenScripts.addEventListener("click", () => send({ type: "plugins_open_dir" }));
  btnLauncherUpdate.addEventListener("click", () => {
    const update = state.pluginsSnapshot && state.pluginsSnapshot.launcher_update;
    if (update) send({ type: "open_url", url: update.url });
  });

  btnReleasePage.addEventListener("click", () => {
    const url = state.pluginsSnapshot && state.pluginsSnapshot.release_url;
    if (url) send({ type: "open_url", url: url });
  });

  // Settings modal
  navItems.forEach((item) => {
    item.addEventListener("click", () => showView(item.dataset.view));
  });

  btnSettings.addEventListener("click", openSettings);
  btnCloseSettings.addEventListener("click", closeSettings);
  btnCancelSettings.addEventListener("click", closeSettings);
  btnSaveSettings.addEventListener("click", () => {
    if (!state.config) return;
    state.config.close_after_launch = chkCloseAfter.checked;
    state.config.auto_inject_projectx = chkAutoInject.checked;
    state.config.debug_logging = chkDebugLogging.checked;
    state.config.custom_launch_command = inpLaunchCmd.value || null;
    send({ type: "save_config", config: state.config });
    settingsModal.hidden = true;
    btnSettings.classList.remove("is-active");
  });

  settingsModal.addEventListener("click", (e) => {
    if (e.target === settingsModal) closeSettings();
  });

  document.addEventListener("keydown", (e) => {
    if (e.key !== "Escape") return;
    if (!settingsModal.hidden) {
      closeSettings();
    } else if (state.view !== "play") {
      showView("play");
    }
  });

  // Signal the Rust backend that the frontend is ready. The backend replies
  // with the "init" event (config + saved sessions). This replaces the old
  // fixed startup delay, which both added latency and could race JS readiness.
  send({ type: "ready" });
})();
