/*
 * Copyright (C) 2013 The Android Open Source Project
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

package com.android.server.am;

import static com.android.server.am.ActivityManagerService.DEBUG_CLEANUP;
import static com.android.server.am.ActivityManagerService.DEBUG_PAUSE;
import static com.android.server.am.ActivityManagerService.TAG;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.os.Bundle;
import android.os.Looper;
import android.os.RemoteException;
import android.util.EventLog;
import android.util.Slog;

import java.io.FileDescriptor;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

public class ActivityStackSupervisor {
    public static final int HOME_STACK_ID = 0;

    final ActivityManagerService mService;
    final Context mContext;
    final Looper mLooper;

    /** Dismiss the keyguard after the next activity is displayed? */
    private boolean mDismissKeyguardOnNextActivity = false;

    /** Identifier counter for all ActivityStacks */
    private int mLastStackId = 0;

    /** Task identifier that activities are currently being started in.  Incremented each time a
     * new task is created. */
    private int mCurTaskId = 0;

    /** The stack containing the launcher app */
    private ActivityStack mHomeStack;
    private ActivityStack mMainStack;

    /** All the non-launcher stacks */
    private ArrayList<ActivityStack> mStacks = new ArrayList<ActivityStack>();

    private boolean mHomeOnTop = true;

    public ActivityStackSupervisor(ActivityManagerService service, Context context,
            Looper looper) {
        mService = service;
        mContext = context;
        mLooper = looper;
    }

    void init() {
        mHomeStack = new ActivityStack(mService, mContext, mLooper, HOME_STACK_ID, this);
        setMainStack(mHomeStack);
        mService.mFocusedStack = mHomeStack;
        mStacks.add(mHomeStack);
    }

    void dismissKeyguard() {
        if (mDismissKeyguardOnNextActivity) {
            mDismissKeyguardOnNextActivity = false;
            mService.mWindowManager.dismissKeyguard();
        }
    }

    boolean isMainStack(ActivityStack stack) {
        return stack == mMainStack;
    }

    void setMainStack(ActivityStack stack) {
        mMainStack = stack;
    }

    void setDismissKeyguard(boolean dismiss) {
        mDismissKeyguardOnNextActivity = dismiss;
    }

    TaskRecord anyTaskForIdLocked(int id) {
        for (int stackNdx = mStacks.size() - 1; stackNdx >= 0; --stackNdx) {
            ActivityStack stack = mStacks.get(stackNdx);
            TaskRecord task = stack.taskForIdLocked(id);
            if (task != null) {
                return task;
            }
        }
        return null;
    }

    int getNextTaskId() {
        do {
            mCurTaskId++;
            if (mCurTaskId <= 0) {
                mCurTaskId = 1;
            }
        } while (anyTaskForIdLocked(mCurTaskId) != null);
        return mCurTaskId;
    }

    void startHomeActivity(Intent intent, ActivityInfo aInfo) {
        mHomeStack.startActivityLocked(null, intent, null, aInfo, null, null, 0, 0, 0, null, 0,
                null, false, null);
        mHomeOnTop = true;
    }

    void handleAppDiedLocked(ProcessRecord app, boolean restarting) {
        // Just in case.
        final int numStacks = mStacks.size();
        for (int stackNdx = 0; stackNdx < numStacks; ++stackNdx) {
            final ActivityStack stack = mStacks.get(stackNdx);
            if (stack.mPausingActivity != null && stack.mPausingActivity.app == app) {
                if (DEBUG_PAUSE || DEBUG_CLEANUP) Slog.v(TAG,
                        "App died while pausing: " + stack.mPausingActivity);
                stack.mPausingActivity = null;
            }
            if (stack.mLastPausedActivity != null && stack.mLastPausedActivity.app == app) {
                stack.mLastPausedActivity = null;
            }

            // Remove this application's activities from active lists.
            boolean hasVisibleActivities = stack.removeHistoryRecordsForAppLocked(app);

            if (!restarting) {
                if (!stack.resumeTopActivityLocked(null)) {
                    // If there was nothing to resume, and we are not already
                    // restarting this process, but there is a visible activity that
                    // is hosted by the process...  then make sure all visible
                    // activities are running, taking care of restarting this
                    // process.
                    if (hasVisibleActivities) {
                        stack.ensureActivitiesVisibleLocked(null, 0);
                    }
                }
            }
        }
    }

    void closeSystemDialogsLocked() {
        final int numStacks = mStacks.size();
        for (int stackNdx = 0; stackNdx < numStacks; ++stackNdx) {
            final ActivityStack stack = mStacks.get(stackNdx);
            stack.closeSystemDialogsLocked();
        }
    }

    /**
     * @return true if some activity was finished (or would have finished if doit were true).
     */
    boolean forceStopPackageLocked(String name, boolean doit, boolean evenPersistent, int userId) {
        boolean didSomething = false;
        final int numStacks = mStacks.size();
        for (int stackNdx = 0; stackNdx < numStacks; ++stackNdx) {
            final ActivityStack stack = mStacks.get(stackNdx);
            if (stack.forceStopPackageLocked(name, doit, evenPersistent, userId)) {
                didSomething = true;
            }
        }
        return didSomething;
    }

    void resumeTopActivityLocked() {
        final int start, end;
        if (mHomeOnTop) {
            start = 0;
            end = 1;
        } else {
            start = 1;
            end = mStacks.size();
        }
        for (int stackNdx = start; stackNdx < end; ++stackNdx) {
            mStacks.get(stackNdx).resumeTopActivityLocked(null);
        }
    }

    void finishTopRunningActivityLocked(ProcessRecord app) {
        final int numStacks = mStacks.size();
        for (int stackNdx = 0; stackNdx < numStacks; ++stackNdx) {
            final ActivityStack stack = mStacks.get(stackNdx);
            stack.finishTopRunningActivityLocked(app);
        }
    }

    void scheduleIdleLocked() {
        for (int stackNdx = mStacks.size() - 1; stackNdx >= 0; --stackNdx) {
            mStacks.get(stackNdx).scheduleIdleLocked();
        }
    }

    void findTaskToMoveToFrontLocked(int taskId, int flags, Bundle options) {
        for (int stackNdx = mStacks.size() - 1; stackNdx >= 0; --stackNdx) {
            if (mStacks.get(stackNdx).findTaskToMoveToFrontLocked(taskId, flags, options)) {
                return;
            }
        }
    }

    private ActivityStack getStack(int stackId) {
        for (int stackNdx = mStacks.size() - 1; stackNdx >= 0; --stackNdx) {
            final ActivityStack stack = mStacks.get(stackNdx);
            if (stack.getStackId() == stackId) {
                return stack;
            }
        }
        return null;
    }

    int createStack(int relativeStackId, int position, float weight) {
        synchronized (this) {
            while (true) {
                if (++mLastStackId <= HOME_STACK_ID) {
                    mLastStackId = HOME_STACK_ID + 1;
                }
                if (getStack(mLastStackId) == null) {
                    break;
                }
            }
            mStacks.add(new ActivityStack(mService, mContext, mLooper, mLastStackId, this));
            return mLastStackId;
        }
    }

    void moveTaskToStack(int taskId, int stackId, boolean toTop) {
        final ActivityStack stack = getStack(stackId);
        if (stack == null) {
            Slog.w(TAG, "moveTaskToStack: no stack for id=" + stackId);
            return;
        }
        stack.moveTask(taskId, toTop);
    }

    void goingToSleepLocked() {
        for (int stackNdx = mStacks.size() - 1; stackNdx >= 0; --stackNdx) {
            mStacks.get(stackNdx).stopIfSleepingLocked();
        }
    }

    boolean shutdownLocked(int timeout) {
        boolean timedout = false;
        final int numStacks = mStacks.size();
        for (int stackNdx = 0; stackNdx < numStacks; ++stackNdx) {
            final ActivityStack stack = mStacks.get(stackNdx);
            if (stack.mResumedActivity != null) {
                stack.stopIfSleepingLocked();
                final long endTime = System.currentTimeMillis() + timeout;
                while (stack.mResumedActivity != null || stack.mPausingActivity != null) {
                    long delay = endTime - System.currentTimeMillis();
                    if (delay <= 0) {
                        Slog.w(TAG, "Activity manager shutdown timed out");
                        timedout = true;
                        break;
                    }
                    try {
                        mService.wait();
                    } catch (InterruptedException e) {
                    }
                }
            }
        }
        return timedout;
    }

    void comeOutOfSleepIfNeededLocked() {
        final int numStacks = mStacks.size();
        for (int stackNdx = 0; stackNdx < numStacks; ++stackNdx) {
            final ActivityStack stack = mStacks.get(stackNdx);
            stack.awakeFromSleepingLocked();
            stack.resumeTopActivityLocked(null);
        }
    }

    void handleAppCrashLocked(ProcessRecord app) {
        final int numStacks = mStacks.size();
        for (int stackNdx = 0; stackNdx < numStacks; ++stackNdx) {
            final ActivityStack stack = mStacks.get(stackNdx);
            stack.handleAppCrashLocked(app);
        }
    }

    boolean updateConfigurationLocked(int changes, ActivityRecord starting) {
        boolean kept = true;
        final int numStacks = mStacks.size();
        for (int stackNdx = 0; stackNdx < numStacks; ++stackNdx) {
            final ActivityStack stack = mStacks.get(stackNdx);
            if (changes != 0 && starting == null) {
                // If the configuration changed, and the caller is not already
                // in the process of starting an activity, then find the top
                // activity to check if its configuration needs to change.
                starting = stack.topRunningActivityLocked(null);
            }

            if (starting != null) {
                if (!stack.ensureActivityConfigurationLocked(starting, changes)) {
                    kept = false;
                }
                // And we need to make sure at this point that all other activities
                // are made visible with the correct configuration.
                stack.ensureActivitiesVisibleLocked(starting, changes);
            }
        }
        return kept;
    }

    void scheduleDestroyAllActivities(ProcessRecord app, String reason) {
        final int numStacks = mStacks.size();
        for (int stackNdx = 0; stackNdx < numStacks; ++stackNdx) {
            final ActivityStack stack = mStacks.get(stackNdx);
            stack.scheduleDestroyActivities(app, false, reason);
        }
    }

    boolean switchUserLocked(int userId, UserStartedState uss) {
        boolean haveActivities = false;
        final int numStacks = mStacks.size();
        for (int stackNdx = 0; stackNdx < numStacks; ++stackNdx) {
            final ActivityStack stack = mStacks.get(stackNdx);
            haveActivities |= stack.switchUserLocked(userId, uss);
        }
        return haveActivities;
    }

    public void dump(PrintWriter pw, String prefix) {
        pw.print(prefix); pw.print("mDismissKeyguardOnNextActivity:");
                pw.println(mDismissKeyguardOnNextActivity);
    }

    boolean dumpActivitiesLocked(FileDescriptor fd, PrintWriter pw, boolean dumpAll,
            boolean dumpClient, String dumpPackage) {
        final int numStacks = mStacks.size();
        for (int stackNdx = 0; stackNdx < numStacks; ++stackNdx) {
            final ActivityStack stack = mStacks.get(stackNdx);
            pw.print("  Stack #"); pw.print(mStacks.indexOf(stack)); pw.println(":");
            stack.dumpActivitiesLocked(fd, pw, dumpAll, dumpClient, dumpPackage);
            pw.println(" ");
            pw.println("  Running activities (most recent first):");
            dumpHistoryList(fd, pw, stack.mLRUActivities, "  ", "Run", false, !dumpAll, false,
                    dumpPackage);
            if (stack.mWaitingVisibleActivities.size() > 0) {
                pw.println(" ");
                pw.println("  Activities waiting for another to become visible:");
                dumpHistoryList(fd, pw, stack.mWaitingVisibleActivities, "  ", "Wait", false,
                        !dumpAll, false, dumpPackage);
            }
            if (stack.mStoppingActivities.size() > 0) {
                pw.println(" ");
                pw.println("  Activities waiting to stop:");
                dumpHistoryList(fd, pw, stack.mStoppingActivities, "  ", "Stop", false,
                        !dumpAll, false, dumpPackage);
            }
            if (stack.mGoingToSleepActivities.size() > 0) {
                pw.println(" ");
                pw.println("  Activities waiting to sleep:");
                dumpHistoryList(fd, pw, stack.mGoingToSleepActivities, "  ", "Sleep", false,
                        !dumpAll, false, dumpPackage);
            }
            if (stack.mFinishingActivities.size() > 0) {
                pw.println(" ");
                pw.println("  Activities waiting to finish:");
                dumpHistoryList(fd, pw, stack.mFinishingActivities, "  ", "Fin", false,
                        !dumpAll, false, dumpPackage);
            }
        }

        for (int stackNdx = 0; stackNdx < numStacks; ++stackNdx) {
            final ActivityStack stack = mStacks.get(stackNdx);
            pw.print("  Stack #"); pw.println(mStacks.indexOf(stack));
            if (stack.mPausingActivity != null) {
                pw.println("  mPausingActivity: " + stack.mPausingActivity);
            }
            pw.println("  mResumedActivity: " + stack.mResumedActivity);
            if (dumpAll) {
                pw.println("  mLastPausedActivity: " + stack.mLastPausedActivity);
                pw.println("  mSleepTimeout: " + stack.mSleepTimeout);
            }
        }

        if (dumpAll) {
            pw.println(" ");
            pw.println("  mCurTaskId: " + mCurTaskId);
        }
        return true;
    }

    static final void dumpHistoryList(FileDescriptor fd, PrintWriter pw, List<ActivityRecord> list,
            String prefix, String label, boolean complete, boolean brief, boolean client,
            String dumpPackage) {
        TaskRecord lastTask = null;
        boolean needNL = false;
        final String innerPrefix = prefix + "      ";
        final String[] args = new String[0];
        for (int i=list.size()-1; i>=0; i--) {
            final ActivityRecord r = list.get(i);
            if (dumpPackage != null && !dumpPackage.equals(r.packageName)) {
                continue;
            }
            final boolean full = !brief && (complete || !r.isInHistory());
            if (needNL) {
                pw.println(" ");
                needNL = false;
            }
            if (lastTask != r.task) {
                lastTask = r.task;
                pw.print(prefix);
                pw.print(full ? "* " : "  ");
                pw.println(lastTask);
                if (full) {
                    lastTask.dump(pw, prefix + "  ");
                } else if (complete) {
                    // Complete + brief == give a summary.  Isn't that obvious?!?
                    if (lastTask.intent != null) {
                        pw.print(prefix); pw.print("  ");
                                pw.println(lastTask.intent.toInsecureStringWithClip());
                    }
                }
            }
            pw.print(prefix); pw.print(full ? "  * " : "    "); pw.print(label);
            pw.print(" #"); pw.print(i); pw.print(": ");
            pw.println(r);
            if (full) {
                r.dump(pw, innerPrefix);
            } else if (complete) {
                // Complete + brief == give a summary.  Isn't that obvious?!?
                pw.print(innerPrefix); pw.println(r.intent.toInsecureString());
                if (r.app != null) {
                    pw.print(innerPrefix); pw.println(r.app);
                }
            }
            if (client && r.app != null && r.app.thread != null) {
                // flush anything that is already in the PrintWriter since the thread is going
                // to write to the file descriptor directly
                pw.flush();
                try {
                    TransferPipe tp = new TransferPipe();
                    try {
                        r.app.thread.dumpActivity(tp.getWriteFd().getFileDescriptor(),
                                r.appToken, innerPrefix, args);
                        // Short timeout, since blocking here can
                        // deadlock with the application.
                        tp.go(fd, 2000);
                    } finally {
                        tp.kill();
                    }
                } catch (IOException e) {
                    pw.println(innerPrefix + "Failure while dumping the activity: " + e);
                } catch (RemoteException e) {
                    pw.println(innerPrefix + "Got a RemoteException while dumping the activity");
                }
                needNL = true;
            }
        }
    }
}