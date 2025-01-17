package android.os;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;

import java.io.FileDescriptor;

/** @hide */
public class BinderWrapper implements IBinder {
    protected final IBinder base;

    public BinderWrapper(IBinder base) {
        this.base = base;
    }

    public boolean transact(int code, @NonNull Parcel data, @Nullable Parcel reply, int flags) throws RemoteException {
        return base.transact(code, data, reply, flags);
    }

    @Nullable
    public IInterface queryLocalInterface(@NonNull String descriptor) {
        return base.queryLocalInterface(descriptor);
    }

    @Nullable
    public String getInterfaceDescriptor() throws RemoteException {
        return base.getInterfaceDescriptor();
    }

    public boolean pingBinder() {
        return base.pingBinder();
    }

    public boolean isBinderAlive() {
        return base.isBinderAlive();
    }

    public void dump(@NonNull FileDescriptor fd, @Nullable String[] args) throws RemoteException {
        base.dump(fd, args);
    }

    public void dumpAsync(@NonNull FileDescriptor fd, @Nullable String[] args) throws RemoteException {
        base.dumpAsync(fd, args);
    }

    public void shellCommand(@Nullable FileDescriptor in, @Nullable FileDescriptor out, @Nullable FileDescriptor err, @NonNull String[] args, @Nullable ShellCallback shellCallback, @NonNull ResultReceiver resultReceiver) throws RemoteException {
        base.shellCommand(in, out, err, args, shellCallback, resultReceiver);
    }

    public void linkToDeath(@NonNull DeathRecipient recipient, int flags) throws RemoteException {
        base.linkToDeath(recipient, flags);
    }

    public boolean unlinkToDeath(@NonNull DeathRecipient recipient, int flags) {
        return base.unlinkToDeath(recipient, flags);
    }

    @Nullable
    public IBinder getExtension() throws RemoteException {
        return base.getExtension();
    }

    @Override
    public void addFrozenStateChangeCallback(@NonNull FrozenStateChangeCallback callback) throws RemoteException {
        base.addFrozenStateChangeCallback(callback);
    }

    @Override
    public boolean removeFrozenStateChangeCallback(@NonNull FrozenStateChangeCallback callback) {
        return base.removeFrozenStateChangeCallback(callback);
    }
}
