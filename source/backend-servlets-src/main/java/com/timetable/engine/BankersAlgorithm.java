package com.timetable.engine;

import java.util.*;

/**
 * Banker's Algorithm (Silberschatz, Operating System Concepts, ch. "Deadlocks").
 *
 *   Available[j]      free instances of resource type j
 *   Max[i][j]         most process i may ever claim of type j
 *   Allocation[i][j]  what process i currently holds
 *   Need[i][j]        Max - Allocation
 *
 * Safety algorithm : find an order of processes that can all finish.
 * Resource-request : pretend to grant a request, then run the safety algorithm;
 *                    grant only if the resulting state is SAFE.
 *
 * releaseOnFinish = true   textbook version: a finished process gives its allocation back.
 * releaseOnFinish = false  timetable version: a booked room/faculty period is never handed
 *                          back inside the planning cycle, so "finishing" CONSUMES the
 *                          remaining Need instead of releasing the Allocation.
 */
public class BankersAlgorithm {

    public static class SafetyResult {
        public boolean safe;
        public List<Integer> sequence = new ArrayList<>();
        public List<String> steps = new ArrayList<>();
    }

    public static class RequestResult {
        public boolean granted;
        public String reason;
        public SafetyResult safety;
    }

    private final int n, m;
    private final int[] available;
    private final int[][] max, alloc;
    private final boolean releaseOnFinish;

    public BankersAlgorithm(int[] available, int[][] max, int[][] alloc, boolean releaseOnFinish) {
        this.available = available.clone();
        this.max = deep(max);
        this.alloc = deep(alloc);
        this.n = max.length;
        this.m = available.length;
        this.releaseOnFinish = releaseOnFinish;
    }

    private static int[][] deep(int[][] a) {
        int[][] c = new int[a.length][];
        for (int i = 0; i < a.length; i++) c[i] = a[i].clone();
        return c;
    }

    public int[][] need() {
        int[][] need = new int[n][m];
        for (int i = 0; i < n; i++)
            for (int j = 0; j < m; j++) need[i][j] = max[i][j] - alloc[i][j];
        return need;
    }

    public int[] available() { return available.clone(); }
    public int[][] allocation() { return deep(alloc); }

    private static boolean leq(int[] a, int[] b) {
        for (int j = 0; j < a.length; j++) if (a[j] > b[j]) return false;
        return true;
    }

    /** Safety algorithm. */
    public SafetyResult isSafe() {
        SafetyResult res = new SafetyResult();
        int[][] need = need();
        int[] work = available.clone();
        boolean[] finish = new boolean[n];
        boolean progress = true;
        while (progress) {
            progress = false;
            for (int i = 0; i < n; i++) {
                if (!finish[i] && leq(need[i], work)) {
                    for (int j = 0; j < m; j++) work[j] += releaseOnFinish ? alloc[i][j] : -need[i][j];
                    finish[i] = true;
                    res.sequence.add(i);
                    res.steps.add("P" + i + " can finish (Need " + Arrays.toString(need[i]) + " <= Work). Work becomes " + Arrays.toString(work));
                    progress = true;
                }
            }
        }
        res.safe = true;
        for (boolean f : finish) if (!f) { res.safe = false; break; }
        if (!res.safe) {
            StringBuilder stuck = new StringBuilder();
            for (int i = 0; i < n; i++) if (!finish[i]) stuck.append("P").append(i).append(' ');
            res.steps.add("No remaining process has Need <= Work. Stuck: " + stuck.toString().trim() + "  -> UNSAFE");
        }
        return res;
    }

    /** Resource-request algorithm. If granted, this object's state is updated. */
    public RequestResult request(int p, int[] req) {
        RequestResult out = new RequestResult();
        int[] need = need()[p];
        if (!leq(req, need)) {
            out.granted = false;
            out.reason = "Process P" + p + " asked for more than its declared maximum claim.";
            return out;
        }
        if (!leq(req, available)) {
            out.granted = false;
            out.reason = "Resources are not available right now; P" + p + " must wait.";
            return out;
        }
        for (int j = 0; j < m; j++) {            // pretend to allocate
            available[j] -= req[j];
            alloc[p][j] += req[j];
        }
        out.safety = isSafe();
        if (out.safety.safe) {
            out.granted = true;
            out.reason = "Granting leaves the system in a SAFE state.";
        } else {
            for (int j = 0; j < m; j++) {        // roll back
                available[j] += req[j];
                alloc[p][j] -= req[j];
            }
            out.granted = false;
            out.reason = "Granting would leave the system in an UNSAFE state, so the request is denied.";
        }
        return out;
    }
}
