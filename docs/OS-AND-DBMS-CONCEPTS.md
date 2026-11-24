# OS & DBMS concepts: what they are here and where the code is

> These techniques run **silently** during normal use, so the admin menu has no "OS" or "concepts" button. For your presentation, two admin-only pages can be opened by address: `/timetable/admin-oslab.html` and `/timetable/admin-tests.html`.

## Mapping model (from the proposal)
A timetable entry is a **process request**. **Rooms, teachers and sections in a period** are the shared **resources** (a "cell"). The engine is the **resource-allocation manager**: it grants or denies a request from the current state.

## Operating-system concepts

| Concept | Implementation | File | Evidence |
|---|---|---|---|
| **Mutual exclusion / critical section** | `synchronized (LOCK)` around check-then-insert, generate, remove and move. Tomcat runs each HTTP request on its own thread, so the race is real. | `SchedulingEngine.java` | System Tests: 8 simultaneous bookings → 1 granted |
| **Race condition** | `bookSingleNoLock` pauses between check and insert to expose the race | `SchedulingEngine.java` | System Tests: "WITHOUT the lock" |
| **Priority scheduling** | Phase 1 allocates all practicals, phase 2 lectures, phase 3 repairs a lecture that found no slot by moving the one lecture that blocks it | `SchedulingEngine.planLabs / planLectures / repairLecture` | OS Lab page → Dry-run (log, PHASE 1-3) |
| **Banker's algorithm (avoidance)** | Available / Max / Allocation / Need matrices, safety algorithm and resource-request algorithm. Used as a batch pre-check (sections = processes; room-periods and each teacher's free periods = resource types) and as a per-practical gate (a practical needs a room + *all* its teachers for 2 periods, acquired period by period). `releaseOnFinish=false` because a booked period is never returned. | `BankersAlgorithm.java`, `SchedulingEngine.bankersRequest / capacityPrecheck` | OS Lab, System Tests |
| **Resource-allocation / wait-for graph** | Pending move requests: A → B means "A waits for something B holds" (room, same teacher, same section) | `MoveService.analyze` | OS Lab → Deadlock; in daily use: Manual Booking → Move a class |
| **Deadlock detection** | Cycle = deadlock; Tarjan's strongly-connected components | `GraphUtil.java` | OS Lab, System Tests |
| **Deadlock recovery** | Break hold-and-wait: apply all moves of the cycle atomically (entries parked on non-allocatable break slots inside one transaction, because MySQL checks UNIQUE keys row by row) | `MoveService.atomicMove` | OS Lab → Resolve; Manual Booking → Move a class resolves it automatically |
| **Process synchronisation (threads)** | `ExecutorService` + `CountDownLatch` start 8 threads at once in the test | `TestServlet.java` | System Tests |

See `GENERATE-LOGIC.md` for the step-by-step logic of the Generate button.

## DBMS concepts

| Concept | Implementation | Evidence |
|---|---|---|
| **Transactions** (ACID) | `setAutoCommit(false)`, `commit()`/`rollback()` on every generate/book/remove/move | A failed batch leaves the old timetable untouched |
| **Constraints** | `UNIQUE(room,slot)`, `UNIQUE(faculty,slot)`, `UNIQUE(section,slot)`; unique exam seats | System Tests: MySQL rejects the racing inserts |
| **Referential integrity** | Foreign keys: `CASCADE` for bookings, `RESTRICT` teacher→subject, `SET NULL` section/subject→course | Try removing a teacher who teaches |
| **Normalisation** | Master tables (course, section, subject, faculty, room, time_slot), link table `subject_faculty`, transaction tables | `sql/final_schema.sql` |
| **Concurrency control** | Application lock + DB constraints (defence in depth) | System Tests |
| **Authentication / authorisation** | `HttpSession` + `AuthFilter` | Student POST → 403 |

## "Do I need C?"
No. Nothing in the proposal requires C: it lists the concepts (mutual exclusion, Banker's, deadlock detection, priority scheduling) and the stack is Java/Tomcat/MySQL. Textbooks show Banker's in C, but the algorithm is language-independent; here it is `BankersAlgorithm.java`. If sir insists, the same ~60-line algorithm can be written as a small standalone C program for the viva.
