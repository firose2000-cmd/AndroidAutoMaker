package androidx.core.app;

import android.os.Bundle;

public final class Person {
    public Bundle toBundle() {
        return new Bundle();
    }

    public static Person fromBundle(Bundle bundle) {
        return new Person();
    }
}
