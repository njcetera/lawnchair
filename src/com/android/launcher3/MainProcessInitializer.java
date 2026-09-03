/*
 * Copyright (C) 2018 The Android Open Source Project
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
 *
 * Modifications copyright 2025, Lawnchair
 */

package com.android.launcher3;

import static android.app.PendingIntent.FLAG_IMMUTABLE;
import static android.app.PendingIntent.FLAG_UPDATE_CURRENT;


import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.StrictMode;
import android.util.Log;

import com.android.launcher3.config.FeatureFlags;
import com.android.launcher3.graphics.BitmapCreationCheck;
import com.android.launcher3.logging.FileLog;
import com.android.launcher3.util.ResourceBasedOverride;

import org.chickenhook.restrictionbypass.Unseal;

import app.lawnchair.preferences.PreferenceManager;

/**
 * Utility class to handle one time initializations of the main process
 */
public class MainProcessInitializer implements ResourceBasedOverride {

    private static final String TAG = "MainProcessInitializer";

    private static final boolean DEBUG_STRICT_MODE = false;

    public static void initialize(Context context) {
        try {
            Unseal.unseal();
            Log.i(TAG, "Unseal success!");
        } catch (Exception e) {
            Log.e(TAG, "Unseal fail!");
            e.printStackTrace();
        }
        PreferenceManager.getInstance(context);
        Overrides.getObject(
                MainProcessInitializer.class, context, R.string.main_process_initializer_class)
                .init(context);
    }

    protected void init(Context context) {
        FileLog.setDir(context.getApplicationContext().getFilesDir());

        if (BitmapCreationCheck.ENABLED) {
            BitmapCreationCheck.startTracking(context);
        }

        // LC-Ares 2026-09-02: StrictMode now actually runs, on debug builds only.
        //
        // It never ran before. This condition was a COMPILE-TIME FALSE in every variant:
        // DEBUG_STRICT_MODE is a bare `false` constant, BuildConfigs.IS_STUDIO_BUILD is a
        // hand-written `false` with no build-variant coupling, and Flags.enableStrictMode() returns
        // a hardcoded `false` from the generated stub. So the whole block was dead code, and
        // `detectActivityLeaks()` -- the exact instrument for the six leak rows in the defect
        // ledger, and the one `meminfo` was structurally unable to see -- has been sitting here
        // switched off the entire time.
        //
        // GATED ON BuildConfig.DEBUG, and NOT on DEBUG_STRICT_MODE. Flipping that constant to true
        // is the obvious move and it is wrong: it is not variant-aware, so it would arm StrictMode
        // in release variants too.
        //
        // CORRECTION (nightly review 2026-09-03): an earlier version of this comment claimed the
        // DEBUG gate keeps StrictMode OFF "on the owner's daily device". IT DOES NOT. The owner's
        // Pixel runs the DEBUG variant -- `build.gradle` gives it `applicationIdSuffix ".debug"`
        // and every harness script targets `app.lawnchair.debug` on serial 59091FDCG000D1 -- so
        // `BuildConfig.DEBUG` is true there and this block IS armed on their phone.
        //
        // What that costs, on the numbers this same comment lists below: twelve `firstBlocking()`
        // call sites plus AppDatabase and WallpaperService `runBlocking` on Room, each now a
        // main-thread `detectDiskReads` violation with a captured stack. Penalties are `penaltyLog`
        // only -- no death, no dialog -- so it is log noise and stack-capture overhead, not a
        // crash risk. Whether a diagnostic instrument should run on the owner's daily driver is
        // THEIR call, so it is left armed and flagged rather than changed overnight.
        //
        // penaltyDeath() REMOVED, and this is not a preference. It was on the VM policy, whose
        // penalties fire from the finalizer/GC thread at an arbitrary later moment, so it is a
        // process kill at a random time attributed to an unrelated stack. On a launcher that is a
        // home-screen crash loop.
        //
        // Not routed into AresInvariants yet, on purpose. The thread policy will be loud here --
        // PreferenceManager2 does a blocking DataStore read in its constructor, there are twelve
        // firstBlocking() call sites in lawnchair/src (six in areslauncher/), and AppDatabase and
        // WallpaperService both runBlocking on Room. Wiring a noisy detector into a counter that
        // ares-smoke FAILS on would make the suite permanently red on day one, which is the
        // broken-window outcome the whole plan is written to avoid. Measure the real rate on a
        // device first, then route only detectActivityLeaks() (API 28+ penaltyListener) once its
        // base rate is known to be zero.
        if (BuildConfig.DEBUG) {
            StrictMode.setThreadPolicy(new StrictMode.ThreadPolicy.Builder()
                    .detectDiskReads()
                    .detectDiskWrites()
                    .detectNetwork()
                    .penaltyLog()
                    .build());
            StrictMode.setVmPolicy(new StrictMode.VmPolicy.Builder()
                    .detectLeakedSqlLiteObjects()
                    .detectLeakedClosableObjects()
                    .detectActivityLeaks()
                    .penaltyLog()
                    .build());
        }

        if (BuildConfigs.IS_DEBUG_DEVICE && FeatureFlags.NOTIFY_CRASHES.get()) {
            final String notificationChannelId = "com.android.launcher3.Debug";
            final String notificationChannelName = "Debug";
            final String notificationTag = "Debug";
            final int notificationId = 0;

            NotificationManager notificationManager =
                    context.getSystemService(NotificationManager.class);
            notificationManager.createNotificationChannel(new NotificationChannel(
                    notificationChannelId, notificationChannelName,
                    NotificationManager.IMPORTANCE_HIGH));

            Thread.currentThread().setUncaughtExceptionHandler((thread, throwable) -> {
                String stackTrace = Log.getStackTraceString(throwable);

                Intent shareIntent = new Intent(Intent.ACTION_SEND);
                shareIntent.setType("text/plain");
                shareIntent.putExtra(Intent.EXTRA_TEXT, stackTrace);
                shareIntent = Intent.createChooser(shareIntent, null);
                PendingIntent sharePendingIntent = PendingIntent.getActivity(
                        context, 0, shareIntent, FLAG_UPDATE_CURRENT | FLAG_IMMUTABLE);

                Notification notification = new Notification.Builder(context, notificationChannelId)
                        .setSmallIcon(android.R.drawable.ic_menu_close_clear_cancel)
                        .setContentTitle("Launcher crash detected!")
                        .setStyle(new Notification.BigTextStyle().bigText(stackTrace))
                        .addAction(android.R.drawable.ic_menu_share, "Share", sharePendingIntent)
                        .build();
                notificationManager.notify(notificationTag, notificationId, notification);

                Thread.UncaughtExceptionHandler defaultUncaughtExceptionHandler =
                        Thread.getDefaultUncaughtExceptionHandler();
                if (defaultUncaughtExceptionHandler != null) {
                    defaultUncaughtExceptionHandler.uncaughtException(thread, throwable);
                }
            });
        }
    }
}
