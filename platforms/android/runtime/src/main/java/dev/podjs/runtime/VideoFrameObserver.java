package dev.podjs.runtime;

import org.json.JSONObject;

/** Optional decoded-frame push, independent of prepare settlement. */
interface VideoFrameObserver { default void firstFrame(JSONObject value) {} default void volumeChanged(JSONObject value) {} default void stateChanged(JSONObject value) {} }
