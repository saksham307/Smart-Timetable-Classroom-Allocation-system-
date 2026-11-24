package com.timetable.importer;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads timetable data from outside the system, so the admin never has to type sections, subjects and teachers.
 * No database access here: this class only turns text into {@link Parsed} rows.
 *
 * Two inputs are understood:
 *  1. HTML of a published timetable page (the layout used on the college site: one weekly grid table followed by a
 *     "course code / subject / faculty" table for every section). Periods per week are COUNTED from the grid,
 *     teachers are read from the table next to it, and sections that attend the same class from the same teacher at
 *     the same time are detected as JOINT classes.
 *  2. CSV with the columns  section, subject, type, periods, teacher, co_teachers, joint_group  (works for any university:
 *     export it from Excel).
 */
public final class TimetableParser {
    private TimetableParser() {}

    public static class Row {
        public String section, subject, teacher, code, jointKey;
        public boolean lab;
        public int ppw;
        public List<String> extras = new ArrayList<>();
    }

    public static class Parsed {
        public final List<String> sections = new ArrayList<>();
        public final Set<String> saturdaySections = new LinkedHashSet<>();
        public final List<Row> rows = new ArrayList<>();
        public final List<String> warnings = new ArrayList<>();
        public Integer detectedSemester;
        public String source;
    }

    private static final int F = Pattern.CASE_INSENSITIVE | Pattern.DOTALL;
    private static final Pattern TABLE = Pattern.compile("<table\\b.*?</table>", F);
    private static final Pattern TR = Pattern.compile("<tr\\b.*?</tr>", F);
    private static final Pattern TD = Pattern.compile("<(td|th)\\b([^>]*)>(.*?)</\\1>", F);
    private static final Pattern COLSPAN = Pattern.compile("colspan\\s*=\\s*[\"']?(\\d+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern CODE = Pattern.compile("([A-Za-z]{2,4}-\\d{2,4}(?:\\(S\\))?)");
    private static final Pattern CODE_START = Pattern.compile("^([A-Za-z]{2,4}-\\d{2,4}(?:\\(S\\))?)");
    private static final Pattern AUDI = Pattern.compile("\\baudi\\b", Pattern.CASE_INSENSITIVE);   // the big hall, not "Audit"
    private static final Pattern DAY = Pattern.compile("^(mon|tue|wed|thu|fri|sat)[a-z]*\\.?$", Pattern.CASE_INSENSITIVE);
    private static final Pattern SECTION = Pattern.compile("Section\\s*[-:]?\\s*([A-Za-z0-9][A-Za-z0-9\\-]*)", Pattern.CASE_INSENSITIVE);
    private static final Pattern SEMESTER = Pattern.compile("\\b(VIII|VII|VI|IV|V|III|II|I|\\d)\\s*(?:th|st|nd|rd)?\\s*Semester", Pattern.CASE_INSENSITIVE);

    // ---------------------------------------------------------------------------------------- HTML

    private static class Cell { String text; int span = 1; }

    private static class Grid {
        String section;
        boolean saturday;
        final Map<String, Integer> counts = new LinkedHashMap<>();
        final Map<String, Set<String>> slotCells = new LinkedHashMap<>();    // "day|col|code" -> unused placeholder
        final List<String[]> codeCells = new ArrayList<>();                  // {day, col, code}
    }

    public static Parsed parseHtml(String html) {
        Parsed out = new Parsed();
        out.source = "html";
        Matcher sm = SEMESTER.matcher(stripTags(html.length() > 20000 ? html.substring(0, 20000) : html));
        if (sm.find()) out.detectedSemester = romanOrNumber(sm.group(1));

        List<Grid> grids = new ArrayList<>();
        Grid pending = null;
        int last = 0, tableNo = 0;
        Matcher tm = TABLE.matcher(html);
        while (tm.find()) {
            tableNo++;
            String before = stripTags(html.substring(last, tm.start()));
            last = tm.end();
            List<List<Cell>> rows = parseTable(tm.group());
            if (isGrid(rows)) {
                Grid g = new Grid();
                String lead = before.length() > 500 ? before.substring(before.length() - 500) : before;
                Matcher m = SECTION.matcher(lead);
                String sec = null;
                while (m.find()) sec = m.group(1);
                g.section = sec != null ? sec : "Section " + (grids.size() + 1);
                readGrid(g, rows);
                grids.add(g);
                pending = g;
            } else if (pending != null && hasCode(rows)) {
                readFacultyTable(out, pending, rows);
                pending = null;
            }
        }
        if (grids.isEmpty()) out.warnings.add("No weekly timetable grid (a table whose rows start with Mon, Tue, Wed...) was found in this page.");
        detectJoint(out, grids);
        return out;
    }

    private static boolean isGrid(List<List<Cell>> rows) {
        int days = 0;
        for (List<Cell> r : rows) if (!r.isEmpty() && DAY.matcher(r.get(0).text).matches()) days++;
        return days >= 3;
    }

    private static boolean hasCode(List<List<Cell>> rows) {
        for (List<Cell> r : rows) for (Cell c : r) if (CODE_START.matcher(c.text).find()) return true;
        return false;
    }

    private static void readGrid(Grid g, List<List<Cell>> rows) {
        for (List<Cell> r : rows) {
            if (r.isEmpty() || !DAY.matcher(r.get(0).text).matches()) continue;
            String day = r.get(0).text.substring(0, 3).toLowerCase();
            int col = 0;
            for (int i = 1; i < r.size(); i++) {
                Cell c = r.get(i);
                int c0 = col;
                col += c.span;
                String t = c.text, u = t.toUpperCase().replace(" ", "");
                if (t.isEmpty()) continue;
                if (day.equals("sat")) g.saturday = true;
                if (u.startsWith("LUNCH") || u.startsWith("LIB") || u.startsWith("ELECTIVE") || u.contains("SWAYAM") || u.contains("TRAINING")
                        || AUDI.matcher(t).find()) continue;
                if (t.startsWith("*")) {                      // "* classes against an even Saturday off": not counted, but still a shared period
                    Matcher sm = CODE.matcher(t);
                    if (sm.find() && !t.toLowerCase().contains("lab")) g.codeCells.add(new String[]{day, String.valueOf(c0), sm.group(1).toUpperCase()});
                    continue;
                }
                if (t.toUpperCase().startsWith("CAREER EXCELLENCE")) { g.counts.merge("CEC", c.span, Integer::sum); continue; }
                if (t.toUpperCase().matches("^(CAREER\\s*S?KILLS|PDCC).*")) {
                    String tl = t.toLowerCase();
                    String k = tl.contains("qar") ? "qar" : tl.contains("verbal") ? "verbal" : tl.contains("soft") ? "softskills" : null;
                    if (k != null) g.counts.merge("PD:" + k, 1, Integer::sum);
                    continue;
                }
                if (t.toUpperCase().startsWith("IKS")) { g.counts.merge("IKS", 1, Integer::sum); continue; }
                if (t.toUpperCase().startsWith("UHV")) { g.counts.merge("UHV", 1, Integer::sum); continue; }
                Matcher m = CODE.matcher(t);
                if (m.find()) {
                    String code = m.group(1).toUpperCase();
                    g.counts.merge(code, 1, Integer::sum);
                    if (!t.toLowerCase().contains("lab")) g.codeCells.add(new String[]{day, String.valueOf(c0), code});
                }
            }
        }
    }

    private static void readFacultyTable(Parsed out, Grid g, List<List<Cell>> rows) {
        String mode = "core";
        Map<String, String[]> core = new LinkedHashMap<>(), labs = new LinkedHashMap<>();   // code -> {name, teachers}
        Map<String, String> pd = new LinkedHashMap<>();
        String cec = null, iks = null, uhv = null;
        for (int i = 1; i < rows.size(); i++) {
            List<String> c = new ArrayList<>();
            boolean any = false;
            for (Cell x : rows.get(i)) { c.add(x.text); if (!x.text.isEmpty()) any = true; }
            if (!any) continue;
            if (c.size() == 1) {
                String l = c.get(0).toLowerCase();
                if (l.startsWith("elective")) mode = "elec"; else if (l.startsWith("lab")) mode = "lab";
                continue;
            }
            if (c.size() >= 2 && c.get(0).isEmpty() && c.get(1).equalsIgnoreCase("lab")) { mode = "lab"; continue; }
            if (c.get(0).toLowerCase().startsWith("career excellence") && !mode.equals("elec")) { cec = c.get(c.size() - 1); continue; }
            if (mode.equals("elec") || c.size() < 3) continue;
            String code = c.get(0), name = c.get(1), fac = c.get(2);
            if (code.toUpperCase().startsWith("XCS")) {
                for (String part : fac.split("/")) {
                    Matcher m = Pattern.compile("^\\s*(QAR|Verbal|Soft\\s*Skills)\\s*-\\s*(.*)$", Pattern.CASE_INSENSITIVE).matcher(part.trim());
                    if (m.find()) pd.put(m.group(1).toLowerCase().replace(" ", ""), m.group(2));
                }
                continue;
            }
            if (code.toUpperCase().startsWith("UHV")) { uhv = fac; continue; }
            if (name.toUpperCase().startsWith("IKS")) { iks = fac; continue; }
            Matcher mc = CODE_START.matcher(code);
            if (!mc.find() || fac.isEmpty()) continue;
            String key = mc.group(1).toUpperCase();
            String nm = name.replaceAll("\\s+(G1|G2|LT|CR|Lab)\\b.*$", "").trim();
            if (fac.contains("/") && fac.toLowerCase().contains("president")) fac = fac.substring(fac.indexOf('/') + 1);
            (mode.equals("lab") ? labs : core).put(key, new String[]{nm, fac});
        }
        String sec = g.section;
        out.sections.add(sec);
        if (g.saturday) out.saturdaySections.add(sec);

        for (Map.Entry<String, String[]> e : core.entrySet()) {
            int n = g.counts.getOrDefault(e.getKey(), 0);
            if (n == 0) continue;
            List<String> t = teachers(e.getValue()[1]);
            if (t.isEmpty()) continue;
            addRow(out, sec, e.getValue()[0] + " (" + e.getKey() + ")", e.getKey(), false, n, t);
        }
        for (Map.Entry<String, String[]> e : labs.entrySet()) {
            int n = g.counts.getOrDefault(e.getKey(), 0);
            if (n == 0) continue;
            List<String> t = teachers(e.getValue()[1]);
            if (t.isEmpty()) continue;
            addRow(out, sec, e.getValue()[0] + " (" + e.getKey() + ")", e.getKey(), true, n, t);
        }
        String[][] pdNames = {{"qar", "Career Skills - QAR"}, {"verbal", "Career Skills - Verbal"}, {"softskills", "Career Skills - Soft Skills"}};
        for (String[] p : pdNames)
            if (g.counts.getOrDefault("PD:" + p[0], 0) > 0 && pd.get(p[0]) != null)
                addRow(out, sec, p[1], null, false, 1, teachers(pd.get(p[0])));
        if (g.counts.getOrDefault("IKS", 0) > 0 && iks != null) addRow(out, sec, "IKS (Online)", null, false, 1, teachers(iks));
        if (g.counts.getOrDefault("UHV", 0) > 0 && uhv != null) addRow(out, sec, "UHV-II (Online)", null, false, 1, teachers(uhv));
        if (g.counts.getOrDefault("CEC", 0) > 0 && cec != null)
            addRow(out, sec, "Career Excellence Classes (CEC)", null, false, Math.min(4, g.counts.get("CEC")), teachers(cec));
    }

    private static void addRow(Parsed out, String sec, String subject, String code, boolean lab, int ppw, List<String> t) {
        if (t.isEmpty()) return;
        Row r = new Row();
        r.section = sec; r.subject = subject; r.code = code; r.lab = lab; r.ppw = ppw; r.teacher = t.get(0);
        for (int i = 1; i < t.size(); i++) if (!sameTeacher(t.get(i), r.teacher) && !containsTeacher(r.extras, t.get(i))) r.extras.add(t.get(i));
        out.rows.add(r);
    }

    /** Sections that attend the same lecture, from the same teacher, in the same period of the week, are one joint class. */
    private static void detectJoint(Parsed out, List<Grid> grids) {
        Map<String, Row> byKey = new HashMap<>();
        for (Row r : out.rows) if (!r.lab && r.code != null) byKey.put(r.section + "|" + r.code, r);
        Map<String, String> parent = new HashMap<>();
        Map<String, Set<String>> cellSections = new LinkedHashMap<>();
        for (Grid g : grids)
            for (String[] cc : g.codeCells) {
                Row r = byKey.get(g.section + "|" + cc[2]);
                if (r == null) continue;
                cellSections.computeIfAbsent(cc[0] + "|" + cc[1] + "|" + cc[2] + "|" + teacherKey(r.teacher), k -> new LinkedHashSet<>()).add(g.section);
            }
        for (Map.Entry<String, Set<String>> e : cellSections.entrySet()) {
            if (e.getValue().size() < 2) continue;
            String[] k = e.getKey().split("\\|");
            List<String> secs = new ArrayList<>(e.getValue());
            for (String s : secs) parent.putIfAbsent(k[2] + "|" + k[3] + "|" + s, k[2] + "|" + k[3] + "|" + s);
            String root = find(parent, k[2] + "|" + k[3] + "|" + secs.get(0));
            for (int i = 1; i < secs.size(); i++) parent.put(find(parent, k[2] + "|" + k[3] + "|" + secs.get(i)), root);
        }
        Map<String, List<String>> comps = new LinkedHashMap<>();
        for (String node : new ArrayList<>(parent.keySet())) comps.computeIfAbsent(find(parent, node), x -> new ArrayList<>()).add(node);
        for (List<String> comp : comps.values()) {
            if (comp.size() < 2) continue;
            List<String> secs = new ArrayList<>();
            String code = comp.get(0).split("\\|")[0];
            for (String n : comp) secs.add(n.split("\\|")[2]);
            secs.sort(Comparator.comparingInt(s -> out.sections.indexOf(s)));
            for (String s : secs) { Row r = byKey.get(s + "|" + code); if (r != null) r.jointKey = code + "|" + secs.get(0); }
        }
    }

    private static String find(Map<String, String> p, String x) {
        while (!p.get(x).equals(x)) { p.put(x, p.get(p.get(x))); x = p.get(x); }
        return x;
    }

    // ---------------------------------------------------------------------------------------- CSV

    public static Parsed parseCsv(String text) {
        Parsed out = new Parsed();
        out.source = "csv";
        List<List<String>> rows = csvRows(text);
        if (rows.isEmpty()) { out.warnings.add("The CSV is empty."); return out; }
        List<String> head = new ArrayList<>();
        for (String h : rows.get(0)) head.add(h.toLowerCase().replaceAll("[^a-z]", ""));
        int iSec = idx(head, "section"), iSub = idx(head, "subject", "subjectname", "course"), iTyp = idx(head, "type", "kind"),
            iPer = idx(head, "periods", "periodsperweek", "ppw", "hoursperweek"), iTea = idx(head, "teacher", "faculty", "facultyname"),
            iCo = idx(head, "coteachers", "coteacher", "extrateachers", "labteachers"), iJoint = idx(head, "jointgroup", "joint");
        if (iSec < 0 || iSub < 0 || iTea < 0 || iPer < 0) {
            out.warnings.add("The first row must name the columns: section, subject, type, periods, teacher (optional: co_teachers, joint_group).");
            return out;
        }
        Map<String, String> jointFirst = new LinkedHashMap<>();
        for (int i = 1; i < rows.size(); i++) {
            List<String> c = rows.get(i);
            String sec = get(c, iSec), sub = get(c, iSub), tea = get(c, iTea);
            if (sec.isEmpty() && sub.isEmpty()) continue;
            int line = i + 1;
            int ppw;
            try { ppw = Integer.parseInt(get(c, iPer).replaceAll("[^0-9]", "")); } catch (NumberFormatException e) { ppw = 0; }
            if (sec.isEmpty() || sub.isEmpty() || tea.isEmpty() || ppw <= 0 || ppw > 12) {
                out.warnings.add("Line " + line + " skipped: needs section, subject, teacher and periods between 1 and 12.");
                continue;
            }
            Row r = new Row();
            r.section = sec; r.subject = sub; r.ppw = ppw;
            String ty = get(c, iTyp).toLowerCase();
            r.lab = ty.startsWith("lab") || ty.startsWith("prac");
            List<String> t = teachers(tea);
            r.teacher = t.get(0);
            for (String x : teachers(get(c, iCo))) if (!sameTeacher(x, r.teacher)) r.extras.add(x);
            String j = get(c, iJoint);
            if (!j.isEmpty() && !r.lab) r.jointKey = "G|" + j;
            out.rows.add(r);
            if (!out.sections.contains(sec)) out.sections.add(sec);
        }
        // a joint group needs at least two sections
        Map<String, Set<String>> members = new LinkedHashMap<>();
        for (Row r : out.rows) if (r.jointKey != null) members.computeIfAbsent(r.jointKey, k -> new LinkedHashSet<>()).add(r.section);
        for (Row r : out.rows)
            if (r.jointKey != null) {
                Set<String> m = members.get(r.jointKey);
                if (m.size() < 2) r.jointKey = null; else r.jointKey = r.jointKey.substring(2) + "|" + m.iterator().next();
            }
        return out;
    }

    private static int idx(List<String> head, String... names) {
        for (String n : names) { int i = head.indexOf(n); if (i >= 0) return i; }
        return -1;
    }

    private static String get(List<String> c, int i) { return i >= 0 && i < c.size() ? c.get(i).trim() : ""; }

    static List<List<String>> csvRows(String text) {
        List<List<String>> rows = new ArrayList<>();
        List<String> cur = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        boolean q = false;
        char delim = text.indexOf(';') >= 0 && text.indexOf(',') < 0 ? ';' : (text.indexOf('\t') >= 0 && text.indexOf(',') < 0 ? '\t' : ',');
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (q) {
                if (ch == '"') { if (i + 1 < text.length() && text.charAt(i + 1) == '"') { sb.append('"'); i++; } else q = false; }
                else sb.append(ch);
            } else if (ch == '"') q = true;
            else if (ch == delim) { cur.add(sb.toString()); sb.setLength(0); }
            else if (ch == '\n' || ch == '\r') {
                if (ch == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') i++;
                cur.add(sb.toString()); sb.setLength(0);
                if (cur.size() > 1 || !cur.get(0).trim().isEmpty()) rows.add(cur);
                cur = new ArrayList<>();
            } else sb.append(ch);
        }
        if (sb.length() > 0 || !cur.isEmpty()) { cur.add(sb.toString()); if (cur.size() > 1 || !cur.get(0).trim().isEmpty()) rows.add(cur); }
        return rows;
    }

    // ---------------------------------------------------------------------------------------- helpers

    private static List<List<Cell>> parseTable(String table) {
        List<List<Cell>> rows = new ArrayList<>();
        Matcher rm = TR.matcher(table);
        while (rm.find()) {
            List<Cell> cells = new ArrayList<>();
            Matcher cm = TD.matcher(rm.group());
            while (cm.find()) {
                Cell c = new Cell();
                c.text = stripTags(cm.group(3));
                Matcher sp = COLSPAN.matcher(cm.group(2));
                if (sp.find()) c.span = Math.max(1, Integer.parseInt(sp.group(1)));
                cells.add(c);
            }
            if (!cells.isEmpty()) rows.add(cells);
        }
        return rows;
    }

    static String stripTags(String s) {
        s = s.replaceAll("(?is)<(script|style)\\b.*?</\\1>", " ").replaceAll("(?i)<br\\s*/?>", " ").replaceAll("(?s)<[^>]*>", " ");
        return unescape(s).replaceAll("[\\s\\u00a0]+", " ").trim();
    }

    private static String unescape(String s) {
        s = s.replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
             .replace("&apos;", "'").replace("&rsquo;", "\u2019").replace("&lsquo;", "\u2018");
        Matcher m = Pattern.compile("&#(x?)([0-9a-fA-F]+);").matcher(s);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            int cp;
            try { cp = Integer.parseInt(m.group(2), m.group(1).isEmpty() ? 10 : 16); } catch (NumberFormatException e) { cp = 32; }
            m.appendReplacement(sb, Matcher.quoteReplacement(new String(Character.toChars(cp))));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static Integer romanOrNumber(String s) {
        switch (s.toUpperCase()) {
            case "I": return 1; case "II": return 2; case "III": return 3; case "IV": return 4;
            case "V": return 5; case "VI": return 6; case "VII": return 7; case "VIII": return 8;
            default: try { return Integer.parseInt(s); } catch (NumberFormatException e) { return null; }
        }
    }

    /** "Dr.Vikash Tripathi" -> "Dr. Vikash Tripathi"; "G1- Ms. X & Mr. Y" -> two teachers. */
    static List<String> teachers(String raw) {
        List<String> out = new ArrayList<>();
        if (raw == null) return out;
        for (String part : raw.split("\\s*(?:&|;|\\band\\b)\\s*")) {
            String n = part.replaceAll("^\\s*G\\d\\s*-?\\s*", "").replaceAll("\\s+", " ").replaceAll("^[ .,&]+|[ .,&]+$", "");
            if (n.isEmpty()) continue;
            Matcher m = Pattern.compile("^(Dr|Mr|Ms|Mrs|Prof)\\.?\\s*(.*)$", Pattern.CASE_INSENSITIVE).matcher(n);
            if (m.find() && !m.group(2).isEmpty()) {
                String t = m.group(1);
                n = t.substring(0, 1).toUpperCase() + t.substring(1).toLowerCase() + ". " + m.group(2).trim();
            }
            out.add(n);
        }
        return out;
    }

    /** Same person whatever the title or spacing. */
    public static String teacherKey(String name) {
        return name.toLowerCase().replaceAll("^(dr|mr|ms|mrs|prof)\\.?\\s*", "").replaceAll("[\\s.]+", "");
    }

    private static boolean sameTeacher(String a, String b) { return teacherKey(a).equals(teacherKey(b)); }

    private static boolean containsTeacher(List<String> l, String n) {
        for (String x : l) if (sameTeacher(x, n)) return true;
        return false;
    }
}
