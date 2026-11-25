package com.xstreaming.live;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.Promise;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.bridge.ReadableMap;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.modules.core.DeviceEventManagerModule;

/** JavaScript bridge: startLive / stopLive / getState, plus "onLiveState" and "onLiveStats" events. */
public class LiveStreamModule extends ReactContextBaseJavaModule {
    private final ReactApplicationContext mContext;

    public LiveStreamModule(ReactApplicationContext context) {
        super(context);
        mContext = context;
        LiveStreamer.getInstance().setListener(new LiveStreamer.Listener() {
            @Override
            public void onStateChanged(int state, @Nullable String message) {
                WritableMap m = Arguments.createMap();
                m.putString("state", stateName(state));
                if (message != null) {
                    m.putString("message", message);
                }
                emit("onLiveState", m);
            }

            @Override
            public void onStats(long bitrateBps, long droppedVideoFrames, int queuedItems) {
                WritableMap m = Arguments.createMap();
                m.putDouble("bitrateKbps", bitrateBps / 1000.0);
                m.putDouble("droppedVideoFrames", droppedVideoFrames);
                m.putInt("queued", queuedItems);
                emit("onLiveStats", m);
            }
        });
    }

    @NonNull
    @Override
    public String getName() {
        return "LiveStreamModule";
    }

    @ReactMethod
    public void startLive(ReadableMap opts, Promise promise) {
        String url = opts.hasKey("url") ? opts.getString("url") : null;
        if (url == null || url.trim().isEmpty()) {
            promise.reject("LIVE_ERROR", "missing url");
            return;
        }
        LiveStreamer.Options o = new LiveStreamer.Options();
        o.url = url.trim();
        o.micEnabled = opts.hasKey("micEnabled") && opts.getBoolean("micEnabled");
        if (opts.hasKey("micVolume")) o.micVolumePercent = opts.getInt("micVolume");
        if (opts.hasKey("gameVolume")) o.gameVolumePercent = opts.getInt("gameVolume");
        if (opts.hasKey("audioBitrate")) o.audioBitrateKbps = opts.getInt("audioBitrate");
        LiveStreamer.getInstance().start(o);
        promise.resolve(true);
    }

    @ReactMethod
    public void stopLive(Promise promise) {
        LiveStreamer.getInstance().stop();
        promise.resolve(true);
    }

    @ReactMethod
    public void getState(Promise promise) {
        promise.resolve(stateName(LiveStreamer.getInstance().getState()));
    }

    // Required by NativeEventEmitter
    @ReactMethod
    public void addListener(String eventName) {}

    @ReactMethod
    public void removeListeners(double count) {}

    private static String stateName(int s) {
        switch (s) {
            case LiveStreamer.STATE_CONNECTING: return "connecting";
            case LiveStreamer.STATE_LIVE: return "live";
            default: return "idle";
        }
    }

    private void emit(String name, WritableMap params) {
        if (!mContext.hasActiveReactInstance()) {
            return;
        }
        mContext.getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter.class).emit(name, params);
    }
}
