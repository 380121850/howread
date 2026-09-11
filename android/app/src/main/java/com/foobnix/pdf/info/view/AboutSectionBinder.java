package com.foobnix.pdf.info.view;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.view.LayoutInflater;
import android.view.View;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.TextView;
import android.widget.Toast;

import com.foobnix.android.utils.Apps;
import com.foobnix.android.utils.LOG;
import com.foobnix.android.utils.TxtUtils;
import com.foobnix.pdf.info.AndroidWhatsNew;
import com.foobnix.pdf.info.R;
import com.foobnix.pdf.info.TintUtil;
import com.foobnix.pdf.info.Urls;

/**
 * Binds the shared about_section layout (version header pill, Librera pro,
 * changelog, licence, support mail, web and rate links). Used by both the
 * bottom of the preferences page and the drawer 软件说明 dialog so the two
 * surfaces look and behave identically.
 */
public class AboutSectionBinder {

    /**
     * The drawer-style 软件说明 entry point: a collapsed row opens this
     * dialog with the full about_section (version pill, changelog, licences,
     * support links).
     */
    public static void showDialog(final Activity a) {
        View content = LayoutInflater.from(a).inflate(R.layout.dialog_about, null);
        bind(a, content);
        new AlertDialog.Builder(a)
                .setView(content)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    public static void bind(final Activity a, View root) {
        try {
            PackageInfo packageInfo = a.getPackageManager().getPackageInfo(a.getPackageName(), 0);
            String version = packageInfo.versionName + "";
            ((TextView) root.findViewById(R.id.pVersion)).setText(
                    String.format("%s: %s", a.getString(R.string.version), version));
            TextView section6 = root.findViewById(R.id.section6);
            section6.setText(String.format("%s: v%s build %s",
                    Apps.getApplicationName(a), version, Apps.getBuildTime(a)));
            TintUtil.setBackgroundFillColor(section6, TintUtil.color);
        } catch (PackageManager.NameNotFoundException e) {
            LOG.e(e);
        }

        TextView whatIsNew = root.findViewById(R.id.whatIsNew);
        whatIsNew.setText(a.getString(R.string.what_is_new_in) + " " + Apps.getApplicationName(a)
                + " v" + Apps.getVersionName(a));
        TxtUtils.underlineTextView(whatIsNew);
        whatIsNew.setOnClickListener(v -> AndroidWhatsNew.show2(a));

        TextView licenses = root.findViewById(R.id.libraryLicenses);
        TxtUtils.underlineTextView(licenses);
        licenses.setOnClickListener(v -> showLicenses(a));

        TextView onMail = root.findViewById(R.id.onMailSupport);
        onMail.setText(TxtUtils.underline(a.getString(R.string.my_email)));
        onMail.setOnClickListener(v -> onEmailSupport(a));

        TextView openWeb = root.findViewById(R.id.openWeb);
        // the project site is intentionally left empty — hide the link when unset
        if (TxtUtils.isEmpty(a.getString(R.string.my_site))) {
            openWeb.setVisibility(View.GONE);
        } else {
            TxtUtils.underlineTextView(openWeb);
            openWeb.setOnClickListener(v -> Urls.open(a, a.getString(R.string.my_site)));
        }

        TextView rateIt = root.findViewById(R.id.onRateIt);
        TxtUtils.underlineTextView(rateIt);
        rateIt.setOnClickListener(v -> Urls.rateIT(a));

        bindProCard(a, root);
    }

    /**
     * Pro card (moved here from the preferences page): purchase entry,
     * long-press the active button = STUB refund, restore / manage links.
     */
    private static void bindProCard(final Activity a, View root) {
        final TextView proUpgradeBtn = root.findViewById(R.id.proUpgradeBtn);
        final TextView proUpgradeHint = root.findViewById(R.id.proUpgradeHint);
        final View proActiveLinks = root.findViewById(R.id.proActiveLinks);
        if (proUpgradeBtn == null) {
            return;
        }
        final Runnable refresh = () -> refreshProCard(a, proUpgradeBtn, proUpgradeHint, proActiveLinks);
        refresh.run();
        proUpgradeBtn.setOnClickListener(v -> mobi.librera.libgoogle.BillingManager.launchPurchaseFlow(a, refresh));
        proUpgradeBtn.setOnLongClickListener(v -> {
            if (com.foobnix.pdf.info.AppsConfig.isProFlavor()
                    && mobi.librera.libgoogle.BillingManager.isProUnlocked()) {
                mobi.librera.libgoogle.BillingManager.simulateRefund(a, refresh);
                return true;
            }
            return false;
        });
        View restore = root.findViewById(R.id.proRestoreBtn);
        if (restore != null) {
            restore.setOnClickListener(v -> mobi.librera.libgoogle.BillingManager.restorePurchases(a, refresh));
        }
        View manage = root.findViewById(R.id.proManageBtn);
        if (manage != null) {
            manage.setOnClickListener(v -> mobi.librera.libgoogle.BillingManager.manageEntitlements(a));
        }
    }

    /** 未解锁态（升级按钮+提示）与已激活态（信息+恢复/管理链接）切换 */
    private static void refreshProCard(Activity a, TextView btn, TextView hint, View activeLinks) {
        if (mobi.librera.libgoogle.BillingManager.isProUnlocked()) {
            btn.setText(R.string.pro_btn_active);
            hint.setText(a.getString(R.string.pro_active_info,
                    mobi.librera.libgoogle.BillingManager.getChannelText(a),
                    mobi.librera.libgoogle.BillingManager.getPurchaseTimeText(a),
                    mobi.librera.libgoogle.BillingManager.getOrderIdSuffix()));
            if (activeLinks != null) {
                activeLinks.setVisibility(View.VISIBLE);
            }
        } else {
            btn.setText(com.foobnix.pdf.info.AppsConfig.isProFlavor() ? R.string.pro_upgrade_btn
                                                                      : R.string.pro_upgrade_btn_fdroid);
            hint.setText(R.string.pro_hint_locked);
            if (activeLinks != null) {
                activeLinks.setVisibility(View.GONE);
            }
        }
    }

    public static void showLicenses(final Activity a) {
        AlertDialog.Builder alert = new AlertDialog.Builder(a);
        alert.setTitle(R.string.licenses_for_libraries);
        WebView wv = new WebView(a);
        wv.loadUrl("file:///android_asset/licenses.html");
        wv.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, String url) {
                view.loadUrl(url);
                return true;
            }
        });
        alert.setView(wv);
        alert.setNegativeButton(R.string.close, (dialog, id) -> dialog.dismiss());
        AlertDialog dialog = alert.create();
        // release the WebView instead of leaking it on every open
        dialog.setOnDismissListener(d -> {
            wv.loadUrl("about:blank");
            wv.destroy();
        });
        dialog.show();
    }

    public static void onEmailSupport(final Activity a) {
        Intent emailIntent = new Intent(Intent.ACTION_SEND);
        String address = a.getString(R.string.my_email).replace("<u>", "").replace("</u>", "");
        emailIntent.putExtra(Intent.EXTRA_EMAIL, new String[]{address});
        emailIntent.putExtra(Intent.EXTRA_SUBJECT,
                Apps.getApplicationName(a) + " " + Apps.getVersionName(a));
        emailIntent.setType("plain/text");
        emailIntent.putExtra(Intent.EXTRA_TEXT, "Hi Support, ");
        try {
            a.startActivity(Intent.createChooser(emailIntent, a.getString(R.string.send_mail)));
        } catch (android.content.ActivityNotFoundException ex) {
            Toast.makeText(a, R.string.there_are_no_email_applications_installed_, Toast.LENGTH_SHORT).show();
        }
    }
}
