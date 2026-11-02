package com.yexclsv.localfiles.alac;

/**
 * Pure-Java Apple Lossless (ALAC) decoder for mono/stereo, 16/20/24/32-bit.
 * One call to decode() handles one MP4 sample (one ALAC frame).
 */
public final class AlacDecoderCore {
    public final int frameLength;
    public final int bitDepth;
    public final int channels;
    public final int sampleRate;

    private final int riceHistoryMult;
    private final int riceInitialHistory;
    private final int riceLimit;

    private final int[][] predictError;
    private final int[][] samples;
    private final int[][] extraBitsBuf;
    private final int[][] lpcCoefs = new int[2][32];

    private byte[] buf;
    private int len;
    private int pos; // bit position, MSB first

    public AlacDecoderCore(byte[] cookie) {
        int off;
        if (cookie.length >= 36 && cookie[4] == 'a' && cookie[5] == 'l' && cookie[6] == 'a' && cookie[7] == 'c') {
            off = 12;
        } else if (cookie.length >= 28) {
            off = 4;
        } else {
            off = 0;
        }
        if (cookie.length - off < 24) throw new IllegalArgumentException("ALAC cookie too short: " + cookie.length);
        int frame = be32(cookie, off);
        bitDepth = cookie[off + 5] & 0xFF;
        riceHistoryMult = cookie[off + 6] & 0xFF;
        riceInitialHistory = cookie[off + 7] & 0xFF;
        riceLimit = cookie[off + 8] & 0xFF;
        channels = cookie[off + 9] & 0xFF;
        sampleRate = be32(cookie, off + 20);
        if (frame <= 0 || frame > 65536) throw new IllegalArgumentException("Bad ALAC frame length " + frame);
        if (channels < 1 || channels > 2) throw new IllegalArgumentException("Unsupported ALAC channel count " + channels);
        if (bitDepth != 16 && bitDepth != 20 && bitDepth != 24 && bitDepth != 32)
            throw new IllegalArgumentException("Unsupported ALAC bit depth " + bitDepth);
        frameLength = frame;
        predictError = new int[2][frame];
        samples = new int[2][frame];
        extraBitsBuf = new int[2][frame];
    }

    private static int be32(byte[] b, int o) {
        return ((b[o] & 0xFF) << 24) | ((b[o + 1] & 0xFF) << 16) | ((b[o + 2] & 0xFF) << 8) | (b[o + 3] & 0xFF);
    }

    // ---------------- bit reader ----------------
    private int byteAt(int i) {
        return i < len ? (buf[i] & 0xFF) : 0;
    }

    private int getBits(int n) {
        if (n == 0) return 0;
        if (n > 25) {
            int hi = getBits(n - 16);
            return (hi << 16) | getBits(16);
        }
        int idx = pos >>> 3;
        int v = (byteAt(idx) << 24) | (byteAt(idx + 1) << 16) | (byteAt(idx + 2) << 8) | byteAt(idx + 3);
        v = (v << (pos & 7)) >>> (32 - n);
        pos += n;
        return v;
    }

    private int getSigned(int n) {
        if (n == 0) return 0;
        return (getBits(n) << (32 - n)) >> (32 - n);
    }

    private int peekBits(int n) {
        int p = pos;
        int v = getBits(n);
        pos = p;
        return v;
    }

    private static int log2(long v) {
        return v > 0 ? 63 - Long.numberOfLeadingZeros(v) : 0;
    }

    // ---------------- decoding ----------------
    /**
     * @param out interleaved output, must hold frameLength * channels ints
     * @return number of samples per channel
     */
    public int decode(byte[] data, int offset, int length, int[] out) {
        if (offset != 0) {
            byte[] c = new byte[length];
            System.arraycopy(data, offset, c, 0, length);
            data = c;
        }
        buf = data;
        len = length;
        pos = 0;
        int chIndex = 0;
        int nb = frameLength;
        while (chIndex < channels) {
            int tag = getBits(3);
            switch (tag) {
                case 0: // SCE
                case 3: // LFE
                    nb = element(1, chIndex);
                    chIndex += 1;
                    break;
                case 1: // CPE
                    if (chIndex + 2 > channels) throw new IllegalStateException("ALAC: too many channels in frame");
                    nb = element(2, chIndex);
                    chIndex += 2;
                    break;
                case 4: { // DSE
                    getBits(4);
                    boolean align = getBits(1) == 1;
                    int count = getBits(8);
                    if (count == 255) count += getBits(8);
                    if (align) pos = (pos + 7) & ~7;
                    pos += count * 8;
                    break;
                }
                case 6: { // FIL
                    int count = getBits(4);
                    if (count == 15) count += getBits(8) - 1;
                    pos += count * 8;
                    break;
                }
                case 7:
                    chIndex = channels;
                    break;
                default:
                    throw new IllegalStateException("ALAC: unsupported element " + tag);
            }
            if (pos > len * 8 + 64) throw new IllegalStateException("ALAC: ran out of data");
        }
        if (channels == 1) {
            for (int i = 0; i < nb; i++) out[i] = samples[0][i];
        } else {
            for (int i = 0; i < nb; i++) {
                out[2 * i] = samples[0][i];
                out[2 * i + 1] = samples[1][i];
            }
        }
        return nb;
    }

    private int element(int ch, int chBase) {
        getBits(4);  // instance tag
        getBits(12); // unused
        boolean hasSize = getBits(1) == 1;
        int extraBits = getBits(2) << 3;
        int bps = bitDepth - extraBits + ch - 1;
        boolean compressed = getBits(1) == 0;
        int nb = hasSize ? getBits(32) : frameLength;
        if (nb < 0 || nb > frameLength) throw new IllegalStateException("ALAC: bad sample count " + nb);
        int decorrShift = 0;
        int decorrLeft = 0;

        if (compressed) {
            decorrShift = getBits(8);
            decorrLeft = getBits(8);
            int[] mode = new int[2];
            int[] quant = new int[2];
            int[] mult = new int[2];
            int[] order = new int[2];
            for (int c = 0; c < ch; c++) {
                mode[c] = getBits(4);
                quant[c] = getBits(4);
                mult[c] = getBits(3);
                order[c] = getBits(5);
                for (int i = order[c] - 1; i >= 0; i--) lpcCoefs[c][i] = getSigned(16);
            }
            if (extraBits > 0) {
                for (int i = 0; i < nb; i++)
                    for (int c = 0; c < ch; c++) extraBitsBuf[c][i] = getBits(extraBits);
            }
            for (int c = 0; c < ch; c++) {
                riceDecompress(predictError[c], nb, bps, mult[c] * riceHistoryMult / 4);
                if (mode[c] != 0) {
                    lpcPredict(predictError[c], predictError[c], nb, bps, null, 31, 0);
                }
                lpcPredict(predictError[c], samples[c], nb, bps, lpcCoefs[c], order[c], quant[c]);
            }
        } else {
            for (int i = 0; i < nb; i++)
                for (int c = 0; c < ch; c++) samples[c][i] = getSigned(bitDepth);
            extraBits = 0;
        }

        if (ch == 2 && decorrLeft != 0) {
            int[] s0 = samples[0];
            int[] s1 = samples[1];
            for (int i = 0; i < nb; i++) {
                int a = s0[i];
                int b = s1[i];
                a -= (b * decorrLeft) >> decorrShift;
                b += a;
                s0[i] = b;
                s1[i] = a;
            }
        }
        if (extraBits > 0) {
            for (int c = 0; c < ch; c++)
                for (int i = 0; i < nb; i++) samples[c][i] = (samples[c][i] << extraBits) | extraBitsBuf[c][i];
        }
        return nb;
    }

    private int decodeScalar(int k, int bps) {
        int x = 0;
        while (x < 9 && getBits(1) == 1) x++;
        if (x > 8) {
            return getBits(bps);
        } else if (k != 1) {
            int extra = peekBits(k);
            x = (x << k) - x;
            if (extra > 1) {
                x += extra - 1;
                pos += k;
            } else {
                pos += k - 1;
            }
        }
        return x;
    }

    private void riceDecompress(int[] outBuf, int nb, int bps, int mult) {
        long history = riceInitialHistory;
        int signModifier = 0;
        for (int i = 0; i < nb; i++) {
            int k = log2((history >>> 9) + 3);
            k = Math.min(k, riceLimit);
            long x = decodeScalar(k, bps) & 0xFFFFFFFFL;
            x += signModifier;
            signModifier = 0;
            outBuf[i] = (int) ((x >>> 1) ^ -(x & 1));

            if (x > 0xFFFF) {
                history = 0xFFFF;
            } else {
                history += x * mult - ((history * mult) >>> 9);
            }
            history &= 0xFFFFFFFFL;

            if (history < 128 && i + 1 < nb) {
                k = 7 - log2(history) + (int) ((history + 16) >>> 6);
                k = Math.min(k, riceLimit);
                int block = decodeScalar(k, 16);
                if (block > 0) {
                    if (block >= nb - i) throw new IllegalStateException("ALAC: bad zero run");
                    for (int j = 1; j <= block; j++) outBuf[i + j] = 0;
                    i += block;
                }
                if (block <= 0xFFFF) signModifier = 1;
                history = 0;
            }
        }
    }

    private static int signExtend(int v, int bits) {
        int s = 32 - bits;
        return (v << s) >> s;
    }

    private static int sign(int v) {
        return v > 0 ? 1 : (v < 0 ? -1 : 0);
    }

    private static void lpcPredict(int[] err, int[] out, int n, int bps, int[] coefs, int order, int quant) {
        out[0] = err[0];
        if (n <= 1) return;
        if (order == 0) {
            if (out != err) System.arraycopy(err, 1, out, 1, n - 1);
            return;
        }
        if (order == 31) {
            for (int i = 1; i < n; i++) out[i] = signExtend(out[i - 1] + err[i], bps);
            return;
        }
        for (int i = 1; i <= order && i < n; i++) out[i] = signExtend(out[i - 1] + err[i], bps);

        int pred = 0;
        for (int i = order + 1; i < n; i++) {
            int d = out[pred++];
            int val = 0;
            for (int j = 0; j < order; j++) val += (out[pred + j] - d) * coefs[j];
            val = (val + (1 << (quant - 1))) >> quant;
            int errVal = err[i];
            val += d + errVal;
            out[i] = signExtend(val, bps);

            int errSign = sign(errVal);
            if (errSign != 0) {
                for (int j = 0; j < order && errVal * errSign > 0; j++) {
                    int v = d - out[pred + j];
                    int sg = sign(v) * errSign;
                    coefs[j] -= sg;
                    v *= sg;
                    errVal -= (v >> quant) * (j + 1);
                }
            }
        }
    }
}
