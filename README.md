# Timetable & Classroom Allocation System  (OSDBMS-T218)

Plain HTML/CSS/JavaScript frontend  +  Java Servlets REST API  +  MySQL, hosted by Apache Tomcat 9.
No npm, no Maven, no build step: everything here is already compiled.

## Run it (3 steps)

1. **Copy the folder `timetable/`** (the one that contains `WEB-INF`) into  
   `apache-tomcat-9.x/webapps/`  → you must end up with `webapps/timetable/WEB-INF/...`  
   (do NOT copy the outer `timetable-deploy` folder, and use either `timetable/` **or** `timetable.war`, not both).
2. Make sure **MySQL is running**. Nothing else to set up: on first start the app creates the database
   `timetable_db`, every table, the break/lunch rows and demo data by itself. First put YOUR MySQL root password in `webapps/timetable/WEB-INF/classes/db.properties` (line `db.password=`, plain text), then start/restart Tomcat.
3. Start Tomcat (`bin\startup.bat`) and open **http://localhost:8080/timetable/**

An existing database from the earlier version is upgraded in place (your rooms, faculty, sections and bookings are kept).

## Logins

| Role | ID | Password | Notes |
|---|---|---|---|
| Admin | `admin1` … `admin4` | `admin123` | Mohit, Saksham, Jatin, Vedika: all equal admins |
| Faculty | `fac1` `fac2` `fac3` | `faculty123` | Dr. A. Verma, Dr. R. Kapoor, Dr. P. Sharma |
| Student | `stu1` `stu2` `stu3` | `student123` | CSE-3A, CSE-2A, BCA-3A |

Faculty see the classes of the faculty record with the **same name** as their login name.

## What is inside

```
timetable-deploy/
├── README.md              ← this file
├── timetable/             ← DEPLOY THIS FOLDER (frontend pages + compiled backend)
│   ├── *.html, css/, js/      the website (20 pages)
│   └── WEB-INF/
│       ├── classes/           compiled Java + db.properties (database settings)
│       ├── lib/               mariadb-java-client.jar (JDBC driver, also works with MySQL)
│       └── web.xml
├── timetable.war          ← the same app as one file (alternative to the folder)
├── sql/final_schema.sql   ← structure of all 17 tables (for the report / ER diagram)
├── docs/
│   ├── PROJECT-STATUS.md      what is done / what is limited, mapped to your report
│   ├── DEMO-SCRIPT.md         a 10-minute walkthrough for your presentation
│   └── OS-AND-DBMS-CONCEPTS.md   viva answers: every concept, where it is in the code
└── source/
    ├── frontend-html/         editable pages (edit, copy into timetable/, refresh browser: no rebuild)
    └── backend-servlets-src/  Java source (com.timetable.*). Java changes need recompiling.
```

## Languages and what each does

| Language | Purpose | Where |
|---|---|---|
| Java (Servlets + JDBC) | REST API, scheduling engine, OS algorithms, authentication | `source/backend-servlets-src` |
| SQL (MySQL) | tables, constraints, queries | created by `SchemaMigrator.java`, see `sql/final_schema.sql` |
| HTML / CSS / JavaScript | the pages, styling, calling the API with `fetch()` | `source/frontend-html` |

Flow: browser (JS) → HTTP + JSON → Servlet → DAO/JDBC → MySQL → JSON → browser.  
No JSP anywhere: the frontend talks to REST APIs, exactly as in the architecture diagram of the proposal.

## Developer pages (hidden on purpose)

The OS and DBMS techniques (locking, transactions, Banker's algorithm, deadlock detection, constraints) run **silently** inside the system; the daily admin menu does not show them.
For your presentation / viva only, two admin-only pages can be opened by typing the address:

* `http://localhost:8080/timetable/admin-oslab.html`   Banker's calculator, engine log (dry-run), deadlock demo
* `http://localhost:8080/timetable/admin-tests.html`   10 automated tests incl. 8 simultaneous admins

## Troubleshooting

* **404 at /timetable/** – the folder is nested one level too deep, or Tomcat was not restarted.
* **"Access denied for user root"** – wrong password in `WEB-INF/classes/db.properties`.
* **Blank data / "Please sign in"** – Tomcat restarted; just sign in again (sessions are in memory).
* **Old look after an update** – hard refresh (Ctrl+Shift+R).
* Built for **Tomcat 9** (`javax.servlet`). Tomcat 10+ uses `jakarta.servlet` and needs a rebuild.


## Bulk import, free classrooms, teacher profile
* **Bulk Import** (Academic setup): load sections, teachers and subjects from a timetable page or CSV, see `docs/BULK-IMPORT.md`.
  A fresh install has only the small demo data; use *Bulk Import → GEU 5th / 3rd sem* to load the real college pages.
* **Free Classrooms** (Scheduling): pick a day and period to see which rooms are empty.
* **Teacher Profile** (Academic setup): weekly load, free periods, subjects, sections and the weekly timetable of one teacher.

* **Logins:** Bulk Import creates a teacher login (`firstname.lastname`, password `faculty123`) and a class login per section (`5-a`, password `student123`).
