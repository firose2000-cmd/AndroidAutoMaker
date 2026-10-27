package com.automaker.nav;

import android.app.Activity;
import android.app.ActivityOptions;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Bundle;
import android.util.Log;
import android.view.Display;

import java.util.List;

public class NavigationActivity extends Activity {
    static final String NAME = "com.automaker.nav.NavigationActivity";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Display current = getDisplay();
        int displayId = current == null ? Display.DEFAULT_DISPLAY : current.getDisplayId();
        if (displayId != Display.DEFAULT_DISPLAY) {
            Intent launch = mainIntent(this);
            if (launch != null) {
                launch.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
                ActivityOptions options = ActivityOptions.makeBasic();
                options.setLaunchDisplayId(displayId);
                try {
                    startActivity(launch, options.toBundle());
                } catch (RuntimeException error) {
                    Log.i("AutoMakerNav", "The app did not open on the car display", error);
                }
            }
        }
        finish();
    }

    static Intent mainIntent(Context context) {
        PackageManager packages = context.getPackageManager();
        Intent query = new Intent(Intent.ACTION_MAIN);
        query.addCategory(Intent.CATEGORY_LAUNCHER);
        query.setPackage(context.getPackageName());
        List<ResolveInfo> matches = packages.queryIntentActivities(query, 0);
        if (matches != null) {
            for (ResolveInfo info : matches) {
                if (info.activityInfo == null) continue;
                if (NAME.equals(info.activityInfo.name)) continue;
                Intent launch = new Intent(Intent.ACTION_MAIN);
                launch.addCategory(Intent.CATEGORY_LAUNCHER);
                launch.setClassName(info.activityInfo.packageName, info.activityInfo.name);
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                return launch;
            }
        }
        Intent fallback = packages.getLaunchIntentForPackage(context.getPackageName());
        if (fallback == null || fallback.getComponent() == null) return fallback;
        if (NAME.equals(fallback.getComponent().getClassName())) return null;
        fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        return fallback;
    }
}
