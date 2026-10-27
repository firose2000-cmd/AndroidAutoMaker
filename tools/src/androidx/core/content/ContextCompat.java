package androidx.core.content;

import android.content.Context;
import android.os.Build;
import android.os.Handler;

import java.util.concurrent.Executor;

public final class ContextCompat {
    private ContextCompat() {}

    public static Executor getMainExecutor(Context context) {
        if (Build.VERSION.SDK_INT >= 28) return context.getMainExecutor();
        final Handler handler = new Handler(context.getMainLooper());
        return new Executor() {
            @Override
            public void execute(Runnable command) {
                handler.post(command);
            }
        };
    }
}
