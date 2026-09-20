const state = {
  notifications: [],
  channels: [],
  watchlist: [],
  settings: { ai: {}, notifications: {} },
  filter: "all",
  search: "",
  socket: null,
  reconnectTimer: null,
};

const $ = (id) => document.getElementById(id);

async function api(path, options = {}) {
  const response = await fetch(path, {
    ...options,
    headers: { "Content-Type": "application/json", ...(options.headers || {}) },
  });
  const text = await response.text();
  let data = null;
  try { data = text ? JSON.parse(text) : null; } catch (_) { data = text; }
  if (!response.ok) throw new Error(data?.detail || data || `Request failed (${response.status})`);
  return data;
}

function escapeHtml(value) {
  return String(value ?? "").replace(/[&<>'"]/g, (char) => ({
    "&": "&amp;", "<": "&lt;", ">": "&gt;", "'": "&#39;", '"': "&quot;",
  }[char]));
}

function formatTime(value) {
  if (!value) return "Just now";
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return String(value);
  const now = Date.now();
  const minutes = Math.floor((now - date.getTime()) / 60000);
  if (minutes < 1) return "Just now";
  if (minutes < 60) return `${minutes}m ago`;
  if (minutes < 1440) return `${Math.floor(minutes / 60)}h ago`;
  return date.toLocaleDateString(undefined, { month: "short", day: "numeric" });
}

function parsedOf(notification) {
  return notification?.message?.parsed || {};
}

function messageOf(notification) {
  return notification?.message || {};
}

function matchesSearch(notification) {
  if (!state.search) return true;
  const parsed = parsedOf(notification);
  const haystack = [
    notification.title,
    notification.body,
    messageOf(notification).text,
    messageOf(notification).source,
    ...(parsed.item_names || []),
    ...(parsed.contact_handles || []),
  ].join(" ").toLowerCase();
  return haystack.includes(state.search.toLowerCase());
}

function filterNotifications() {
  return state.notifications.filter((notification) => {
    const parsed = parsedOf(notification);
    const category = parsed.category || "other";
    if (state.filter === "game" && category !== "game") return false;
    if (state.filter === "account" && category !== "account") return false;
    if (state.filter === "urgent" && !notification.urgent) return false;
    return matchesSearch(notification);
  });
}

function priceLabel(parsed) {
  if (parsed.price !== undefined && parsed.price !== null && String(parsed.price).trim()) {
    return `${parsed.price}${parsed.currency ? ` ${parsed.currency}` : ""}`;
  }
  return "Price not listed";
}

function availabilityClass(value) {
  return ["available", "sold"].includes(value) ? value : "";
}

function renderStats() {
  const messages = state.notifications.map((notification) => messageOf(notification));
  $("stat-all").textContent = messages.length;
  $("stat-games").textContent = messages.filter((message) => parsedOf({ message }).category === "game").length;
  $("stat-accounts").textContent = messages.filter((message) => parsedOf({ message }).category === "account").length;
  $("stat-urgent").textContent = state.notifications.filter((notification) => notification.urgent).length;
}

function renderFeed() {
  const feed = $("feed");
  const notifications = filterNotifications();
  if (!notifications.length) {
    feed.innerHTML = `<div class="empty-card"><strong>No listings here yet.</strong><span>New matching messages will appear live.</span></div>`;
    return;
  }
  feed.innerHTML = notifications.map(renderCard).join("");
}

function renderCard(notification) {
  const parsed = parsedOf(notification);
  const message = messageOf(notification);
  const items = (parsed.item_names || []).map(escapeHtml);
  const title = items.join(", ") || escapeHtml(notification.title || "New listing");
  const handles = (parsed.contact_handles || []).join(", ") || "No contact found";
  const category = escapeHtml(parsed.category || "other");
  const availability = escapeHtml(parsed.availability || "unknown");
  const media = message.media_urls || [];
  const imageStrip = media.length ? `<div class="album-strip">${media.slice(0, 5).map((item, index) => `
    <button data-open-detail="${notification.id}" aria-label="Open image ${index + 1}">
      <img src="${escapeHtml(item.url)}" alt="Listing image ${index + 1}" loading="lazy" />
    </button>`).join("")}${media.length > 5 ? `<div class="album-more"><span>+${media.length - 5}</span></div>` : ""}</div>` : "";
  const telegramLink = notification.telegram_url || message.telegram_url;
  return `<article class="listing-card ${notification.urgent ? "is-urgent" : ""}" data-card="${notification.id}">
    <div class="card-top">
      <div class="source-line"><span class="source-dot"></span>@${escapeHtml(message.source || "telegram")}</div>
      <div class="time-line">${escapeHtml(formatTime(notification.created_at || message.created_at))}</div>
    </div>
    <div class="badge-row">
      <span class="badge">${category}</span>
      <span class="badge ${availabilityClass(parsed.availability)}">${availability}</span>
      ${notification.urgent ? `<span class="badge urgent">watchlist hit</span>` : ""}
      ${message.album_count > 1 ? `<span class="badge">${message.album_count} images</span>` : ""}
    </div>
    <h3 class="listing-title">${title}</h3>
    <p class="listing-text">${escapeHtml(message.text || parsed.summary || "No text caption")}</p>
    ${imageStrip}
    <div class="card-meta">
      <div class="data-pair"><span>Price</span><strong class="price">${escapeHtml(priceLabel(parsed))}</strong></div>
      <div class="data-pair"><span>DM</span><strong class="handles">${escapeHtml(handles)}</strong></div>
      <div class="data-pair"><span>State</span><strong>${availability}</strong></div>
    </div>
    <div class="card-actions">
      <button data-open-detail="${notification.id}">View in app</button>
      ${telegramLink ? `<a class="telegram-link" href="${escapeHtml(telegramLink)}" target="_blank" rel="noopener">Open in Telegram ↗</a>` : ""}
      ${notification.read_at ? "" : `<button data-mark-read="${notification.id}">Mark read</button>`}
    </div>
  </article>`;
}

function renderWatchlist() {
  const list = $("watchlist-list");
  const enabled = state.watchlist.filter((item) => item.enabled);
  $("watchlist-count").textContent = enabled.length;
  list.innerHTML = enabled.length ? enabled.map((item) => `<span class="watch-chip">${escapeHtml(item.name)}<button data-remove-watchlist="${item.id}" aria-label="Remove ${escapeHtml(item.name)}">×</button></span>`).join("") : `<span class="muted">Nothing here yet.</span>`;
}

function renderSources() {
  const list = $("source-list");
  list.innerHTML = state.channels.length ? state.channels.map((channel) => `
    <div class="source-row ${channel.source.startsWith("-") ? "private" : ""}">
      <span class="source-icon">${channel.source.startsWith("-") ? "▣" : "#"}</span>
      <span class="source-name">${escapeHtml(channel.display_name || `@${channel.source}`)}</span>
      <span class="source-state">${channel.enabled ? "On" : "Off"}</span>
    </div>`).join("") : `<span class="muted">No channels configured.</span>`;
}

function renderSettings() {
  const ai = state.settings.ai || {};
  const notifications = state.settings.notifications || {};
  $("ai-base-url").value = ai.base_url || "";
  $("ai-model").value = ai.model || "";
  $("ai-vision-model").value = ai.vision_model || ai.model || "";
  $("enable-vision").checked = Boolean(ai.enable_vision);
  $("only-priced").checked = Boolean(notifications.only_notify_with_price);
  const target = notifications.click_target || "app";
  const radio = document.querySelector(`input[name="click-target"][value="${target}"]`);
  if (radio) radio.checked = true;
  $("ai-status").textContent = ai.configured ? "Ready" : "Not configured";
  $("ai-status").classList.toggle("is-ready", Boolean(ai.configured));
}

function setFilter(filter) {
  state.filter = filter;
  document.querySelectorAll("[data-filter]").forEach((element) => {
    element.classList.toggle("is-active", element.dataset.filter === filter);
    element.classList.toggle("is-selected", element.dataset.filter === filter && element.classList.contains("stat-card"));
  });
  renderFeed();
}

function showToast(message) {
  const toast = $("toast");
  toast.textContent = message;
  toast.classList.add("is-visible");
  clearTimeout(showToast.timer);
  showToast.timer = setTimeout(() => toast.classList.remove("is-visible"), 2600);
}

function openLayer(id) { $(id).hidden = false; document.body.style.overflow = "hidden"; }
function closeLayer(id) { $(id).hidden = true; document.body.style.overflow = ""; }

function showDetail(notification) {
  if (!notification) return;
  const parsed = parsedOf(notification);
  const message = messageOf(notification);
  const media = message.media_urls || [];
  const images = media.length ? `<div class="detail-image-grid">${media.map((item, index) => `<img src="${escapeHtml(item.url)}" alt="Listing image ${index + 1}" loading="lazy" />`).join("")}</div>` : "";
  const telegramLink = notification.telegram_url || message.telegram_url;
  $("detail-title").textContent = (parsed.item_names || []).join(", ") || notification.title;
  $("detail-content").innerHTML = `
    <div class="badge-row"><span class="badge">${escapeHtml(parsed.category || "other")}</span><span class="badge ${availabilityClass(parsed.availability)}">${escapeHtml(parsed.availability || "unknown")}</span></div>
    <p class="drawer-copy">From @${escapeHtml(message.source || "telegram")} · ${escapeHtml(formatTime(message.posted_at || notification.created_at))}</p>
    ${images}
    <div class="card-meta"><div class="data-pair"><span>Price</span><strong class="price">${escapeHtml(priceLabel(parsed))}</strong></div><div class="data-pair"><span>DM</span><strong class="handles">${escapeHtml((parsed.contact_handles || []).join(", ") || "No contact found")}</strong></div></div>
    <p class="detail-copy">${escapeHtml(message.text || parsed.summary || "No caption")}</p>
    <div class="card-actions">
      ${telegramLink ? `<a class="telegram-link" href="${escapeHtml(telegramLink)}" target="_blank" rel="noopener">Open this message in Telegram ↗</a>` : ""}
      ${notification.read_at ? "" : `<button data-detail-mark-read="${notification.id}">Mark read</button>`}
    </div>`;
  openLayer("detail-layer");
  if (!notification.read_at) markRead(notification.id);
}

async function markRead(id) {
  const notification = state.notifications.find((item) => item.id === Number(id));
  if (!notification || notification.read_at) return;
  try {
    await api(`/api/notifications/${id}/read`, { method: "POST" });
    notification.read_at = new Date().toISOString();
    renderFeed();
  } catch (error) { showToast(error.message); }
}

async function loadAll() {
  try {
    const [notifications, channels, watchlist, settings] = await Promise.all([
      api("/api/notifications?limit=200"),
      api("/api/channels"),
      api("/api/watchlist"),
      api("/api/settings"),
    ]);
    state.notifications = notifications || [];
    state.channels = channels || [];
    state.watchlist = watchlist || [];
    state.settings = settings || state.settings;
    renderStats(); renderFeed(); renderWatchlist(); renderSources(); renderSettings();
  } catch (error) {
    $("feed").innerHTML = `<div class="empty-card"><strong>Could not load the feed.</strong><span>${escapeHtml(error.message)}</span></div>`;
    showToast(error.message);
  }
}

function mergeNotification(notification) {
  state.notifications = [notification, ...state.notifications.filter((item) => item.id !== notification.id)].slice(0, 200);
  renderStats(); renderFeed();
}

function setConnection(status) {
  const pill = $("connection-pill");
  pill.textContent = status;
  pill.className = `status-pill ${status === "Live" ? "is-live" : status === "Connecting" ? "is-pending" : "is-offline"}`;
}

function connectSocket() {
  if (state.socket && [WebSocket.OPEN, WebSocket.CONNECTING].includes(state.socket.readyState)) return;
  const protocol = window.location.protocol === "https:" ? "wss" : "ws";
  setConnection("Connecting");
  const socket = new WebSocket(`${protocol}://${window.location.host}/ws/notifications`);
  state.socket = socket;
  socket.onopen = () => setConnection("Live");
  socket.onmessage = (event) => {
    try {
      const data = JSON.parse(event.data);
      if (data.type !== "new_notification" || !data.notification) return;
      mergeNotification(data.notification);
      const notification = data.notification;
      if ("Notification" in window && Notification.permission === "granted") {
        const alert = new Notification(notification.title, { body: notification.body, tag: String(notification.id) });
        alert.onclick = () => {
          window.focus();
          if (notification.click_target === "telegram" && notification.telegram_url) window.open(notification.telegram_url, "_blank");
          else showDetail(notification);
        };
      }
    } catch (_) { /* ignore malformed socket messages */ }
  };
  socket.onclose = () => {
    setConnection("Offline");
    clearTimeout(state.reconnectTimer);
    state.reconnectTimer = setTimeout(connectSocket, 3000);
  };
  socket.onerror = () => setConnection("Offline");
}

async function loadDialogs() {
  const target = $("joined-dialogs");
  target.innerHTML = `<span class="muted">Looking at joined channels…</span>`;
  try {
    const dialogs = await api("/api/telegram/dialogs");
    target.innerHTML = dialogs.length ? dialogs.map((dialog) => `
      <div class="joined-row ${dialog.selected ? "is-selected" : ""}">
        <span class="source-icon">${dialog.private ? "▣" : "#"}</span>
        <span class="joined-name">${escapeHtml(dialog.title || dialog.source)}${dialog.private ? " · private" : ""}</span>
        ${dialog.selected ? `<span class="source-state">Added</span>` : `<button class="small-button" data-add-source="${escapeHtml(dialog.source)}" data-add-title="${escapeHtml(dialog.title || dialog.source)}">Add</button>`}
      </div>`).join("") : `<span class="muted">No joined channels found.</span>`;
  } catch (error) {
    target.innerHTML = `<span class="muted">${escapeHtml(error.message)}</span>`;
  }
}

async function addChannel(source, title) {
  try {
    await api("/api/channels", { method: "POST", body: JSON.stringify({ source, display_name: title, enabled: true }) });
    state.channels = await api("/api/channels");
    renderSources();
    await loadDialogs();
    showToast("Channel added");
  } catch (error) { showToast(error.message); }
}

async function saveAI(event) {
  event.preventDefault();
  const payload = {
    base_url: $("ai-base-url").value.trim() || null,
    model: $("ai-model").value.trim() || null,
    vision_model: $("ai-vision-model").value.trim() || null,
    enable_vision: $("enable-vision").checked,
  };
  const key = $("ai-api-key").value.trim();
  if (key) payload.api_key = key;
  try {
    const ai = await api("/api/settings/ai", { method: "PUT", body: JSON.stringify(payload) });
    state.settings.ai = ai;
    $("ai-api-key").value = "";
    renderSettings();
    showToast("AI settings saved");
  } catch (error) { showToast(error.message); }
}

async function loadModels() {
  try {
    const result = await api("/api/ai/models");
    $("model-options").innerHTML = (result.models || []).map((model) => `<option value="${escapeHtml(model.id)}"></option>`).join("");
    showToast(`${result.models.length} models loaded`);
  } catch (error) { showToast(error.message); }
}

async function saveNotificationSettings() {
  const checked = document.querySelector("input[name=click-target]:checked");
  try {
    const notifications = await api("/api/settings/notifications", {
      method: "PUT",
      body: JSON.stringify({
        only_notify_with_price: $("only-priced").checked,
        click_target: checked?.value || "app",
      }),
    });
    state.settings.notifications = notifications;
    showToast("Notification settings saved");
  } catch (error) { showToast(error.message); }
}

async function addWatchlist(event) {
  event.preventDefault();
  const input = $("watchlist-input");
  const name = input.value.trim();
  if (!name) return;
  try {
    await api("/api/watchlist", { method: "POST", body: JSON.stringify({ name, enabled: true }) });
    state.watchlist = await api("/api/watchlist");
    input.value = "";
    renderWatchlist();
    showToast("Watchlist updated");
  } catch (error) { showToast(error.message); }
}

async function removeWatchlist(id) {
  try {
    await api(`/api/watchlist/${id}`, { method: "DELETE" });
    state.watchlist = state.watchlist.filter((item) => item.id !== Number(id));
    renderWatchlist();
  } catch (error) { showToast(error.message); }
}

async function markAllRead() {
  try {
    await Promise.all(state.notifications.filter((item) => !item.read_at).map((item) => api(`/api/notifications/${item.id}/read`, { method: "POST" })));
    state.notifications.forEach((item) => { item.read_at = item.read_at || new Date().toISOString(); });
    renderFeed();
    showToast("All notifications marked read");
  } catch (error) { showToast(error.message); }
}

function bindEvents() {
  document.querySelectorAll("[data-filter]").forEach((element) => element.addEventListener("click", () => setFilter(element.dataset.filter)));
  $("search-input").addEventListener("input", (event) => { state.search = event.target.value.trim(); renderFeed(); });
  $("refresh-button").addEventListener("click", loadAll);
  $("settings-button").addEventListener("click", () => openLayer("settings-layer"));
  document.querySelectorAll("[data-close-settings]").forEach((element) => element.addEventListener("click", () => closeLayer("settings-layer")));
  document.querySelectorAll("[data-close-detail]").forEach((element) => element.addEventListener("click", () => closeLayer("detail-layer")));
  $("watchlist-form").addEventListener("submit", addWatchlist);
  $("mark-all-read").addEventListener("click", markAllRead);
  $("ai-form").addEventListener("submit", saveAI);
  $("load-models").addEventListener("click", loadModels);
  $("test-ai").addEventListener("click", async () => {
    const resultBox = $("ai-result");
    resultBox.hidden = false;
    resultBox.textContent = "Testing…";
    try { const result = await api("/api/ai/test", { method: "POST" }); resultBox.textContent = result.ok ? "Text model responded." : result.error || "AI test failed."; }
    catch (error) { resultBox.textContent = error.message; }
  });
  $("save-notification-settings").addEventListener("click", saveNotificationSettings);
  $("find-dialogs").addEventListener("click", () => { openLayer("settings-layer"); loadDialogs(); });
  $("load-dialogs").addEventListener("click", loadDialogs);
  $("enable-alerts").addEventListener("click", async () => {
    if (!("Notification" in window)) return showToast("This browser does not support alerts");
    const permission = await Notification.requestPermission();
    showToast(permission === "granted" ? "Browser alerts enabled" : "Browser alerts are off");
  });
  $("manual-channel-form").addEventListener("submit", async (event) => {
    event.preventDefault();
    const source = $("manual-source").value.trim();
    if (!source) return;
    await addChannel(source, $("manual-title").value.trim() || source);
    $("manual-source").value = ""; $("manual-title").value = "";
  });
  $("joined-dialogs").addEventListener("click", (event) => {
    const button = event.target.closest("[data-add-source]");
    if (button) addChannel(button.dataset.addSource, button.dataset.addTitle);
  });
  $("watchlist-list").addEventListener("click", (event) => {
    const button = event.target.closest("[data-remove-watchlist]");
    if (button) removeWatchlist(button.dataset.removeWatchlist);
  });
  $("feed").addEventListener("click", (event) => {
    const readButton = event.target.closest("[data-mark-read]");
    if (readButton) return markRead(readButton.dataset.markRead);
    const detailRead = event.target.closest("[data-detail-mark-read]");
    if (detailRead) return markRead(detailRead.dataset.detailMarkRead);
    if (event.target.closest("a")) return;
    const button = event.target.closest("[data-open-detail]");
    const card = event.target.closest("[data-card]");
    const id = button?.dataset.openDetail || card?.dataset.card;
    if (id) showDetail(state.notifications.find((item) => item.id === Number(id)));
  });
  document.querySelectorAll("[data-nav]").forEach((element) => element.addEventListener("click", () => {
    const nav = element.dataset.nav;
    document.querySelectorAll("[data-nav]").forEach((item) => item.classList.toggle("is-active", item === element));
    if (nav === "settings") openLayer("settings-layer");
    else if (nav === "watchlist") $("watchlist-panel").scrollIntoView({ behavior: "smooth" });
    else window.scrollTo({ top: 0, behavior: "smooth" });
  }));
}

async function init() {
  bindEvents();
  await loadAll();
  connectSocket();
}

init();

