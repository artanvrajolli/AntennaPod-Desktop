package android.os;

import java.util.ArrayList;
import java.util.List;

public final class Parcel {
    private final List<Object> data = new ArrayList<>();
    private int position;

    public static Parcel obtain() {
        return new Parcel();
    }

    public void writeString(String value) {
        data.add(value);
    }

    public String readString() {
        return (String) data.get(position++);
    }

    public void writeLong(long value) {
        data.add(value);
    }

    public long readLong() {
        return (Long) data.get(position++);
    }

    public void writeInt(int value) {
        data.add(value);
    }

    public int readInt() {
        return (Integer) data.get(position++);
    }

    public void writeByte(byte value) {
        data.add(value);
    }

    public byte readByte() {
        return (Byte) data.get(position++);
    }

    public void writeBundle(Bundle value) {
        data.add(value);
    }

    public Bundle readBundle() {
        Object value = data.get(position++);
        return (Bundle) value;
    }

    public void setDataPosition(int position) {
        this.position = position;
    }
}
