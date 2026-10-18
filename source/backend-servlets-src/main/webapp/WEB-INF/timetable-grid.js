const GRID_DAYS = ["Mon", "Tue", "Wed", "Thu", "Fri"];

/**
 * Draws the weekly grid (days across, periods down, fixed break rows in between).
 *  timeslots : /api/timeslots   entries : /api/timetable rows
 *  opts: { onRemove(entryId), onCellClick(slot, entry|null), showSection, flags:{entryId:'text'},
 *          off:Set(slotIds shown as unavailable), mineFacultyId, emptyDash }
 */
function renderTimetableGrid(container, timeslots, entries, opts) {
  opts = opts || {};
  const rowsByTime = new Map();
  timeslots.forEach((s) => {
    if (!rowsByTime.has(s.time)) rowsByTime.set(s.time, { time: s.time, brk: s.isBreak, label: s.label, slots: {} });
    rowsByTime.get(s.time).slots[s.day] = s;
  });
  const rows = Array.from(rowsByTime.values()).sort((a, b) => a.time.localeCompare(b.time));
  const days = GRID_DAYS.filter((d) => timeslots.some((s) => s.day === d));
  const bySlot = new Map();
  entries.forEach((e) => { const k = e.slot.id; if (!bySlot.has(k)) bySlot.set(k, []); bySlot.get(k).push(e); });

  let html = '<div class="tt-scroll"><div class="tt-grid" style="grid-template-columns:86px repeat(' + days.length + ',minmax(0,1fr))">';
  html += '<div class="tt-cell tt-time" style="background:var(--ink)"></div>' + days.map((d) => '<div class="tt-head">' + d + "</div>").join("");
  rows.forEach((r) => {
    if (r.brk) { html += '<div class="tt-break">' + esc(r.label || "Break") + " &middot; " + esc(r.time) + "</div>"; return; }
    html += '<div class="tt-cell tt-time">' + esc(r.time) + "</div>";
    days.forEach((d) => {
      const slot = r.slots[d];
      const list = slot ? (bySlot.get(slot.id) || []) : [];
      const off = slot && opts.off && opts.off.has(slot.id);
      const clickable = opts.onCellClick && slot;
      let inner = "";
      list.forEach((e) => {
        const lab = e.isLab === true || e.isLab === 1;
        inner += '<div' + (list.length > 1 ? ' style="margin-bottom:6px"' : "") + '><div class="tt-subject">' + esc(e.subject) + "</div>" +
          '<div class="tt-meta">' + esc(e.faculty) + "</div>" +
          '<div class="tt-meta">' + esc(e.room) + (opts.showSection ? " &middot; " + esc(e.section) : "") + (lab ? " &middot; 2-period practical" : "") + "</div>" +
          (opts.flags && opts.flags[e.id] ? '<span class="tt-flag">' + esc(opts.flags[e.id]) + "</span>" : "") +
          (opts.onRemove ? '<button class="tt-x" title="Remove" data-rm="' + e.id + '">&times;</button>' : "") + "</div>";
      });
      if (!list.length) inner = off ? '<div class="tt-meta">Unavailable</div>' : '<span style="color:var(--line)">&mdash;</span>';
      const anyLab = list.some((e) => e.isLab === true || e.isLab === 1);
      html += '<div class="tt-cell' + (anyLab ? " lab" : "") + (off ? " off" : "") + (clickable ? " clickable" : "") + '"' +
        (slot ? ' data-slot="' + slot.id + '"' : "") + ">" + inner + "</div>";
    });
  });
  html += "</div></div>";
  container.innerHTML = html;

  if (opts.onRemove) container.querySelectorAll("[data-rm]").forEach((b) =>
    b.addEventListener("click", (ev) => { ev.stopPropagation(); opts.onRemove(Number(b.dataset.rm)); }));
  if (opts.onCellClick) container.querySelectorAll("[data-slot]").forEach((c) =>
    c.addEventListener("click", () => {
      const id = Number(c.dataset.slot);
      const slot = timeslots.find((s) => s.id === id);
      opts.onCellClick(slot, (bySlot.get(id) || [])[0] || null);
    }));
}

function gridLegend() {
  return '<div class="legend"><span><i></i>Lecture</span><span><i class="lab"></i>Practical (2 consecutive periods)</span><span><i class="brk"></i>Fixed break / lunch</span></div>';
}
