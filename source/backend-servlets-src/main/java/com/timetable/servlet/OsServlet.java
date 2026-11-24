package com.timetable.servlet;

import com.timetable.engine.BankersAlgorithm;
import com.timetable.engine.MoveService;
import com.timetable.util.ApiServlet;

import javax.servlet.annotation.WebServlet;
import java.util.*;

/**
 * The "OS Lab" API.
 *  POST /api/os/bankers          Banker's safety / resource-request calculator (textbook semantics by default)
 *  GET  /api/os/deadlock         wait-for graph + detected deadlocks + recent move requests
 *  POST /api/os/moves            submit a class re-allocation request (may WAIT)
 *  DELETE /api/os/moves/{id}     cancel a waiting request
 *  POST /api/os/deadlock/resolve atomically apply every move in a deadlocked cycle
 *  POST /api/os/deadlock/demo    create a ready-made circular wait
 */
@WebServlet({"/api/os", "/api/os/*"})
public class OsServlet extends ApiServlet {

    private final MoveService moves = new MoveService();

    @Override
    protected Object get(Req r) throws Exception {
        if (r.path().equals("/deadlock")) {
            Map<String, Object> out = moves.analyze();
            out.put("history", moves.history());
            return out;
        }
        if (r.path().equals("/moves")) return moves.history();
        throw new ApiException(404, "Unknown OS-lab endpoint.");
    }

    @Override
    protected Object post(Req r) throws Exception {
        switch (r.path()) {
            case "/bankers": return bankers(r);
            case "/moves":
                r.status = 201;
                return moves.submit(r.needInt("entryId"), r.needInt("roomId"), r.needInt("timeslotId"));
            case "/deadlock/resolve": return moves.resolve();
            case "/deadlock/demo": return moves.demo();
            default: throw new ApiException(404, "Unknown OS-lab endpoint.");
        }
    }

    @Override
    protected Object delete(Req r) throws Exception {
        if (!r.path().startsWith("/moves/") || r.pathId() == null) throw new ApiException(404, "Unknown OS-lab endpoint.");
        moves.cancel(r.pathId());
        return null;
    }

    // ------------------------------------------------------------------ Banker's calculator
    @SuppressWarnings("unchecked")
    private static int[] vec(Object o, String name) {
        if (!(o instanceof List)) throw new ApiException(400, "'" + name + "' must be a list of numbers.");
        List<Object> l = (List<Object>) o;
        int[] a = new int[l.size()];
        for (int i = 0; i < a.length; i++) {
            if (!(l.get(i) instanceof Number)) throw new ApiException(400, "'" + name + "' must contain only numbers.");
            a[i] = ((Number) l.get(i)).intValue();
            if (a[i] < 0) throw new ApiException(400, "'" + name + "' can't contain negative numbers.");
        }
        return a;
    }

    @SuppressWarnings("unchecked")
    private static int[][] mat(Object o, String name) {
        if (!(o instanceof List)) throw new ApiException(400, "'" + name + "' must be a matrix.");
        List<Object> rows = (List<Object>) o;
        int[][] m = new int[rows.size()][];
        for (int i = 0; i < m.length; i++) m[i] = vec(rows.get(i), name);
        return m;
    }

    @SuppressWarnings("unchecked")
    private Object bankers(Req r) throws Exception {
        int[] available = vec(r.body().get("available"), "available");
        int[][] max = mat(r.body().get("max"), "max");
        int[][] alloc = mat(r.body().get("allocation"), "allocation");
        if (max.length == 0 || max.length != alloc.length) throw new ApiException(400, "'max' and 'allocation' need the same number of processes.");
        for (int i = 0; i < max.length; i++) {
            if (max[i].length != available.length || alloc[i].length != available.length)
                throw new ApiException(400, "Every row must have one entry per resource type (" + available.length + ").");
            for (int j = 0; j < available.length; j++)
                if (alloc[i][j] > max[i][j]) throw new ApiException(400, "Allocation can't exceed Max (process P" + i + ").");
        }
        boolean release = !r.body().containsKey("release") || r.bool("release");
        BankersAlgorithm b = new BankersAlgorithm(available, max, alloc, release);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("need", b.need());
        out.put("safety", safety(b.isSafe()));
        Object req = r.body().get("request");
        if (req instanceof Map) {
            Map<String, Object> rq = (Map<String, Object>) req;
            int p = ((Number) rq.get("process")).intValue();
            if (p < 0 || p >= max.length) throw new ApiException(400, "Request process is out of range.");
            BankersAlgorithm.RequestResult rr = b.request(p, vec(rq.get("vector"), "request vector"));
            Map<String, Object> ro = new LinkedHashMap<>();
            ro.put("granted", rr.granted);
            ro.put("reason", rr.reason);
            if (rr.safety != null) ro.put("safety", safety(rr.safety));
            out.put("request", ro);
            out.put("availableAfter", b.available());
            out.put("allocationAfter", b.allocation());
        }
        return out;
    }

    private static Map<String, Object> safety(BankersAlgorithm.SafetyResult s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("safe", s.safe);
        List<String> seq = new ArrayList<>();
        for (int i : s.sequence) seq.add("P" + i);
        m.put("sequence", seq);
        m.put("steps", s.steps);
        return m;
    }
}
