/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.server.thread;

import static android.net.thread.ThreadNetworkException.ERROR_INTERNAL_ERROR;
import static android.net.thread.ThreadNetworkException.ERROR_UNAVAILABLE;

import android.net.thread.IOperationReceiver;
import android.net.thread.ThreadNetworkException;
import android.os.RemoteException;

import com.android.internal.annotations.GuardedBy;

import java.util.HashSet;
import java.util.Set;

/** A {@link IOperationReceiver} wrapper which makes it easier to invoke the callbacks. */
final class OperationReceiverWrapper {
    private final IOperationReceiver mReceiver;
    private final boolean mExpectOtDaemonDied;

    private static final Object sPendingReceiversLock = new Object();

    @GuardedBy("sPendingReceiversLock")
    private static final Set<OperationReceiverWrapper> sPendingReceivers = new HashSet<>();

    public OperationReceiverWrapper(IOperationReceiver receiver) {
        this(receiver, false /* expectOtDaemonDied */);
    }

    /**
     * Creates a new {@link OperationReceiverWrapper}.
     *
     * <p>If {@code expectOtDaemonDied} is {@code true}, it's expected that ot-daemon becomes dead
     * before {@code receiver} is completed with {@code onSuccess} and {@code onError} and {@code
     * receiver#onSuccess} will be invoked in this case.
     */
    public OperationReceiverWrapper(IOperationReceiver receiver, boolean expectOtDaemonDied) {
        mReceiver = receiver;
        mExpectOtDaemonDied = expectOtDaemonDied;

        synchronized (sPendingReceiversLock) {
            sPendingReceivers.add(this);
        }
    }

    public static void onOtDaemonDied() {
        synchronized (sPendingReceiversLock) {
            for (OperationReceiverWrapper receiver : sPendingReceivers) {
                try {
                    if (receiver.mExpectOtDaemonDied) {
                        receiver.mReceiver.onSuccess();
                    } else {
                        receiver.mReceiver.onError(ERROR_UNAVAILABLE, "Thread daemon died");
                    }
                } catch (RemoteException e) {
                    // The client is dead, do nothing
                }
            }
            sPendingReceivers.clear();
        }
    }

    public void onSuccess() {
        synchronized (sPendingReceiversLock) {
            sPendingReceivers.remove(this);
        }

        try {
            mReceiver.onSuccess();
        } catch (RemoteException e) {
            // The client is dead, do nothing
        }
    }

    public void onError(Throwable e) {
        if (e instanceof ThreadNetworkException) {
            ThreadNetworkException threadException = (ThreadNetworkException) e;
            onError(threadException.getErrorCode(), threadException.getMessage());
        } else if (e instanceof RemoteException) {
            onError(ERROR_INTERNAL_ERROR, "Thread stack error");
        } else {
            throw new AssertionError(e);
        }
    }

    public void onError(int errorCode, String errorMessage, Object... messageArgs) {
        synchronized (sPendingReceiversLock) {
            sPendingReceivers.remove(this);
        }

        try {
            mReceiver.onError(errorCode, String.format(errorMessage, messageArgs));
        } catch (RemoteException e) {
            // The client is dead, do nothing
        }
    }
}
