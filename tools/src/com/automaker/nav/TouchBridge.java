package com.automaker.nav;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.accessibilityservice.TouchInteractionController;
import android.content.res.Configuration;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.Region;
import android.hardware.display.DisplayManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.SparseArray;
import android.view.Display;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.Executor;

public class TouchBridge extends AccessibilityService implements TouchInteractionController.Callback {
    static final String NAME = "com.automaker.nav.TouchBridge";
    private static final int IME_ENTER = 0x01020054;
    private static WeakReference<TouchBridge> bridge;
    private static final int TOUCH_EXPLORATION = 0x4;
    private static final int MOTION_SOURCES = InputDevice.SOURCE_TOUCHSCREEN
            | InputDevice.SOURCE_MOUSE
            | InputDevice.SOURCE_STYLUS;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Executor callbackExecutor = new Executor() {
        @Override
        public void execute(Runnable command) {
            main.post(command);
        }
    };
    private final SparseArray<TouchInteractionController> controllers = new SparseArray<>();
    private final Rect contentRegion = new Rect();
    private int contentDisplay = -1;
    private boolean capturing;
    private boolean stopping;
    private boolean observing;
    private boolean gestureInside;
    private boolean loggedPicture;
    private boolean loggedMissing;
    private boolean loggedWindows;
    private boolean loggedHole;
    private boolean loggedSkip;
    private boolean scanning;
    private int scanDisplay = -1;
    private int stackMisses;
    private final Runnable trackSession = new Runnable() {
        @Override
        public void run() {
            if (!NavigationCarService.sessionActive()) {
                stopCapturing();
                return;
            }
            refreshContentRegion();
            if (!observing && contentDisplay >= 0 && !contentRegion.isEmpty()) {
                NavigationCarService.placeTouchLayer(contentDisplay, new Rect(contentRegion));
            }
            main.postDelayed(this, 500);
        }
    };

    @Override
    protected void onServiceConnected() {
        bridge = new WeakReference<>(this);
        AccessibilityServiceInfo info = getServiceInfo();
        if (info != null) {
            info.eventTypes = AccessibilityEvent.TYPE_VIEW_FOCUSED | AccessibilityEvent.TYPE_VIEW_CLICKED
                    | AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED;
            info.packageNames = new String[] {getPackageName(),
                    "com.google.android.projection.gearhead",
                    "com.google.android.apps.automotive.templates.host"};
            setServiceInfo(info);
        }
        startSession();
    }

    static void startSession() {
        final TouchBridge service = bridge == null ? null : bridge.get();
        if (service == null) return; // User has not enabled the service in Android settings.
        service.main.post(new Runnable() {
            @Override public void run() {
                if (!NavigationCarService.sessionActive()) return;
                service.stopping = false;
                service.clearExploration();
                service.observing = service.observeTouches();
                service.main.removeCallbacks(service.trackSession);
                service.main.post(service.trackSession);
            }
        });
    }

    static void stopSession() {
        final TouchBridge service = bridge == null ? null : bridge.get();
        if (service != null) service.main.post(new Runnable() {
            @Override public void run() { service.stopCapturing(); }
        });
    }

    private boolean observeTouches() {
        AccessibilityServiceInfo info = getServiceInfo();
        if (info == null) return false;
        try {
            Method method = AccessibilityServiceInfo.class.getDeclaredMethod("setObservedMotionEventSources", int.class);
            method.setAccessible(true);
            info.setMotionEventSources(MOTION_SOURCES);
            method.invoke(info, Integer.valueOf(MOTION_SOURCES));
            setServiceInfo(info);
            AccessibilityServiceInfo updated = getServiceInfo();
            int observed = -1;
            if (updated != null) {
                Method read = AccessibilityServiceInfo.class.getDeclaredMethod("getObservedMotionEventSources");
                read.setAccessible(true);
                Object value = read.invoke(updated);
                if (value instanceof Integer) observed = (Integer) value;
            }
            Log.i("AutoMakerNav", "Touch observe sources " + (updated == null ? -1 : updated.getMotionEventSources())
                    + " observed " + observed);
            if (observed != MOTION_SOURCES) {
                info.setMotionEventSources(0);
                setServiceInfo(info);
                return false;
            }
            return true;
        } catch (Throwable error) {
            Throwable cause = error.getCause() == null ? error : error.getCause();
            Log.i("AutoMakerNav", "Touch observe unavailable " + cause.getClass().getSimpleName()
                    + " " + cause.getMessage());
            try {
                info.setMotionEventSources(0);
                setServiceInfo(info);
            } catch (Throwable ignored) {
            }
            return false;
        }
    }

    private void armControllers() {
        if (controllers.size() > 0) return;
        DisplayManager manager = (DisplayManager) getSystemService(DISPLAY_SERVICE);
        if (manager == null) return;
        Display[] displays = manager.getDisplays();
        for (int i = 0; i < displays.length; i++) {
            int id = displays[i].getDisplayId();
            try {
                TouchInteractionController controller = getTouchInteractionController(id);
                controller.registerCallback(callbackExecutor, this);
                controllers.put(id, controller);
            } catch (Throwable error) {
                Log.i("AutoMakerNav", "Touch delegate unavailable on " + id);
            }
        }
    }

    private void stopCapturing() {
        if (stopping) return;
        stopping = true;
        observing = false;
        main.removeCallbacks(trackSession);
        capturing = false;
        contentRegion.setEmpty();
        contentDisplay = -1;
        NavigationCarService.placeTouchLayer(Display.INVALID_DISPLAY, new Rect());
        clearExploration();
        NavigationCarService.releaseTouchCapture(getPackageName());
    }

    private void clearExploration() {
        try {
            AccessibilityServiceInfo info = getServiceInfo();
            if (info == null) return;
            info.setMotionEventSources(0);
            info.flags &= ~TOUCH_EXPLORATION;
            setServiceInfo(info);
        } catch (Throwable ignored) {
        }
    }

    private boolean applyPassthrough() {
        DisplayManager manager = (DisplayManager) getSystemService(DISPLAY_SERVICE);
        if (manager == null) return false;
        Display[] displays = manager.getDisplays();
        boolean holed = false;
        try {
            for (int i = 0; i < displays.length; i++) {
                Display display = displays[i];
                Point size = new Point();
                display.getRealSize(size);
                Region region = new Region(0, 0, Math.max(size.x, 1), Math.max(size.y, 1));
                if (display.getDisplayId() == contentDisplay && safeHole(size, contentRegion)) {
                    region.op(contentRegion, Region.Op.DIFFERENCE);
                    holed = true;
                }
                setTouchExplorationPassthroughRegion(display.getDisplayId(), region);
            }
        } catch (Throwable error) {
            return false;
        }
        if (holed && !loggedHole) {
            loggedHole = true;
            Log.i("AutoMakerNav", "Touch hole " + contentRegion.left + "," + contentRegion.top
                    + " " + contentRegion.width() + "x" + contentRegion.height());
        }
        return true;
    }

    private static boolean safeHole(Point display, Rect hole) {
        if (display.x <= 0 || display.y <= 0 || hole.width() < 80 || hole.height() < 80) return false;
        return hole.left > 2 || hole.top > 2 || hole.right < display.x - 2 || hole.bottom < display.y - 2;
    }

    @Override
    public void onMotionEvent(MotionEvent event) {
        if (!observing || event == null || !NavigationCarService.sessionActive()) return;
        int action = event.getActionMasked();
        int display = displayOf(event);
        float rawX = event.getX();
        float rawY = event.getY();
        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN) {
            gestureInside = display == contentDisplay && contentRegion.contains(Math.round(rawX), Math.round(rawY));
            if (!gestureInside) return;
            Log.i("AutoMakerNav", "Touch at " + (int) rawX + "," + (int) rawY);
        }
        if (!gestureInside || contentRegion.isEmpty()) return;
        float x = (rawX - contentRegion.left) * NavigationCarService.screenWidth() / Math.max(contentRegion.width(), 1);
        float y = (rawY - contentRegion.top) * NavigationCarService.screenHeight() / Math.max(contentRegion.height(), 1);
        NavigationCarService.deliverMappedTouch(action, x, y);
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) gestureInside = false;
    }

    @Override
    public void onStateChanged(int state) {
        if (state == TouchInteractionController.STATE_CLEAR) capturing = false;
    }

    private void refreshContentRegion() {
        int width = NavigationCarService.screenWidth();
        int height = NavigationCarService.screenHeight();
        float target = width / (float) Math.max(height, 1);
        Rect best = null;
        int bestDisplay = -1;
        float bestDelta = Float.MAX_VALUE;
        int bestArea = 0;
        StringBuilder seen = loggedWindows ? null : new StringBuilder();
        try {
            SparseArray<List<AccessibilityWindowInfo>> all = getWindowsOnAllDisplays();
            if (all == null) return;
            for (int i = 0; i < all.size(); i++) {
                int displayId = all.keyAt(i);
                List<AccessibilityWindowInfo> windows = all.valueAt(i);
                if (windows == null) continue;
                if (!fingerDisplay(displayId)) {
                    for (int w = 0; w < windows.size(); w++) {
                        AccessibilityWindowInfo skipped = windows.get(w);
                        if (skipped != null) skipped.recycle();
                    }
                    continue;
                }
                for (int w = 0; w < windows.size(); w++) {
                    AccessibilityWindowInfo window = windows.get(w);
                    if (window == null) continue;
                    AccessibilityNodeInfo root = null;
                    try {
                        root = window.getRoot();
                        CharSequence packageName = root == null ? null : root.getPackageName();
                        if (seen != null && packageName != null && seen.length() < 220) {
                            seen.append(packageName).append("@").append(displayId).append(" ");
                        }
                        if (packageName != null && carPackage(packageName.toString())) {
                            Rect found = new Rect();
                            float[] score = new float[] {Float.MAX_VALUE, 0f};
                            visit(root, target, found, score);
                            int area = (int) score[1];
                            if (!found.isEmpty() && (score[0] < bestDelta - 0.02f
                                    || (Math.abs(score[0] - bestDelta) <= 0.02f && area > bestArea))) {
                                bestDelta = score[0];
                                bestArea = area;
                                bestDisplay = displayId;
                                best = found;
                            }
                        }
                    } finally {
                        if (root != null) root.recycle();
                        window.recycle();
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        if (!loggedWindows) {
            loggedWindows = true;
            Log.i("AutoMakerNav", "Touch windows " + (seen == null || seen.length() == 0 ? "none" : seen.toString()));
        }
        if (best == null) {
            if (!loggedMissing) {
                loggedMissing = true;
                Log.i("AutoMakerNav", "Touch picture missing");
            }
            scheduleHeadUnitPicture();
            return;
        }
        Rect fitted = insetPicture(bestDisplay, best) ? new Rect(best) : fitContent(best, target);
        int nextArea = fitted.width() * fitted.height();
        if (contentDisplay >= 0 && !contentRegion.isEmpty()) {
            int currentArea = Math.max(contentRegion.width() * contentRegion.height(), 1);
            if (Math.abs(nextArea - currentArea) > currentArea * 0.2f) return;
        }
        contentRegion.set(fitted);
        contentDisplay = bestDisplay;
        if (!loggedPicture) {
            loggedPicture = true;
            Log.i("AutoMakerNav", "Touch picture display " + bestDisplay + " " + fitted.left + "," + fitted.top
                    + " " + fitted.width() + "x" + fitted.height());
        }
    }

    private void scheduleHeadUnitPicture() {
        if (scanning) return;
        scanning = true;
        new Thread(new Runnable() {
            @Override
            public void run() {
                Rect picture = new Rect();
                int display = -1;
                try {
                    picture = headUnitPicture();
                    display = scanDisplay;
                } catch (Throwable error) {
                    Log.i("AutoMakerNav", "Touch stack failed " + error.getClass().getSimpleName());
                }
                final Rect found = picture;
                final int foundDisplay = display;
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        scanning = false;
                        if (stopping || !NavigationCarService.sessionActive()) return;
                        if (found.isEmpty()) {
                            stackMisses++;
                            if (stackMisses < 3 || contentRegion.isEmpty()) return;
                            contentRegion.setEmpty();
                            contentDisplay = -1;
                            stackMisses = 0;
                            NavigationCarService.placeTouchLayer(Display.INVALID_DISPLAY, new Rect());
                            Log.i("AutoMakerNav", "Touch picture hidden");
                            return;
                        }
                        stackMisses = 0;
                        contentRegion.set(found);
                        contentDisplay = foundDisplay;
                        if (!observing) NavigationCarService.placeTouchLayer(foundDisplay, new Rect(found));
                        if (!loggedPicture) {
                            loggedPicture = true;
                            Log.i("AutoMakerNav", "Touch picture display " + foundDisplay + " " + found.left + ","
                                    + found.top + " " + found.width() + "x" + found.height());
                        }
                    }
                });
            }
        }, "AutoMakerTouch").start();
    }

    private Rect headUnitPicture() {
        String dump = NavigationCarService.shellCapture("am stack list");
        if (dump.length() == 0) {
            Log.i("AutoMakerNav", "Touch stack empty");
            return new Rect();
        }
        String[] lines = dump.split("\n");
        int displayId = -1;
        Rect rootFrame = null;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line.contains("RootTask") && line.contains("displayId=")) {
                displayId = numberAfter(line, "displayId=");
                rootFrame = bracketRect(line, "bounds=[");
            }
            if (!line.contains("headlessunit") || !line.contains("visible=true")) continue;
            Rect frame = bracketRect(line, "bounds=[");
            if (frame == null) frame = rootFrame;
            boolean finger = fingerDisplay(displayId);
            if (frame == null || frame.width() < 80 || frame.height() < 80 || !finger) {
                if (!loggedSkip) {
                    loggedSkip = true;
                    Log.i("AutoMakerNav", "Touch stack skipped display " + displayId
                            + " frame " + (frame == null ? "none" : frame.width() + "x" + frame.height())
                            + " finger " + finger);
                }
                continue;
            }
            scanDisplay = displayId;
            return insetBar(frame);
        }
        return new Rect();
    }

    private Rect insetBar(Rect frame) {
        int ghostW = 0;
        int ghostH = 0;
        int barW = 0;
        int barH = 0;
        DisplayManager manager = (DisplayManager) getSystemService(DISPLAY_SERVICE);
        Display[] displays = manager == null ? null : manager.getDisplays();
        if (displays != null) {
            for (int i = 0; i < displays.length; i++) {
                Display display = displays[i];
                String name = "";
                try {
                    name = String.valueOf(display.getName());
                } catch (Throwable ignored) {
                }
                Point size = new Point();
                display.getRealSize(size);
                boolean gearhead = name.contains("gearhead");
                if ((name.contains("FacetBar") || name.contains("GhFacet")) && size.x * size.y > barW * barH) {
                    barW = size.x;
                    barH = size.y;
                } else if (gearhead && name.contains("GhostActivity") && size.x * size.y > ghostW * ghostH) {
                    ghostW = size.x;
                    ghostH = size.y;
                }
            }
        }
        Rect picture = new Rect(frame);
        if (ghostH > 0 && barH > 0 && barH < barW && Math.abs(barW - ghostW) <= Math.max(ghostW, 1) * 0.25f) {
            int pictureH = Math.max(Math.round(frame.height() * (ghostH / (float) (ghostH + barH))), 1);
            picture.bottom = picture.top + Math.min(pictureH, frame.height());
            return picture;
        }
        if (ghostW > 0 && barW > 0 && barW < barH && Math.abs(barH - ghostH) <= Math.max(ghostH, 1) * 0.25f) {
            int pictureW = Math.max(Math.round(frame.width() * (ghostW / (float) (ghostW + barW))), 1);
            picture.right = picture.left + Math.min(pictureW, frame.width());
            return picture;
        }
        picture.bottom = picture.top + Math.min(Math.max(Math.round(frame.height() * 0.9f), 1), frame.height());
        return picture;
    }

    private static int numberAfter(String text, String key) {
        int at = text.indexOf(key);
        if (at < 0) return -1;
        int start = at + key.length();
        int end = start;
        while (end < text.length() && Character.isDigit(text.charAt(end))) end++;
        if (end == start) return -1;
        try {
            return Integer.parseInt(text.substring(start, end));
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    private static Rect bracketRect(String text, String key) {
        int at = text.indexOf(key);
        if (at < 0) return null;
        int open = at + key.length();
        int split = text.indexOf("][", open);
        int close = split < 0 ? -1 : text.indexOf(']', split + 2);
        if (close < 0) return null;
        String[] parts = text.substring(open, close).split("\\]\\[");
        if (parts.length != 2) return null;
        String[] start = parts[0].split(",");
        String[] end = parts[1].split(",");
        if (start.length != 2 || end.length != 2) return null;
        try {
            return new Rect(Integer.parseInt(start[0].trim()), Integer.parseInt(start[1].trim()),
                    Integer.parseInt(end[0].trim()), Integer.parseInt(end[1].trim()));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static Rect fitContent(Rect window, float target) {
        Rect fitted = new Rect(window);
        float aspect = window.width() / (float) Math.max(window.height(), 1);
        if (Math.abs(aspect - target) < 0.08f) return fitted;
        if (aspect < target) {
            fitted.bottom = fitted.top + Math.min(Math.max(Math.round(window.width() / target), 1), window.height());
            return fitted;
        }
        fitted.right = fitted.left + Math.min(Math.max(Math.round(window.height() * target), 1), window.width());
        return fitted;
    }

    private static float aspectDelta(Rect rect, float target) {
        return Math.abs(rect.width() / (float) Math.max(rect.height(), 1) - target);
    }

    private static void visit(AccessibilityNodeInfo node, float target, Rect best, float[] score) {
        if (node == null) return;
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        int area = bounds.width() * bounds.height();
        if (area > 40000) {
            float delta = Math.abs(bounds.width() / (float) Math.max(bounds.height(), 1) - target);
            if (delta < score[0] - 0.02f || (Math.abs(delta - score[0]) <= 0.02f && area > score[1])) {
                score[0] = delta;
                score[1] = area;
                best.set(bounds);
            }
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            visit(child, target, best, score);
            if (child != null) child.recycle();
        }
    }

    private boolean fingerDisplay(int displayId) {
        if (displayId < 0 || displayId == NavigationCarService.ownDisplay()) return false;
        if (displayId == Display.DEFAULT_DISPLAY || displayId == 1) return true;
        DisplayManager manager = (DisplayManager) getSystemService(DISPLAY_SERVICE);
        if (manager == null) return displayId == Display.DEFAULT_DISPLAY;
        Display display = manager.getDisplay(displayId);
        if (display == null) return false;
        try {
            return createDisplayContext(display).getResources().getConfiguration().touchscreen
                    != Configuration.TOUCHSCREEN_NOTOUCH;
        } catch (Throwable ignored) {
            return displayId == Display.DEFAULT_DISPLAY;
        }
    }

    private boolean insetPicture(int displayId, Rect picture) {
        DisplayManager manager = (DisplayManager) getSystemService(DISPLAY_SERVICE);
        Display display = manager == null ? null : manager.getDisplay(displayId);
        if (display == null) return false;
        Point size = new Point();
        display.getRealSize(size);
        if (picture.left <= 2 && picture.top <= 2 && picture.right >= size.x - 2 && picture.bottom >= size.y - 2) {
            return false;
        }
        return picture.width() < size.x - 8 || picture.height() < size.y - 8;
    }

    private static boolean carPackage(String name) {
        return name.contains("gearhead") || name.contains("headlessunit");
    }

    private static int displayOf(MotionEvent event) {
        try {
            Method method = MotionEvent.class.getDeclaredMethod("getDisplayId");
            method.setAccessible(true);
            Object value = method.invoke(event);
            if (value instanceof Integer) return (Integer) value;
        } catch (Throwable ignored) {
        }
        return -1;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || !NavigationCarService.sessionActive()) return;
        int type = event.getEventType();
        if (type != AccessibilityEvent.TYPE_VIEW_FOCUSED
                && type != AccessibilityEvent.TYPE_VIEW_CLICKED
                && type != AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED) return;
        AccessibilityNodeInfo source = event.getSource();
        if (source == null) return;
        try {
            if (!source.isEditable() || source.isPassword()) return;
            CharSequence packageName = source.getPackageName();
            if (packageName == null || !packageName.toString().equals(getPackageName())) return;
            if (windowDisplay(source) != NavigationCarService.ownDisplay()) return;
            CharSequence text = source.getText();
            NavigationCarService.requestKeyboard(
                    text == null ? "" : text.toString(),
                    source.getInputType(),
                    source.isPassword(),
                    type == AccessibilityEvent.TYPE_VIEW_CLICKED);
        } finally {
            source.recycle();
        }
    }

    static boolean writeEditor(int displayId, String text) {
        TouchBridge service = bridge == null ? null : bridge.get();
        if (service == null || displayId == Display.INVALID_DISPLAY) return false;
        AccessibilityNodeInfo editor = service.findEditor(displayId);
        if (editor == null) return false;
        try {
            Bundle args = new Bundle();
            args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text == null ? "" : text);
            return editor.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
        } finally {
            editor.recycle();
        }
    }

    static boolean submitEditor(int displayId) {
        TouchBridge service = bridge == null ? null : bridge.get();
        if (service == null || displayId == Display.INVALID_DISPLAY) return false;
        AccessibilityNodeInfo editor = service.findEditor(displayId);
        if (editor == null) return false;
        try {
            int action = IME_ENTER;
            try {
                Field field = AccessibilityNodeInfo.AccessibilityAction.class.getField("ACTION_IME_ENTER");
                Object value = field.get(null);
                Method getId = value.getClass().getMethod("getId");
                Object id = getId.invoke(value);
                if (id instanceof Integer) action = (Integer) id;
            } catch (Throwable ignored) {
            }
            return editor.performAction(action);
        } finally {
            editor.recycle();
        }
    }

    private AccessibilityNodeInfo findEditor(int displayId) {
        AccessibilityNodeInfo match = null;
        try {
            SparseArray<List<AccessibilityWindowInfo>> all = getWindowsOnAllDisplays();
            if (all == null) return null;
            for (int i = 0; i < all.size(); i++) {
                List<AccessibilityWindowInfo> windows = all.valueAt(i);
                if (windows == null) continue;
                if (all.keyAt(i) != displayId) {
                    recycleWindows(windows);
                    continue;
                }
                for (int w = 0; w < windows.size(); w++) {
                    AccessibilityWindowInfo window = windows.get(w);
                    if (window == null) continue;
                    AccessibilityNodeInfo root = null;
                    try {
                        root = window.getRoot();
                        CharSequence packageName = root == null ? null : root.getPackageName();
                        if (packageName == null || !packageName.toString().equals(getPackageName())) continue;
                        AccessibilityNodeInfo editor = pickEditor(root);
                        if (editor == null) continue;
                        boolean focused = editor.isFocused() || editor.isAccessibilityFocused();
                        boolean matchFocused = match != null && (match.isFocused() || match.isAccessibilityFocused());
                        if (match == null || (focused && !matchFocused)) {
                            if (match != null) match.recycle();
                            match = editor;
                        } else {
                            editor.recycle();
                        }
                    } finally {
                        if (root != null) root.recycle();
                        window.recycle();
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return match;
    }

    private static AccessibilityNodeInfo pickEditor(AccessibilityNodeInfo node) {
        if (node == null) return null;
        AccessibilityNodeInfo match = node.isEditable() ? AccessibilityNodeInfo.obtain(node) : null;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            AccessibilityNodeInfo found = pickEditor(child);
            if (child != null) child.recycle();
            if (found == null) continue;
            boolean focused = found.isFocused() || found.isAccessibilityFocused();
            boolean matchFocused = match != null && (match.isFocused() || match.isAccessibilityFocused());
            if (match == null || (focused && !matchFocused)) {
                if (match != null) match.recycle();
                match = found;
            } else {
                found.recycle();
            }
        }
        return match;
    }

    private static void recycleWindows(List<AccessibilityWindowInfo> windows) {
        for (int i = 0; i < windows.size(); i++) {
            AccessibilityWindowInfo window = windows.get(i);
            if (window != null) window.recycle();
        }
    }

    private static int windowDisplay(AccessibilityNodeInfo node) {
        AccessibilityWindowInfo window = node.getWindow();
        if (window == null) return -1;
        try {
            Method method = AccessibilityWindowInfo.class.getMethod("getDisplayId");
            Object value = method.invoke(window);
            if (value instanceof Integer) return (Integer) value;
        } catch (Throwable ignored) {
        } finally {
            window.recycle();
        }
        return -1;
    }

    @Override
    public void onInterrupt() {
        stopCapturing();
    }

    @Override
    public void onDestroy() {
        stopCapturing();
        TouchBridge current = bridge == null ? null : bridge.get();
        if (current == this) bridge = null;
        main.removeCallbacks(trackSession);
        super.onDestroy();
    }
}
