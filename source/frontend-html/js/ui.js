const $ = (sel, root) => (root || document).querySelector(sel);
const $$ = (sel, root) => Array.from((root || document).querySelectorAll(sel));

function esc(v) {
  return String(v === null || v === undefined ? "" : v)
    .replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;").replace(/"/g, "&quot;").replace(/'/g, "&#39;");
}

function toast(msg, type) {
  let box = document.getElementById("toast");
  if (!box) { box = document.createElement("div"); box.id = "toast"; document.body.appendChild(box); }
  const t = document.createElement("div");
  t.className = "toast " + (type || "");
  t.textContent = msg;
  box.appendChild(t);
  setTimeout(() => t.remove(), 4200);
}

function banner(el, type, html) { el.innerHTML = html ? '<div class="banner ' + type + '">' + html + "</div>" : ""; }

function fillSelect(sel, items, valueFn, labelFn, placeholder, keep) {
  const prev = keep ? sel.value : "";
  sel.innerHTML = (placeholder ? '<option value="">' + esc(placeholder) + "</option>" : "") +
    items.map((it) => '<option value="' + esc(valueFn(it)) + '">' + esc(labelFn(it)) + "</option>").join("");
  if (prev && Array.from(sel.options).some((o) => o.value === prev)) sel.value = prev;
}

function fmtDateTime(ts) {
  if (!ts) return "";
  const d = new Date(String(ts).replace(" ", "T").replace(/\.0$/, ""));
  if (isNaN(d)) return ts;
  return d.toLocaleDateString("en-GB", { day: "2-digit", month: "short", year: "numeric" }) + ", " +
    d.toLocaleTimeString("en-GB", { hour: "2-digit", minute: "2-digit" });
}
function fmtDate(s) {
  const d = new Date(s + "T00:00:00");
  return isNaN(d) ? s : d.toLocaleDateString("en-GB", { weekday: "short", day: "2-digit", month: "short", year: "numeric" });
}

function labBadge(isLab) {
  return isLab ? '<span class="badge badge-lab">Practical</span>' : '<span class="badge badge-lecture">Lecture</span>';
}

function barRows(rows, labelKey, valueKey, max, cls, fmt) {
  const top = max || Math.max(1, ...rows.map((r) => Number(r[valueKey]) || 0));
  return rows.map((r) => {
    const v = Number(r[valueKey]) || 0;
    const pct = Math.max(0, Math.min(100, (v / top) * 100));
    return '<div class="bar-row"><div class="bar-label" title="' + esc(r[labelKey]) + '">' + esc(r[labelKey]) +
      '</div><div class="bar-track"><div class="bar-fill ' + (cls || "") + '" style="width:' + pct + '%"></div></div><div class="bar-val">' +
      esc(fmt ? fmt(r) : v) + "</div></div>";
  }).join("");
}

/** next calendar date (yyyy-mm-dd) that falls on the given weekday abbreviation (Mon..Fri), today included */
function nextDateFor(day) {
  const names = ["Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"];
  const d = new Date();
  for (let i = 0; i < 8; i++) {
    if (names[d.getDay()] === day) break;
    d.setDate(d.getDate() + 1);
  }
  return d.getFullYear() + "-" + String(d.getMonth() + 1).padStart(2, "0") + "-" + String(d.getDate()).padStart(2, "0");
}
