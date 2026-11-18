package io.github.icmrnbw.tvremote;

import android.app.Activity;
import android.util.DisplayMetrics;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import com.google.android.gms.ads.AdRequest;
import com.google.android.gms.ads.AdSize;
import com.google.android.gms.ads.AdView;
import com.google.android.gms.ads.MobileAds;
import com.google.android.ump.ConsentInformation;
import com.google.android.ump.ConsentRequestParameters;
import com.google.android.ump.UserMessagingPlatform;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The banner at the bottom of the remote. Asks for ad consent first where the law requires it
 * (Google's consent form, set up in AdMob → Privacy &amp; messaging), then loads one anchored banner.
 * Debug builds and builds without real AdMob IDs show Google's test ads.
 */
final class Ads {
    private final Activity activity;
    private final FrameLayout container;
    private final ConsentInformation consent;
    private final AtomicBoolean started = new AtomicBoolean();
    private AdView banner;

    Ads(Activity activity, FrameLayout container) {
        this.activity = activity;
        this.container = container;
        this.consent = UserMessagingPlatform.getConsentInformation(activity);
    }

    /** Call once from onCreate. */
    void start() {
        if (!BuildConfig.ADS_ENABLED) return;
        ConsentRequestParameters params = new ConsentRequestParameters.Builder().build();
        consent.requestConsentInfoUpdate(activity, params,
                () -> UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity, formError -> {
                    if (consent.canRequestAds()) startAds();
                }),
                requestError -> {
                    // Offline or misconfigured: use the consent the user gave last time, if any.
                    if (consent.canRequestAds()) startAds();
                });
        // A returning user's earlier choice is enough to start right away.
        if (consent.canRequestAds()) startAds();
    }

    /** True where the user must be able to change their ad choices (EEA, UK, some US states). */
    boolean privacyOptionsRequired() {
        return BuildConfig.ADS_ENABLED && consent.getPrivacyOptionsRequirementStatus()
                == ConsentInformation.PrivacyOptionsRequirementStatus.REQUIRED;
    }

    void showPrivacyOptions() {
        UserMessagingPlatform.showPrivacyOptionsForm(activity, formError -> {
            if (consent.canRequestAds()) startAds();
        });
    }

    private void startAds() {
        if (!started.compareAndSet(false, true)) return;
        new Thread(() -> {
            MobileAds.initialize(activity, status -> { });
            activity.runOnUiThread(this::loadBanner);
        }, "ads-init").start();
    }

    private void loadBanner() {
        if (activity.isFinishing() || activity.isDestroyed()) return;
        DisplayMetrics metrics = activity.getResources().getDisplayMetrics();
        int widthDp = (int) (metrics.widthPixels / metrics.density);
        banner = new AdView(activity);
        banner.setAdUnitId(BuildConfig.ADMOB_BANNER_ID);
        banner.setAdSize(AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(activity, widthDp));
        container.removeAllViews();
        container.addView(banner, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        banner.loadAd(new AdRequest.Builder().build());
    }

    void resume() {
        if (banner != null) banner.resume();
    }

    void pause() {
        if (banner != null) banner.pause();
    }

    void destroy() {
        if (banner != null) banner.destroy();
        banner = null;
    }
}
