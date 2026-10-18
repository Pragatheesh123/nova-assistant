chrome.storage.session.get("connected").then(({ connected }) => {
  document.getElementById("st").classList.toggle("on", !!connected);
  document.getElementById("txt").textContent = connected ? "Connected to Nova" : "Nova isn't running";
});
