const STORAGE_KEY = "joanakar-archive-v1";
const CHANNEL_KEY = "joanakar-channel-v1";

const state = {
  tracks: loadTracks(),
  filter: "all",
  query: "",
  installPrompt: null,
};

const els = {
  collection: document.querySelector("#collection"),
  total: document.querySelector("#totalCount"),
  live: document.querySelector("#liveCount"),
  favorites: document.querySelector("#favoriteCount"),
  search: document.querySelector("#searchInput"),
  trackDialog: document.querySelector("#trackDialog"),
  trackForm: document.querySelector("#trackForm"),
  channelDialog: document.querySelector("#channelDialog"),
  channelForm: document.querySelector("#channelForm"),
  menu: document.querySelector("#menuPanel"),
  toast: document.querySelector("#toast"),
  install: document.querySelector("#installButton"),
};

function loadTracks() {
  try {
    const value = JSON.parse(localStorage.getItem(STORAGE_KEY) || "[]");
    return Array.isArray(value) ? value : [];
  } catch { return []; }
}

function saveTracks() {
  localStorage.setItem(STORAGE_KEY, JSON.stringify(state.tracks));
  render();
}

function escapeHtml(value) {
  return String(value).replace(/[&<>'"]/g, char => ({"&":"&amp;","<":"&lt;",">":"&gt;","'":"&#39;",'"':"&quot;"}[char]));
}

function safeYoutubeUrl(value) {
  try {
    const url = new URL(value);
    const host = url.hostname.replace(/^www\./, "");
    return ["youtube.com", "m.youtube.com", "youtu.be"].includes(host) ? url.href : null;
  } catch { return null; }
}

function render() {
  els.total.textContent = state.tracks.length;
  els.live.textContent = state.tracks.filter(item => item.type === "live").length;
  els.favorites.textContent = state.tracks.filter(item => item.favorite).length;

  const query = state.query.trim().toLocaleLowerCase("es");
  const visible = state.tracks.filter(item => {
    const filterMatch = state.filter === "all" || (state.filter === "favorite" ? item.favorite : item.type === state.filter);
    const queryMatch = !query || `${item.title} ${item.year || ""}`.toLocaleLowerCase("es").includes(query);
    return filterMatch && queryMatch;
  });

  if (!visible.length) {
    const hasItems = state.tracks.length > 0;
    els.collection.innerHTML = `<div class="empty"><div class="empty-mark">♪</div><h3>${hasItems ? "No hay resultados" : "Tu archivo está preparado"}</h3><p>${hasItems ? "Prueba con otra búsqueda o cambia el filtro." : "Añade tu primera pieza real. El catálogo permanecerá guardado en este dispositivo."}</p>${hasItems ? "" : '<button class="primary-btn" type="button" data-action="add">Añadir primera pieza</button>'}</div>`;
    return;
  }

  els.collection.innerHTML = visible.map(item => {
    const url = safeYoutubeUrl(item.url) || "#";
    return `<article class="track-card">
      <div class="track-art">
        <span class="type-badge">${item.type === "live" ? "LIVE ACT" : "TRACK"}</span>
        <a class="play" href="${escapeHtml(url)}" target="_blank" rel="noopener" aria-label="Abrir ${escapeHtml(item.title)} en YouTube">
          <svg viewBox="0 0 24 24" aria-hidden="true"><path d="m8 5 11 7-11 7z"/></svg>
        </a>
      </div>
      <div class="track-body">
        <div class="track-meta">${item.year ? escapeHtml(item.year) : "SIN AÑO"} · YOUTUBE</div>
        <h3 title="${escapeHtml(item.title)}">${escapeHtml(item.title)}</h3>
        <div class="track-actions">
          <button class="favorite ${item.favorite ? "active" : ""}" type="button" data-action="favorite" data-id="${escapeHtml(item.id)}">${item.favorite ? "★ Favorito" : "☆ Favorito"}</button>
          <button type="button" data-action="delete" data-id="${escapeHtml(item.id)}">Eliminar</button>
        </div>
      </div>
    </article>`;
  }).join("");
}

let toastTimer;
function toast(message) {
  els.toast.textContent = message;
  els.toast.classList.add("show");
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => els.toast.classList.remove("show"), 2400);
}

document.querySelector("#addTrackButton").addEventListener("click", () => els.trackDialog.showModal());
document.querySelector("#channelButton").addEventListener("click", () => {
  const channel = localStorage.getItem(CHANNEL_KEY);
  if (channel) window.open(channel, "_blank", "noopener");
  else els.channelDialog.showModal();
});

els.trackForm.addEventListener("submit", event => {
  if (event.submitter?.value === "cancel") return;
  event.preventDefault();
  const data = new FormData(els.trackForm);
  const url = safeYoutubeUrl(data.get("url"));
  if (!url) { toast("Añade un enlace válido de YouTube"); return; }
  state.tracks.unshift({
    id: crypto.randomUUID ? crypto.randomUUID() : `${Date.now()}`,
    title: String(data.get("title")).trim(),
    type: data.get("type") === "live" ? "live" : "track",
    year: String(data.get("year") || "").trim(),
    url,
    favorite: false,
  });
  saveTracks();
  els.trackForm.reset();
  els.trackDialog.close();
  toast("Pieza guardada");
});

els.channelForm.addEventListener("submit", event => {
  if (event.submitter?.value === "cancel") return;
  event.preventDefault();
  const url = safeYoutubeUrl(new FormData(els.channelForm).get("channel"));
  if (!url) { toast("Añade un enlace válido de YouTube"); return; }
  localStorage.setItem(CHANNEL_KEY, url);
  els.channelDialog.close();
  toast("Canal guardado");
});

els.search.addEventListener("input", event => { state.query = event.target.value; render(); });
document.querySelectorAll(".filter").forEach(button => button.addEventListener("click", () => {
  document.querySelectorAll(".filter").forEach(item => item.classList.toggle("active", item === button));
  state.filter = button.dataset.filter;
  render();
}));

els.collection.addEventListener("click", event => {
  const button = event.target.closest("button[data-action]");
  if (!button) return;
  if (button.dataset.action === "add") { els.trackDialog.showModal(); return; }
  const item = state.tracks.find(track => track.id === button.dataset.id);
  if (!item) return;
  if (button.dataset.action === "favorite") item.favorite = !item.favorite;
  if (button.dataset.action === "delete" && confirm(`¿Eliminar “${item.title}” del archivo?`)) state.tracks = state.tracks.filter(track => track.id !== item.id);
  saveTracks();
});

document.querySelector("#menuButton").addEventListener("click", event => {
  event.stopPropagation();
  els.menu.hidden = !els.menu.hidden;
});
document.addEventListener("click", event => { if (!els.menu.contains(event.target)) els.menu.hidden = true; });

document.querySelector("#exportButton").addEventListener("click", () => {
  const backup = JSON.stringify({version: 1, tracks: state.tracks}, null, 2);
  if (window.Android && typeof window.Android.saveBackup === "function") {
    window.Android.saveBackup(backup);
    return;
  }
  const blob = new Blob([backup], {type: "application/json"});
  const link = document.createElement("a");
  link.href = URL.createObjectURL(blob);
  link.download = "joanakar-techno-archive.json";
  link.click();
  URL.revokeObjectURL(link.href);
  toast("Copia exportada");
});

document.querySelector("#importInput").addEventListener("change", async event => {
  const file = event.target.files?.[0];
  if (!file) return;
  try {
    const data = JSON.parse(await file.text());
    if (!Array.isArray(data.tracks)) throw new Error();
    const clean = data.tracks.filter(item => item && item.title && safeYoutubeUrl(item.url)).map(item => ({
      id: String(item.id || Date.now() + Math.random()), title: String(item.title), type: item.type === "live" ? "live" : "track",
      year: String(item.year || ""), url: safeYoutubeUrl(item.url), favorite: Boolean(item.favorite),
    }));
    state.tracks = clean;
    saveTracks();
    toast("Copia importada");
  } catch { toast("El archivo no es una copia válida"); }
  event.target.value = "";
});

document.querySelector("#clearButton").addEventListener("click", () => {
  if (!state.tracks.length || !confirm("¿Vaciar todo el archivo? Esta acción no se puede deshacer.")) return;
  state.tracks = [];
  saveTracks();
  toast("Archivo vacío");
});

window.addEventListener("beforeinstallprompt", event => {
  event.preventDefault();
  state.installPrompt = event;
  els.install.hidden = false;
});
els.install.addEventListener("click", async () => {
  if (!state.installPrompt) return;
  state.installPrompt.prompt();
  await state.installPrompt.userChoice;
  state.installPrompt = null;
  els.install.hidden = true;
});

if ("serviceWorker" in navigator) window.addEventListener("load", () => navigator.serviceWorker.register("./sw.js"));
render();
