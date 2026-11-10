/*
 * Copyright (C) 2026 piko-plus <https://github.com/g8row/piko-plus>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.morphe.extension.instagram.theme;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Paint;
import android.os.Build;
import android.widget.TextView;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import app.morphe.extension.shared.Logger;

/**
 * Fills Instagram's grey secondary buttons (Edit profile, Share profile...) with the Material You
 * container tone. IgdsButton paints them itself: its SECONDARY painter fills a Paint from
 * ?igds_color_secondary_button_panavision, so the colour is replaced on that Paint after the
 * painter sets it. Pressed and disabled states only change the Paint's alpha, which is kept.
 */
public final class MaterialYouButtons {

    private MaterialYouButtons() {
    }

    private static boolean logged;

    /**
     * Injected at the end of the SECONDARY painter's A04(Resources, TextView). The painter fills
     * its Paint from a ColorStateList it keeps and re-applies on every drawable state change, and
     * that list was resolved before the Material You overlay reached it, so both the Paint and the
     * list are replaced with the attribute's current (themed) colour.
     */
    public static void onSecondaryPainter(Object painter, Resources resources, TextView label) {
        if (painter == null || label == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.S
                || !MaterialYouTheme.isMaterialYouEnabled()) {
            return;
        }
        try {
            Paint paint = paintOf(painter);
            if (paint == null) return;
            Context context = label.getContext();
            int stale = paint.getColor();
            int color = themeColor(context, "igds_color_secondary_button_panavision");
            if (color == 0 || (color | 0xff000000) == (stale | 0xff000000)) {
                int id = context.getResources().getIdentifier(
                        "piko_dynamic_secondary_container", "color", context.getPackageName());
                if (id == 0) return;
                color = context.getColor(id);
            }
            for (Class<?> c = painter.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                for (Field field : c.getDeclaredFields()) {
                    if (field.getType() != android.content.res.ColorStateList.class
                            || java.lang.reflect.Modifier.isStatic(field.getModifiers())) continue;
                    field.setAccessible(true);
                    android.content.res.ColorStateList list = (android.content.res.ColorStateList) field.get(painter);
                    if (list != null && list.getDefaultColor() == stale) {
                        field.set(painter, android.content.res.ColorStateList.valueOf(color));
                    }
                }
            }
            if (!logged) {
                logged = true;
                android.util.Log.i("PikoPlus", "secondary button fill " + Integer.toHexString(stale)
                        + " -> " + Integer.toHexString(color));
            }
            int alpha = paint.getAlpha();
            paint.setColor(color);
            paint.setAlpha(alpha);
        } catch (Throwable t) {
            Logger.printException(() -> "Failed to theme a secondary button", t);
        }
    }

    /**
     * Injected at the returns of the static ring colour resolver (X.05iJ.A03(attr, fallback) in
     * 452), which turns the igds_color_gradient_* attributes into the hex strings of the default
     * story ring gradient once, at class init.
     */
    public static String ringColor(int attribute, int fallback, String hex) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || !MaterialYouTheme.isMaterialYouEnabled()) {
            return hex;
        }
        try {
            Context context = app.morphe.extension.shared.Utils.getContext();
            String name = context.getResources().getResourceEntryName(attribute);
            String ring;
            switch (name) {
                case "igds_color_gradient_yellow": ring = "piko_dynamic_ring_1"; break;
                case "igds_color_gradient_orange": ring = "piko_dynamic_ring_2"; break;
                case "igds_color_gradient_pink": ring = "piko_dynamic_ring_4"; break;
                case "igds_color_gradient_lavender": ring = "piko_dynamic_ring_5"; break;
                default: return hex;
            }
            int id = context.getResources().getIdentifier(ring, "color", context.getPackageName());
            if (id == 0) return hex;
            return Integer.toHexString(systemTone(context, ring, context.getColor(id)));
        } catch (Throwable t) {
            Logger.printException(() -> "Failed to theme the story ring", t);
            return hex;
        }
    }

    /**
     * The ring aliases resolve through the Material You overlay, which the application context
     * the ring colours are read from may not have; read the system tone they stand for instead.
     */
    private static int systemTone(Context context, String alias, int fallback) {
        boolean night = (context.getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        String tone;
        switch (alias) {
            case "piko_dynamic_ring_1": tone = night ? "system_accent3_100" : "system_accent3_300"; break;
            case "piko_dynamic_ring_2": tone = night ? "system_accent3_200" : "system_accent3_500"; break;
            case "piko_dynamic_ring_4": tone = night ? "system_accent1_300" : "system_accent1_500"; break;
            case "piko_dynamic_ring_5": tone = night ? "system_accent1_400" : "system_accent1_700"; break;
            default: return fallback;
        }
        int id = context.getResources().getIdentifier(tone, "color", "android");
        return id == 0 ? fallback : context.getColor(id);
    }

    /**
     * Injected at the returns of NoteBubbleView.A0E(Context): the bubble fill. Notes without a
     * custom colour get the page background in light mode, so they vanish into it; those are
     * given the container tone. Custom note colours from the server are kept.
     */
    public static int noteBubbleColor(Context context, int color) {
        if (context == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.S
                || !MaterialYouTheme.isMaterialYouEnabled()) {
            return color;
        }
        try {
            if (color != themeColor(context, "igds_color_primary_background")
                    && color != themeColor(context, "igds_color_elevated_highlight_background")) {
                return color;
            }
            int id = context.getResources().getIdentifier(
                    "piko_dynamic_secondary_container", "color", context.getPackageName());
            return id == 0 ? color : context.getColor(id);
        } catch (Throwable t) {
            Logger.printException(() -> "Failed to theme a note bubble", t);
            return color;
        }
    }

    private static int themeColor(Context context, String attribute) {
        int attr = context.getResources().getIdentifier(attribute, "attr", context.getPackageName());
        if (attr == 0) return 0;
        android.util.TypedValue value = new android.util.TypedValue();
        if (!context.getTheme().resolveAttribute(attr, value, true)) return 0;
        return value.resourceId != 0 ? context.getColor(value.resourceId) : value.data;
    }

    private static Method getStyleMethod;
    private static final java.util.Set<String> loggedStyles = new java.util.HashSet<>();

    /**
     * Injected at the end of IgdsButton.A01(), where every button style picks its painter. A
     * painter whose fill is the theme's secondary button grey gets the container tone, whichever
     * painter class the style uses.
     */
    public static void onStyleApplied(android.view.View button) {
        if (button == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.S
                || !MaterialYouTheme.isMaterialYouEnabled()) {
            return;
        }
        try {
            Object painter = null;
            for (Class<?> c = button.getClass(); c != null && painter == null; c = c.getSuperclass()) {
                if (!c.getName().equals("com.instagram.igds.components.button.IgdsButton")) continue;
                for (Field field : c.getDeclaredFields()) {
                    if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) continue;
                    field.setAccessible(true);
                    Object value = field.get(button);
                    if (value != null && paintOf(value) != null) {
                        painter = value;
                        break;
                    }
                }
            }
            if (painter == null) return;
            Paint paint = paintOf(painter);
            Context context = button.getContext();
            int grey = themeColor(context, "igds_color_secondary_button_panavision");
            if (getStyleMethod == null) getStyleMethod = button.getClass().getMethod("getStyle");
            Object style = getStyleMethod.invoke(button);
            String key = style + "/" + painter.getClass().getName();
            if (loggedStyles.add(key)) {
                android.util.Log.i("PikoPlus", "IgdsButton style=" + key + " fill="
                        + Integer.toHexString(paint.getColor()) + " themeGrey=" + Integer.toHexString(grey));
            }
            if ((paint.getColor() | 0xff000000) != (grey | 0xff000000)) return;
            int id = context.getResources().getIdentifier(
                    "piko_dynamic_secondary_container", "color", context.getPackageName());
            if (id == 0) return;
            int alpha = paint.getAlpha();
            paint.setColor(context.getColor(id));
            paint.setAlpha(alpha);
            button.invalidate();
        } catch (Throwable t) {
            Logger.printException(() -> "Failed to theme an IgdsButton", t);
        }
    }

    private static Paint paintOf(Object painter) throws IllegalAccessException {
        for (Class<?> c = painter.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field field : c.getDeclaredFields()) {
                if (field.getType() == Paint.class && !java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                    field.setAccessible(true);
                    Object value = field.get(painter);
                    if (value != null) return (Paint) value;
                }
            }
        }
        return null;
    }
}
