package com.openminis.app.ui.settings;

import android.content.Context;
import com.openminis.app.security.HarkIntegrityGuard;

/**
 * Hark Community & Project Security Guard (Java compatibility layer).
 * Delegates to HarkIntegrityGuard.
 */
public final class HarkCommunityGuard {

    private HarkCommunityGuard() {}

    public static String getGithubUrl() {
        return HarkIntegrityGuard.INSTANCE.getVerifiedGithubUrl();
    }

    public static String getQqGroup() {
        return HarkIntegrityGuard.INSTANCE.getVerifiedQqGroup();
    }

    public static void openGithub(Context context) {
        HarkIntegrityGuard.INSTANCE.openGithub(context);
    }

    public static void joinQqGroup(Context context) {
        HarkIntegrityGuard.INSTANCE.joinQqGroup(context);
    }

    public static void copyToClipboard(Context context, String text, String toastMessage) {
        HarkIntegrityGuard.INSTANCE.copyToClipboard(context, text, toastMessage);
    }
}
