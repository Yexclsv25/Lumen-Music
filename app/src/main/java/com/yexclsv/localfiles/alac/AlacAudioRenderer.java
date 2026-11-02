package com.yexclsv.localfiles.alac;

import android.os.Handler;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.decoder.CryptoConfig;
import androidx.media3.decoder.DecoderException;
import androidx.media3.exoplayer.audio.AudioRendererEventListener;
import androidx.media3.exoplayer.audio.AudioSink;
import androidx.media3.exoplayer.audio.DecoderAudioRenderer;

/** Plays Apple Lossless, which Android has no hardware/system decoder for. */
@UnstableApi
public final class AlacAudioRenderer extends DecoderAudioRenderer<AlacDecoder> {

    public AlacAudioRenderer(
            @Nullable Handler eventHandler,
            @Nullable AudioRendererEventListener eventListener,
            AudioSink audioSink) {
        super(eventHandler, eventListener, audioSink);
    }

    @Override
    public String getName() {
        return "AlacAudioRenderer";
    }

    @Override
    protected int supportsFormatInternal(Format format) {
        if (!MimeTypes.AUDIO_ALAC.equals(format.sampleMimeType)) {
            return C.FORMAT_UNSUPPORTED_TYPE;
        }
        if (format.initializationData.isEmpty()) {
            return C.FORMAT_UNSUPPORTED_SUBTYPE;
        }
        if (!sinkSupports(format, C.ENCODING_PCM_16BIT)
                && !sinkSupports(format, C.ENCODING_PCM_32BIT)) {
            return C.FORMAT_UNSUPPORTED_SUBTYPE;
        }
        return C.FORMAT_HANDLED;
    }

    private boolean sinkSupports(Format format, @C.PcmEncoding int encoding) {
        return sinkSupportsFormat(
                new Format.Builder()
                        .setSampleMimeType(MimeTypes.AUDIO_RAW)
                        .setChannelCount(format.channelCount)
                        .setSampleRate(format.sampleRate)
                        .setPcmEncoding(encoding)
                        .build());
    }

    @Override
    protected AlacDecoder createDecoder(Format format, @Nullable CryptoConfig cryptoConfig)
            throws DecoderException {
        return new AlacDecoder(format);
    }

    @Override
    protected Format getOutputFormat(AlacDecoder decoder) {
        return new Format.Builder()
                .setSampleMimeType(MimeTypes.AUDIO_RAW)
                .setChannelCount(decoder.channelCount)
                .setSampleRate(decoder.sampleRate)
                .setPcmEncoding(decoder.pcmEncoding)
                .build();
    }
}
