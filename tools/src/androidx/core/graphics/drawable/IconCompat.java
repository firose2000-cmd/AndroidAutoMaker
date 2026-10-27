package androidx.core.graphics.drawable;

import android.os.Bundle;

public final class IconCompat {
    public Bundle toBundle() {
        return new Bundle();
    }

    public static IconCompat createFromBundle(Bundle bundle) {
        return new IconCompat();
    }
}
