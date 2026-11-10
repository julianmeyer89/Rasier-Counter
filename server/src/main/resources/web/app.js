"use strict";

const COL = {
  import: "#d84315", export: "#1565c0", solar: "#f9a825", battery: "#2e7d32", green: "#1b5e20",
};
let token = localStorage.getItem("token") || "";
let lastState = null;
let statsDays = 7;

// ---- API ----
async function api(path, opts = {}) {
  const headers = Object.assign({ "Content-Type": "application/json" }, opts.headers || {});
  if (token) headers["X-Auth-Token"] = token;
  const res = await fetch("/api/" + path, Object.assign({ headers }, opts));
  if (res.status === 401) { showTokenPrompt(); throw new Error("unauthorized"); }
  if (!res.ok) throw new Error("HTTP " + res.status);
  const ct = res.headers.get("content-type") || "";
  return ct.includes("json") ? res.json() : res.text();
}

// ---- helpers ----
const W = (w) => (w == null ? "–" : Math.round(w) + " W");
const KWH = (wh) => (wh == null ? "–" : (wh / 1000).toFixed(2) + " kWh");
const EUR = (v) => (v == null ? "–" : v.toFixed(2).replace(".", ",") + " €");
const PCT = (v) => (v == null ? "–" : Math.round(v) + " %");
const el = (html) => { const t = document.createElement("template"); t.innerHTML = html.trim(); return t.content.firstChild; };
function ago(ms) {
  if (!ms) return "nie";
  const s = Math.floor((Date.now() - ms) / 1000);
  if (s < 60) return "vor " + s + " s";
  if (s < 3600) return "vor " + Math.floor(s / 60) + " min";
  if (s < 86400) return "vor " + Math.floor(s / 3600) + " h";
  return new Date(ms).toLocaleString("de-DE", { day: "2-digit", month: "2-digit", hour: "2-digit", minute: "2-digit" });
}
function toast(msg) {
  const t = document.getElementById("toast");
  t.textContent = msg; t.classList.add("show");
  setTimeout(() => t.classList.remove("show"), 2500);
}
const chargeModeLabel = (m) => ({ SIMULTANEOUS: "gleichzeitig laden/entladen", CHARGE_THEN_DISCHARGE: "erst voll laden", UNKNOWN: "–" }[m] || "–");

// ---- rendering ----
function row(label, value, cls) {
  return `<div class="row"><span class="label">${label}</span><span class="value ${cls || ""}">${value}</span></div>`;
}

function renderOverview(s) {
  const g = s.group;
  const gridCls = g.gridW == null ? "" : g.gridW < 0 ? "export" : "import";
  const gridLabel = g.gridW == null ? "kein CT002-Wert" : (g.gridW < 0 ? "Einspeisung" : "Bezug") + " " + W(Math.abs(g.gridW));
  let html = "";
  if (s.mode === "demo") html += `<div class="card"><div class="note">Demo-Modus: simulierte Geräte.</div></div>`;
  for (const a of s.alerts) {
    html += `<div class="alert ${a.severity === "INFO" ? "info" : ""}"><b>${a.title}</b><br>${a.message}</div>`;
  }
  html += `<div class="card"><h2>Jetzt</h2>
    <div class="big-row">
      <div class="big"><span class="v solar">${W(g.pvW)}</span><span class="l">Solar</span></div>
      <div class="big"><span class="v battery">${PCT(g.socPercent)}</span><span class="l">Speicher</span></div>
      <div class="big"><span class="v">${W(g.houseW)}</span><span class="l">Haus</span></div>
    </div>
    <div class="bar"><div style="width:${Math.max(0, Math.min(100, g.socPercent || 0))}%"></div></div>
    <div style="height:10px"></div>
    ${row("Speicher → Wechselrichter", W(g.outputW))}
    ${row("Wechselrichter → Haus (gesch.)", W(g.inverterAcW))}
    ${row(g.gridW != null && g.gridW < 0 ? "Einspeisung ins Netz" : "Bezug aus dem Netz", gridLabel, gridCls)}
    ${row(g.chargeW >= 0 ? "Speicher lädt" : "Speicher entlädt", W(Math.abs(g.chargeW)))}
    ${g.storedWh != null ? row("Gespeichert", KWH(g.storedWh)) : ""}
  </div>`;
  if (s.today) {
    const t = s.today;
    html += `<div class="card"><h2>Heute</h2>
      ${row("Ersparnis", EUR(t.savingsEuro), "battery")}
      ${row("Solarertrag", KWH(t.pvWh), "solar")}
      ${row("Selbst genutzt", KWH(t.selfConsumedWh))}
      ${row("Hausverbrauch", KWH(t.houseWh))}
      ${row("Netzbezug", KWH(t.gridImportWh), "import")}
      ${row("Einspeisung", KWH(t.gridExportWh), "export")}
      ${row("Autarkie", PCT(t.autarkyPercent))}
    </div>`;
  }
  html += `<div class="card"><h2>24 Stunden</h2><canvas id="histChart"></canvas>
    <div class="legend"><span style="color:${COL.import}">Netz</span><span style="color:${COL.solar}">Solar</span><span style="color:${COL.battery}">Speicherabgabe</span></div></div>`;
  html += `<div class="card"><h2>Verbindung</h2>
    ${row((s.link.connected ? "✅ " : "⚠️ ") + "Cloud", ago(s.link.lastDataMs))}
    <div class="note">${s.link.detail}</div></div>`;
  document.getElementById("overview").innerHTML = html;
  loadHistory();
}

function renderBatteries(s) {
  let html = `<div class="card"><h2>Gruppe (${s.batteries.length} Speicher)</h2>
    ${row("Ladestand gesamt", PCT(s.group.socPercent))}
    ${s.group.capacityWh != null ? row("Kapazität", KWH(s.group.capacityWh)) : ""}
    ${row("Solar", W(s.group.pvW), "solar")}
    ${row("Abgabe", W(s.group.outputW))}
    ${row("Ladestand-Unterschied", s.group.socSpread + " %")}
    ${s.group.heldBatteryId ? `<div class="note">Ausgleich aktiv: ein Speicher pausiert.</div>` : ""}
  </div>`;
  for (const b of s.batteries) {
    html += `<div class="card"><h2>${b.name}</h2>
      <div class="bar"><div style="width:${b.socPercent}%"></div></div><div style="height:8px"></div>
      ${row("Ladestand", b.socPercent + " %")}
      ${b.capacityWh != null ? row("Gespeichert", KWH(b.capacityWh * b.socPercent / 100)) : ""}
      ${b.pvInputW.map((w, i) => row("PV-Eingang " + (i + 1), W(w), "solar")).join("")}
      ${b.outputW.map((w, i) => row("Ausgang " + (i + 1) + (b.outputEnabled[i] === false ? " (aus)" : ""), W(w))).join("")}
      ${row("Entladetiefe (DoD)", b.dodPercent != null ? b.dodPercent + " % (bis " + (100 - b.dodPercent) + " %)" : "–")}
      ${row("Lademodus", chargeModeLabel(b.chargeMode))}
      ${row("Adaptiver Modus", b.adaptiveMode == null ? "–" : b.adaptiveMode ? "an" : "aus")}
      ${b.dailyPvWh != null ? row("PV heute", KWH(b.dailyPvWh)) : ""}
      ${b.temperatureC != null ? row("Temperatur", b.temperatureC + " °C") : ""}
      ${row("Firmware", b.firmware || "–")}
      ${row("Aktualisiert", ago(Date.now() - b.ageSeconds * 1000))}
      <div style="height:10px"></div>
      <label class="field">Entladetiefe: <b id="dodv-${b.id}">${b.dodPercent || 90}</b> %</label>
      <input type="range" min="10" max="100" value="${b.dodPercent || 90}" oninput="document.getElementById('dodv-${b.id}').textContent=this.value" id="dod-${b.id}">
      <button class="sec" onclick="sendCmd('${b.id}','dod',+document.getElementById('dod-${b.id}').value)">DoD setzen</button>
      <button class="sec" onclick="sendCmd('${b.id}','chargeMode', ${b.chargeMode === "CHARGE_THEN_DISCHARGE" ? 0 : 1})">Lademodus wechseln</button>
      ${b.adaptiveMode === false ? `<button class="sec" onclick="sendCmd('${b.id}','adaptive',1)">Adaptiven Modus an</button>` : ""}
    </div>`;
  }
  if (s.suggestions && s.suggestions.length) {
    html += `<div class="card"><h2>Vorschläge der Automatik</h2><div class="note">Automatisches Schreiben ist aus.</div>`;
    for (const c of s.suggestions) {
      const label = c.type === "dod" ? "Entladetiefe " + c.value + " %" : c.type === "chargeMode" ? "Lademodus " + (c.value ? "erst voll laden" : "gleichzeitig") : "adaptiv " + (c.value ? "an" : "aus");
      html += `<div class="row"><span>${c.batteryId.slice(-6)}: ${label}</span><button class="sec" onclick="sendCmd('${c.batteryId}','${c.type}',${c.value})">Ausführen</button></div>`;
    }
    html += `</div>`;
  }
  document.getElementById("batteries").innerHTML = html;
}

async function sendCmd(batteryId, type, value) {
  if (!confirm("Einstellung per Cloud an den Speicher senden?\n\nDie Änderung wird dauerhaft gespeichert.")) return;
  try {
    const r = await api("command", { method: "POST", body: JSON.stringify({ batteryId, type, value }) });
    toast(r.ok ? "Gesendet" : "Fehlgeschlagen");
    setTimeout(refresh, 1500);
  } catch (e) { toast("Fehler: " + e.message); }
}

async function loadHistory() {
  try {
    const h = await api("history?hours=24");
    const c = document.getElementById("histChart");
    if (!c) return;
    lineChart(c, [
      { color: COL.import, points: h.points.filter(p => p.gridW != null).map(p => [p.t, p.gridW]) },
      { color: COL.solar, points: h.points.map(p => [p.t, p.pvW]) },
      { color: COL.battery, points: h.points.map(p => [p.t, p.outW]) },
    ], true);
  } catch (e) { /* ignore */ }
}

async function renderStats() {
  const s = await api(`stats?days=${statsDays}&label=${statsDays} Tage`);
  const t = s.total;
  let html = `<div class="card"><div>`;
  for (const [d, lbl] of [[7, "7 Tage"], [30, "30 Tage"], [365, "Jahr"]]) {
    html += `<button class="sec ${statsDays === d ? "on" : ""}" onclick="statsDays=${d};renderStats()">${lbl}</button>`;
  }
  html += `</div></div>`;
  html += `<div class="card"><h2>Ersparnis ${t.date}</h2>
    <div class="big-row">
      <div class="big"><span class="v battery">${EUR(t.savingsEuro)}</span><span class="l">gespart</span></div>
      <div class="big"><span class="v import">${EUR(t.gridCostEuro)}</span><span class="l">Netzkosten</span></div>
      <div class="big"><span class="v">${PCT(t.autarkyPercent)}</span><span class="l">Autarkie</span></div>
    </div></div>`;
  html += `<div class="card"><h2>Energie ${t.date}</h2>
    ${row("Solarertrag", KWH(t.pvWh), "solar")}
    ${row("Hausverbrauch", KWH(t.houseWh))}
    ${row("davon Solar", KWH(t.selfConsumedWh), "battery")}
    ${row("Netzbezug", KWH(t.gridImportWh), "import")}
    ${row("Einspeisung", KWH(t.gridExportWh), "export")}</div>`;
  if (s.days.length) {
    html += `<div class="card"><h2>Tageswerte</h2><canvas id="barChart"></canvas>
      <div class="legend"><span style="color:${COL.battery}">Solar genutzt</span><span style="color:${COL.import}">Bezug</span><span style="color:${COL.export}">Einspeisung</span></div></div>`;
  }
  if (s.meterPeriods && s.meterPeriods.length) {
    html += `<div class="card"><h2>Zählerstand-Zeiträume</h2>`;
    for (const p of s.meterPeriods.slice(0, 8)) {
      html += row(new Date(p.fromMs).toLocaleDateString("de-DE") + "–" + new Date(p.toMs).toLocaleDateString("de-DE"),
        KWH(p.importKwh * 1000) + " Bezug" + (p.savingsEuro != null ? " · " + EUR(p.savingsEuro) : ""));
    }
    html += `</div>`;
  }
  html += `<div class="card"><h2>Zählerstand eintragen</h2>
    <div class="note">Werte vom Stromzähler (Iskra). 1.8.0 = Bezug, 2.8.0 = Einspeisung.</div>
    <label class="field">Bezug 1.8.0 (kWh)</label><input id="mImport" type="number" step="0.1">
    <label class="field">Einspeisung 2.8.0 (kWh, optional)</label><input id="mExport" type="number" step="0.1">
    <button class="primary" onclick="saveMeter()">Eintragen</button></div>`;
  document.getElementById("stats").innerHTML = html;
  if (s.days.length) {
    barChart(document.getElementById("barChart"), s.days.map(d => [
      [COL.battery, d.selfConsumedWh], [COL.import, d.gridImportWh], [COL.export, d.gridExportWh],
    ]));
  }
}

async function saveMeter() {
  const imp = parseFloat(document.getElementById("mImport").value);
  const exp = parseFloat(document.getElementById("mExport").value);
  if (isNaN(imp)) { toast("Bezug eingeben"); return; }
  await api("meter", { method: "POST", body: JSON.stringify({ importKwh: imp, exportKwh: isNaN(exp) ? null : exp }) });
  toast("Eingetragen"); renderStats();
}

async function renderCalibration() {
  const s = lastState;
  if (!s) return;
  const c = s.calibration;
  let html = `<div class="card"><div class="note">Die Kalibrierung lädt einen Speicher voll, hält ihn, entlädt ihn und stellt dann deine Einstellungen wieder her. Immer nur ein Speicher.</div></div>`;
  html += `<div class="card"><h2>Status</h2>`;
  if (!c || !c.running) {
    html += `<div>${c ? "Letzte: " + c.phase : "Noch keine Kalibrierung."}</div>`;
    if (c && c.lastFinishedMs) html += row("Zuletzt", ago(c.lastFinishedMs));
    html += `<div style="height:8px"></div><button class="primary" onclick="calStart([])">Alle kalibrieren</button>`;
    for (const b of s.batteries) html += `<button class="sec" onclick="calStart(['${b.id}'])">${b.name}</button>`;
  } else {
    html += row("Schritt", c.phase) + row("Fortschritt", (c.index + 1) + " von " + c.total) + row("Gestartet", ago(c.startedMs));
    html += `<div style="height:8px"></div><button class="sec" onclick="calAbort()">Abbrechen</button>`;
  }
  if (c && c.results) for (const r of c.results) html += row((r.success ? "✅ " : "❌ ") + r.batteryId.slice(-6), r.message);
  if (!s.automationsMayWrite) html += `<div class="note warn" style="margin-top:8px">„Automatik darf schreiben“ ist aus. Die Kalibrierung macht dann nur Vorschläge.</div>`;
  html += `</div>`;
  html += `<div class="card"><h2>Protokoll</h2><div class="events" id="events">lädt…</div></div>`;
  document.getElementById("calibration").innerHTML = html;
  loadEvents();
}

async function calStart(ids) { await api("calibration/start", { method: "POST", body: JSON.stringify({ batteryIds: ids }) }); toast("Gestartet"); setTimeout(refresh, 1000); }
async function calAbort() { await api("calibration/abort", { method: "POST" }); toast("Abgebrochen"); setTimeout(refresh, 1000); }

async function loadEvents() {
  try {
    const ev = await api("events");
    const d = document.getElementById("events");
    if (d) d.innerHTML = ev.map(e => `<div>${new Date(e.t).toLocaleString("de-DE", { day: "2-digit", month: "2-digit", hour: "2-digit", minute: "2-digit" })} ${e.text}</div>`).join("") || "Keine Einträge.";
  } catch (e) { /* ignore */ }
}

async function renderSettings() {
  const s = await api("settings");
  const html = `<div class="card"><h2>Stromtarif</h2>
    <label class="field">Bezugspreis (ct/kWh)</label><input id="s_import" type="number" step="0.1" value="${s.importCentPerKwh}">
    <label class="field">Einspeisevergütung (ct/kWh)</label><input id="s_feed" type="number" step="0.1" value="${s.feedInCentPerKwh}">
    <label class="field">Grundgebühr (€/Monat)</label><input id="s_base" type="number" step="0.1" value="${s.baseFeeEuroPerMonth}">
    </div>
    <div class="card"><h2>Anlage</h2>
    <label class="field">Wechselrichter max. (W)</label><input id="s_invmax" type="number" value="${s.inverterMaxW}">
    <label class="field">Wirkungsgrad (%)</label><input id="s_eff" type="number" step="1" value="${Math.round(s.inverterEfficiencyPercent)}">
    <label class="field">Abfrage-Intervall (s)</label><input id="s_poll" type="number" value="${s.pollIntervalSeconds}">
    </div>
    <div class="card"><h2>Automatik</h2>
    <div class="switch"><span>Automatik darf Einstellungen ändern</span><input type="checkbox" id="s_write" ${s.automationsMayWrite ? "checked" : ""}></div>
    <div class="note">Aus: nur Vorschläge. An: Kalibrierung und Ausgleich ändern DoD und Lademodus selbst (Tageslimit aktiv).</div>
    <div class="switch"><span>Ladestände ausgleichen</span><input type="checkbox" id="s_bal" ${s.balancerEnabled ? "checked" : ""}></div>
    <label class="field">Kalibrierung alle … Tage (0 = manuell)</label><input id="s_cal" type="number" value="${s.calibrationIntervalDays}">
    </div>
    <div style="padding:0 6px"><button class="primary" onclick="saveSettings()">Speichern</button></div>`;
  document.getElementById("settings").innerHTML = html;
}

async function saveSettings() {
  const body = {
    importCentPerKwh: +v("s_import"), feedInCentPerKwh: +v("s_feed"), baseFeeEuroPerMonth: +v("s_base"),
    inverterEfficiencyPercent: +v("s_eff"), inverterMaxW: +v("s_invmax"), pollIntervalSeconds: +v("s_poll"),
    automationsMayWrite: document.getElementById("s_write").checked,
    balancerEnabled: document.getElementById("s_bal").checked,
    calibrationIntervalDays: +v("s_cal"),
  };
  await api("settings", { method: "POST", body: JSON.stringify(body) });
  toast("Gespeichert");
}
const v = (id) => document.getElementById(id).value;

// ---- charts ----
function lineChart(canvas, series, zero) {
  const dpr = window.devicePixelRatio || 1;
  const w = canvas.clientWidth, h = 160;
  canvas.width = w * dpr; canvas.height = h * dpr;
  const ctx = canvas.getContext("2d"); ctx.scale(dpr, dpr); ctx.clearRect(0, 0, w, h);
  const all = series.flatMap(s => s.points);
  if (all.length < 2) return;
  const xs = all.map(p => p[0]), ys = all.map(p => p[1]);
  let minX = Math.min(...xs), maxX = Math.max(...xs);
  let minY = Math.min(...ys), maxY = Math.max(...ys);
  if (zero) { minY = Math.min(minY, 0); maxY = Math.max(maxY, 0); }
  if (maxY - minY < 1) maxY = minY + 1; if (maxX === minX) maxX = minX + 1;
  const X = (x) => (x - minX) / (maxX - minX) * w;
  const Y = (y) => h - (y - minY) / (maxY - minY) * h;
  if (zero) { ctx.strokeStyle = "rgba(128,128,128,.4)"; ctx.beginPath(); ctx.moveTo(0, Y(0)); ctx.lineTo(w, Y(0)); ctx.stroke(); }
  for (const s of series) {
    if (s.points.length < 2) continue;
    ctx.strokeStyle = s.color; ctx.lineWidth = 2; ctx.beginPath();
    s.points.forEach((p, i) => i ? ctx.lineTo(X(p[0]), Y(p[1])) : ctx.moveTo(X(p[0]), Y(p[1])));
    ctx.stroke();
  }
}
function barChart(canvas, groups) {
  const dpr = window.devicePixelRatio || 1;
  const w = canvas.clientWidth, h = 160;
  canvas.width = w * dpr; canvas.height = h * dpr;
  const ctx = canvas.getContext("2d"); ctx.scale(dpr, dpr); ctx.clearRect(0, 0, w, h);
  const max = Math.max(0.001, ...groups.flat().map(b => b[1]));
  const gw = w / groups.length;
  groups.forEach((bars, gi) => {
    const bw = gw * 0.8 / bars.length;
    bars.forEach((b, bi) => {
      const bh = b[1] / max * h;
      ctx.fillStyle = b[0];
      ctx.fillRect(gi * gw + gw * 0.1 + bi * bw, h - bh, bw * 0.9, bh);
    });
  });
}

// ---- token ----
function showTokenPrompt() { document.getElementById("tokenPrompt").classList.remove("hidden"); }
document.getElementById("tokenSave").onclick = () => {
  token = document.getElementById("tokenInput").value.trim();
  localStorage.setItem("token", token);
  document.getElementById("tokenPrompt").classList.add("hidden");
  refresh();
};

// ---- tabs & loop ----
let activeTab = "overview";
document.querySelectorAll("#tabs button").forEach(btn => {
  btn.onclick = () => {
    activeTab = btn.dataset.tab;
    document.querySelectorAll("#tabs button").forEach(b => b.classList.toggle("active", b === btn));
    document.querySelectorAll(".tab").forEach(t => t.classList.toggle("active", t.id === activeTab));
    renderActive();
  };
});
document.getElementById("refresh").onclick = refresh;

async function refresh() {
  try {
    lastState = await api("state");
    renderActive();
  } catch (e) { /* token prompt or transient */ }
}
function renderActive() {
  if (!lastState) return;
  if (activeTab === "overview") renderOverview(lastState);
  else if (activeTab === "batteries") renderBatteries(lastState);
  else if (activeTab === "stats") renderStats();
  else if (activeTab === "calibration") renderCalibration();
  else if (activeTab === "settings") renderSettings();
}

refresh();
setInterval(() => { if (activeTab === "overview" || activeTab === "batteries") refresh(); }, 10000);
