/*
 * Copyright (C) 2026 piko-plus <https://github.com/g8row/piko-plus>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.morphe.extension.instagram.theme;

import android.content.Context;
import android.graphics.Color;
import android.os.Build;
import android.util.SparseIntArray;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;

/**
 * Recolours Instagram's cached Compose Prism palettes with Material You tones.
 *
 * The palettes are plain objects of packed Compose colours ({@code argb << 32}), built from the
 * Prism grey and blue scales. Only their background was themed before, so Compose buttons, search
 * fields and secondary text kept Instagram's neutral greys, which sit badly on a tinted background.
 * Every field holding one of those scale values is rewritten here to the Material You tone of the
 * same role. A grey means a different role in light and dark (f8f9f9 is a surface in light and the
 * text colour in dark), so the table is picked by the background the palette is drawn on.
 */
public final class MaterialYouComposePalette {

    private MaterialYouComposePalette() {
    }

    private static final int[][] LIGHT = {
            // Surfaces: near-white cards, then the tinted container tone for fields and buttons.
            {0xfcfcfd, tone("system_accent1_10")},
            {0xf8f9f9, tone("system_accent2_100")},
            {0xf3f5f7, tone("system_accent2_100")},
            {0xf0f2f5, tone("system_accent2_100")},
            {0xe9edf0, tone("system_accent2_100")},
            {0xe7eaee, tone("system_accent2_200")},
            // Dividers, outlines and secondary text.
            {0xdce0e5, tone("system_neutral2_200")},
            {0xdbdfe4, tone("system_neutral2_200")},
            {0xc5cbd3, tone("system_neutral2_300")},
            {0xa2a9b4, tone("system_neutral2_400")},
            {0xa2aab4, tone("system_neutral2_400")},
            {0x838a93, tone("system_neutral2_500")},
            {0x838993, tone("system_neutral2_500")},
            {0x686f78, tone("system_neutral2_600")},
            {0x6f7680, tone("system_neutral2_600")},
            {0x5e646d, tone("system_neutral2_700")},
            {0x57606b, tone("system_neutral2_700")},
            // Dark greys are text and icons on a light background.
            {0x48505b, tone("system_neutral2_800")},
            {0x363c44, tone("system_neutral1_900")},
            {0x2b3036, tone("system_neutral1_900")},
            {0x25292e, tone("system_neutral1_900")},
            {0x212328, tone("system_neutral1_900")},
            {0x191c1f, tone("system_neutral1_900")},
            {0x0c1014, tone("system_neutral1_900")},
            // Instagram blue is the primary colour.
            {0x0095f6, tone("system_accent1_600")},
            {0x007fe0, tone("system_accent1_600")},
            {0x0b6ed3, tone("system_accent1_700")},
            {0x016dcb, tone("system_accent1_700")},
    };

    private static final int[][] DARK = {
            // Surfaces and containers, lighter than the neutral1_900 background.
            {0x0c1014, tone("system_neutral1_900")},
            {0x191c1f, tone("system_neutral2_900")},
            {0x212328, tone("system_accent2_800")},
            {0x25292e, tone("system_accent2_800")},
            {0x2b3036, tone("system_accent2_800")},
            {0x363c44, tone("system_accent2_700")},
            {0x48505b, tone("system_neutral2_700")},
            {0x57606b, tone("system_neutral2_600")},
            {0x5e646d, tone("system_neutral2_600")},
            // Secondary text and icons.
            {0x686f78, tone("system_neutral2_500")},
            {0x6f7680, tone("system_neutral2_500")},
            {0x838a93, tone("system_neutral2_400")},
            {0x838993, tone("system_neutral2_400")},
            {0xa2a9b4, tone("system_neutral2_300")},
            {0xa2aab4, tone("system_neutral2_300")},
            {0xc5cbd3, tone("system_neutral2_200")},
            {0xdce0e5, tone("system_neutral2_200")},
            {0xdbdfe4, tone("system_neutral2_200")},
            {0xe7eaee, tone("system_neutral2_100")},
            {0xe9edf0, tone("system_neutral2_100")},
            {0xf0f2f5, tone("system_neutral2_100")},
            // Light greys are the text colour on a dark background.
            {0xf3f5f7, tone("system_neutral1_50")},
            {0xf8f9f9, tone("system_neutral1_50")},
            {0xfcfcfd, tone("system_neutral1_50")},
            {0x0095f6, tone("system_accent1_200")},
            {0x007fe0, tone("system_accent1_200")},
            {0x0b6ed3, tone("system_accent1_300")},
            {0x016dcb, tone("system_accent1_300")},
    };

    private static SparseIntArray lightMap;
    private static SparseIntArray darkMap;
    private static Class<?> fieldsClass;
    private static List<Field> colorFields;

    /** A system palette colour by name, resolved when the tables are built; 0 when unavailable. */
    private static int tone(String name) {
        try {
            Context context = Utils.getContext();
            int id = context.getResources().getIdentifier(name, "color", "android");
            return id == 0 ? 0 : context.getColor(id) & 0xffffff;
        } catch (Exception e) {
            return 0;
        }
    }

    private static SparseIntArray build(int[][] table) {
        SparseIntArray map = new SparseIntArray(table.length);
        for (int[] row : table) {
            if (row[1] != 0 || row[0] == 0) {
                map.put(row[0], row[1]);
            }
        }
        return map;
    }

    /**
     * Injected into the Compose palette bridge after the background is written.
     *
     * @param palette    a cached Prism palette.
     * @param background the Material You background the palette was given.
     */
    public static void remap(Object palette, int background) {
        android.util.Log.i("PikoPlus", "compose palette remap: " + (palette == null ? null : palette.getClass().getName())
                + " bg=" + Integer.toHexString(background) + " materialYou=" + MaterialYouTheme.isMaterialYouEnabled());
        if (palette == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.S
                || !MaterialYouTheme.isMaterialYouEnabled()) {
            return;
        }
        try {
            boolean dark = luminance(background) < 0.4;
            SparseIntArray map;
            synchronized (MaterialYouComposePalette.class) {
                if (lightMap == null) {
                    lightMap = build(LIGHT);
                    darkMap = build(DARK);
                }
                map = dark ? darkMap : lightMap;
                if (fieldsClass != palette.getClass()) {
                    colorFields = longFields(palette.getClass());
                    fieldsClass = palette.getClass();
                }
            }
            int changed = 0;
            for (Field field : colorFields) {
                long packed = field.getLong(palette);
                // sRGB Compose colours keep the colour space id (0) in the low bits.
                if ((packed & 0xffffffffL) != 0) continue;
                int argb = (int) (packed >>> 32);
                int alpha = argb >>> 24;
                int replacement = map.get(argb & 0xffffff, -1);
                if (replacement < 0) continue;
                long next = ((long) ((alpha << 24) | replacement)) << 32;
                field.setLong(palette, next);
                changed++;
            }
            android.util.Log.i("PikoPlus", "compose palette remap: dark=" + dark + " fields=" + colorFields.size() + " changed=" + changed);
        } catch (Throwable t) {
            Logger.printException(() -> "Failed to apply Material You to a Compose palette", t);
        }
    }

    private static List<Field> longFields(Class<?> type) {
        List<Field> fields = new ArrayList<>();
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field field : c.getDeclaredFields()) {
                if (field.getType() == long.class && !Modifier.isStatic(field.getModifiers())) {
                    field.setAccessible(true);
                    fields.add(field);
                }
            }
        }
        return fields;
    }

    private static double luminance(int argb) {
        return (0.299 * Color.red(argb) + 0.587 * Color.green(argb) + 0.114 * Color.blue(argb)) / 255.0;
    }
}
