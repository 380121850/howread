package com.foobnix.remote;

/**
 * Head-byte DRM probe for the MOBI family (tech-spec §3.5): the PalmDOC
 * header inside record 0 carries an encryption field — 0 = plain, non-zero
 * = DRM-protected. Only needs the first few KB of the file (record list
 * included), so the check is one short remote read.
 */
public class MobiHead {

    private MobiHead() {
    }

    /**
     * @param head the first bytes of the file (≥ a few KB recommended)
     * @return Boolean.TRUE / FALSE when the MOBI head parses, null when the
     *         bytes do not look like a parseable MOBI/AZW head (probe
     *         inconclusive — treat as not encrypted and let the engine speak)
     */
    public static Boolean isEncrypted(byte[] head) {
        try {
            if (head == null || head.length < 88) {
                return null;
            }
            // PalmDB header: record0 offset is the first record-list entry
            // at byte 78 (4-byte file offset)
            long rec0 = u32(head, 78);
            if (rec0 < 0 || rec0 + 14 > head.length) {
                return null;
            }
            // PalmDOC header at record0: compression(2) unused(2) textLength(4)
            // recordCount(2) recordSize(2) encryption(2) — encryption at +12
            int encryption = u16(head, (int) rec0 + 12);
            return encryption != 0;
        } catch (Exception e) {
            return null;
        }
    }

    private static long u32(byte[] b, int off) {
        return ((b[off] & 0xFFL) << 24) | ((b[off + 1] & 0xFFL) << 16)
                | ((b[off + 2] & 0xFFL) << 8) | (b[off + 3] & 0xFFL);
    }

    private static int u16(byte[] b, int off) {
        return ((b[off] & 0xFF) << 8) | (b[off + 1] & 0xFF);
    }
}
