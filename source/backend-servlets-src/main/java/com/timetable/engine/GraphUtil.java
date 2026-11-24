package com.timetable.engine;

import java.util.*;

/**
 * Deadlock detection on a wait-for graph (a resource-allocation graph with the resource
 * nodes collapsed): an edge A -> B means "A is waiting for something B holds".
 * A deadlock exists exactly when the graph contains a cycle. We find every strongly
 * connected component with Tarjan's algorithm; any component with more than one node
 * (or a self-loop) is a set of mutually waiting processes.
 */
public class GraphUtil {

    public static List<List<Integer>> deadlockedSets(Map<Integer, List<Integer>> adj) {
        Set<Integer> nodes = new TreeSet<>(adj.keySet());
        for (List<Integer> l : adj.values()) nodes.addAll(l);

        final int[] counter = {0};
        Map<Integer, Integer> index = new HashMap<>(), low = new HashMap<>();
        Deque<Integer> stack = new ArrayDeque<>();
        Set<Integer> onStack = new HashSet<>();
        List<List<Integer>> result = new ArrayList<>();

        for (Integer v : nodes) {
            if (!index.containsKey(v)) strong(v, adj, counter, index, low, stack, onStack, result);
        }
        return result;
    }

    private static void strong(int v, Map<Integer, List<Integer>> adj, int[] counter, Map<Integer, Integer> index,
                               Map<Integer, Integer> low, Deque<Integer> stack, Set<Integer> onStack, List<List<Integer>> out) {
        index.put(v, counter[0]);
        low.put(v, counter[0]);
        counter[0]++;
        stack.push(v);
        onStack.add(v);

        for (int w : adj.getOrDefault(v, Collections.emptyList())) {
            if (!index.containsKey(w)) {
                strong(w, adj, counter, index, low, stack, onStack, out);
                low.put(v, Math.min(low.get(v), low.get(w)));
            } else if (onStack.contains(w)) {
                low.put(v, Math.min(low.get(v), index.get(w)));
            }
        }
        if (low.get(v).equals(index.get(v))) {
            List<Integer> comp = new ArrayList<>();
            int w;
            do {
                w = stack.pop();
                onStack.remove(w);
                comp.add(w);
            } while (w != v);
            boolean selfLoop = adj.getOrDefault(v, Collections.emptyList()).contains(v);
            if (comp.size() > 1 || selfLoop) {
                Collections.sort(comp);
                out.add(comp);
            }
        }
    }

    /** One concrete cycle inside a strongly connected component, for display: [a, b, c, a]. */
    public static List<Integer> sampleCycle(Map<Integer, List<Integer>> adj, List<Integer> component) {
        Set<Integer> comp = new HashSet<>(component);
        int start = component.get(0);
        List<Integer> path = new ArrayList<>();
        Set<Integer> seen = new HashSet<>();
        if (dfs(start, start, adj, comp, path, seen, true)) return path;
        return new ArrayList<>(component);
    }

    private static boolean dfs(int v, int start, Map<Integer, List<Integer>> adj, Set<Integer> comp,
                               List<Integer> path, Set<Integer> seen, boolean first) {
        path.add(v);
        for (int w : adj.getOrDefault(v, Collections.emptyList())) {
            if (!comp.contains(w)) continue;
            if (w == start && !(first && path.size() == 1 && !adj.get(v).contains(v))) {
                path.add(w);
                return true;
            }
            if (!seen.contains(w)) {
                seen.add(w);
                if (dfs(w, start, adj, comp, path, seen, false)) return true;
            }
        }
        path.remove(path.size() - 1);
        return false;
    }
}
