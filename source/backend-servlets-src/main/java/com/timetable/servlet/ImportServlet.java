package com.timetable.servlet;

import com.timetable.engine.SchedulingEngine;
import com.timetable.importer.AccountService;
import com.timetable.importer.TimetableImporter;
import com.timetable.importer.TimetableParser;
import com.timetable.util.ApiServlet;
import com.timetable.util.DBUtil;

import javax.servlet.annotation.WebServlet;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.sql.Connection;
import java.util.Map;

/**
 * Bulk import of sections, teachers and subjects from a published timetable page (URL or pasted HTML) or a CSV.
 *   POST /api/import/preview  -> what would be imported (nothing is saved)
 *   POST /api/import/commit   -> saves it in one transaction
 * POST needs the admin role (AuthFilter). Existing data is updated, never duplicated.
 */
@WebServlet("/api/import/*")
public class ImportServlet extends ApiServlet {

    private static void needAdmin(Req r) {
        Map<String, Object> u = r.user();
        if (u == null || !"admin".equals(String.valueOf(u.get("role")))) throw new ApiException(403, "Administrator access is required.");
    }

    /** GET /api/import/accounts : every teacher / class login (admin only, includes the default passwords). */
    @Override
    protected Object get(Req r) throws Exception {
        needAdmin(r);
        try (Connection c = DBUtil.getConnection()) { return AccountService.list(c); }
    }

    @Override
    protected Object post(Req r) throws Exception {
        String path = r.path();
        if (path.endsWith("/accounts")) {                              // create missing teacher / class logins
            synchronized (SchedulingEngine.LOCK) {
                try (Connection c = DBUtil.getConnection()) {
                    c.setAutoCommit(false);
                    try { Map<String, Object> res = AccountService.createAll(c); c.commit(); return res; }
                    catch (Exception e) { c.rollback(); throw e; }
                }
            }
        }
        boolean commit = path.endsWith("/commit");
        if (!commit && !path.endsWith("/preview")) throw new ApiException(404, "Use /api/import/preview or /api/import/commit.");

        String source = r.str("source");
        String text = r.str("text");
        if ("url".equals(source)) text = fetch(r.needStr("url"));
        if (text == null || text.trim().isEmpty()) throw new ApiException(400, "Paste the timetable page / CSV, or give its address.");
        TimetableParser.Parsed parsed = "csv".equals(source) ? TimetableParser.parseCsv(text) : TimetableParser.parseHtml(text);

        TimetableImporter.Options o = new TimetableImporter.Options();
        Integer sem = r.intOrNull("semester");
        o.semester = sem != null ? sem : (parsed.detectedSemester != null ? parsed.detectedSemester : 1);
        Integer yr = r.intOrNull("year");
        o.year = yr != null ? yr : (o.semester + 1) / 2;
        Integer st = r.intOrNull("strength");
        o.strength = st != null && st > 0 ? st : 60;
        if (r.str("course") != null && !r.str("course").trim().isEmpty()) o.course = r.str("course").trim();
        if (r.str("saturday") != null) o.saturday = r.str("saturday");
        if (r.str("prefix") != null) o.prefix = r.str("prefix");
        o.addRooms = r.bool("addRooms");
        Object ren = r.body().get("rename");
        if (ren instanceof Map) for (Map.Entry<?, ?> e : ((Map<?, ?>) ren).entrySet())
            if (e.getValue() != null) o.rename.put(String.valueOf(e.getKey()), String.valueOf(e.getValue()));
        if (parsed.rows.isEmpty()) throw new ApiException(422, parsed.warnings.isEmpty()
                ? "Nothing could be read from this page. Use the CSV template instead." : String.join(" ", parsed.warnings));

        if (!commit) {
            try (Connection c = DBUtil.getConnection()) { return TimetableImporter.preview(c, parsed, o); }
        }
        synchronized (SchedulingEngine.LOCK) {                       // never import while a timetable is being generated
            try (Connection c = DBUtil.getConnection()) {
                c.setAutoCommit(false);
                try {
                    Map<String, Object> res = TimetableImporter.commit(c, parsed, o);
                    if (!"false".equals(String.valueOf(r.body().get("createLogins")))) res.put("logins", AccountService.createAll(c));
                    c.commit();
                    return res;
                } catch (Exception e) {
                    c.rollback();
                    throw e;
                }
            }
        }
    }

    /** Downloads a web page (http/https only, 6 MB max, 20 s). */
    private static String fetch(String address) throws Exception {
        URL url = new URL(address.trim());
        if (!url.getProtocol().equals("http") && !url.getProtocol().equals("https")) throw new ApiException(400, "Only http:// and https:// addresses can be imported.");
        HttpURLConnection con = (HttpURLConnection) url.openConnection();
        con.setConnectTimeout(10000);
        con.setReadTimeout(20000);
        con.setInstanceFollowRedirects(true);
        con.setRequestProperty("User-Agent", "Mozilla/5.0 (TimetableImporter)");
        int code = con.getResponseCode();
        if (code >= 400) throw new ApiException(502, "The site answered with HTTP " + code + ". Open the page in your browser, save it and paste its source instead.");
        String type = con.getContentType();
        String cs = "UTF-8";
        if (type != null && type.toLowerCase().contains("charset=")) cs = type.substring(type.toLowerCase().indexOf("charset=") + 8).replace("\"", "").trim();
        try (InputStream in = con.getInputStream(); ByteArrayOutputStream bo = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n, total = 0;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > 6_000_000) throw new ApiException(413, "That page is larger than 6 MB.");
                bo.write(buf, 0, n);
            }
            return new String(bo.toByteArray(), cs);
        }
    }
}
