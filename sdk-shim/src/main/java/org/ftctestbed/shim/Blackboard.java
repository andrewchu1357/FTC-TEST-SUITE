package org.ftctestbed.shim;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * OpMode.blackboard (SDK 10.1): a map that keeps its contents from one OpMode to the next until
 * the Robot Controller restarts, so an AUTO can leave its end pose for the TeleOp. On the
 * simulator each robot has its own, as each real robot has its own Robot Controller; a new
 * simulator (a reload with new code is like deploying the app again) starts them empty.
 */
public final class Blackboard extends HashMap<String, Object> {
    private static final Map<Object, HashMap<String, Object>> BOARDS = new HashMap<Object, HashMap<String, Object>>();
    private static final Object ROBOT_1 = new Object();

    static void simClearAll() {
        synchronized (BOARDS) {
            BOARDS.clear();
        }
    }

    private static HashMap<String, Object> board() {
        Object key = SimHooks.robotKey();
        if (key == null) key = ROBOT_1;
        synchronized (BOARDS) {
            HashMap<String, Object> b = BOARDS.get(key);
            if (b == null) {
                b = new HashMap<String, Object>();
                BOARDS.put(key, b);
            }
            return b;
        }
    }

    @Override public int size() { return board().size(); }
    @Override public boolean isEmpty() { return board().isEmpty(); }
    @Override public Object get(Object key) { return board().get(key); }
    @Override public boolean containsKey(Object key) { return board().containsKey(key); }
    @Override public boolean containsValue(Object value) { return board().containsValue(value); }
    @Override public Object put(String key, Object value) { return board().put(key, value); }
    @Override public void putAll(Map<? extends String, ?> m) { board().putAll(m); }
    @Override public Object remove(Object key) { return board().remove(key); }
    @Override public void clear() { board().clear(); }
    @Override public Set<String> keySet() { return board().keySet(); }
    @Override public Collection<Object> values() { return board().values(); }
    @Override public Set<Map.Entry<String, Object>> entrySet() { return board().entrySet(); }
    @Override public Object getOrDefault(Object key, Object defaultValue) { return board().getOrDefault(key, defaultValue); }
    @Override public Object putIfAbsent(String key, Object value) { return board().putIfAbsent(key, value); }
    @Override public boolean remove(Object key, Object value) { return board().remove(key, value); }
    @Override public boolean replace(String key, Object oldValue, Object newValue) { return board().replace(key, oldValue, newValue); }
    @Override public Object replace(String key, Object value) { return board().replace(key, value); }

    @Override
    public Object computeIfAbsent(String key, java.util.function.Function<? super String, ?> f) {
        return board().computeIfAbsent(key, f);
    }

    @Override
    public Object computeIfPresent(String key, java.util.function.BiFunction<? super String, ? super Object, ?> f) {
        return board().computeIfPresent(key, f);
    }

    @Override
    public Object compute(String key, java.util.function.BiFunction<? super String, ? super Object, ?> f) {
        return board().compute(key, f);
    }

    @Override
    public Object merge(String key, Object value, java.util.function.BiFunction<? super Object, ? super Object, ?> f) {
        return board().merge(key, value, f);
    }

    @Override public void forEach(java.util.function.BiConsumer<? super String, ? super Object> action) { board().forEach(action); }

    @Override
    public void replaceAll(java.util.function.BiFunction<? super String, ? super Object, ?> f) {
        board().replaceAll(f);
    }

    @Override public Object clone() { return new HashMap<String, Object>(board()); }
    @Override public boolean equals(Object o) { return board().equals(o); }
    @Override public int hashCode() { return board().hashCode(); }
    @Override public String toString() { return board().toString(); }
}
