# 10-minute demo script

Sign in as **admin1 / admin123** at http://localhost:8080/timetable/

| Min | Do this | Say this |
|---|---|---|
| 0:30 | Sidebar → **Overview** | "One dashboard: courses, sections, how complete every timetable is." |
| 1:00 | **Courses**, **Sections**, **Subjects** | "Course → Year → Section. Years 2 and 3 are enabled. Subjects belong to a course and year; a practical can have several teachers (open DBMS Lab)." Try removing *Dr. A. Verma* → the message shows referential integrity. |
| 2:00 | **Generate Timetable** → B.Tech CSE, Year 3 → **Generate all sections** | "Labs are placed first (priority scheduling), practicals take two consecutive periods, breaks stay empty, every day is used." Point at the grid and the break rows. |
| 3:00 | Type the address `/timetable/admin-oslab.html` → *Banker's* tab → **Dry-run all sections** | "These OS techniques run silently in daily use, so there is no menu button for them. This developer page shows the engine's log: the resource-allocation manager grants cells (room / teacher / section × period); the Banker's pre-check is the safety check over rooms and teachers' free periods." |
| 4:00 | **Manual Booking**: book a free period, book it again | "Second attempt is refused with the reason and a free alternative; **Use this period** applies it." Then choose a lab at 10:00 → refused: a break follows. |
| 5:00 | Address `/timetable/admin-tests.html` → **Run all tests** | "8 admins book the same room at once: exactly 1 wins (mutual exclusion). The same race without the lock: MySQL's UNIQUE keys still keep one row. Banker's, deadlock graph and conflict accuracy are tested too." |
| 6:30 | Back on **OS Lab** → *Banker's*: *Run safety check*, *Check request*, *Example that turns unsafe* | "Textbook example: safe sequence P1,P3,P4,P0,P2; the last example is denied as UNSAFE." |
| 7:30 | **OS Lab → Deadlock detection → Create demo deadlock → Resolve**. Then show the everyday version: **Manual Booking → Move a class** | "Two classes each want the other's room: a cycle in the wait-for graph = deadlock, detected, not silent. Recovery applies both moves atomically in one transaction. In daily use the admin just presses *Move class* and this is detected and fixed automatically." |
| 8:30 | **Substitutions**: pick a class, date, *Find free teachers*, assign. Then **Exams & Seating**: generate, *View seating* | "Only genuinely free teachers are offered. Exam seating interleaves sections so neighbours differ." |
| 9:30 | Sign out → **fac1 / faculty123** (timetable, availability) → **stu1 / student123** (timetable, exam hall, notices) | "Each role sees only its pages; the server refuses writes from non-admins (HTTP 403)." |

If asked "is it in C?": no. The concepts are language-independent; here they are implemented in Java with real threads (Tomcat gives every request its own thread), which is why the race is real and testable.
