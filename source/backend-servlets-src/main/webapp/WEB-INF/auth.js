function getUser() {
  const raw = sessionStorage.getItem("user");
  return raw ? JSON.parse(raw) : null;
}
function setUser(user) { sessionStorage.setItem("user", JSON.stringify(user)); }

function homeFor(role) {
  return role === "admin" ? "admin-overview.html" : role === "faculty" ? "faculty.html" : "student.html";
}

async function logout() {
  try { await Api.post("/logout", {}); } catch (e) { /* ignore */ }
  sessionStorage.removeItem("user");
  window.location.href = "index.html";
}

/** Protects a page. role = "admin" | "faculty" | "student" | "any". Returns the user, or null after redirecting. */
function requireRole(role) {
  const user = getUser();
  if (!user) { window.location.href = "index.html"; return null; }
  if (role !== "any" && user.role !== role) { window.location.href = homeFor(user.role); return null; }
  return user;
}
