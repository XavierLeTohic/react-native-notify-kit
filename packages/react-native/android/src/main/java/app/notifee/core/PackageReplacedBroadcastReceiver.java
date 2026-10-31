package app.notifee.core;

/*
 * Copyright (c) 2016-present Invertase Limited & Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this library except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/*
 * This is invoked when the application package is replaced (app update).
 * Android places the updated app in the stopped state until the user launches
 * it, and the stopped-state filter suppresses delivery of the AlarmManager
 * broadcast PendingIntents backing persisted trigger notifications: any
 * trigger whose fire time falls between the update and the next manual app
 * launch is silently dropped. Receiving ACTION_MY_PACKAGE_REPLACED re-arms
 * every persisted trigger from the library database immediately at update
 * time, so reminders keep working without requiring the user to open the app
 * after updating. This mirrors RebootBroadcastReceiver, which covers the
 * device-restart case, and shares the same race-guarded recovery path.
 */
public class PackageReplacedBroadcastReceiver extends BroadcastReceiver {
  private static final String TAG = "PackageReplacedReceiver";

  @Override
  public void onReceive(Context context, Intent intent) {
    if (intent == null) {
      Logger.w(TAG, "Ignoring package replaced event with null intent");
      return;
    }

    String action = intent.getAction();
    if (!Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
      Logger.w(TAG, "Ignoring unsupported package replaced event action: " + action);
      return;
    }

    PendingResult pendingResult = goAsync();
    // Same handoff discipline as RebootBroadcastReceiver: track whether the
    // synchronous section successfully handed off to the async reschedule
    // path. If not, the finally block must call finish() to avoid leaving the
    // broadcast unterminated — Android will otherwise kill the process after
    // ~10s and race subsequent recovery broadcasts.
    boolean asyncHandoffSucceeded = false;
    try {
      Logger.i(TAG, "Received package replaced event");
      if (ContextHolder.getApplicationContext() == null) {
        ContextHolder.setApplicationContext(context.getApplicationContext());
      }
      new NotifeeAlarmManager().rescheduleNotifications(pendingResult);
      asyncHandoffSucceeded = true;
    } catch (Throwable t) {
      Logger.e(TAG, "Failed to reschedule notifications after package replacement", t);
    } finally {
      if (!asyncHandoffSucceeded && pendingResult != null) {
        pendingResult.finish();
      }
    }
  }
}
