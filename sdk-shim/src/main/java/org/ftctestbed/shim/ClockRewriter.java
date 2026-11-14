package org.ftctestbed.shim;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Points a class's calls to System.nanoTime(), System.currentTimeMillis(), Thread.sleep(long)
 * and Thread.sleep(long, int) at {@link SimTime}'s methods of the same name and signature.
 *
 * Only the constant pool changes: those method references get a new owner class (two entries
 * are appended), so the bytecode, stack maps and everything else stay exactly as compiled.
 */
public final class ClockRewriter {
    private ClockRewriter() {}

    static final String TARGET = "org/ftctestbed/shim/SimTime";

    private static final int UTF8 = 1, INTEGER = 3, FLOAT = 4, LONG = 5, DOUBLE = 6, CLASS = 7, STRING = 8,
            FIELDREF = 9, METHODREF = 10, INTERFACE_METHODREF = 11, NAME_AND_TYPE = 12, METHOD_HANDLE = 15,
            METHOD_TYPE = 16, DYNAMIC = 17, INVOKE_DYNAMIC = 18, MODULE = 19, PACKAGE = 20;

    /** The rewritten class, or null if it doesn't call any of those methods (or isn't a class file). */
    public static byte[] rewrite(byte[] b) {
        try {
            return rewriteOrNull(b);
        } catch (RuntimeException | IOException e) {
            return null; // not something we understand: leave it alone
        }
    }

    private static byte[] rewriteOrNull(byte[] b) throws IOException {
        if (b.length < 10 || u4(b, 0) != 0xCAFEBABEL) return null;
        int count = u2(b, 8);
        int[] offset = new int[count];
        int[] tag = new int[count];
        int p = 10;
        for (int i = 1; i < count; i++) {
            offset[i] = p;
            tag[i] = b[p] & 0xFF;
            switch (tag[i]) {
                case UTF8: p += 3 + u2(b, p + 1); break;
                case INTEGER: case FLOAT: case FIELDREF: case METHODREF: case INTERFACE_METHODREF:
                case NAME_AND_TYPE: case DYNAMIC: case INVOKE_DYNAMIC: p += 5; break;
                case LONG: case DOUBLE: p += 9; i++; break;
                case CLASS: case STRING: case METHOD_TYPE: case MODULE: case PACKAGE: p += 3; break;
                case METHOD_HANDLE: p += 4; break;
                default: return null;
            }
        }
        int poolEnd = p;
        boolean any = false;
        boolean[] patch = new boolean[count];
        for (int i = 1; i < count; i++) {
            if (tag[i] != METHODREF) continue;
            String owner = utf8(b, offset, tag, u2(b, offset[u2(b, offset[i] + 1)] + 1));
            int nat = u2(b, offset[i] + 3);
            String name = utf8(b, offset, tag, u2(b, offset[nat] + 1));
            String desc = utf8(b, offset, tag, u2(b, offset[nat] + 3));
            boolean hit = ("java/lang/System".equals(owner) && "()J".equals(desc)
                    && ("nanoTime".equals(name) || "currentTimeMillis".equals(name)))
                    || ("java/lang/Thread".equals(owner) && "sleep".equals(name) && ("(J)V".equals(desc) || "(JI)V".equals(desc)));
            if (hit) {
                patch[i] = true;
                any = true;
            }
        }
        if (!any || count + 2 > 0xFFFF) return null;

        int utf8Index = count, classIndex = count + 1;
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(b.length + 64);
        DataOutputStream out = new DataOutputStream(bytes);
        out.write(b, 0, 8);
        out.writeShort(count + 2);
        int copied = 10;
        for (int i = 1; i < count; i++) {
            if (!patch[i]) continue;
            out.write(b, copied, offset[i] + 1 - copied); // everything up to the class index
            out.writeShort(classIndex);
            copied = offset[i] + 3;
        }
        out.write(b, copied, poolEnd - copied);
        byte[] name = TARGET.getBytes(StandardCharsets.UTF_8);
        out.writeByte(UTF8);
        out.writeShort(name.length);
        out.write(name);
        out.writeByte(CLASS);
        out.writeShort(utf8Index);
        out.write(b, poolEnd, b.length - poolEnd);
        out.flush();
        return bytes.toByteArray();
    }

    private static String utf8(byte[] b, int[] offset, int[] tag, int index) {
        if (index <= 0 || index >= offset.length || tag[index] != UTF8) return "";
        int len = u2(b, offset[index] + 1);
        // Modified UTF-8; the names we look for are plain ASCII.
        return new String(b, offset[index] + 3, len, StandardCharsets.ISO_8859_1);
    }

    private static int u2(byte[] b, int p) {
        return ((b[p] & 0xFF) << 8) | (b[p + 1] & 0xFF);
    }

    private static long u4(byte[] b, int p) {
        return ((long) u2(b, p) << 16) | u2(b, p + 2);
    }
}
