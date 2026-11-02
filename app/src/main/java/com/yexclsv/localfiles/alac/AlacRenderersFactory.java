package com.yexclsv.localfiles.alac;

import android.content.Context;
import android.os.Handler;

import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.Renderer;
import androidx.media3.exoplayer.audio.AudioRendererEventListener;
import androidx.media3.exoplayer.audio.AudioSink;
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector;

import java.util.ArrayList;

/** Default renderers plus our ALAC software decoder. */
@UnstableApi
public final class AlacRenderersFactory extends DefaultRenderersFactory {

    public AlacRenderersFactory(Context context) {
        super(context);
        setEnableDecoderFallback(true);
    }

    @Override
    protected void buildAudioRenderers(
            Context context,
            int extensionRendererMode,
            MediaCodecSelector mediaCodecSelector,
            boolean enableDecoderFallback,
            AudioSink audioSink,
            Handler eventHandler,
            AudioRendererEventListener eventListener,
            ArrayList<Renderer> out) {
        super.buildAudioRenderers(
                context, extensionRendererMode, mediaCodecSelector, enableDecoderFallback,
                audioSink, eventHandler, eventListener, out);
        out.add(new AlacAudioRenderer(eventHandler, eventListener, audioSink));
    }
}
