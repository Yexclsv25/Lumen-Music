package com.yexclsv.localfiles.alac;

import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.decoder.DecoderException;
import androidx.media3.decoder.DecoderInputBuffer;
import androidx.media3.decoder.SimpleDecoder;
import androidx.media3.decoder.SimpleDecoderOutputBuffer;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Media3 decoder wrapper around the pure-Java ALAC core. */
@UnstableApi
public final class AlacDecoder
        extends SimpleDecoder<DecoderInputBuffer, SimpleDecoderOutputBuffer, DecoderException> {

    private final AlacDecoderCore core;
    private final int[] pcm;
    public final int channelCount;
    public final int sampleRate;
    @C.PcmEncoding
    public final int pcmEncoding;

    public AlacDecoder(Format format) throws DecoderException {
        super(new DecoderInputBuffer[4], new SimpleDecoderOutputBuffer[4]);
        if (format.initializationData.isEmpty()) {
            throw new DecoderException("ALAC: missing codec cookie");
        }
        AlacDecoderCore c;
        try {
            c = new AlacDecoderCore(format.initializationData.get(0));
        } catch (RuntimeException e) {
            throw new DecoderException("ALAC: bad codec cookie", e);
        }
        core = c;
        pcm = new int[c.frameLength * c.channels];
        channelCount = c.channels;
        sampleRate = c.sampleRate;
        pcmEncoding = c.bitDepth == 16 ? C.ENCODING_PCM_16BIT : C.ENCODING_PCM_32BIT;
        setInitialInputBufferSize(32 * 1024);
    }

    @Override
    public String getName() {
        return "AlacDecoder";
    }

    @Override
    protected DecoderInputBuffer createInputBuffer() {
        return new DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_DIRECT);
    }

    @Override
    protected SimpleDecoderOutputBuffer createOutputBuffer() {
        return new SimpleDecoderOutputBuffer(this::releaseOutputBuffer);
    }

    @Override
    protected DecoderException createUnexpectedDecodeException(Throwable error) {
        return new DecoderException("Unexpected ALAC decode error", error);
    }

    @Override
    protected DecoderException decode(
            DecoderInputBuffer inputBuffer, SimpleDecoderOutputBuffer outputBuffer, boolean reset) {
        ByteBuffer in = inputBuffer.data;
        if (in == null) return new DecoderException("ALAC: empty input");
        in.position(0);
        byte[] bytes = new byte[in.limit()];
        in.get(bytes);

        int n;
        try {
            n = core.decode(bytes, 0, bytes.length, pcm);
        } catch (RuntimeException e) {
            return new DecoderException("ALAC decode failed", e);
        }

        int total = n * core.channels;
        int shift = 32 - core.bitDepth;
        boolean is16 = pcmEncoding == C.ENCODING_PCM_16BIT;
        ByteBuffer out = outputBuffer.init(inputBuffer.timeUs, total * (is16 ? 2 : 4));
        out.order(ByteOrder.LITTLE_ENDIAN);
        if (is16) {
            for (int i = 0; i < total; i++) out.putShort((short) pcm[i]);
        } else {
            for (int i = 0; i < total; i++) out.putInt(pcm[i] << shift);
        }
        out.flip();
        return null;
    }
}
