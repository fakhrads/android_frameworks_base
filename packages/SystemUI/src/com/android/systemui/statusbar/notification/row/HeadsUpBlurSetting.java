/*
 * Copyright (C) 2026 The Android Open Source Project
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

package com.android.systemui.statusbar.notification.row;

import android.content.ContentResolver;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.UserHandle;
import android.provider.Settings;

/**
 * {@link Settings.System#HEADS_UP_BLUR}, read on every heads-up background change, so it is kept
 * in memory and refreshed by an observer instead of being read from the provider each time. A
 * change applies to the next heads-up state change of each row.
 */
public final class HeadsUpBlurSetting {

    private static volatile boolean sEnabled = true;

    private HeadsUpBlurSetting() {}

    /** Whether heads-up notifications use the blurred, translucent background. */
    public static boolean isEnabled() {
        return sEnabled;
    }

    /** Starts following the setting; called once when the status bar starts. */
    public static void observe(ContentResolver resolver) {
        Uri uri = Settings.System.getUriFor(Settings.System.HEADS_UP_BLUR);
        ContentObserver observer = new ContentObserver(null) {
            @Override
            public void onChange(boolean selfChange) {
                sEnabled = Settings.System.getIntForUser(resolver, Settings.System.HEADS_UP_BLUR,
                        1, UserHandle.USER_CURRENT) != 0;
            }
        };
        resolver.registerContentObserver(uri, false, observer, UserHandle.USER_ALL);
        observer.onChange(true);
    }
}
