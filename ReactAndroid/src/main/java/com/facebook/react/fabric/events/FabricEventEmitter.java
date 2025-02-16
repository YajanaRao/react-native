/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.facebook.react.fabric.events;

import static com.facebook.react.uimanager.events.TouchesHelper.CHANGED_TOUCHES_KEY;
import static com.facebook.react.uimanager.events.TouchesHelper.TARGET_KEY;
import static com.facebook.react.uimanager.events.TouchesHelper.TARGET_SURFACE_KEY;
import static com.facebook.react.uimanager.events.TouchesHelper.TOUCHES_KEY;

import android.util.Pair;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.facebook.common.logging.FLog;
import com.facebook.react.bridge.ReadableMap;
import com.facebook.react.bridge.WritableArray;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.bridge.WritableNativeArray;
import com.facebook.react.bridge.WritableNativeMap;
import com.facebook.react.fabric.FabricUIManager;
import com.facebook.react.uimanager.events.RCTModernEventEmitter;
import com.facebook.react.uimanager.events.TouchEventType;
import com.facebook.systrace.Systrace;
import java.util.HashSet;
import java.util.Set;

public class FabricEventEmitter implements RCTModernEventEmitter {

  private static final String TAG = "FabricEventEmitter";

  @NonNull private final FabricUIManager mUIManager;

  public FabricEventEmitter(@NonNull FabricUIManager uiManager) {
    mUIManager = uiManager;
  }

  @Override
  public void receiveEvent(int reactTag, @NonNull String eventName, @Nullable WritableMap params) {
    receiveEvent(-1, reactTag, eventName, params);
  }

  @Override
  public void receiveEvent(
      int surfaceId, int reactTag, String eventName, @Nullable WritableMap params) {
    receiveEvent(surfaceId, reactTag, eventName, false, 0, params);
  }

  @Override
  public void receiveEvent(
      int surfaceId,
      int reactTag,
      String eventName,
      boolean canCoalesceEvent,
      int customCoalesceKey,
      @Nullable WritableMap params) {
    Systrace.beginSection(
        Systrace.TRACE_TAG_REACT_JAVA_BRIDGE,
        "FabricEventEmitter.receiveEvent('" + eventName + "')");
    mUIManager.receiveEvent(
        surfaceId, reactTag, eventName, canCoalesceEvent, customCoalesceKey, params);
    Systrace.endSection(Systrace.TRACE_TAG_REACT_JAVA_BRIDGE);
  }

  @Override
  public void receiveTouches(
      @NonNull String eventTopLevelType,
      @NonNull WritableArray touches,
      @NonNull WritableArray changedIndices) {
    Systrace.beginSection(
        Systrace.TRACE_TAG_REACT_JAVA_BRIDGE,
        "FabricEventEmitter.receiveTouches('" + eventTopLevelType + "')");

    boolean isPointerEndEvent =
        TouchEventType.END.getJsName().equalsIgnoreCase(eventTopLevelType)
            || TouchEventType.CANCEL.getJsName().equalsIgnoreCase(eventTopLevelType);

    Pair<WritableArray, WritableArray> result =
        isPointerEndEvent
            ? removeTouchesAtIndices(touches, changedIndices)
            : touchSubsequence(touches, changedIndices);

    WritableArray changedTouches = result.first;
    touches = result.second;

    int eventCategory = getTouchCategory(eventTopLevelType);
    for (int jj = 0; jj < changedTouches.size(); jj++) {
      WritableMap touch = getWritableMap(changedTouches.getMap(jj));
      // Touch objects can fulfill the role of `DOM` `Event` objects if we set
      // the `changedTouches`/`touches`. This saves allocations.

      touch.putArray(CHANGED_TOUCHES_KEY, copyWritableArray(changedTouches));
      touch.putArray(TOUCHES_KEY, copyWritableArray(touches));
      WritableMap nativeEvent = touch;
      int rootNodeID = 0;
      int targetSurfaceId = nativeEvent.getInt(TARGET_SURFACE_KEY);
      int targetReactTag = nativeEvent.getInt(TARGET_KEY);
      if (targetReactTag < 1) {
        FLog.e(TAG, "A view is reporting that a touch occurred on tag zero.");
      } else {
        rootNodeID = targetReactTag;
      }

      mUIManager.receiveEvent(
          targetSurfaceId, rootNodeID, eventTopLevelType, false, 0, touch, eventCategory);
    }

    Systrace.endSection(Systrace.TRACE_TAG_REACT_JAVA_BRIDGE);
  }

  /** TODO T31905686 optimize this to avoid copying arrays */
  private WritableArray copyWritableArray(@NonNull WritableArray array) {
    WritableNativeArray ret = new WritableNativeArray();
    for (int i = 0; i < array.size(); i++) {
      ret.pushMap(getWritableMap(array.getMap(i)));
    }
    return ret;
  }

  /**
   * Destroys `touches` by removing touch objects at indices `indices`. This is to maintain
   * compatibility with W3C touch "end" events, where the active touches don't include the set that
   * has just been "ended".
   *
   * <p>This method was originally in ReactNativeRenderer.js
   *
   * <p>TODO: this method is a copy from ReactNativeRenderer.removeTouchesAtIndices and it needs to
   * be rewritten in a more efficient way,
   *
   * @param touches {@link WritableArray} Deserialized touch objects.
   * @param indices {WritableArray} Indices to remove from `touches`.
   * @return {Array<Touch>} Subsequence of removed touch objects.
   */
  private @NonNull Pair<WritableArray, WritableArray> removeTouchesAtIndices(
      @NonNull WritableArray touches, @NonNull WritableArray indices) {
    WritableArray rippedOut = new WritableNativeArray();
    // use an unsafe downcast to alias to nullable elements,
    // so we can delete and then compact.
    WritableArray tempTouches = new WritableNativeArray();
    Set<Integer> rippedOutIndices = new HashSet<>();
    for (int i = 0; i < indices.size(); i++) {
      int index = indices.getInt(i);
      rippedOut.pushMap(getWritableMap(touches.getMap(index)));
      rippedOutIndices.add(index);
    }
    for (int j = 0; j < touches.size(); j++) {
      if (!rippedOutIndices.contains(j)) {
        tempTouches.pushMap(getWritableMap(touches.getMap(j)));
      }
    }

    return new Pair<>(rippedOut, tempTouches);
  }

  /**
   * Selects a subsequence of `Touch`es, without destroying `touches`.
   *
   * <p>This method was originally in ReactNativeRenderer.js
   *
   * @param touches {@link WritableArray} Deserialized touch objects.
   * @param changedIndices {@link WritableArray} Indices by which to pull subsequence.
   * @return {Array<Touch>} Subsequence of touch objects.
   */
  private @NonNull Pair<WritableArray, WritableArray> touchSubsequence(
      @NonNull WritableArray touches, @NonNull WritableArray changedIndices) {
    WritableArray result = new WritableNativeArray();
    for (int i = 0; i < changedIndices.size(); i++) {
      result.pushMap(getWritableMap(touches.getMap(changedIndices.getInt(i))));
    }
    return new Pair<>(result, touches);
  }

  /**
   * TODO: this is required because the WritableNativeArray.getMap() returns a ReadableMap instead
   * of the original writableMap. this will change in the near future.
   *
   * @param readableMap {@link ReadableMap} source map
   */
  private @NonNull WritableMap getWritableMap(@NonNull ReadableMap readableMap) {
    WritableNativeMap map = new WritableNativeMap();
    map.merge(readableMap);
    return map;
  }

  @EventCategoryDef
  private static int getTouchCategory(String touchEventType) {
    int category = EventCategoryDef.UNSPECIFIED;
    if (TouchEventType.MOVE.getJsName().equals(touchEventType)) {
      category = EventCategoryDef.CONTINUOUS;
    } else if (TouchEventType.START.getJsName().equals(touchEventType)) {
      category = EventCategoryDef.CONTINUOUS_START;
    } else if (TouchEventType.END.getJsName().equals(touchEventType)
        || TouchEventType.CANCEL.getJsName().equals(touchEventType)) {
      category = EventCategoryDef.CONTINUOUS_END;
    }

    return category;
  }
}
