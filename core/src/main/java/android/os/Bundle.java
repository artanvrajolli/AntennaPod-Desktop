package android.os;

import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;

public final class Bundle implements Serializable {
    private final Map<String, Object> values = new HashMap<>();

    public Bundle() {
    }

    public void putString(String key, String value) {
        values.put(key, value);
    }

    public String getString(String key) {
        Object value = values.get(key);
        return value instanceof String ? (String) value : null;
    }

    public void putLong(String key, long value) {
        values.put(key, value);
    }

    public long getLong(String key, long defaultValue) {
        Object value = values.get(key);
        return value instanceof Long ? (Long) value : defaultValue;
    }

    public void putInt(String key, int value) {
        values.put(key, value);
    }

    public int getInt(String key, int defaultValue) {
        Object value = values.get(key);
        return value instanceof Integer ? (Integer) value : defaultValue;
    }

    public void putBoolean(String key, boolean value) {
        values.put(key, value);
    }

    public boolean getBoolean(String key, boolean defaultValue) {
        Object value = values.get(key);
        return value instanceof Boolean ? (Boolean) value : defaultValue;
    }

    @Override
    public int hashCode() {
        return values.hashCode();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Bundle)) {
            return false;
        }
        return values.equals(((Bundle) o).values);
    }
}
