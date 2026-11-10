"use strict";

const COL = {
  import: "#d84315", export: "#1565c0", solar: "#f9a825", battery: "#2e7d32", green: "#1b5e20",
};
let token = localStorage.getItem("token") || "";
let lastState = null;
let statsView = { mode: localStorage.getItem("statsMode") || "week", anchor: new Date(), from: null, to: null };

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
  return new Date(ms).toLocaleString("de-DE", { day: "2-digit", month: "2-digit", year: "numeric", hour: "2-digit", minute: "2-digit", hourCycle: "h23" });
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

// Card header with an "i" button that opens the explanation window.
function cardHead(title) {
  return `<div class="cardhead"><h2>${title}</h2><button class="infobtn" onclick="openInfo()" title="Erklärung">i</button></div>`;
}

const VALUE_INFO = `
<p class="note">Ohne DTU sind einige Werte geschätzt. Die exakten Netzwerte stehen in „Zählerbasiert“, sobald zwei Ablesungen vorliegen.</p>
<table class="info-table">
  <tr><th>Wert</th><th>Bedeutung</th></tr>
  <tr><td>Solarertrag</td><td>Energie, die die PV-Module erzeugt haben (Summe der PV-Eingänge der Speicher).</td></tr>
  <tr><td>Speicherabgabe</td><td>Energie, die die Speicher an den Wechselrichter abgegeben haben. Bei diesem Aufbau fast gleich dem Solarertrag.</td></tr>
  <tr><td>Hausverbrauch</td><td>Gesamtverbrauch des Hauses = Netzbezug − Einspeisung + Wechselrichter-Abgabe.</td></tr>
  <tr><td>Eigenverbrauch</td><td>Solarstrom, den das Haus selbst genutzt hat (nicht eingespeist). Grundlage der Ersparnis.</td></tr>
  <tr><td>Netzbezug</td><td>Aus dem Netz gekaufter Strom.</td></tr>
  <tr><td>Einspeisung</td><td>Ins Netz abgegebener Strom.</td></tr>
  <tr><td>Autarkie</td><td>Anteil des Hausverbrauchs, der aus eigenem Solar kam (Eigenverbrauch ÷ Hausverbrauch).</td></tr>
</table>
<p class="note">Zusammenhang: Hausverbrauch = Eigenverbrauch + Netzbezug.</p>`;

function openInfo() {
  document.getElementById("infoBody").innerHTML = VALUE_INFO;
  document.getElementById("infoOverlay").classList.remove("hidden");
}
function closeInfo() { document.getElementById("infoOverlay").classList.add("hidden"); }

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
    html += `<div class="card">${cardHead("Heute")}
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
      ${row("Entladetiefe (DoD)", b.dodPercent != null ? b.dodPercent + " % (Mindestladestand " + (100 - b.dodPercent) + " %)" : "–")}
      ${row("Lademodus", chargeModeLabel(b.chargeMode))}
      ${row("Adaptiver Modus", b.adaptiveMode == null ? "–" : b.adaptiveMode ? "an" : "aus")}
      ${b.dailyPvWh != null ? row("PV heute", KWH(b.dailyPvWh)) : ""}
      ${b.temperatureC != null ? row("Temperatur", b.temperatureC + " °C") : ""}
      ${row("Firmware", b.firmware || "–")}
      ${row("Aktualisiert", ago(Date.now() - b.ageSeconds * 1000))}
      <div style="height:10px"></div>
      <label class="field">Entladetiefe: <b id="dodv-${b.id}">${b.dodPercent ?? 90}</b> % → entlädt bis <b>${100 - (b.dodPercent ?? 90)} %</b> Ladestand</label>
      <input type="range" min="0" max="100" value="${b.dodPercent ?? 90}" oninput="document.getElementById('dodv-${b.id}').textContent=this.value" id="dod-${b.id}">
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
    if (!c || !h.points.length) return;
    const t0 = h.points[0].t, t1 = h.points[h.points.length - 1].t;
    const xTicks = [];
    for (let i = 0; i <= 4; i++) {
      const tx = t0 + (t1 - t0) * i / 4;
      xTicks.push({ x: tx, label: new Date(tx).toLocaleTimeString("de-DE", { hour: "2-digit", minute: "2-digit" }) });
    }
    lineChart(c, [
      { color: COL.import, points: h.points.filter(p => p.gridW != null).map(p => [p.t, p.gridW]) },
      { color: COL.solar, points: h.points.map(p => [p.t, p.pvW]) },
      { color: COL.battery, points: h.points.map(p => [p.t, p.outW]) },
    ], { zero: true, unit: "W", xTicks });
  } catch (e) { /* ignore */ }
}

// Energy categories for the statistics chart. key -> {label, color, wh(day), eur?}
const STAT_CATS = [
  { key: "pv", label: "Solarertrag", color: COL.solar, wh: (d) => d.pvWh },
  { key: "house", label: "Hausverbrauch", color: "#6a7bd0", wh: (d) => d.houseWh },
  { key: "self", label: "Eigenverbrauch", color: COL.battery, wh: (d) => d.selfConsumedWh },
  { key: "import", label: "Netzbezug", color: COL.import, wh: (d) => d.gridImportWh },
  { key: "export", label: "Einspeisung", color: COL.export, wh: (d) => d.gridExportWh },
  { key: "battery", label: "Speicherabgabe", color: "#00897b", wh: (d) => d.batteryOutputWh },
];
let statsData = null;
let statsLabels = [];
let statsChartMode = localStorage.getItem("statsChartMode") || "bar";
let statsOff = new Set(JSON.parse(localStorage.getItem("statsOff") || "[]"));
const pad = (n) => String(n).padStart(2, "0");
const fmt = (d) => d.getFullYear() + "-" + pad(d.getMonth() + 1) + "-" + pad(d.getDate());
const fmtMs = (ms) => fmt(new Date(ms));
const deDate = (d) => d.toLocaleDateString("de-DE", { day: "2-digit", month: "2-digit", year: "numeric" });

function statsPersist() {
  localStorage.setItem("statsOff", JSON.stringify([...statsOff]));
  localStorage.setItem("statsChartMode", statsChartMode);
}
function toggleCat(key) {
  if (statsOff.has(key)) statsOff.delete(key); else statsOff.add(key);
  statsPersist(); drawStatsChart(); renderCatChips();
}
function setChartMode(m) { statsChartMode = m; statsPersist(); renderStats(); }
function setMode(m) { statsView.mode = m; localStorage.setItem("statsMode", m); renderStats(); }
function applyCustom() { statsView.from = v("cfrom"); statsView.to = v("cto"); renderStats(); }
function nav(delta) {
  const a = statsView.anchor;
  if (statsView.mode === "day") a.setDate(a.getDate() + delta);
  else if (statsView.mode === "week") a.setDate(a.getDate() + 7 * delta);
  else if (statsView.mode === "month") a.setMonth(a.getMonth() + delta);
  else if (statsView.mode === "year") a.setFullYear(a.getFullYear() + delta);
  statsView.anchor = new Date(a); renderStats();
}
function startOfWeek(d) { const x = new Date(d); x.setDate(x.getDate() - ((x.getDay() + 6) % 7)); return x; }
function computeWindow() {
  const a = statsView.anchor;
  if (statsView.mode === "day") return { from: fmt(a), to: fmt(a), bucket: "hour", label: a.toLocaleDateString("de-DE", { weekday: "short", day: "2-digit", month: "2-digit", year: "numeric" }) };
  if (statsView.mode === "week") { const s = startOfWeek(a), e = new Date(s); e.setDate(s.getDate() + 6); return { from: fmt(s), to: fmt(e), bucket: "day", label: deDate(s) + "–" + deDate(e) }; }
  if (statsView.mode === "month") { const s = new Date(a.getFullYear(), a.getMonth(), 1), e = new Date(a.getFullYear(), a.getMonth() + 1, 0); return { from: fmt(s), to: fmt(e), bucket: "day", label: a.toLocaleDateString("de-DE", { month: "long", year: "numeric" }) }; }
  if (statsView.mode === "year") return { from: a.getFullYear() + "-01-01", to: a.getFullYear() + "-12-31", bucket: "month", label: String(a.getFullYear()) };
  const f = statsView.from || fmt(a), t = statsView.to || fmt(a);
  const span = (new Date(t) - new Date(f)) / 86400000;
  return { from: f, to: t, bucket: span > 92 ? "month" : "day", label: f + " – " + t };
}

function renderCatChips() {
  const box = document.getElementById("catChips");
  if (!box) return;
  box.innerHTML = STAT_CATS.map(c => {
    const on = !statsOff.has(c.key);
    return `<button class="chip ${on ? "on" : ""}" style="${on ? `--c:${c.color}` : ""}" onclick="toggleCat('${c.key}')">
      <span class="dot" style="background:${on ? c.color : "transparent"};border-color:${c.color}"></span>${c.label}</button>`;
  }).join("");
}
let statsSelected = null;
function xTicksFor(labels) {
  const step = Math.max(1, Math.ceil(labels.length / 7));
  return labels.map((l, i) => ({ x: i, label: l, i })).filter(t => t.i % step === 0 || t.i === labels.length - 1);
}
function drawStatsChart() {
  const canvas = document.getElementById("statsChart");
  if (!canvas || !statsData || !statsData.length) return;
  if (statsSelected == null || statsSelected > statsData.length - 1) statsSelected = statsData.length - 1;
  const cats = STAT_CATS.filter(c => !statsOff.has(c.key));
  if (statsChartMode === "line") {
    lineChart(canvas, cats.map(c => ({ color: c.color, points: statsData.map((d, i) => [i, c.wh(d) / 1000]) })),
      { unit: "kWh", selected: statsSelected, xTicks: xTicksFor(statsLabels) });
  } else {
    barChart(canvas, statsData.map(d => cats.map(c => [c.color, c.wh(d) / 1000])),
      { unit: "kWh", labels: statsLabels, selected: statsSelected });
  }
  updateReadout();
}
function onStatsSlider(v) { statsSelected = +v; drawStatsChart(); }
function updateReadout() {
  const box = document.getElementById("readout");
  if (!box) return;
  const d = statsData && statsData[statsSelected];
  if (!d) { box.innerHTML = ""; return; }
  box.innerHTML = `<div class="ro-h">${statsLabels[statsSelected]}</div>` +
    STAT_CATS.filter(c => !statsOff.has(c.key)).map(c =>
      `<span class="ro"><span class="dot" style="background:${c.color};border-color:${c.color}"></span>${c.label}: <b>${KWH(c.wh(d))}</b></span>`).join("");
}

async function renderStats() {
  const w = computeWindow();
  const s = await api(`stats?from=${w.from}&to=${w.to}&bucket=${w.bucket}&label=${encodeURIComponent(w.label)}`);
  statsData = s.buckets.map(b => b.energy);
  statsLabels = s.buckets.map(b => b.label);
  statsSelected = null;
  const t = s.total;
  let html = `<div class="card"><div class="chips">`;
  for (const [m, lbl] of [["day", "Tag"], ["week", "Woche"], ["month", "Monat"], ["year", "Jahr"], ["custom", "Eigen"]])
    html += `<button class="sec ${statsView.mode === m ? "on" : ""}" onclick="setMode('${m}')">${lbl}</button>`;
  html += `</div><div style="margin-top:8px"><button class="sec" onclick="sinceLastReading()">seit letztem Ablesen</button></div>`;
  if (statsView.mode === "custom") {
    html += `<div style="margin-top:8px"><label class="field">von</label><input type="date" id="cfrom" value="${fmtMs(s.fromMs)}">
      <label class="field">bis</label><input type="date" id="cto" value="${fmtMs(s.toMs - 1)}">
      <button class="primary" onclick="applyCustom()">Anzeigen</button></div>`;
  } else {
    html += `<div class="navrow"><button class="ghost" onclick="nav(-1)">◀</button><b>${w.label}</b><button class="ghost" onclick="nav(1)">▶</button></div>`;
  }
  html += `</div>`;

  html += `<div class="card"><h2>Ersparnis</h2><div class="big-row">
    <div class="big"><span class="v battery">${EUR(t.savingsEuro)}</span><span class="l">gespart</span></div>
    <div class="big"><span class="v import">${EUR(t.gridCostEuro)}</span><span class="l">Netzkosten</span></div>
    <div class="big"><span class="v">${PCT(t.autarkyPercent)}</span><span class="l">Autarkie</span></div></div></div>`;

  if (s.meterTotal) {
    const m = s.meterTotal;
    html += `<div class="card"><h2>Zählerbasiert (exakt)</h2>
      <div class="note">Aus deinen Zählerständen in diesem Zeitraum berechnet – die genaue Grundlage, nicht geschätzt.</div>
      ${row("Zeitraum", new Date(m.fromMs).toLocaleDateString("de-DE") + "–" + new Date(m.toMs).toLocaleDateString("de-DE"))}
      ${row("Netzbezug", KWH(m.importKwh * 1000), "import")}
      ${m.exportKwh != null ? row("Einspeisung", KWH(m.exportKwh * 1000), "export") : ""}
      ${m.houseKwh != null ? row("Hausverbrauch", KWH(m.houseKwh * 1000)) : ""}
      ${m.selfConsumedKwh != null ? row("Eigenverbrauch", KWH(m.selfConsumedKwh * 1000), "battery") : ""}
      ${m.savingsEuro != null ? row("Ersparnis", EUR(m.savingsEuro), "battery") : ""}</div>`;
  }

  if (statsData.length) {
    html += `<div class="card"><h2>Diagramm</h2>
      <div style="margin-bottom:8px">
        <button class="sec ${statsChartMode === "bar" ? "on" : ""}" onclick="setChartMode('bar')">Balken</button>
        <button class="sec ${statsChartMode === "line" ? "on" : ""}" onclick="setChartMode('line')">Linie</button></div>
      <div id="catChips" class="chips"></div>
      <canvas id="statsChart"></canvas>
      <input type="range" min="0" max="${Math.max(0, statsData.length - 1)}" value="${statsData.length - 1}" oninput="onStatsSlider(this.value)" style="width:100%">
      <div id="readout" class="readout"></div>
      <div class="note">Regler ziehen für einen Zeitpunkt. Kategorien antippen zum Ein-/Ausblenden. Werte in kWh.</div></div>`;
  } else {
    html += `<div class="card"><div class="note">Für diesen Zeitraum liegen noch keine Daten vor.</div></div>`;
  }

  html += `<div class="card">${cardHead("Summe")}<div class="note" style="margin-top:-4px;margin-bottom:6px">Zeitraum: ${w.label}</div>${STAT_CATS.map(c => row(c.label, KWH(c.wh(t)))).join("")}${row("Autarkie", PCT(t.autarkyPercent))}</div>`;

  if (s.meterPeriods && s.meterPeriods.length) {
    html += `<div class="card"><h2>Ablesungen im Zeitraum</h2>`;
    for (const p of s.meterPeriods.slice(0, 10))
      html += row(new Date(p.fromMs).toLocaleDateString("de-DE") + "–" + new Date(p.toMs).toLocaleDateString("de-DE"),
        KWH(p.importKwh * 1000) + " Bezug" + (p.savingsEuro != null ? " · " + EUR(p.savingsEuro) : ""));
    html += `</div>`;
  }

  html += `<div class="card"><h2>Zählerstand eintragen</h2>
    <div class="note">Werte vom Stromzähler (Iskra). 1.8.0 = Bezug, 2.8.0 = Einspeisung. Das Ablesedatum ist die Grundlage für die exakte Auswertung.</div>
    <label class="field">Ablesedatum</label><input id="mDate" type="date" value="${fmtMs(Date.now())}">
    <label class="field">Bezug 1.8.0 (kWh)</label><input id="mImport" type="number" step="0.1">
    <label class="field">Einspeisung 2.8.0 (kWh, optional)</label><input id="mExport" type="number" step="0.1">
    <button class="primary" onclick="saveMeter()">Eintragen</button></div>`;

  document.getElementById("stats").innerHTML = html;
  renderCatChips(); drawStatsChart();
}

function sinceLastReading() {
  const ms = lastState && lastState.lastMeterReadingMs;
  if (!ms) { toast("Noch keine Ablesung eingetragen"); return; }
  statsView.mode = "custom"; localStorage.setItem("statsMode", "custom");
  statsView.from = fmtMs(ms); statsView.to = fmt(new Date());
  renderStats();
}

async function saveMeter() {
  const imp = parseFloat(v("mImport"));
  const exp = parseFloat(v("mExport"));
  const dateStr = v("mDate");
  if (isNaN(imp)) { toast("Bezug eingeben"); return; }
  const dateMs = dateStr ? new Date(dateStr + "T12:00:00").getTime() : Date.now();
  await api("meter", { method: "POST", body: JSON.stringify({ importKwh: imp, exportKwh: isNaN(exp) ? null : exp, dateMs }) });
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
    if (d) d.innerHTML = ev.map(e => `<div>${new Date(e.t).toLocaleString("de-DE", { day: "2-digit", month: "2-digit", year: "numeric", hour: "2-digit", minute: "2-digit", hourCycle: "h23" })} ${e.text}</div>`).join("") || "Keine Einträge.";
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
    <label class="field">Abfrage-Intervall (s), Minimum 5</label><input id="s_poll" type="number" min="5" value="${s.pollIntervalSeconds}">
    <div class="note">Kleiner = häufigere Live-Werte, aber mehr Last auf der Marstek-Cloud. 5 s ist das sinnvolle Minimum; 10–30 s sind sparsam.</div>
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
const CHART_H = 180, PAD_L = 46, PAD_B = 18, PAD_T = 8;
function axisFmt(v) {
  const a = Math.abs(v);
  if (a >= 1000) return (v / 1000).toFixed(a >= 10000 ? 0 : 1).replace(".", ",") + "k";
  if (a >= 10 || v === 0) return String(Math.round(v));
  return v.toFixed(1).replace(".", ",");
}
function axisTicks(min, max) {
  if (min === max) max = min + 1;
  return [min, (min + max) / 2, max];
}
function chartSetup(canvas) {
  const dpr = window.devicePixelRatio || 1, w = canvas.clientWidth, h = CHART_H;
  canvas.width = w * dpr; canvas.height = h * dpr;
  const ctx = canvas.getContext("2d"); ctx.scale(dpr, dpr); ctx.clearRect(0, 0, w, h);
  return { ctx, w, h, L: PAD_L, R: w - 8, T: PAD_T, B: h - PAD_B };
}
function drawYAxis(ctx, g, minY, maxY, Y, unit) {
  const muted = getComputedStyle(document.body).getPropertyValue("--muted") || "#888";
  ctx.font = "10px system-ui"; ctx.fillStyle = muted;
  ctx.strokeStyle = "rgba(128,128,128,.18)"; ctx.lineWidth = 1;
  for (const tv of axisTicks(minY, maxY)) {
    const yy = Y(tv);
    ctx.beginPath(); ctx.moveTo(g.L, yy); ctx.lineTo(g.R, yy); ctx.stroke();
    ctx.textAlign = "right"; ctx.textBaseline = "middle"; ctx.fillText(axisFmt(tv), g.L - 4, yy);
  }
  if (unit) { ctx.textAlign = "left"; ctx.textBaseline = "top"; ctx.fillText(unit, 2, 0); }
}
function drawXLabels(ctx, g, ticks) {
  const muted = getComputedStyle(document.body).getPropertyValue("--muted") || "#888";
  ctx.fillStyle = muted; ctx.font = "10px system-ui"; ctx.textAlign = "center"; ctx.textBaseline = "top";
  for (const t of ticks) ctx.fillText(t.label, t.px, g.B + 3);
}

// series: [{color, points:[[x,y]]}]; opts: {zero, unit, xTicks:[{x,label}], selected (x value)}
function lineChart(canvas, series, opts = {}) {
  const { zero = false, unit = "", xTicks = null, selected = null } = opts;
  const g = chartSetup(canvas); const { ctx } = g;
  const all = series.flatMap(s => s.points);
  if (!all.length) return;
  const xs = all.map(p => p[0]), ys = all.map(p => p[1]);
  let minX = Math.min(...xs), maxX = Math.max(...xs), minY = Math.min(...ys), maxY = Math.max(...ys);
  if (zero) { minY = Math.min(minY, 0); maxY = Math.max(maxY, 0); }
  if (maxY - minY < 0.001) maxY = minY + 1; if (maxX === minX) maxX = minX + 1;
  const X = (x) => g.L + (x - minX) / (maxX - minX) * (g.R - g.L);
  const Y = (y) => g.B - (y - minY) / (maxY - minY) * (g.B - g.T);
  drawYAxis(ctx, g, minY, maxY, Y, unit);
  if (zero) { ctx.strokeStyle = "rgba(128,128,128,.4)"; ctx.beginPath(); ctx.moveTo(g.L, Y(0)); ctx.lineTo(g.R, Y(0)); ctx.stroke(); }
  if (selected != null) {
    const sx = X(selected); ctx.strokeStyle = "rgba(128,128,128,.6)"; ctx.setLineDash([4, 3]);
    ctx.beginPath(); ctx.moveTo(sx, g.T); ctx.lineTo(sx, g.B); ctx.stroke(); ctx.setLineDash([]);
  }
  for (const s of series) {
    if (s.points.length === 1) { ctx.fillStyle = s.color; ctx.beginPath(); ctx.arc(X(s.points[0][0]), Y(s.points[0][1]), 3, 0, 7); ctx.fill(); continue; }
    if (s.points.length < 2) continue;
    ctx.strokeStyle = s.color; ctx.lineWidth = 2; ctx.beginPath();
    s.points.forEach((p, i) => i ? ctx.lineTo(X(p[0]), Y(p[1])) : ctx.moveTo(X(p[0]), Y(p[1]))); ctx.stroke();
    if (selected != null) { const pt = s.points.find(p => p[0] === selected); if (pt) { ctx.fillStyle = s.color; ctx.beginPath(); ctx.arc(X(selected), Y(pt[1]), 3.5, 0, 7); ctx.fill(); } }
  }
  if (xTicks) drawXLabels(ctx, g, xTicks.map(t => ({ px: X(t.x), label: t.label })));
}

// groups: [[ [color,value] ]]; opts: {unit, labels:[string], selected (group index)}
function barChart(canvas, groups, opts = {}) {
  const { unit = "", labels = null, selected = null } = opts;
  const g = chartSetup(canvas); const { ctx } = g;
  if (!groups.length) return;
  const max = Math.max(0.001, ...groups.flat().map(b => b[1]));
  const Y = (v) => g.B - v / max * (g.B - g.T);
  drawYAxis(ctx, g, 0, max, Y, unit);
  const gw = (g.R - g.L) / groups.length;
  groups.forEach((bars, gi) => {
    if (selected === gi) { ctx.fillStyle = "rgba(128,128,128,.14)"; ctx.fillRect(g.L + gi * gw, g.T, gw, g.B - g.T); }
    const bw = gw * 0.8 / Math.max(1, bars.length);
    bars.forEach((b, bi) => { ctx.fillStyle = b[0]; ctx.fillRect(g.L + gi * gw + gw * 0.1 + bi * bw, Y(b[1]), bw * 0.9, g.B - Y(b[1])); });
  });
  if (labels) {
    const step = Math.max(1, Math.ceil(groups.length / 7));
    const ticks = labels.map((l, i) => ({ px: g.L + i * gw + gw / 2, label: l, i }))
      .filter(t => t.i % step === 0 || t.i === labels.length - 1);
    drawXLabels(ctx, g, ticks);
  }
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
