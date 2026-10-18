// All pages are served by the same Tomcat app as the REST API, so a relative path works (no CORS).
// The login creates a server-side session (cookie); a 401 anywhere sends the user back to the login page.
const API_BASE = "/timetable/api";

async function apiRequest(path, options = {}) {
  const res = await fetch(API_BASE + path, {
    headers: { "Content-Type": "application/json" },
    credentials: "same-origin",
    ...options,
  });
  const text = await res.text();
  let data = null;
  try { data = text ? JSON.parse(text) : null; } catch (e) { data = null; }
  if (res.status === 401 && !path.startsWith("/login")) {
    sessionStorage.removeItem("user");
    window.location.href = "index.html";
    throw new Error("Please sign in again.");
  }
  if (!res.ok) {
    const err = new Error((data && (data.error || data.conflict)) || "Request failed (" + res.status + ")");
    err.payload = data;
    err.status = res.status;
    throw err;
  }
  return data;
}

const Api = {
  get: (p) => apiRequest(p),
  post: (p, body) => apiRequest(p, { method: "POST", body: JSON.stringify(body || {}) }),
  put: (p, body) => apiRequest(p, { method: "PUT", body: JSON.stringify(body || {}) }),
  del: (p) => apiRequest(p, { method: "DELETE" }),
  login: (id, password) => apiRequest("/login", { method: "POST", body: JSON.stringify({ id, password }) }),
};
