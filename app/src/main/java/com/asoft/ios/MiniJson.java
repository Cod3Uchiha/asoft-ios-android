package com.asoft.ios;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A small JSON reader for the files the works report keeps and fetches (devices.json, the run and setup records).
 * It is here and not org.json because the JVM tests of the pure classes have no org.json (the android.jar stubs
 * throw). Objects come back as Map, arrays as List, numbers as Double, strings as String, true/false as Boolean,
 * null as JNULL. Anything that is not valid JSON (or nests deeper than 12) gives null: callers treat that as
 * "no file".
 */
final class MiniJson {
    private MiniJson() {}

    static final Object JNULL = new Object();
    private static final int MAX_DEPTH = 12;

    static Object parse(String s) {
        if (s == null) {
            return null;
        }
        try {
            Reader r = new Reader(s);
            r.ws();
            Object v = r.value(0);
            r.ws();
            return r.i == s.length() ? v : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** @o as a string; "" for anything that is not one. */
    static String str(Object o) {
        return o instanceof String ? (String) o : "";
    }

    /** @m's @key as a string ("" when absent or not a string). */
    static String str(Map<?, ?> m, String key) {
        return str(m.get(key));
    }

    /** @m's @key as a number, @none when absent or not a number. */
    static double num(Map<?, ?> m, String key, double none) {
        Object o = m.get(key);
        return o instanceof Double ? (Double) o : none;
    }

    static boolean bool(Map<?, ?> m, String key) {
        return Boolean.TRUE.equals(m.get(key));
    }

    /** @s as a JSON string literal. */
    static String quote(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':
                    b.append("\\\"");
                    break;
                case '\\':
                    b.append("\\\\");
                    break;
                case '\n':
                    b.append("\\n");
                    break;
                case '\r':
                    b.append("\\r");
                    break;
                case '\t':
                    b.append("\\t");
                    break;
                default:
                    if (c < 32) {
                        b.append(String.format("\\u%04x", (int) c));
                    } else {
                        b.append(c);
                    }
            }
        }
        return b.append('"').toString();
    }

    private static final class Reader {
        final String s;
        int i;

        Reader(String s) {
            this.s = s.length() > 0 && s.charAt(0) == '﻿' ? s.substring(1) : s;
        }

        void ws() {
            while (i < s.length() && " \t\r\n".indexOf(s.charAt(i)) >= 0) {
                i++;
            }
        }

        Object value(int depth) {
            if (depth > MAX_DEPTH) {
                throw new IllegalStateException("too deep");
            }
            char c = s.charAt(i);
            if (c == '{') {
                return object(depth);
            } else if (c == '[') {
                return array(depth);
            } else if (c == '"') {
                return string();
            } else if (s.startsWith("true", i)) {
                i += 4;
                return Boolean.TRUE;
            } else if (s.startsWith("false", i)) {
                i += 5;
                return Boolean.FALSE;
            } else if (s.startsWith("null", i)) {
                i += 4;
                return JNULL;
            }
            int from = i;
            while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) {
                i++;
            }
            if (i == from) {
                throw new IllegalStateException("unexpected " + c);
            }
            return Double.valueOf(s.substring(from, i));
        }

        Map<String, Object> object(int depth) {
            Map<String, Object> m = new LinkedHashMap<>();
            i++;
            ws();
            if (s.charAt(i) == '}') {
                i++;
                return m;
            }
            while (true) {
                ws();
                String k = string();
                ws();
                expect(':');
                ws();
                m.put(k, value(depth + 1));
                ws();
                if (s.charAt(i) == ',') {
                    i++;
                    continue;
                }
                expect('}');
                return m;
            }
        }

        List<Object> array(int depth) {
            List<Object> l = new ArrayList<>();
            i++;
            ws();
            if (s.charAt(i) == ']') {
                i++;
                return l;
            }
            while (true) {
                ws();
                l.add(value(depth + 1));
                ws();
                if (s.charAt(i) == ',') {
                    i++;
                    continue;
                }
                expect(']');
                return l;
            }
        }

        void expect(char c) {
            if (s.charAt(i) != c) {
                throw new IllegalStateException("expected " + c);
            }
            i++;
        }

        String string() {
            expect('"');
            StringBuilder b = new StringBuilder();
            while (true) {
                char c = s.charAt(i++);
                if (c == '"') {
                    return b.toString();
                }
                if (c != '\\') {
                    b.append(c);
                    continue;
                }
                char e = s.charAt(i++);
                switch (e) {
                    case 'n':
                        b.append('\n');
                        break;
                    case 'r':
                        b.append('\r');
                        break;
                    case 't':
                        b.append('\t');
                        break;
                    case 'b':
                        b.append('\b');
                        break;
                    case 'f':
                        b.append('\f');
                        break;
                    case 'u':
                        b.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                        i += 4;
                        break;
                    default:
                        b.append(e);      // " \ /
                }
            }
        }
    }
}
