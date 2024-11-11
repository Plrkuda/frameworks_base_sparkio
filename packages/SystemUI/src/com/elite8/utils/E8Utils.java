package com.elite8.utils;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Color;
import java.util.Random;

public class E8Utils {
    public static int applyAlphaColor(int i, int i2) {
        return (i & 16777215) | (i2 << 24);
    }

    public static int getRandomColor(Context context) {
        Random random = new Random();
        int i = isThemeDark(context).booleanValue() ? 16777215 : -7829368;
        return Color.rgb((Color.red(i) + random.nextInt(256)) / 2, (Color.green(i) + random.nextInt(256)) / 2, (Color.blue(i) + random.nextInt(256)) / 2);
    }

    public static int getLightDarkColor(Context context, int i, int i2) {
        Resources resources = context.getResources();
        return !isThemeDark(context).booleanValue() ? resources.getColor(i) : resources.getColor(i2);
    }

    private static Boolean isThemeDark(Context context) {
        int i = context.getResources().getConfiguration().uiMode & 48;
        if (i != 16) {
            if (i == 32) {
                return Boolean.TRUE;
            }
            return Boolean.FALSE;
        }
        return Boolean.FALSE;
    }
}
