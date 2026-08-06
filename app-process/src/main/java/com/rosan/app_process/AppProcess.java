package com.rosan.app_process;

import android.app.ActivityThread;
import android.content.ComponentName;
import android.content.Context;
import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.Closeable;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public abstract class AppProcess implements Closeable {
    private static final String TAG = "AppProcess";

    static final int TRANSACTION_REMOTE_TRANSACT = IBinder.FIRST_CALL_TRANSACTION + 2;

    private Context mContext = null;

    private volatile IProcessManager mManager = null;

    private final ConcurrentMap<String, IBinder> mChildProcess = new ConcurrentHashMap<>();

    private static ProcessParams generateProcessParams(
            @NonNull String classPath,
            @NonNull String entryClassName,
            @NonNull List<String> args,
            @Nullable String niceName
    ) {
        List<String> cmdList = new ArrayList<>();
        cmdList.add("/system/bin/app_process");
        cmdList.add("-Djava.class.path=" + classPath);
        cmdList.add("/system/bin");
        if (niceName != null) cmdList.add("--nice-name=" + niceName);
        cmdList.add(entryClassName);
        cmdList.addAll(args);
        return new ProcessParams(cmdList);
    }

    static <T> T binderWithCleanCallingIdentity(Callable<T> action) throws Exception {
        final long callingIdentity = Binder.clearCallingIdentity();
        try {
            return action.call();
        } finally {
            Binder.restoreCallingIdentity(callingIdentity);
        }
    }

    private static IBinder binderWrapper(IProcessManager manager, IBinder binder) {
        return new BinderWrapper(manager, binder);
    }

    static boolean remoteTransact(IProcessManager manager, IBinder binder, int code, Parcel data, Parcel reply, int flags) {
        IBinder managerBinder = manager.asBinder();
        Parcel processData = Parcel.obtain();
        try {
            processData.writeInterfaceToken(Objects.requireNonNull(managerBinder.getInterfaceDescriptor()));
            processData.writeStrongBinder(binder);
            processData.writeInt(code);
            processData.writeInt(flags);
            processData.appendFrom(data, 0, data.dataSize());
            return managerBinder.transact(TRANSACTION_REMOTE_TRANSACT, processData, reply, 0);
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        } finally {
            processData.recycle();
        }
    }

    <T> @NonNull Process start(@NonNull String classPath, @NonNull Class<T> entryClass, @NonNull String[] args, @Nullable String niceName) throws IOException {
        return newProcess(generateProcessParams(classPath, entryClass.getName(), Arrays.asList(args), niceName));
    }

    public boolean init() {
        return init(ActivityThread.currentActivityThread().getApplication());
    }

    public synchronized boolean init(@NonNull Context context) {
        if (initialized()) return true;
        mContext = context;
        mManager = null;
        mChildProcess.clear();

        IProcessManager manager = newManager();
        if (manager == null) return false;
        mManager = manager;

        try {
            final IBinder managerBinder = manager.asBinder();
            managerBinder.linkToDeath(() -> {
                IProcessManager current = mManager;
                if (current == null || managerBinder != current.asBinder()) return;
                mManager = null;
                mChildProcess.clear();
            }, 0);
        } catch (RemoteException e) {
            Log.w(TAG, "Failed to link process manager death recipient", e);
        }
        return initialized();
    }

    private @Nullable IProcessManager newManager() {
        IBinder binder = isolatedServiceBinder(new ComponentName(mContext.getPackageName(), ProcessManager.class.getName()));
        if (binder == null) return null;
        return IProcessManager.Stub.asInterface(binder);
    }

    private boolean initialized() {
        IProcessManager manager = mManager;
        return mContext != null && manager != null && manager.asBinder().isBinderAlive();
    }

    @Override
    public synchronized void close() {
        IProcessManager manager = mManager;
        mContext = null;
        mManager = null;
        mChildProcess.clear();
        if (manager == null || !manager.asBinder().pingBinder()) return;
        try {
            manager.exit(0);
        } catch (RuntimeException rethrown) {
            throw rethrown;
        } catch (Exception ignored) {
        }
    }

    protected @NonNull Process newProcess(@NonNull ProcessParams params) throws IOException {
        return new ProcessBuilder().command(params.getCmdList()).start();
    }

    private @NonNull IProcessManager requireManager() {
        IProcessManager manager = mManager;
        if (mContext == null || manager == null || !manager.asBinder().isBinderAlive())
            throw new IllegalStateException("please call init() first.");
        return manager;
    }

    public IBinder binderWrapper(IBinder binder) {
        return binderWrapper(requireManager(), binder);
    }

    private final ConcurrentMap<String, Object> locks = new ConcurrentHashMap<>();

    private Object buildLock(String token) {
        return locks.computeIfAbsent(token, ignored -> new Object());
    }

    private IBinder isolatedServiceBinder(@NonNull ComponentName componentName) {
        String token = componentName.flattenToString();
        synchronized (buildLock(token)) {
            IBinder existsBinder = mChildProcess.get(token);
            if (existsBinder != null && existsBinder.isBinderAlive()) return existsBinder;
            if (existsBinder != null) mChildProcess.remove(token, existsBinder);
            final IBinder binder = isolatedServiceBinderUnchecked(componentName);
            if (binder == null) return null;
            mChildProcess.put(token, binder);
            try {
                binder.linkToDeath(() -> {
                    IBinder curBinder = mChildProcess.get(token);
                    if (curBinder == null || curBinder != binder) return;
                    mChildProcess.remove(token, binder);
                }, 0);
            } catch (RemoteException e) {
                mChildProcess.remove(token, binder);
                return null;
            }
            return binder;
        }
    }

    private IBinder isolatedServiceBinderUnchecked(@NonNull ComponentName componentName) {
        Context context = mContext;
        if (context == null) return null;
        return NewProcessReceiver.start(context, this, componentName);
    }

    public abstract static class Terminal extends AppProcess {
        protected abstract @NonNull List<String> newTerminal();

        @NonNull
        @Override
        protected Process newProcess(@NonNull ProcessParams params) throws IOException {
            ProcessParams newParams = new ProcessParams(params).setCmdList(newTerminal());
            Process process = super.newProcess(newParams);
            PrintWriter printWriter = new PrintWriter(process.getOutputStream(), true);
            int count = 0;
            StringBuilder buffer = new StringBuilder();
            for (String element : params.getCmdList()) {
                if (++count > 1) buffer.append(" ");
                buffer.append(element);
            }
            printWriter.println(buffer);
            printWriter.println("exit $?");
            return process;
        }
    }

    public static class Root extends Terminal {

        @NonNull
        @Override
        protected List<String> newTerminal() {
            List<String> terminal = new ArrayList<>();
            terminal.add("su");
            return terminal;
        }
    }

    protected static final class ProcessParams {
        private @NonNull List<String> mCmdList;

        ProcessParams(@NonNull List<String> cmdList) {
            this.mCmdList = cmdList;
        }

        ProcessParams(@NonNull ProcessParams params) {
            this.mCmdList = new ArrayList<>(params.getCmdList());
        }

        @NonNull
        List<String> getCmdList() {
            return mCmdList;
        }

        ProcessParams setCmdList(@NonNull List<String> mCmdList) {
            this.mCmdList = mCmdList;
            return this;
        }

    }
}
