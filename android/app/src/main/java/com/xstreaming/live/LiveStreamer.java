package com.xstreaming.live;

import android.annotation.SuppressLint;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.pedro.common.ConnectChecker;
import com.pedro.rtmp.rtmp.RtmpClient;

import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * Sends the Xbox Remote Play stream to an RTMP server (YouTube Live).
 *
 * How it works (no screen capture involved):
 *  - VIDEO: the Xbox already sends H.264. We receive the same compressed frames that go into
 *    the phone's video decoder and forward them as-is ("passthrough"). No re-encoding, so the
 *    phone does almost no extra work. Quality = the stream quality chosen in XStreaming.
 *  - AUDIO: we receive the decoded game sound (PCM) right before it is played, optionally mix in
 *    the phone microphone, encode to AAC and send it.
 *
 * The taps live in LowLatencyVideoDecoder / DelegatingAudioTrack (native engine) and
 * NanoRustBridge (nano engine), right next to the existing GameRecorder taps.
 *
 * ASSUMPTIONS (v1, to be validated on a real device):
 *  - The Xbox stream is H.264 (XStreaming only offers H.264 codecs today).
 *  - The game audio arrives as 16-bit PCM (true for both engines today).
 *  - The resolution does not change in the middle of a live. If it does, YouTube may show
 *    artifacts until the live is restarted.
 */
public class LiveStreamer {
    private static final String TAG = "LiveStreamer";
    private static volatile LiveStreamer sInstance;

    public static final int STATE_IDLE = 0;
    public static final int STATE_CONNECTING = 1;
    public static final int STATE_LIVE = 2;

    public interface Listener {
        void onStateChanged(int state, @Nullable String message);
        void onStats(long bitrateBps, long droppedVideoFrames, int queuedItems);
    }

    public static class Options {
        public String url;               // full RTMP url, already including the stream key
        public boolean micEnabled;
        public int micVolumePercent = 100;
        public int gameVolumePercent = 100;
        public int audioBitrateKbps = 128;
    }

    private final Object mLock = new Object();
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());

    private volatile int mState = STATE_IDLE;
    private volatile boolean mActive = false;       // taps should feed us
    private volatile boolean mConnected = false;    // RTMP publish accepted
    private volatile boolean mFirstKeyframeSent = false;

    @Nullable private Listener mListener;
    @Nullable private RtmpClient mRtmp;
    @Nullable private Options mOptions;

    private HandlerThread mAudioThread;
    private Handler mAudioHandler;
    @Nullable private MediaCodec mAacEncoder;
    private int mAudioSampleRate = 48000;
    private int mAudioChannels = 2;
    private long mAudioFramesQueued = 0;   // used to build smooth audio timestamps
    private long mAudioBaseUs = -1;

    private long mStartNs = -1;
    private long mLastVideoTsUs = -1;
    private byte[] mSps;
    private byte[] mPps;
    private boolean mVideoInfoSent = false;
    private int mWidth = 0;
    private int mHeight = 0;

    private MicCapture mMic;

    private LiveStreamer() {}

    public static LiveStreamer getInstance() {
        if (sInstance == null) {
            synchronized (LiveStreamer.class) {
                if (sInstance == null) {
                    sInstance = new LiveStreamer();
                }
            }
        }
        return sInstance;
    }

    public void setListener(@Nullable Listener listener) {
        mListener = listener;
    }

    /** Cheap check used by the taps so they do nothing when no live is running. */
    public boolean isActive() {
        return mActive;
    }

    public int getState() {
        return mState;
    }

    // ------------------------------------------------------------------ start / stop

    public void start(@NonNull Options options) {
        synchronized (mLock) {
            if (mState != STATE_IDLE) {
                notifyState(mState, "Live already running");
                return;
            }
            mOptions = options;
            mStartNs = -1;
            mLastVideoTsUs = -1;
            mSps = null;
            mPps = null;
            mVideoInfoSent = false;
            mFirstKeyframeSent = false;
            mConnected = false;
            mAudioFramesQueued = 0;
            mAudioBaseUs = -1;

            mAudioThread = new HandlerThread("LiveStreamerAudio");
            mAudioThread.start();
            mAudioHandler = new Handler(mAudioThread.getLooper());

            RtmpClient rtmp = new RtmpClient(mConnectChecker);
            // Default codecs of RtmpClient are H.264 + AAC, which is what YouTube wants.
            rtmp.setReTries(10);
            rtmp.setFps(60);
            rtmp.setAudioInfo(mAudioSampleRate, mAudioChannels == 2);
            mRtmp = rtmp;

            if (options.micEnabled) {
                mMic = new MicCapture(mAudioSampleRate);
                mMic.start();
            }

            mActive = true;
            setState(STATE_CONNECTING, null);
            rtmp.connect(options.url);
        }
    }

    public void stop() {
        synchronized (mLock) {
            if (mState == STATE_IDLE && !mActive) {
                return;
            }
            mActive = false;
            mConnected = false;
            if (mRtmp != null) {
                try {
                    mRtmp.disconnect();
                } catch (Throwable t) {
                    Log.w(TAG, "disconnect failed", t);
                }
                mRtmp = null;
            }
            if (mMic != null) {
                mMic.stop();
                mMic = null;
            }
            final MediaCodec encoder = mAacEncoder;
            mAacEncoder = null;
            final HandlerThread thread = mAudioThread;
            if (mAudioHandler != null) {
                mAudioHandler.post(() -> {
                    releaseEncoder(encoder);
                    if (thread != null) {
                        thread.quitSafely();
                    }
                });
            } else {
                releaseEncoder(encoder);
            }
            mAudioThread = null;
            mAudioHandler = null;
            setState(STATE_IDLE, null);
        }
    }

    // ------------------------------------------------------------------ video tap

    /**
     * Called with each compressed H.264 frame received from the Xbox (Annex-B, with start codes).
     * The buffer is NOT modified.
     */
    public void feedVideoFrame(ByteBuffer buffer, boolean isKeyframe, int width, int height) {
        if (!mActive || buffer == null) {
            return;
        }
        ByteBuffer dup = buffer.duplicate();
        int size = dup.remaining();
        if (size <= 4) {
            return;
        }
        byte[] data = new byte[size];
        dup.get(data);

        boolean hasIdr = scanNals(data);
        boolean key = isKeyframe || hasIdr;
        if (width > 0 && height > 0) {
            mWidth = width;
            mHeight = height;
        }

        RtmpClient rtmp = mRtmp;
        if (rtmp == null || !mConnected) {
            return;
        }
        if (!mVideoInfoSent) {
            if (mSps == null || mPps == null) {
                return; // wait for a keyframe carrying SPS/PPS
            }
            rtmp.setVideoInfo(ByteBuffer.wrap(mSps), ByteBuffer.wrap(mPps), null);
            if (mWidth > 0 && mHeight > 0) {
                rtmp.setVideoResolution(mWidth, mHeight);
            }
            mVideoInfoSent = true;
        }
        if (!mFirstKeyframeSent) {
            if (!key) {
                return; // a live must start on a keyframe
            }
            mFirstKeyframeSent = true;
        }

        long tsUs = nowUs();
        if (tsUs <= mLastVideoTsUs) {
            tsUs = mLastVideoTsUs + 1000;
        }
        mLastVideoTsUs = tsUs;

        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        info.set(0, data.length, tsUs, key ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0);
        try {
            rtmp.sendVideo(ByteBuffer.wrap(data), info);
        } catch (Throwable t) {
            Log.w(TAG, "sendVideo failed", t);
        }
    }

    // ------------------------------------------------------------------ audio tap

    /** Called with each chunk of decoded game sound (16-bit PCM), right before it is played. */
    public void feedGameAudio(ByteBuffer pcm, int sampleRate, int channelCount) {
        if (!mActive || pcm == null || !mFirstKeyframeSent) {
            return;
        }
        ByteBuffer dup = pcm.duplicate();
        final byte[] bytes = new byte[dup.remaining()];
        if (bytes.length == 0) {
            return;
        }
        dup.get(bytes);
        final int sr = sampleRate > 0 ? sampleRate : 48000;
        final int ch = channelCount > 0 ? channelCount : 2;
        Handler h = mAudioHandler;
        if (h != null) {
            h.post(() -> encodeAudio(bytes, sr, ch));
        }
    }

    private void encodeAudio(byte[] gamePcm, int sampleRate, int channels) {
        if (!mActive) {
            return;
        }
        Options opt = mOptions;
        if (opt == null) {
            return;
        }
        // Always send stereo 16-bit PCM to the encoder.
        byte[] stereo = channels == 2 ? gamePcm : monoToStereo(gamePcm);
        if (sampleRate != mAudioSampleRate || mAacEncoder == null) {
            if (sampleRate != mAudioSampleRate) {
                mAudioSampleRate = sampleRate;
                RtmpClient rtmp = mRtmp;
                if (rtmp != null) {
                    rtmp.setAudioInfo(sampleRate, true);
                }
            }
            if (mAacEncoder == null) {
                mAacEncoder = createEncoder(sampleRate, opt.audioBitrateKbps);
                if (mAacEncoder == null) {
                    return;
                }
            }
        }

        mixInPlace(stereo, opt.gameVolumePercent, mMic, opt.micVolumePercent);

        int frames = stereo.length / 4;
        if (mAudioBaseUs < 0) {
            mAudioBaseUs = nowUs();
        }
        long ptsUs = mAudioBaseUs + (mAudioFramesQueued * 1_000_000L) / sampleRate;
        // If audio fell far behind the wall clock (e.g. the game was silent / paused), resync.
        long now = nowUs();
        if (now - ptsUs > 500_000L) {
            mAudioBaseUs = now - (mAudioFramesQueued * 1_000_000L) / sampleRate;
            ptsUs = now;
        }
        mAudioFramesQueued += frames;

        MediaCodec enc = mAacEncoder;
        int offset = 0;
        try {
            while (offset < stereo.length) {
                int inIndex = enc.dequeueInputBuffer(10_000);
                if (inIndex < 0) {
                    break; // encoder busy: drop the rest of this chunk instead of blocking playback
                }
                ByteBuffer in = enc.getInputBuffer(inIndex);
                if (in == null) {
                    break;
                }
                in.clear();
                int n = Math.min(in.remaining(), stereo.length - offset);
                n -= n % 4;
                in.put(stereo, offset, n);
                long chunkPts = ptsUs + ((offset / 4) * 1_000_000L) / sampleRate;
                enc.queueInputBuffer(inIndex, 0, n, chunkPts, 0);
                offset += n;
                drainEncoder(enc);
            }
            drainEncoder(enc);
        } catch (Throwable t) {
            Log.w(TAG, "audio encode failed", t);
        }
    }

    private void drainEncoder(MediaCodec enc) {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        while (true) {
            int outIndex = enc.dequeueOutputBuffer(info, 0);
            if (outIndex < 0) {
                return; // TRY_AGAIN_LATER or FORMAT_CHANGED
            }
            ByteBuffer out = enc.getOutputBuffer(outIndex);
            boolean isConfig = (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0;
            RtmpClient rtmp = mRtmp;
            if (out != null && !isConfig && info.size > 0 && rtmp != null && mConnected) {
                byte[] copy = new byte[info.size];
                out.position(info.offset);
                out.limit(info.offset + info.size);
                out.get(copy);
                MediaCodec.BufferInfo sendInfo = new MediaCodec.BufferInfo();
                sendInfo.set(0, copy.length, info.presentationTimeUs, 0);
                rtmp.sendAudio(ByteBuffer.wrap(copy), sendInfo);
            }
            enc.releaseOutputBuffer(outIndex, false);
        }
    }

    @Nullable
    private static MediaCodec createEncoder(int sampleRate, int bitrateKbps) {
        try {
            MediaFormat f = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, 2);
            f.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
            f.setInteger(MediaFormat.KEY_BIT_RATE, Math.max(64, bitrateKbps) * 1000);
            f.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 32768);
            MediaCodec c = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
            c.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            c.start();
            return c;
        } catch (Throwable t) {
            Log.e(TAG, "Cannot create AAC encoder", t);
            return null;
        }
    }

    private static void releaseEncoder(@Nullable MediaCodec c) {
        if (c == null) {
            return;
        }
        try { c.stop(); } catch (Throwable ignored) {}
        try { c.release(); } catch (Throwable ignored) {}
    }

    // ------------------------------------------------------------------ helpers

    private long nowUs() {
        long now = System.nanoTime();
        if (mStartNs < 0) {
            mStartNs = now;
        }
        return (now - mStartNs) / 1000L;
    }

    /** Finds SPS/PPS (stores them) and reports whether the frame holds an IDR slice. */
    private boolean scanNals(byte[] d) {
        boolean idr = false;
        int n = d.length;
        int start = -1;
        int type = -1;
        for (int i = 0; i + 3 <= n; ) {
            int sc = 0;
            if (i + 3 <= n && d[i] == 0 && d[i + 1] == 0 && d[i + 2] == 1) {
                sc = 3;
            } else if (i + 4 <= n && d[i] == 0 && d[i + 1] == 0 && d[i + 2] == 0 && d[i + 3] == 1) {
                sc = 4;
            }
            if (sc > 0) {
                if (start >= 0) {
                    storeParamSet(d, start, i, type);
                }
                start = i;
                if (i + sc < n) {
                    type = d[i + sc] & 0x1F;
                    if (type == 5) {
                        idr = true;
                    }
                }
                i += sc;
            } else {
                i++;
            }
        }
        if (start >= 0) {
            storeParamSet(d, start, n, type);
        }
        return idr;
    }

    private void storeParamSet(byte[] d, int from, int to, int type) {
        if (type == 7) {
            byte[] sps = Arrays.copyOfRange(d, from, to);
            if (mSps != null && mVideoInfoSent && !Arrays.equals(mSps, sps)) {
                Log.w(TAG, "SPS changed during live (resolution change?)");
            }
            mSps = sps;
        } else if (type == 8) {
            mPps = Arrays.copyOfRange(d, from, to);
        }
    }

    private static byte[] monoToStereo(byte[] mono) {
        byte[] out = new byte[mono.length * 2];
        for (int i = 0, o = 0; i + 1 < mono.length; i += 2, o += 4) {
            out[o] = mono[i];
            out[o + 1] = mono[i + 1];
            out[o + 2] = mono[i];
            out[o + 3] = mono[i + 1];
        }
        return out;
    }

    /** Applies game volume and adds the microphone (if any) into the stereo game buffer. */
    private static void mixInPlace(byte[] stereo, int gameVol, @Nullable MicCapture mic, int micVol) {
        int frames = stereo.length / 4;
        short[] micFrames = mic != null ? mic.read(frames) : null;
        if (gameVol == 100 && micFrames == null) {
            return;
        }
        float g = gameVol / 100f;
        float m = micVol / 100f;
        for (int f = 0; f < frames; f++) {
            float micSample = micFrames != null ? micFrames[f] * m : 0f;
            for (int c = 0; c < 2; c++) {
                int idx = f * 4 + c * 2;
                short s = (short) ((stereo[idx] & 0xFF) | (stereo[idx + 1] << 8));
                int v = Math.round(s * g + micSample);
                if (v > Short.MAX_VALUE) v = Short.MAX_VALUE;
                if (v < Short.MIN_VALUE) v = Short.MIN_VALUE;
                stereo[idx] = (byte) (v & 0xFF);
                stereo[idx + 1] = (byte) ((v >> 8) & 0xFF);
            }
        }
    }

    private void setState(int state, @Nullable String message) {
        mState = state;
        notifyState(state, message);
    }

    private void notifyState(int state, @Nullable String message) {
        Listener l = mListener;
        if (l != null) {
            mMainHandler.post(() -> l.onStateChanged(state, message));
        }
    }

    private final ConnectChecker mConnectChecker = new ConnectChecker() {
        @Override
        public void onConnectionStarted(@NonNull String url) {
            Log.i(TAG, "RTMP connecting");
        }

        @Override
        public void onConnectionSuccess() {
            Log.i(TAG, "RTMP connected");
            mConnected = true;
            mVideoInfoSent = false;
            mFirstKeyframeSent = false;
            setState(STATE_LIVE, null);
        }

        @Override
        public void onConnectionFailed(@NonNull String reason) {
            Log.w(TAG, "RTMP failed: " + reason);
            mConnected = false;
            RtmpClient rtmp = mRtmp;
            if (mActive && rtmp != null && rtmp.shouldRetry(reason)) {
                // Network hiccup: try again in 5 s without ending the live.
                setState(STATE_CONNECTING, "reconnecting");
                rtmp.reConnect(5000, null);
                return;
            }
            stop();
            notifyState(STATE_IDLE, reason);
        }

        @Override
        public void onDisconnect() {
            Log.i(TAG, "RTMP disconnected");
            mConnected = false;
        }

        @Override
        public void onAuthError() {
            stop();
            notifyState(STATE_IDLE, "auth");
        }

        @Override
        public void onAuthSuccess() {}

        @Override
        public void onNewBitrate(long bitrate) {
            Listener l = mListener;
            RtmpClient rtmp = mRtmp;
            if (l != null && rtmp != null) {
                final long dropped = rtmp.getDroppedVideoFrames();
                final int queued = rtmp.getItemsInCache();
                mMainHandler.post(() -> l.onStats(bitrate, dropped, queued));
            }
        }
    };

    // ------------------------------------------------------------------ microphone

    /**
     * Records the phone microphone into a small ring buffer. The game audio "pulls" from it,
     * so both stay in step. Mono 16-bit at the game sample rate.
     *
     * ASSUMPTION: using headphones. With the phone speaker, the mic will also pick up the game
     * sound and viewers will hear it twice (slightly delayed).
     */
    private static class MicCapture {
        private final int mSampleRate;
        private final short[] mRing;
        private int mWrite = 0;
        private int mAvailable = 0;
        private AudioRecord mRecord;
        private Thread mThread;
        private volatile boolean mRunning;

        MicCapture(int sampleRate) {
            mSampleRate = sampleRate;
            mRing = new short[sampleRate]; // 1 second
        }

        @SuppressLint("MissingPermission") // permission is requested from JS before starting
        void start() {
            try {
                int min = AudioRecord.getMinBufferSize(mSampleRate,
                        AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
                mRecord = new AudioRecord(MediaRecorder.AudioSource.MIC, mSampleRate,
                        AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                        Math.max(min, mSampleRate / 5 * 2));
                if (mRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                    Log.w(TAG, "Microphone not available");
                    mRecord.release();
                    mRecord = null;
                    return;
                }
                mRecord.startRecording();
            } catch (Throwable t) {
                Log.w(TAG, "Microphone start failed", t);
                mRecord = null;
                return;
            }
            mRunning = true;
            mThread = new Thread(() -> {
                short[] buf = new short[mSampleRate / 50]; // 20 ms
                while (mRunning) {
                    int n = mRecord.read(buf, 0, buf.length);
                    if (n > 0) {
                        synchronized (this) {
                            for (int i = 0; i < n; i++) {
                                mRing[mWrite] = buf[i];
                                mWrite = (mWrite + 1) % mRing.length;
                            }
                            mAvailable = Math.min(mRing.length, mAvailable + n);
                        }
                    }
                }
            }, "LiveStreamerMic");
            mThread.start();
        }

        /** Returns exactly {@code frames} samples (zero-padded if the mic is behind). */
        synchronized short[] read(int frames) {
            short[] out = new short[frames];
            int take = Math.min(frames, mAvailable);
            int readPos = (mWrite - mAvailable + mRing.length) % mRing.length;
            for (int i = 0; i < take; i++) {
                out[i] = mRing[(readPos + i) % mRing.length];
            }
            mAvailable -= take;
            // Keep latency low: if more than 150 ms piled up, drop the oldest part.
            int maxBacklog = mSampleRate * 150 / 1000;
            if (mAvailable > maxBacklog) {
                mAvailable = maxBacklog;
            }
            return out;
        }

        void stop() {
            mRunning = false;
            if (mThread != null) {
                try { mThread.join(500); } catch (InterruptedException ignored) {}
                mThread = null;
            }
            if (mRecord != null) {
                try { mRecord.stop(); } catch (Throwable ignored) {}
                try { mRecord.release(); } catch (Throwable ignored) {}
                mRecord = null;
            }
        }
    }
}
