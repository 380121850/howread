package com.foobnix.profile;

import android.content.ComponentName;
import android.content.Intent;
import android.net.Uri;

import androidx.benchmark.macro.MacrobenchmarkScope;
import androidx.benchmark.macro.junit4.BaselineProfileRule;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Rule;
import org.junit.Test;

import kotlin.Unit;
import kotlin.jvm.functions.Function1;

/**
 * Plan D: generate a Baseline Profile (text HSP rule list) for the pro build by
 * driving the real open-book flows under a profile session. Run manually via:
 *   adb shell am instrument -w <pkg>/com.foobnix.profile.GenerateBaselineProfile
 * The rule writes the stable profile to the app-external dir
 *   /sdcard/Android/data/<pkg>/files/  (pulled and placed into src/main/baseline-prof/).
 * No gradle plugin / no record task needed: AGP 8.13 natively consumes
 * src/main/baseline-prof/ via mergeXArtProfile + compileXArtProfile.
 */
public class GenerateBaselineProfile {

    private static final String HOME = "com.foobnix.ui2.MainTabs2";
    private static final String OPENER = "com.foobnix.OpenerActivity";

    @Rule
    public final BaselineProfileRule profileRule = new BaselineProfileRule();

    @Test
    public void generateBaselineProfile() {
        final String pkg = InstrumentationRegistry.getInstrumentation()
                .getTargetContext().getPackageName();
        final String dir = "/sdcard/Android/data/" + pkg + "/Download";

        Function1<MacrobenchmarkScope, Unit> block = scope -> {
            // 1) cold home: app init + main tab (exercises Application + MainTabs2)
            scope.startActivityAndWait(new Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_LAUNCHER)
                    .setComponent(new ComponentName(pkg, HOME)));
            scope.killProcess();

            // 2) cold-open big EPUB via the opener activity
            scope.startActivityAndWait(viewIntent(dir + "/book_bigepub.epub", pkg));
            scope.killProcess();

            // 3) cold-open big PDF via the opener activity
            scope.startActivityAndWait(viewIntent(dir + "/big25.pdf", pkg));
            scope.killProcess();

            // 4) cold-open big EPUB again (warm cache + Plan A prefetch adoption path)
            scope.startActivityAndWait(viewIntent(dir + "/book_bigepub.epub", pkg));
            scope.killProcess();

            return Unit.INSTANCE;
        };

        profileRule.collect(pkg, block);
    }

    private static Intent viewIntent(String uri, String pkg) {
        return new Intent(Intent.ACTION_VIEW, Uri.parse("file://" + uri))
                .setComponent(new ComponentName(pkg, OPENER));
    }
}
