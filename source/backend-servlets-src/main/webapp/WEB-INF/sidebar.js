const I = {
  overview: '<svg viewBox="0 0 20 20" fill="none"><rect x="2.5" y="2.5" width="6" height="6" rx="1.3" stroke="currentColor" stroke-width="1.4"/><rect x="11.5" y="2.5" width="6" height="6" rx="1.3" stroke="currentColor" stroke-width="1.4"/><rect x="2.5" y="11.5" width="6" height="6" rx="1.3" stroke="currentColor" stroke-width="1.4"/><rect x="11.5" y="11.5" width="6" height="6" rx="1.3" stroke="currentColor" stroke-width="1.4"/></svg>',
  courses: '<svg viewBox="0 0 20 20" fill="none"><path d="M2.5 7.5 10 4l7.5 3.5L10 11 2.5 7.5Z" stroke="currentColor" stroke-width="1.4" stroke-linejoin="round"/><path d="M5.5 9.5v4c0 1 2 2.2 4.5 2.2s4.5-1.2 4.5-2.2v-4" stroke="currentColor" stroke-width="1.4"/></svg>',
  rooms: '<svg viewBox="0 0 20 20" fill="none"><path d="M3 17V5.5L10 3l7 2.5V17" stroke="currentColor" stroke-width="1.4" stroke-linejoin="round"/><path d="M7.5 17v-5h5v5" stroke="currentColor" stroke-width="1.4" stroke-linejoin="round"/></svg>',
  faculty: '<svg viewBox="0 0 20 20" fill="none"><circle cx="10" cy="6.5" r="3" stroke="currentColor" stroke-width="1.4"/><path d="M3.5 17c0-3.6 2.9-6 6.5-6s6.5 2.4 6.5 6" stroke="currentColor" stroke-width="1.4" stroke-linecap="round"/></svg>',
  subjects: '<svg viewBox="0 0 20 20" fill="none"><path d="M4 3.5h9a2 2 0 0 1 2 2V17H6a2 2 0 0 1-2-2V3.5Z" stroke="currentColor" stroke-width="1.4" stroke-linejoin="round"/><path d="M6.5 7.5h6M6.5 10.5h6" stroke="currentColor" stroke-width="1.4" stroke-linecap="round"/></svg>',
  sections: '<svg viewBox="0 0 20 20" fill="none"><rect x="2.5" y="4" width="15" height="12" rx="1.6" stroke="currentColor" stroke-width="1.4"/><path d="M2.5 8h15" stroke="currentColor" stroke-width="1.4"/></svg>',
  timetable: '<svg viewBox="0 0 20 20" fill="none"><rect x="2.5" y="3.5" width="15" height="13" rx="1.6" stroke="currentColor" stroke-width="1.4"/><path d="M2.5 7.5h15M7 2v3M13 2v3" stroke="currentColor" stroke-width="1.4" stroke-linecap="round"/></svg>',
  booking: '<svg viewBox="0 0 20 20" fill="none"><circle cx="10" cy="10" r="7.2" stroke="currentColor" stroke-width="1.4"/><path d="M10 6.5v7M6.5 10h7" stroke="currentColor" stroke-width="1.4" stroke-linecap="round"/></svg>',
  subs: '<svg viewBox="0 0 20 20" fill="none"><path d="M4 7h11l-3-3M16 13H5l3 3" stroke="currentColor" stroke-width="1.4" stroke-linecap="round" stroke-linejoin="round"/></svg>',
  exams: '<svg viewBox="0 0 20 20" fill="none"><rect x="4" y="3" width="12" height="14" rx="1.6" stroke="currentColor" stroke-width="1.4"/><path d="M7 7.5h6M7 10.5h6M7 13.5h3.5" stroke="currentColor" stroke-width="1.4" stroke-linecap="round"/></svg>',
  notices: '<svg viewBox="0 0 20 20" fill="none"><path d="M5 14V9a5 5 0 0 1 10 0v5l1.5 1.5h-13L5 14Z" stroke="currentColor" stroke-width="1.4" stroke-linejoin="round"/><path d="M8.5 17.5h3" stroke="currentColor" stroke-width="1.4" stroke-linecap="round"/></svg>',
  analytics: '<svg viewBox="0 0 20 20" fill="none"><path d="M3.5 16.5h13" stroke="currentColor" stroke-width="1.4" stroke-linecap="round"/><rect x="5" y="9" width="2.6" height="6" rx=".6" stroke="currentColor" stroke-width="1.3"/><rect x="9" y="5" width="2.6" height="10" rx=".6" stroke="currentColor" stroke-width="1.3"/><rect x="13" y="11" width="2.6" height="4" rx=".6" stroke="currentColor" stroke-width="1.3"/></svg>',
  oslab: '<svg viewBox="0 0 20 20" fill="none"><rect x="5" y="5" width="10" height="10" rx="1.6" stroke="currentColor" stroke-width="1.4"/><path d="M8 2.5v2.5M12 2.5v2.5M8 15v2.5M12 15v2.5M2.5 8H5M2.5 12H5M15 8h2.5M15 12h2.5" stroke="currentColor" stroke-width="1.3" stroke-linecap="round"/></svg>',
  tests: '<svg viewBox="0 0 20 20" fill="none"><circle cx="10" cy="10" r="7.2" stroke="currentColor" stroke-width="1.4"/><path d="m6.8 10.2 2.2 2.2 4.2-4.4" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/></svg>',
  clock: '<svg viewBox="0 0 20 20" fill="none"><circle cx="10" cy="10" r="7.2" stroke="currentColor" stroke-width="1.4"/><path d="M10 5.8V10l3 1.8" stroke="currentColor" stroke-width="1.4" stroke-linecap="round"/></svg>',
};

const NAV = {
  admin: [
    { href: "admin-overview.html", key: "overview", label: "Overview" },
    { group: "Academic setup" },
    { href: "admin-courses.html", key: "courses", label: "Courses" },
    { href: "admin-sections.html", key: "sections", label: "Sections" },
    { href: "admin-subjects.html", key: "subjects", label: "Subjects" },
    { href: "admin-faculty.html", key: "faculty", label: "Faculty" },
    { href: "admin-rooms.html", key: "rooms", label: "Rooms & Labs" },
    { group: "Scheduling" },
    { href: "admin-timetable.html", key: "timetable", label: "Generate Timetable" },
    { href: "admin-booking.html", key: "booking", label: "Manual Booking" },
    { href: "admin-substitutions.html", key: "subs", label: "Substitutions" },
    { href: "admin-exams.html", key: "exams", label: "Exams & Seating" },
    { group: "Communication & insight" },
    { href: "admin-notices.html", key: "notices", label: "Notice Board" },
    { href: "admin-analytics.html", key: "analytics", label: "Analytics" },
  ],
  faculty: [
    { href: "faculty.html", key: "timetable", label: "My Timetable" },
    { href: "faculty-availability.html", key: "availability", label: "My Availability", icon: "clock" },
    { href: "exams.html", key: "exams", label: "Exam Schedule" },
    { href: "notices.html", key: "notices", label: "Notice Board" },
  ],
  student: [
    { href: "student.html", key: "timetable", label: "My Timetable" },
    { href: "exams.html", key: "exams", label: "Exam Schedule" },
    { href: "notices.html", key: "notices", label: "Notice Board" },
  ],
};

function initials(name) {
  return String(name).replace(/\(.*?\)/g, "").split(/\s+/).filter(Boolean).slice(0, 2).map((w) => w[0].toUpperCase()).join("") || "U";
}

/** Draws the sidebar for the signed-in user. activeKey = the nav item to highlight. */
function renderSidebar(user, activeKey) {
  const roleLabel = { admin: "Administrator", faculty: "Faculty", student: "Student" }[user.role];
  const links = NAV[user.role].map((it) => {
    if (it.group) return '<div class="nav-label">' + esc(it.group) + "</div>";
    return '<a href="' + it.href + '" class="' + (it.key === activeKey ? "active" : "") + '"><span class="nav-icon">' +
      (I[it.icon || it.key] || I.timetable) + "</span>" + esc(it.label) + "</a>";
  }).join("");
  document.getElementById("sidebar").innerHTML =
    '<div class="brand"><div class="brand-mark">TC</div><h1>Timetable &amp; Classroom<br/>Allocation</h1></div>' +
    '<div class="user-badge"><div class="user-avatar">' + esc(initials(user.name)) + '</div><div class="user-meta"><div class="user-name">' +
    esc(user.name) + '</div><div class="user-role">' + roleLabel + "</div></div></div>" +
    "<nav>" + links + "</nav>" +
    '<button class="logout" onclick="logout()"><svg viewBox="0 0 20 20" fill="none" width="15" height="15"><path d="M8 17H4.5a1.5 1.5 0 0 1-1.5-1.5v-11A1.5 1.5 0 0 1 4.5 3H8M13 14l4-4-4-4M17 10H8" stroke="currentColor" stroke-width="1.4" stroke-linecap="round" stroke-linejoin="round"/></svg>Sign out</button>';
}
