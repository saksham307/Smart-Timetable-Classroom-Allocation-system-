# Project status against the proposal (OSDBMS-T218)

Everything below was **run and checked** (live server + a headless-browser run of every page); see the System Tests page for the live evidence.

## Goals

| # | Goal in the report | Status | Where |
|---|---|---|---|
| 1 | Research & analysis | Design follows the report's references (Silberschatz: deadlocks/synchronisation, Elmasri: concurrency control). The written literature review is for the team to write. | `docs/OS-AND-DBMS-CONCEPTS.md` |
| 2 | Database and UI design | Done: 17 tables, 20 pages | `sql/final_schema.sql`, `timetable/*.html` |
| 3 | Conflict detection engine (mutual exclusion, critical sections) | Done: single lock + transaction; room / teacher / section checked; clean message + suggested alternative | `SchedulingEngine.java` |
| 4 | Scheduling rules: consecutive labs, fixed breaks/lunch, substitution, several faculty for practicals | Done | engine, `SubstitutionServlet`, `subject_faculty` |
| 5 | Backend ↔ database with transactions, locking, constraints | Done | `Db`, `DBUtil`, UNIQUE keys |
| 6 | Exam timetable + seating, notice board | Done. "Notifications" = in-app notice board (system events post automatically). No e-mail/SMS. | `ExamService`, `NoticeServlet` |
| 7 | Testing & evaluation | Live **System Tests** page: 10 automated tests (accuracy, concurrency, constraints, Banker's, deadlock, integrity, timing). Running it on *real college data* is still for the team to do: enter the real data and press Run. | `TestServlet` |

## Milestones

M1 design ✔ · M2 schema + admin UI ✔ · M3 conflict engine with locking/transactions ✔ · M4 lab / break / substitution rules ✔ · M5 exams, seating, notices ✔ · M6 analytics + testing ✔ · M7 deployment ✔; final report text and presentation are the team's.

## Architecture diagram (5 layers)

User layer (admin / faculty / student pages) ✔ · Application layer (REST APIs + authentication/authorisation) ✔ · Scheduling engine (allocation, conflict detection, alternatives, rescheduling via move requests) ✔ · Data layer (MySQL, master + transaction tables) ✔ · External events: *optional* in the report. Faculty leave is covered by availability + substitution; **room maintenance is not implemented**.

## New in this version

* The OS / DBMS techniques are **hidden from the daily interface**: no "OS Lab" or "System Tests" menu items. Two developer pages remain reachable by address for the viva (see README). Deadlock handling is now a normal feature, **Manual Booking → Move a class**, where waiting requests are queued and circular waits are detected and resolved automatically.

* Course → Year → Section hierarchy. Only **Year 2 and Year 3** are enabled (enforced in the API and the forms).
* Real login sessions; only admins can change data (student/faculty get HTTP 403).
* Edit-in-place for courses, sections, subjects, faculty, rooms.
* Faculty availability page; the scheduler never uses a blocked period.
* Database creates/upgrades itself on start; password lives in `db.properties`.

## Honest limitations (say these before sir asks)

* **Banker's algorithm model.** A booked room/teacher period is never given back during a planning cycle, so the engine runs the algorithm with "release = off" (a process finishes by *consuming* its Need). It is used as (a) a whole-batch safety pre-check over rooms + each teacher's free periods and (b) a per-session gate for practicals. Because practical blocks are aligned (09-11, 11:15-13:15, 14-16), overlapping claims rarely arise, so the gate usually answers SAFE; the pre-check does report UNSAFE when capacity is short, and the OS Lab page runs the classic textbook example with a real UNSAFE denial.
* The generator is a balanced greedy heuristic, not an optimal solver. If capacity is too tight it says exactly what could not be placed.
* Lab room capacity (30) is not compared with section strength (60): labs are assumed to run in batches. Lecture rooms prefer a capacity ≥ the section.
* Passwords are stored as plain text (demo). A real deployment must hash them (e.g. BCrypt).
* Single campus, single semester at a time (assumption 3 in the report).
* Tested with Java 21 → bytecode for Java 11, MariaDB 10.11 and Jetty 9 as stand-ins. The code uses only standard Servlet 4.0 / JDBC / SQL, but it was **not** run on your exact Tomcat 9.0.116 + MySQL.
