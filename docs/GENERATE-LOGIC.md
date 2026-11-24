# How the "Generate" button works (for evaluators)

## What happens when you click it
1. **Choose scope.** Course + Semester lists that semester's sections. *Generate all sections* sends all their ids; *Generate this section* sends one id.
2. **Request.** `POST /api/timetable/generate` -> `TimetableServlet.generate` -> `SchedulingEngine.generate(sectionIds, dryRun)`.
3. **Critical section.** The whole run is inside `synchronized (LOCK)` and one database transaction (`setAutoCommit(false)`), so two admins can never generate at the same time and a half-finished run is never visible.
4. **Fresh start for the chosen sections.** Their old entries are deleted *inside the transaction*. Entries of sections NOT chosen stay and count as already-occupied resources (a teacher or room used there is busy).
5. **Banker's pre-check.** Sections are the processes; room-periods and each teacher's free periods are the resources. The safety algorithm reports whether the demand can be met at all.
6. **Phase 1 - practicals first (priority scheduling).** Every lab needs a room + *all* its teachers for 2 consecutive periods. Sessions are taken round-robin over sections. Each request goes through the Banker's resource-request check before it is granted. A practical may cross a short break but never lunch / the open elective period.
7. **Phase 2 - lectures.** One period at a time, round-robin over sections, preferring a day the section has few classes on, and avoiding the same subject twice on a day. Each section keeps one "home" room where possible. Saturday is used only by sections marked as working Saturdays.
8. **Phase 3 - repair.** If a lecture finds no free period, the engine looks for the single lecture that blocks it (same section or same teacher), moves that lecture to another free period, and puts the missing one in the freed slot.
9. **Save.** All placements are inserted, then `commit()`. The UNIQUE keys on (room, slot), (teacher, slot) and (section, slot) are a second safety net.
10. **Show.** The page reloads the grid *from the database*, so what is displayed is exactly what is saved. Anything that could not be placed is listed ("could not place"); it is never forced into a clash.

## Joint classes
Some lectures are given by one teacher to several sections together (e.g. A+B). Such a group is scheduled **once**: one period
where all its sections are free, the teacher is free, and one room seats everybody. Every member section gets that period, so the
teacher's weekly load counts it once (the dashboard counts distinct periods). Generating a section that belongs to a joint group
also regenerates its partners, because the shared period must be kept in step. The database still refuses two *different* classes in
one room or for one teacher in a period; only rows of the same joint class (same `joint_id`) may share them.

## Is the result the same every time?
Yes, for the same starting data: there is no randomness anywhere in the engine (no `Random`, no shuffling), and every database read is ordered. The result changes only when the data it starts from changes:
- **Other sections/semesters already stored.** Teachers, rooms and periods used there are busy. Generating *one* section therefore differs from generating *all* sections, and generating Semester 3 before or after Semester 5 differs (ten teachers teach in both).
- **Different scope.** The OS Lab page "Dry-run all sections" plans every section in the database at once (all semesters), so it differs from generating one semester.
- **Manual bookings, moves or changed subject/teacher data** between two runs.

To get the same timetable every time, generate in the same order from the same data: Semester 5 (all sections), then Semester 3 (all sections).
