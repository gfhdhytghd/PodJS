/*
 * Copyright 2019 The Android Open Source Project
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
package dev.podjs.runtime;

import android.graphics.Paint;
import android.text.style.LineHeightSpan;

/**
 * Java adaptation of AndroidX Compose 1.7.1 LineHeightStyleSpan, restricted to
 * proportional alignment and trimming first-line top / last-line bottom.
 * Source: androidx.compose.ui:ui-text-android:1.7.1 sources, Apache-2.0.
 */
final class PodLineHeightSpan implements LineHeightSpan {
    private final float height;
    private final int endIndex;
    private final boolean trimFirst;
    private int firstAscent = Integer.MIN_VALUE, ascent, descent, lastDescent;

    PodLineHeightSpan(float height, int endIndex, boolean trimFirst) {
        this.height = height; this.endIndex = endIndex; this.trimFirst = trimFirst;
    }
    @Override public void chooseHeight(CharSequence text, int start, int end,
            int spanStartVertical, int lineHeight, Paint.FontMetricsInt metrics) {
        int current = metrics.descent - metrics.ascent;
        if (current <= 0 || (start == 0 && end == endIndex && trimFirst)) return;
        if (firstAscent == Integer.MIN_VALUE) {
            int requested = (int) Math.ceil(height);
            int difference = requested - current;
            float ratio = Math.abs((float) metrics.ascent) / current;
            int descentDifference = (int) Math.ceil(difference * (difference <= 0 ? ratio : 1f - ratio));
            descent = metrics.descent + descentDifference;
            ascent = descent - requested;
            firstAscent = trimFirst ? metrics.ascent : ascent;
            lastDescent = metrics.descent;
        }
        metrics.ascent = start == 0 ? firstAscent : ascent;
        metrics.descent = end == endIndex ? lastDescent : descent;
    }
}
