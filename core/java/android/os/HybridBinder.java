package android.os;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.util.Log;

import java.io.FileDescriptor;
import java.util.Arrays;

/**
 * Fuses two binders together.
 * Transaction routing decisions are made by looking at transaction codes.
 * The rest of operations are forwarded to the first ("base") binder.
 *
 * @hide
 */
public final class HybridBinder extends BinderWrapper {
    private static final String TAG = "HybridBinder";
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.VERBOSE);

    private final IBinder secondBinder;
    // sorted array of handled transactions codes
    private final int[] secondBinderTxnCodes;

    public HybridBinder(Context ctx, IBinder base, BinderDef secondBinderDef) {
        super(base);
        this.secondBinder = secondBinderDef.getInstance(ctx);
        this.secondBinderTxnCodes = secondBinderDef.transactionCodes;
    }

    public boolean transact(int code, @NonNull Parcel data, @Nullable Parcel reply, int flags) throws RemoteException {
        if (DEBUG) {
            Log.d(TAG, "call " + (code - IBinder.FIRST_CALL_TRANSACTION));
        }
        if (Arrays.binarySearch(secondBinderTxnCodes, code) >= 0) {
            return secondBinder.transact(code, data, reply, flags);
        }
        return base.transact(code, data, reply, flags);
    }

    @Nullable
    public IInterface queryLocalInterface(@NonNull String descriptor) {
        return null;
    }
}
