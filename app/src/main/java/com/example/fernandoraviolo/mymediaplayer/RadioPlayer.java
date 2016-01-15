/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.example.fernandoraviolo.mymediaplayer;

import com.google.android.exoplayer.CodecCounters;
import com.google.android.exoplayer.DummyTrackRenderer;
import com.google.android.exoplayer.ExoPlaybackException;
import com.google.android.exoplayer.ExoPlayer;
import com.google.android.exoplayer.MediaCodecAudioTrackRenderer;
import com.google.android.exoplayer.MediaCodecTrackRenderer.DecoderInitializationException;
import com.google.android.exoplayer.MediaFormat;
import com.google.android.exoplayer.TimeRange;
import com.google.android.exoplayer.TrackRenderer;
import com.google.android.exoplayer.audio.AudioTrack;
import com.google.android.exoplayer.chunk.ChunkSampleSource;
import com.google.android.exoplayer.chunk.Format;
import com.google.android.exoplayer.dash.DashChunkSource;
import com.google.android.exoplayer.drm.StreamingDrmSessionManager;
import com.google.android.exoplayer.upstream.BandwidthMeter;
import com.google.android.exoplayer.upstream.DefaultBandwidthMeter;
import com.google.android.exoplayer.util.DebugTextViewHelper;
import com.google.android.exoplayer.util.PlayerControl;

import android.media.MediaCodec.CryptoException;
import android.os.Handler;
import android.os.Looper;
import android.view.Surface;

import java.io.IOException;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A wrapper around {@link ExoPlayer} that provides a higher level interface.
 */
public class RadioPlayer implements ExoPlayer.Listener, ChunkSampleSource.EventListener, DefaultBandwidthMeter.EventListener, MediaCodecAudioTrackRenderer.EventListener,
        StreamingDrmSessionManager.EventListener, DashChunkSource.EventListener, DebugTextViewHelper.Provider {

    public void stop() {

        player.stop();
    }

    /**
     * Builds renderers for the player.
     */
    public interface RendererBuilder {
        /**
         * Builds renderers for playback.
         *
         * @param player The player for which renderers are being built.
         *     should be invoked once the renderers have been built. If building fails
         */
        void buildRenderers(RadioPlayer player);
        /**
         * Cancels the current build operation, if there is one. Else does nothing.
         */
        void cancel();
    }

    /**
     * A listener for core events.
     */
    public interface Listener {
        void onStateChanged(boolean playWhenReady, int playbackState);
        void onError(Exception e);
    }

    /**
     * A listener for internal errors.
     * <p>
     * These errors are not visible to the user, and hence this listener is provided for
     * informational purposes only. Note however that an internal error may cause a fatal
     * error if the player fails to recover. If this happens, {@link Listener#onError(Exception)}
     * will be invoked.
     */
    public interface InternalErrorListener {
        void onRendererInitializationError(Exception e);
        void onAudioTrackInitializationError(AudioTrack.InitializationException e);
        void onAudioTrackWriteError(AudioTrack.WriteException e);
        void onAudioTrackUnderrun(int bufferSize, long bufferSizeMs, long elapsedSinceLastFeedMs);
        void onDecoderInitializationError(DecoderInitializationException e);
        void onCryptoError(CryptoException e);
        void onLoadError(int sourceId, IOException e);
        void onDrmSessionManagerError(Exception e);
    }

    /**
     * A listener for debugging information.
     */
    public interface InfoListener {
        void onAudioFormatEnabled(Format format, int trigger, long mediaTimeMs);
        void onDroppedFrames(int count, long elapsed);
        void onBandwidthSample(int elapsedMs, long bytes, long bitrateEstimate);
        void onLoadStarted(int sourceId, long length, int type, int trigger, Format format,
                           long mediaStartTimeMs, long mediaEndTimeMs);
        void onLoadCompleted(int sourceId, long bytesLoaded, int type, int trigger, Format format,
                             long mediaStartTimeMs, long mediaEndTimeMs, long elapsedRealtimeMs, long loadDurationMs);
        void onDecoderInitialized(String decoderName, long elapsedRealtimeMs,
                                  long initializationDurationMs);
        void onAvailableRangeChanged(TimeRange availableRange);
    }

    // Constants pulled into this class for convenience.
    public static final int STATE_IDLE = ExoPlayer.STATE_IDLE;
    public static final int STATE_PREPARING = ExoPlayer.STATE_PREPARING;
    public static final int STATE_BUFFERING = ExoPlayer.STATE_BUFFERING;
    public static final int STATE_READY = ExoPlayer.STATE_READY;
    public static final int STATE_ENDED = ExoPlayer.STATE_ENDED;
    public static final int TRACK_DISABLED = ExoPlayer.TRACK_DISABLED;
    public static final int TRACK_DEFAULT = ExoPlayer.TRACK_DEFAULT;

    public static final int RENDERER_COUNT = 1;
    public static final int TYPE_AUDIO = 0;

    private static final int RENDERER_BUILDING_STATE_IDLE = 1;
    private static final int RENDERER_BUILDING_STATE_BUILDING = 2;
    private static final int RENDERER_BUILDING_STATE_BUILT = 3;

    private final RendererBuilder rendererBuilder;
    private final ExoPlayer player;
    private final PlayerControl playerControl;
    private final Handler mainHandler;
    private final CopyOnWriteArrayList<Listener> listeners;

    private int rendererBuildingState;
    private int lastReportedPlaybackState;
    private boolean lastReportedPlayWhenReady;

    private Surface surface;
    private CodecCounters codecCounters;

    private BandwidthMeter bandwidthMeter;
    private boolean backgrounded;

    private InternalErrorListener internalErrorListener;
    private InfoListener infoListener;

    public RadioPlayer(RendererBuilder rendererBuilder) {
        this.rendererBuilder = rendererBuilder;
        player = ExoPlayer.Factory.newInstance(RENDERER_COUNT, 500, 5000);
        player.addListener(this);
        playerControl = new PlayerControl(player);
        mainHandler = new Handler();
        listeners = new CopyOnWriteArrayList<>();
        lastReportedPlaybackState = STATE_IDLE;
        rendererBuildingState = RENDERER_BUILDING_STATE_IDLE;
    }

    public PlayerControl getPlayerControl() {
        return playerControl;
    }

    public void addListener(Listener listener) {
        listeners.add(listener);
    }

    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    public void setInternalErrorListener(InternalErrorListener listener) {
        internalErrorListener = listener;
    }

    public void setInfoListener(InfoListener listener) {
        infoListener = listener;
    }


    public void setSurface(Surface surface) {
        this.surface = surface;
    }

    public Surface getSurface() {
        return surface;
    }

    public void blockingClearSurface() {
        surface = null;
    }

    public int getTrackCount(int type) {
        return player.getTrackCount(type);
    }

    public MediaFormat getTrackFormat(int type, int index) {
        return player.getTrackFormat(type, index);
    }

    public int getSelectedTrack(int type) {
        return player.getSelectedTrack(type);
    }

    public void setSelectedTrack(int type, int index) {
        player.setSelectedTrack(type, index);
    }

    public boolean getBackgrounded() {
        return backgrounded;
    }

    public void prepare() {
        if (rendererBuildingState == RENDERER_BUILDING_STATE_BUILT) {
            player.stop();
        }
        rendererBuilder.cancel();
        rendererBuildingState = RENDERER_BUILDING_STATE_BUILDING;
        maybeReportPlayerState();
        rendererBuilder.buildRenderers(this);
    }

    /**
     * Invoked with the results from a {@link RendererBuilder}.
     *
     * @param renderers Renderers indexed by TYPE_* constants. An individual
     *     element may be null if there do not exist tracks of the corresponding type.
     * @param bandwidthMeter Provides an estimate of the currently available bandwidth. May be null.
     */
  /* package */ void onRenderers(TrackRenderer[] renderers, BandwidthMeter bandwidthMeter) {
        for (int i = 0; i < RENDERER_COUNT; i++) {
            if (renderers[i] == null) {
                // Convert a null renderer to a dummy renderer.
                renderers[i] = new DummyTrackRenderer();
            }
        }
        // Complete preparation.
        this.bandwidthMeter = bandwidthMeter;
        player.prepare(renderers);

        rendererBuildingState = RENDERER_BUILDING_STATE_BUILT;
    }

    /**
     * Invoked if a {@link RendererBuilder} encounters an error.
     *
     * @param e Describes the error.
     */
  /* package */ void onRenderersError(Exception e) {
        if (internalErrorListener != null) {
            internalErrorListener.onRendererInitializationError(e);
        }
        for (Listener listener : listeners) {
            listener.onError(e);
        }
        rendererBuildingState = RENDERER_BUILDING_STATE_IDLE;
        maybeReportPlayerState();
    }

    public void setPlayWhenReady(boolean playWhenReady) {
        player.setPlayWhenReady(playWhenReady);
    }

    public void seekTo(long positionMs) {
        player.seekTo(positionMs);
    }

    public void release() {
        rendererBuilder.cancel();
        rendererBuildingState = RENDERER_BUILDING_STATE_IDLE;
        surface = null;
        player.release();
    }

    public int getPlaybackState() {
        if (rendererBuildingState == RENDERER_BUILDING_STATE_BUILDING) {
            return STATE_PREPARING;
        }
        int playerState = player.getPlaybackState();
        if (rendererBuildingState == RENDERER_BUILDING_STATE_BUILT && playerState == STATE_IDLE) {
            // This is an edge case where the renderers are built, but are still being passed to the
            // player's playback thread.
            return STATE_PREPARING;
        }
        return playerState;
    }

    @Override
    public BandwidthMeter getBandwidthMeter() {
        return bandwidthMeter;
    }

    @Override
    public CodecCounters getCodecCounters() {
        return codecCounters;
    }

    @Override
    public long getCurrentPosition() {
        return player.getCurrentPosition();
    }

    @Override
    public Format getFormat() {
        return null;
    }

    public long getDuration() {
        return player.getDuration();
    }

    public int getBufferedPercentage() {
        return player.getBufferedPercentage();
    }

    public boolean getPlayWhenReady() {
        return player.getPlayWhenReady();
    }

    /* package */ Looper getPlaybackLooper() {
        return player.getPlaybackLooper();
    }

    /* package */ Handler getMainHandler() {
        return mainHandler;
    }

    @Override
    public void onPlayerStateChanged(boolean playWhenReady, int state) {
        maybeReportPlayerState();
    }

    @Override
    public void onPlayerError(ExoPlaybackException exception) {
        rendererBuildingState = RENDERER_BUILDING_STATE_IDLE;
        for (Listener listener : listeners) {
            listener.onError(exception);
        }
    }

    @Override
    public void onBandwidthSample(int elapsedMs, long bytes, long bitrateEstimate) {
        if (infoListener != null) {
            infoListener.onBandwidthSample(elapsedMs, bytes, bitrateEstimate);
        }
    }

    @Override
    public void onDownstreamFormatChanged(int sourceId, Format format, int trigger,
                                          long mediaTimeMs) {
        if (infoListener == null) {
            return;
        }
        if (sourceId == TYPE_AUDIO) {
            infoListener.onAudioFormatEnabled(format, trigger, mediaTimeMs);
        }
    }

    @Override
    public void onDrmKeysLoaded() {
        // Do nothing.
    }

    @Override
    public void onDrmSessionManagerError(Exception e) {
        if (internalErrorListener != null) {
            internalErrorListener.onDrmSessionManagerError(e);
        }
    }

    @Override
    public void onDecoderInitializationError(DecoderInitializationException e) {
        if (internalErrorListener != null) {
            internalErrorListener.onDecoderInitializationError(e);
        }
    }

    @Override
    public void onAudioTrackInitializationError(AudioTrack.InitializationException e) {
        if (internalErrorListener != null) {
            internalErrorListener.onAudioTrackInitializationError(e);
        }
    }

    @Override
    public void onAudioTrackWriteError(AudioTrack.WriteException e) {
        if (internalErrorListener != null) {
            internalErrorListener.onAudioTrackWriteError(e);
        }
    }

    @Override
    public void onAudioTrackUnderrun(int bufferSize, long bufferSizeMs, long elapsedSinceLastFeedMs) {
        if (internalErrorListener != null) {
            internalErrorListener.onAudioTrackUnderrun(bufferSize, bufferSizeMs, elapsedSinceLastFeedMs);
        }
    }

    @Override
    public void onCryptoError(CryptoException e) {
        if (internalErrorListener != null) {
            internalErrorListener.onCryptoError(e);
        }
    }

    @Override
    public void onDecoderInitialized(String decoderName, long elapsedRealtimeMs,
                                     long initializationDurationMs) {
        if (infoListener != null) {
            infoListener.onDecoderInitialized(decoderName, elapsedRealtimeMs, initializationDurationMs);
        }
    }

    @Override
    public void onLoadError(int sourceId, IOException e) {
        if (internalErrorListener != null) {
            internalErrorListener.onLoadError(sourceId, e);
        }
    }

    @Override
    public void onAvailableRangeChanged(TimeRange availableRange) {
        if (infoListener != null) {
            infoListener.onAvailableRangeChanged(availableRange);
        }
    }

    @Override
    public void onPlayWhenReadyCommitted() {
        // Do nothing.
    }

    @Override
    public void onLoadStarted(int sourceId, long length, int type, int trigger, Format format,
                              long mediaStartTimeMs, long mediaEndTimeMs) {
        if (infoListener != null) {
            infoListener.onLoadStarted(sourceId, length, type, trigger, format, mediaStartTimeMs,
                    mediaEndTimeMs);
        }
    }

    @Override
    public void onLoadCompleted(int sourceId, long bytesLoaded, int type, int trigger, Format format,
                                long mediaStartTimeMs, long mediaEndTimeMs, long elapsedRealtimeMs, long loadDurationMs) {
        if (infoListener != null) {
            infoListener.onLoadCompleted(sourceId, bytesLoaded, type, trigger, format, mediaStartTimeMs,
                    mediaEndTimeMs, elapsedRealtimeMs, loadDurationMs);
        }
    }

    @Override
    public void onLoadCanceled(int sourceId, long bytesLoaded) {
        // Do nothing.
    }

    @Override
    public void onUpstreamDiscarded(int sourceId, long mediaStartTimeMs, long mediaEndTimeMs) {
        // Do nothing.
    }

    private void maybeReportPlayerState() {
        boolean playWhenReady = player.getPlayWhenReady();
        int playbackState = getPlaybackState();
        if (lastReportedPlayWhenReady != playWhenReady || lastReportedPlaybackState != playbackState) {
            for (Listener listener : listeners) {
                listener.onStateChanged(playWhenReady, playbackState);
            }
            lastReportedPlayWhenReady = playWhenReady;
            lastReportedPlaybackState = playbackState;
        }
    }
}
