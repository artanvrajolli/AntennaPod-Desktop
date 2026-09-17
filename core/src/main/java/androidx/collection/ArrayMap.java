package androidx.collection;

import java.util.LinkedHashMap;
import java.util.Map;

public class ArrayMap<K, V> extends LinkedHashMap<K, V> {
    public ArrayMap() {
    }

    public ArrayMap(int capacity) {
        super(capacity);
    }

    public ArrayMap(Map<? extends K, ? extends V> map) {
        super(map);
    }
}
