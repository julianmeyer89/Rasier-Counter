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
function subRow(label, value, cls) {
  return `<div class="row sub"><span class="label">${label}</span><span class="value ${cls || ""}">${value}</span></div>`;
}
function subhead(text) { return `<div class="subhead">${text}</div>`; }

// Card header with an "i" button that opens the explanation window.
function cardHead(title) {
  return `<div class="cardhead"><h2>${title}</h2><button class="infobtn" onclick="openInfo()" title="Erklärung">i</button></div>`;
}

const VALUE_INFO = `
<p class="note">Ohne DTU sind einige Werte geschätzt. Die exakten Netzwerte stehen in „Zählerbasiert“, sobald zwei Ablesungen vorliegen.</p>
<p class="note">Die Werte sind gruppiert in <b>Verbrauch</b> (was das Haus braucht) und <b>Erzeugung</b> (was die Solaranlage liefert).</p>
<p class="note"><b>Grundlage:</b> Wechselrichter-Abgabe = Speicherabgabe × Wirkungsgrad (höchstens die WR-Maximalleistung). Daraus folgen die übrigen Werte:</p>
<table class="info-table">
  <tr><th>Wert</th><th>Berechnung</th></tr>
  <tr><td>Hausverbrauch</td><td>= „davon Solar“ + „aus dem Netz“</td></tr>
  <tr><td>davon Solar</td><td>= Speicherabgabe × Wirkungsgrad − Einspeisung</td></tr>
  <tr><td>aus dem Netz</td><td>= am CT002 gemessener Bezug</td></tr>
  <tr><td>Solarertrag</td><td>= PV-Eingang Speicher 1 + PV-Eingang Speicher 2</td></tr>
  <tr><td>Einspeisung</td><td>= am CT002 gemessene Einspeisung</td></tr>
  <tr><td>Autarkie</td><td>= „davon Solar“ ÷ Hausverbrauch</td></tr>
  <tr><td>Ersparnis</td><td>= „davon Solar“ × Bezugspreis + Einspeisung × Einspeisevergütung</td></tr>
</table>
<p class="note">Im 24‑Stunden-Diagramm und in der Statistik-Grafik erscheint zusätzlich „Speicherabgabe“ = Ausgang Speicher 1 + Ausgang Speicher 2 (bei diesem Aufbau fast gleich dem Solarertrag).</p>`;

const EFFICIENCY_INFO = `
<p class="note">Der Wechselrichter wandelt den Gleichstrom der Speicher in Haushaltsstrom (Wechselstrom) um. Dabei geht ein kleiner Teil als Wärme verloren. Der Wirkungsgrad sagt, wie viel davon tatsächlich im Haus ankommt – 95 % bedeutet 5 % Verlust.</p>
<p class="note"><b>So wird gerechnet:</b> Die Speicher melden, wie viel sie an den Wechselrichter abgeben. Die im Haus ankommende Leistung = <i>Speicherabgabe × Wirkungsgrad</i>. Dieser Wert steckt dann in allen abgeleiteten Zahlen.</p>
<table class="info-table">
  <tr><th>Wert</th><th>Formel (Wirkungsgrad = W)</th></tr>
  <tr><td>Wechselrichter-Abgabe</td><td>= Speicherabgabe × W (max. WR-Leistung)</td></tr>
  <tr><td>davon Solar</td><td>= Speicherabgabe × W − Einspeisung → steigt mit W</td></tr>
  <tr><td>Hausverbrauch</td><td>= „davon Solar“ + „aus dem Netz“ → steigt mit W</td></tr>
  <tr><td>Autarkie</td><td>= „davon Solar“ ÷ Hausverbrauch → steigt mit W</td></tr>
  <tr><td>Ersparnis</td><td>= „davon Solar“ × Bezugspreis + Einspeisung × Vergütung → nur der erste Teil steigt mit W</td></tr>
</table>
<p class="note"><b>Nicht betroffen:</b> „aus dem Netz“ und Einspeisung werden direkt am CT002 gemessen und sind vom Wirkungsgrad unabhängig – egal, was hier eingestellt ist.</p>
<p class="note"><b>Wann die Änderung rückwirkt:</b> Die bereits gespeicherten Tagessummen (Woche/Monat/Jahr) werden <b>nicht</b> neu berechnet – sie behalten den Wirkungsgrad, mit dem sie aufgezeichnet wurden; nur neue Messungen ab jetzt nutzen den neuen Wert. Die Stunden-Ansicht der letzten rund zwei Tage und die Werte unter „Zählerbasiert (exakt)“ werden dagegen bei jedem Aufruf neu gerechnet und übernehmen den neuen Wirkungsgrad sofort, auch rückwirkend.</p>
<p class="note">Realistisch für den HMS-1600-4T sind etwa 92–96 %. Zu hoch gesetzt wirken die Werte zu optimistisch, zu niedrig zu pessimistisch.</p>`;

const INFO = {
  values: { title: "Was bedeuten die Werte?", body: VALUE_INFO },
  efficiency: { title: "Wirkungsgrad", body: EFFICIENCY_INFO },
};

function openInfo(key) {
  const i = INFO[key] || INFO.values;
  document.getElementById("infoTitle").textContent = i.title;
  document.getElementById("infoBody").innerHTML = i.body;
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
      ${subhead("Verbrauch")}
      ${row("Hausverbrauch", KWH(t.houseWh))}
      ${subRow("davon Solar", KWH(t.selfConsumedWh), "battery")}
      ${subRow("aus dem Netz", KWH(t.gridImportWh), "import")}
      ${subhead("Erzeugung")}
      ${row("Solarertrag", KWH(t.pvWh), "solar")}
      ${row("Einspeisung", KWH(t.gridExportWh), "export")}
      ${row("Autarkie", PCT(t.autarkyPercent))}
    </div>`;
  }
  html += `<div class="card"><h2>24 Stunden</h2><canvas id="histChart"></canvas>
    <div class="legend"><span style="color:${COL.import}">Netz</span><span style="color:${COL.solar}">Solar</span><span style="color:${COL.battery}">Speicherabgabe</span></div></div>`;
  const q = s.meterFreshPercent;
  const qIcon = q == null ? "" : (q >= 95 ? "✅ " : q >= 80 ? "🟡 " : "⚠️ ");
  const qRow = q == null ? "" : row(qIcon + "Datenqualität CT002", q + " %");
  html += `<div class="card"><h2>Verbindung</h2>
    ${row((s.link.connected ? "✅ " : "⚠️ ") + "Cloud", ago(s.link.lastDataMs))}
    ${qRow}
    <div class="note">${s.link.detail}</div>
    ${q != null && q < 80 ? '<div class="note">Viele Abfragen bleiben ohne frische Antwort – evtl. ist das CT002-Intervall zu kurz. In den Einstellungen erhöhen.</div>' : ""}</div>`;
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
      ${b.dailyOutputWh != null ? row("Abgabe heute", KWH(b.dailyOutputWh), "battery") : ""}
      ${b.dailyPvWh != null ? row("PV heute", KWH(b.dailyPvWh), "solar") : ""}
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
  const modeBtns = [["day", "Tag"], ["week", "Woche"], ["month", "Monat"], ["year", "Jahr"]]
    .map(([m, l]) => `<button class="sec ${batView.mode === m ? "on" : ""}" onclick="setBatMode('${m}')">${l}</button>`).join("");
  html += `<div class="card"><div class="chips">${modeBtns}</div>
    <div class="navrow"><button class="ghost" onclick="batNav(-1)">◀</button><b id="batLabel"></b><button class="ghost" onclick="batNav(1)">▶</button></div></div>
    <div class="card"><h2>Lade-/Entladekurven je Speicher</h2>
    <div style="margin-bottom:8px">
      <button class="sec ${batChartMode === "bar" ? "on" : ""}" onclick="setBatChartMode('bar')">Balken</button>
      <button class="sec ${batChartMode === "line" ? "on" : ""}" onclick="setBatChartMode('line')">Linie</button></div>
    <div id="batChips" class="chips"></div>
    <canvas id="batChart"></canvas>
    <input type="range" id="batSlider" min="0" max="0" value="0" oninput="onBatSlider(this.value)" style="width:100%">
    <div id="batReadout" class="readout"></div>
    <div class="note">Ladung/Entladung = Energie in bzw. aus dem Speicher (PV minus Abgabe). Tag: 5‑Minuten-Auflösung, sonst je Tag bzw. Monat. Kategorien antippen zum Ein-/Ausblenden.</div></div>`;
  document.getElementById("batteries").innerHTML = html;
  loadBatteryStats();
}

// ---- per-battery charge/discharge chart (mirrors the statistics chart) ----
const BAT_METRICS = [
  { key: "charge", label: "Ladung", field: "charge" },
  { key: "discharge", label: "Entladung", field: "discharge" },
  { key: "pv", label: "PV", field: "pv" },
  { key: "output", label: "Abgabe", field: "output" },
];
// One colour family per battery (teal, indigo, …), one shade per metric.
const BAT_PALETTE = ["#00897b", "#26a69a", "#80cbc4", "#4db6ac", "#3949ab", "#5c6bc0", "#9fa8da", "#7986cb", "#e8a33d", "#c0557a"];
let batView = { mode: localStorage.getItem("batMode") || "week", anchor: new Date() };
let batMetrics = new Set(JSON.parse(localStorage.getItem("batMetrics") || '["discharge","charge"]'));
let batChartMode = localStorage.getItem("batChartMode") || "bar";
let batData = null, batKey = "", batSelected = null;
const batColor = (bi, mi) => BAT_PALETTE[(bi * BAT_METRICS.length + mi) % BAT_PALETTE.length];

function computeBatWindow() {
  const a = batView.anchor;
  if (batView.mode === "day") return { from: fmt(a), to: fmt(a), bucket: "5min", label: a.toLocaleDateString("de-DE", { weekday: "short", day: "2-digit", month: "2-digit", year: "numeric" }) };
  if (batView.mode === "week") { const s = startOfWeek(a), e = new Date(s); e.setDate(s.getDate() + 6); return { from: fmt(s), to: fmt(e), bucket: "day", label: deDate(s) + "–" + deDate(e) }; }
  if (batView.mode === "month") { const s = new Date(a.getFullYear(), a.getMonth(), 1), e = new Date(a.getFullYear(), a.getMonth() + 1, 0); return { from: fmt(s), to: fmt(e), bucket: "day", label: a.toLocaleDateString("de-DE", { month: "long", year: "numeric" }) }; }
  return { from: a.getFullYear() + "-01-01", to: a.getFullYear() + "-12-31", bucket: "month", label: String(a.getFullYear()) };
}
function setBatMode(m) { batView.mode = m; localStorage.setItem("batMode", m); batSelected = null; renderBatteries(lastState); }
function batNav(d) {
  const a = batView.anchor;
  if (batView.mode === "day") a.setDate(a.getDate() + d);
  else if (batView.mode === "week") a.setDate(a.getDate() + 7 * d);
  else if (batView.mode === "month") a.setMonth(a.getMonth() + d);
  else a.setFullYear(a.getFullYear() + d);
  batView.anchor = new Date(a); batSelected = null; renderBatteries(lastState);
}
function setBatChartMode(m) { batChartMode = m; localStorage.setItem("batChartMode", m); renderBatteries(lastState); }
function toggleBatMetric(k) {
  if (batMetrics.has(k)) batMetrics.delete(k); else batMetrics.add(k);
  localStorage.setItem("batMetrics", JSON.stringify([...batMetrics])); drawBatteryChart();
}
function onBatSlider(v) { batSelected = +v; drawBatteryChart(); }
async function loadBatteryStats(force) {
  const w = computeBatWindow();
  const lbl = document.getElementById("batLabel"); if (lbl) lbl.textContent = w.label;
  const key = `${w.from}|${w.to}|${w.bucket}`;
  try {
    if (force || key !== batKey || !batData) {
      batData = await api(`batterystats?from=${w.from}&to=${w.to}&bucket=${w.bucket}`);
      batKey = key;
    }
    drawBatteryChart();
  } catch (e) { /* ignore */ }
}
function batSeriesList() {
  const out = [];
  (batData?.series || []).forEach((b, bi) => BAT_METRICS.forEach((m, mi) => {
    if (batMetrics.has(m.key)) out.push({ color: batColor(bi, mi), label: `${b.name} ${m.label}`, values: b[m.field] || [] });
  }));
  return out;
}
function renderBatChips() {
  const box = document.getElementById("batChips"); if (!box) return;
  box.innerHTML = BAT_METRICS.map((m, mi) => {
    const on = batMetrics.has(m.key), c = batColor(0, mi);
    return `<button class="chip ${on ? "on" : ""}" style="${on ? `--c:${c}` : ""}" onclick="toggleBatMetric('${m.key}')"><span class="dot" style="background:${on ? c : "transparent"};border-color:${c}"></span>${m.label}</button>`;
  }).join("");
}
function drawBatteryChart() {
  const canvas = document.getElementById("batChart"); if (!canvas) return;
  renderBatChips();
  const labels = batData?.labels || [];
  const series = batSeriesList();
  const slider = document.getElementById("batSlider"), ro = document.getElementById("batReadout");
  if (!labels.length || !series.length) {
    if (slider) slider.max = 0;
    if (ro) ro.innerHTML = `<div class="note">Für diesen Zeitraum liegen noch keine Speicherdaten vor.</div>`;
    barChart(canvas, [], {}); return;
  }
  if (batSelected == null || batSelected > labels.length - 1) batSelected = labels.length - 1;
  if (slider) { slider.max = labels.length - 1; slider.value = batSelected; }
  if (batChartMode === "line") {
    lineChart(canvas, series.map(s => ({ color: s.color, points: labels.map((_, i) => [i, (s.values[i] || 0) / 1000]) })),
      { unit: "kWh", selected: batSelected, xTicks: /^\d\d:\d\d$/.test(labels[0]) ? timeTicks(labels) : xTicksFor(labels) });
  } else {
    barChart(canvas, labels.map((_, i) => series.map(s => [s.color, (s.values[i] || 0) / 1000])), { unit: "kWh", labels, selected: batSelected });
  }
  ro.innerHTML = `<div class="ro-h">${labels[batSelected]}</div>` + series.map(s =>
    `<span class="ro" style="color:${s.color}"><span class="dot" style="background:${s.color};border-color:${s.color}"></span>${s.label}: <b style="color:${s.color}">${KWH(s.values[batSelected] || 0)}</b></span>`).join("");
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
  if (statsView.mode === "day") return { from: fmt(a), to: fmt(a), bucket: "5min", label: a.toLocaleDateString("de-DE", { weekday: "short", day: "2-digit", month: "2-digit", year: "numeric" }) };
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
// Time labels "HH:MM" -> ticks on full hours every 3 h.
function timeTicks(labels) {
  const ticks = [];
  labels.forEach((l, i) => {
    const [h, m] = l.split(":");
    if (m === "00" && (+h) % 3 === 0) ticks.push({ x: i, label: h + ":00" });
  });
  return ticks;
}
// For the day view (labels "HH:MM") place ticks on full hours; otherwise space evenly.
function statsXTicks() {
  if (statsLabels.length && /^\d\d:\d\d$/.test(statsLabels[0])) return timeTicks(statsLabels);
  return xTicksFor(statsLabels);
}
function drawStatsChart() {
  const canvas = document.getElementById("statsChart");
  if (!canvas || !statsData || !statsData.length) return;
  if (statsSelected == null || statsSelected > statsData.length - 1) statsSelected = statsData.length - 1;
  const cats = STAT_CATS.filter(c => !statsOff.has(c.key));
  if (statsChartMode === "line") {
    lineChart(canvas, cats.map(c => ({ color: c.color, points: statsData.map((d, i) => [i, c.wh(d) / 1000]) })),
      { unit: "kWh", selected: statsSelected, xTicks: statsXTicks() });
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
      `<span class="ro" style="color:${c.color}"><span class="dot" style="background:${c.color};border-color:${c.color}"></span>${c.label}: <b style="color:${c.color}">${KWH(c.wh(d))}</b></span>`).join("");
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

  html += `<div class="card">${cardHead("Summe")}
    <div class="note" style="margin-top:-4px;margin-bottom:6px">Zeitraum: ${w.label}</div>
    ${subhead("Verbrauch")}
    ${row("Hausverbrauch", KWH(t.houseWh))}
    ${subRow("davon Solar", KWH(t.selfConsumedWh), "battery")}
    ${subRow("aus dem Netz", KWH(t.gridImportWh), "import")}
    ${subhead("Erzeugung")}
    ${row("Solarertrag", KWH(t.pvWh), "solar")}
    ${row("Einspeisung", KWH(t.gridExportWh), "export")}
    ${row("Ersparnis", EUR(t.savingsEuro), "battery")}
    ${row("Autarkie", PCT(t.autarkyPercent))}</div>`;

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
    <label class="field">Wirkungsgrad (%) <button class="infobtn infobtn-inline" onclick="openInfo('efficiency')" title="Erklärung">i</button></label><input id="s_eff" type="number" step="1" value="${Math.round(s.inverterEfficiencyPercent)}">
    <label class="field">Abfrage CT002 (s), Minimum 0,5, in 0,1-Schritten</label><input id="s_pollct" type="number" min="0.5" step="0.1" value="${s.ct002PollIntervalSeconds}">
    <label class="field">Abfrage Speicher (s), Minimum 5</label><input id="s_pollbat" type="number" min="5" step="1" value="${s.batteryPollIntervalSeconds}">
    <div class="note">Der CT002 (Netzzähler) wird oft abgefragt für feine Live-Werte; die Speicher ändern sich langsam und werden seltener abgefragt. Empfehlung: CT002 2 s, Speicher 5 s. Werte unter 1 s gehen, erzeugen aber die meiste Dauerlast — bei Problemen zeigt die Datenqualität (Übersicht) es an, dann wieder erhöhen.</div>
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
    inverterEfficiencyPercent: +v("s_eff"), inverterMaxW: +v("s_invmax"),
    ct002PollIntervalSeconds: +v("s_pollct"), batteryPollIntervalSeconds: +v("s_pollbat"),
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
