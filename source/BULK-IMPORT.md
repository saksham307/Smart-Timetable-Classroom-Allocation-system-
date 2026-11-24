# Bulk import (Admin → Bulk Import)

The system no longer has any hard-coded college data. Sections, teachers and subjects are loaded from a published
timetable page or a CSV, then the timetable is generated. The manual pages (Sections, Subjects, Faculty) still work
for sudden changes.

## What it reads
1. **Website address / page source / uploaded .html** — a page that has, for every section, a weekly grid
   (rows Mon..Sat, cells such as `TCS-502 LT-6`) followed by a table of *course code | subject | faculty*.
   * periods per week are **counted from the grid**; labs count as sessions of 2 periods;
   * the teacher of each subject is read **per section** (different teachers for the same subject are kept);
   * lab co-teachers (`A & B`), Career Skills / IKS / CEC rows are understood;
   * sections that attend the **same class from the same teacher in the same period** are detected as a *joint class*;
   * a Saturday row marks the section as a Saturday section.
   The two real GEU pages in `timetable/sample-data/` import exactly the data the project used before.
2. **CSV** (any university; export from Excel) — columns `section, subject, type, periods, teacher, co_teachers, joint_group`
   (`timetable/sample-data/timetable-template.csv`).

## Steps
Preview (nothing saved: sections, teachers new/known, joint classes, warnings such as an overloaded teacher; section names
can be edited) → Import (one database transaction; existing rows are updated, never duplicated) → Generate Timetable.

## Limits
A page whose layout is different (no weekly grid table, or no faculty table) cannot be read automatically: use the CSV.
A site that blocks downloads: open the page, save it, and upload the file.

## Logins for teachers and students
On import (option "create a login for every teacher and every section", on by default) the system creates
* one **teacher login** per teacher, id like `vikash.tripathi`, password `faculty123` — it opens *My Timetable* with that teacher's classes
  (linked by id, so the name need not match anything);
* one **class login** per section, id like `5-a` or `3-ai-ds1`, password `student123` — it opens that section's timetable.
*Bulk Import → Create missing logins* does the same for data that is already imported, and *Download all logins (CSV)* gives the list to hand out.
