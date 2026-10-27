package com.automaker.game;

import android.app.Activity;
import android.app.Application;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.view.Display;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.TextView;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

public class KeyboardStay extends ContentProvider {
    private static final Set<View> WATCHED = Collections.newSetFromMap(new WeakHashMap<View, Boolean>());

    @Override
    public boolean onCreate() {
        Context context = getContext();
        if (context == null) return true;
        Context app = context.getApplicationContext();
        if (!(app instanceof Application)) return true;
        ((Application) app).registerActivityLifecycleCallbacks(new Guard());
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }

    private static final class Guard implements Application.ActivityLifecycleCallbacks {
        @Override
        public void onActivityCreated(Activity activity, Bundle savedInstanceState) {
            prepare(activity);
        }

        @Override
        public void onActivityStarted(Activity activity) {
            prepare(activity);
        }

        @Override
        public void onActivityResumed(Activity activity) {
            prepare(activity);
            disarm(activity.getCurrentFocus());
        }

        @Override
        public void onActivityPaused(Activity activity) {
        }

        @Override
        public void onActivityStopped(Activity activity) {
        }

        @Override
        public void onActivitySaveInstanceState(Activity activity, Bundle outState) {
        }

        @Override
        public void onActivityDestroyed(Activity activity) {
        }

        private void prepare(final Activity activity) {
            holdWindow(activity);
            Window window = activity.getWindow();
            if (window == null) return;
            final View decor = window.getDecorView();
            if (decor == null || !WATCHED.add(decor)) return;
            decor.getViewTreeObserver().addOnGlobalFocusChangeListener(new ViewTreeObserver.OnGlobalFocusChangeListener() {
                @Override
                public void onGlobalFocusChanged(View oldFocus, View newFocus) {
                    disarm(newFocus);
                }
            });
        }

        private static void holdWindow(Activity activity) {
            if (!carDisplay(activity) || activity.getWindow() == null) return;
            activity.getWindow().setSoftInputMode(
                    WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
                            | WindowManager.LayoutParams.SOFT_INPUT_STATE_UNCHANGED);
        }

        private static void disarm(View focus) {
            if (!(focus instanceof TextView)) return;
            TextView field = (TextView) focus;
            if (!field.onCheckIsTextEditor()) return;
            int options = field.getImeOptions();
            int next = options | EditorInfo.IME_FLAG_NO_EXTRACT_UI | EditorInfo.IME_FLAG_NO_FULLSCREEN;
            if (next != options) field.setImeOptions(next);
        }

        private static boolean carDisplay(Activity activity) {
            Display display = displayOf(activity);
            if (display == null) return false;
            if (display.getDisplayId() != Display.DEFAULT_DISPLAY) return true;
            try {
                String name = String.valueOf(display.getName()).toLowerCase();
                return name.contains("gearhead") || name.contains("headless") || name.contains("virtual");
            } catch (Throwable ignored) {
                return false;
            }
        }

        private static Display displayOf(Activity activity) {
            try {
                Method method = Activity.class.getMethod("getDisplay");
                Object value = method.invoke(activity);
                if (value instanceof Display) return (Display) value;
            } catch (Throwable ignored) {
            }
            return null;
        }
    }
}
