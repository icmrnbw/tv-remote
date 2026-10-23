package io.github.icmrnbw.tvremote.protocol;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Just enough protobuf to speak the Android TV remote protocol, so the app needs no
 * generated code or extra libraries. Messages on the wire are prefixed by their length
 * as a varint.
 */
public final class Proto {
    private Proto() {}

    public static final class Writer {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();

        public Writer uint(int field, long value) {
            key(field, 0);
            raw(value);
            return this;
        }

        public Writer bool(int field, boolean value) {
            return uint(field, value ? 1 : 0);
        }

        public Writer bytes(int field, byte[] value) {
            key(field, 2);
            raw(value.length);
            out.write(value, 0, value.length);
            return this;
        }

        public Writer string(int field, String value) {
            return bytes(field, value.getBytes(StandardCharsets.UTF_8));
        }

        public Writer message(int field, Writer value) {
            return bytes(field, value.toByteArray());
        }

        public byte[] toByteArray() {
            return out.toByteArray();
        }

        private void key(int field, int wireType) {
            raw(((long) field << 3) | wireType);
        }

        private void raw(long value) {
            while ((value & ~0x7FL) != 0) {
                out.write((int) ((value & 0x7F) | 0x80));
                value >>>= 7;
            }
            out.write((int) value);
        }
    }

    /** A parsed message: field number to the values seen for it (Long or byte[]). */
    public static final class Message {
        private final Map<Integer, List<Object>> fields = new HashMap<>();

        public static Message parse(byte[] data) throws IOException {
            Message m = new Message();
            int[] pos = {0};
            while (pos[0] < data.length) {
                long key = readVarint(data, pos);
                int field = (int) (key >>> 3);
                int wireType = (int) (key & 7);
                Object value;
                switch (wireType) {
                    case 0:
                        value = readVarint(data, pos);
                        break;
                    case 1:
                        value = slice(data, pos, 8);
                        break;
                    case 2:
                        value = slice(data, pos, (int) readVarint(data, pos));
                        break;
                    case 5:
                        value = slice(data, pos, 4);
                        break;
                    default:
                        throw new IOException("Unsupported protobuf wire type " + wireType);
                }
                List<Object> values = m.fields.get(field);
                if (values == null) {
                    values = new ArrayList<>();
                    m.fields.put(field, values);
                }
                values.add(value);
            }
            return m;
        }

        public boolean has(int field) {
            return fields.containsKey(field);
        }

        public long uint(int field, long fallback) {
            Object v = last(field);
            return v instanceof Long ? (Long) v : fallback;
        }

        public byte[] bytes(int field) {
            Object v = last(field);
            return v instanceof byte[] ? (byte[]) v : null;
        }

        public String string(int field, String fallback) {
            byte[] b = bytes(field);
            return b == null ? fallback : new String(b, StandardCharsets.UTF_8);
        }

        /** The embedded message in a field, or null when the field is absent. */
        public Message message(int field) throws IOException {
            byte[] b = bytes(field);
            return b == null ? null : parse(b);
        }

        private Object last(int field) {
            List<Object> values = fields.get(field);
            return values == null ? null : values.get(values.size() - 1);
        }

        private static long readVarint(byte[] data, int[] pos) throws IOException {
            long result = 0;
            for (int shift = 0; shift < 64; shift += 7) {
                if (pos[0] >= data.length) throw new IOException("Truncated varint");
                int b = data[pos[0]++] & 0xFF;
                result |= (long) (b & 0x7F) << shift;
                if ((b & 0x80) == 0) return result;
            }
            throw new IOException("Malformed varint");
        }

        private static byte[] slice(byte[] data, int[] pos, int length) throws IOException {
            if (length < 0 || pos[0] + length > data.length) throw new IOException("Truncated field");
            byte[] out = new byte[length];
            System.arraycopy(data, pos[0], out, 0, length);
            pos[0] += length;
            return out;
        }
    }

    public static void writeFramed(OutputStream out, byte[] message) throws IOException {
        long length = message.length;
        while ((length & ~0x7FL) != 0) {
            out.write((int) ((length & 0x7F) | 0x80));
            length >>>= 7;
        }
        out.write((int) length);
        out.write(message);
        out.flush();
    }

    public static byte[] readFramed(InputStream in) throws IOException {
        int length = 0;
        for (int shift = 0; ; shift += 7) {
            int b = in.read();
            if (b < 0) throw new EOFException("Connection closed by the TV");
            length |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) break;
            if (shift > 21) throw new IOException("Message too long");
        }
        byte[] message = new byte[length];
        new DataInputStream(in).readFully(message);
        return message;
    }
}
