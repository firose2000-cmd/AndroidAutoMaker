package androidx.core.location;

import android.location.LocationListener;
import android.os.Bundle;

public interface LocationListenerCompat extends LocationListener {
    @Override
    default void onStatusChanged(String provider, int status, Bundle extras) {}

    @Override
    default void onProviderEnabled(String provider) {}

    @Override
    default void onProviderDisabled(String provider) {}
}
