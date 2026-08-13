const eden = document.querySelector("#eden");
const status = document.querySelector("#status");
const detail = document.querySelector("#detail");
const login = document.querySelector("#login");

async function refresh() {
  try {
    const state = await window.go.desktop.App.State();
    eden.textContent = state.edenDetected ? "Detected" : "Not found";
    eden.title = state.edenPath || "";
    status.textContent = state.status;
    status.className = "pill " + state.status.toLowerCase();
    detail.textContent = state.detail || "";
    login.textContent = state.connected ? "Google Drive connected" : "Connect Google Drive";
    login.disabled = state.connected || state.busy;
  } catch (_) {
    status.textContent = "Offline";
    status.className = "pill offline";
  }
}

login.addEventListener("click", async () => {
  login.disabled = true;
  detail.textContent = "Waiting for Google authorization…";
  try { await window.go.desktop.App.Login(); }
  catch (error) { detail.textContent = String(error); }
  await refresh();
});
refresh();
setInterval(refresh, 1000);

