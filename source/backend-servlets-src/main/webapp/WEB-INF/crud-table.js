/**
 * Reusable master-data table with Add / Edit / Remove.
 *   CrudTable({ container, resource:'/sections', query: () => '?courseId=1',
 *               columns:[{label, key | render:(row)=>html}],
 *               fields:[{key,label,type:'text|number|select|multiselect', options:[{value,label}],
 *                        default, help, min, max, required, showIf:(values)=>bool, rowKey}],
 *               removeLabel:(row)=>text, onChange:()=>{} })
 * returns { reload(), setOptions(key, options) }
 */
function CrudTable(o) {
  const el = o.container;
  let rows = [];
  let editing = null;
  const fields = o.fields;

  el.innerHTML = '<div class="crud-error" style="padding:0 20px"></div><div class="table-wrap"></div><form class="form-grid" novalidate></form>';
  const errBox = el.querySelector(".crud-error");
  const wrap = el.querySelector(".table-wrap");
  const form = el.querySelector("form");

  function showError(msg) { errBox.innerHTML = msg ? '<div class="banner err" style="margin:14px 0 0">' + esc(msg) + "</div>" : ""; }

  async function reload() {
    try {
      rows = await Api.get(o.resource + (o.query ? o.query() : ""));
      drawTable();
    } catch (e) { showError(e.message); }
  }

  function drawTable() {
    const head = o.columns.map((c) => "<th>" + esc(c.label) + "</th>").join("") + "<th></th>";
    const body = rows.length ? rows.map((r) =>
      "<tr>" + o.columns.map((c) => "<td>" + (c.render ? c.render(r) : esc(r[c.key])) + "</td>").join("") +
      '<td class="actions"><button class="btn-ghost btn-sm" data-edit="' + r.id + '">Edit</button>' +
      '<button class="btn-danger btn-sm" data-del="' + r.id + '">Remove</button></td></tr>'
    ).join("") : '<tr><td colspan="' + (o.columns.length + 1) + '" class="empty">' + esc(o.emptyText || "Nothing here yet — add the first entry below.") + "</td></tr>";
    wrap.innerHTML = '<table class="data-table"><thead><tr>' + head + "</tr></thead><tbody>" + body + "</tbody></table>";
    wrap.querySelectorAll("[data-edit]").forEach((b) => b.addEventListener("click", () => startEdit(Number(b.dataset.edit))));
    wrap.querySelectorAll("[data-del]").forEach((b) => b.addEventListener("click", () => remove(Number(b.dataset.del))));
  }

  function optionsOf(f) { return typeof f.options === "function" ? f.options() : (f.options || []); }

  function drawForm(values) {
    values = values || {};
    form.innerHTML = fields.map((f) => {
      const v = values[f.key] !== undefined ? values[f.key] : (f.default !== undefined ? f.default : "");
      let input;
      if (f.type === "select") {
        input = '<select data-k="' + f.key + '"><option value="">' + esc(f.placeholder || "Select…") + "</option>" +
          optionsOf(f).map((op) => '<option value="' + esc(op.value) + '"' + (String(op.value) === String(v) ? " selected" : "") + ">" + esc(op.label) + "</option>").join("") + "</select>";
      } else if (f.type === "multiselect") {
        const chosen = String(v || "").split(",").filter(Boolean);
        input = '<select data-k="' + f.key + '" multiple size="3">' +
          optionsOf(f).map((op) => '<option value="' + esc(op.value) + '"' + (chosen.includes(String(op.value)) ? " selected" : "") + ">" + esc(op.label) + "</option>").join("") + "</select>";
      } else {
        input = '<input data-k="' + f.key + '" type="' + (f.type === "number" ? "number" : "text") + '" placeholder="' + esc(f.placeholder || f.label) + '" value="' + esc(v) + '"' +
          (f.min !== undefined ? ' min="' + f.min + '"' : "") + (f.max !== undefined ? ' max="' + f.max + '"' : "") + " />";
      }
      return '<div class="field" data-field="' + f.key + '"><label>' + esc(f.label) + "</label>" + input + (f.help ? '<span class="help">' + esc(f.help) + "</span>" : "") + "</div>";
    }).join("") +
      '<div class="form-actions"><button class="btn-primary" type="submit">' + (editing ? "Save changes" : "Add") + "</button>" +
      (editing ? '<button class="btn-ghost" type="button" data-cancel>Cancel</button>' : "") + "</div>";
    form.querySelectorAll("select, input").forEach((x) => x.addEventListener("change", applyShowIf));
    const cancel = form.querySelector("[data-cancel]");
    if (cancel) cancel.addEventListener("click", () => { editing = null; drawForm(); });
    applyShowIf();
  }

  function read() {
    const v = {};
    fields.forEach((f) => {
      const x = form.querySelector('[data-k="' + f.key + '"]');
      if (!x) return;
      if (f.type === "multiselect") v[f.key] = Array.from(x.selectedOptions).map((s) => s.value).join(",");
      else if (f.type === "number") v[f.key] = x.value === "" ? "" : Number(x.value);
      else v[f.key] = x.value.trim();
    });
    return v;
  }

  function applyShowIf() {
    const v = read();
    fields.forEach((f) => {
      const w = form.querySelector('[data-field="' + f.key + '"]');
      if (w && f.showIf) w.classList.toggle("hidden", !f.showIf(v));
    });
  }

  function startEdit(id) {
    const r = rows.find((x) => x.id === id);
    if (!r) return;
    editing = id;
    const values = {};
    fields.forEach((f) => {
      const k = f.rowKey || f.key;
      values[f.key] = typeof r[k] === "boolean" ? String(r[k]) : (r[k] === null || r[k] === undefined ? "" : r[k]);
    });
    drawForm(values);
    form.scrollIntoView({ behavior: "smooth", block: "nearest" });
  }

  async function remove(id) {
    const r = rows.find((x) => x.id === id);
    if (!confirm("Remove " + (o.removeLabel ? o.removeLabel(r) : "this entry") + "?")) return;
    showError("");
    try {
      await Api.del(o.resource + "/" + id);
      if (editing === id) { editing = null; drawForm(); }
      toast("Removed.", "ok");
      await reload();
      if (o.onChange) o.onChange();
    } catch (e) { showError(e.message); }
  }

  form.addEventListener("submit", async (ev) => {
    ev.preventDefault();
    showError("");
    const v = read();
    for (const f of fields) {
      const hidden = f.showIf && !f.showIf(v);
      const needed = f.required !== false && f.type !== "multiselect" && !hidden;
      if (needed && (v[f.key] === "" || v[f.key] === undefined)) { showError(f.label + " is required."); return; }
      if (hidden) v[f.key] = "";
    }
    try {
      if (editing) await Api.put(o.resource + "/" + editing, v);
      else await Api.post(o.resource, v);
      toast(editing ? "Changes saved." : "Added.", "ok");
      editing = null;
      drawForm();
      await reload();
      if (o.onChange) o.onChange();
    } catch (e) { showError(e.message); }
  });

  drawForm();
  reload();
  return {
    reload,
    redrawForm: () => drawForm(editing ? undefined : undefined),
  };
}
