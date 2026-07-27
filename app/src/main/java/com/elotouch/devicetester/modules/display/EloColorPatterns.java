package com.elotouch.devicetester.modules.display;

import android.content.Context;

import java.io.IOException;
import java.util.Arrays;

/** Shared helper: enumerates the bundled color-pattern assets in slide order. */
final class EloColorPatterns {

    static final String ASSET_DIR = "color_patterns";

    private EloColorPatterns() { }

    /** Sorted asset file names (e.g. "pattern_01.png"); empty array on error. */
    static String[] list(Context ctx) {
        try {
            String[] names = ctx.getAssets().list(ASSET_DIR);
            if (names == null) return new String[0];
            Arrays.sort(names);
            return names;
        } catch (IOException e) {
            return new String[0];
        }
    }
}
