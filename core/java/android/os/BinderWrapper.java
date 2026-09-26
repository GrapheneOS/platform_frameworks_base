package android.os;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;

import java.io.FileDescriptor;
import java.util.concurrent.Executor;

/** @hide */
public class BinderWrapper implements IBinder {
    protected final IBinder base;

    public BinderWrapper(IBinder base) {
        this.base = base;
    }

    @Override
    public boolean transact(int code, @NonNull Parcel data, @Nullable Parcel reply, int flags) throws RemoteException {
        return base.transact(code, data, reply, flags);
    }

    @Nullable
    @Override
    public IInterface queryLocalInterface(@NonNull String descriptor) {
        return base.queryLocalInterface(descriptor);
    }

    @Nullable
    @Override
    public String getInterfaceDescriptor() throws RemoteException {
        return base.getInterfaceDescriptor();
    }

    @Override
    public boolean pingBinder() {
        return base.pingBinder();
    }

    @Override
    public boolean isBinderAlive() {
        return base.isBinderAlive();
    }

    @Override
    public void dump(@NonNull FileDescriptor fd, @Nullable String[] args) throws RemoteException {
        base.dump(fd, args);
    }

    @Override
    public void dumpAsync(@NonNull FileDescriptor fd, @Nullable String[] args) throws RemoteException {
        base.dumpAsync(fd, args);
    }

    @Override
    public void shellCommand(@Nullable FileDescriptor in, @Nullable FileDescriptor out, @Nullable FileDescriptor err, @NonNull String[] args, @Nullable ShellCallback shellCallback, @NonNull ResultReceiver resultReceiver) throws RemoteException {
        base.shellCommand(in, out, err, args, shellCallback, resultReceiver);
    }

    @Override
    public void linkToDeath(@NonNull DeathRecipient recipient, int flags) throws RemoteException {
        base.linkToDeath(recipient, flags);
    }

    @Override
    public boolean unlinkToDeath(@NonNull DeathRecipient recipient, int flags) {
        return base.unlinkToDeath(recipient, flags);
    }

    @Nullable
    @Override
    public IBinder getExtension() throws RemoteException {
        return base.getExtension();
    }

    @Override
    public void addFrozenStateChangeCallback(@NonNull FrozenStateChangeCallback callback) throws RemoteException {
        base.addFrozenStateChangeCallback(callback);
    }

    @Override
    public void addFrozenStateChangeCallback(@NonNull Executor executor,
            @NonNull FrozenStateChangeCallback callback) throws RemoteException {
        base.addFrozenStateChangeCallback(executor, callback);
    }

    @Override
    public boolean removeFrozenStateChangeCallback(@NonNull FrozenStateChangeCallback callback) {
        return base.removeFrozenStateChangeCallback(callback);
    }
}
