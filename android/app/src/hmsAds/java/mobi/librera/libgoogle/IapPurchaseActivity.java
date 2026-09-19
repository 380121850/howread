package mobi.librera.libgoogle;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Toast;

import com.foobnix.android.utils.LOG;
import com.foobnix.model.AppProfile;
import com.foobnix.model.AppSP;
import com.foobnix.pdf.info.R;
import com.huawei.hmf.tasks.Task;
import com.huawei.hms.iap.Iap;
import com.huawei.hms.iap.IapApiException;
import com.huawei.hms.iap.IapClient;
import com.huawei.hms.iap.entity.InAppPurchaseData;
import com.huawei.hms.iap.entity.IsEnvReadyResult;
import com.huawei.hms.iap.entity.OrderStatusCode;
import com.huawei.hms.iap.entity.PurchaseIntentReq;
import com.huawei.hms.iap.entity.PurchaseIntentResult;
import com.huawei.hms.iap.entity.PurchaseResultInfo;
import com.huawei.hms.support.api.client.Status;

/**
 * Transparent helper owning the HMS IAP cashier round-trip, so no call-site
 * Activity needs an onActivityResult override (the Pro card lives in two
 * places — preferences bottom card and the drawer about dialog).
 *
 * Flow: isEnvReady (60000 → startResolutionForResult) → createPurchaseIntent
 * (non-consumable pro product) → startResolutionForResult →
 * onActivityResult → parsePurchaseResultInfoFromIntent → PURCHASED →
 * cache the unlock in AppSP → run the pending UI refresh → finish.
 *
 * Non-consumables need no acknowledge/consume step in HMS IAP.
 */
public class IapPurchaseActivity extends Activity {

    private static final String TAG = "HW-IAP";
    private static final int REQUEST_ENV_READY = 7001;
    private static final int REQUEST_PURCHASE = 7002;

    /** Pending UI refresh from the launching screen (set by launch()). */
    private static volatile Runnable sPendingRefresh;

    static void launch(Activity from, Runnable onRefresh) {
        sPendingRefresh = onRefresh;
        from.startActivity(new Intent(from, IapPurchaseActivity.class));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        checkEnvAndPurchase();
    }

    private void checkEnvAndPurchase() {
        try {
            Task<IsEnvReadyResult> task = Iap.getIapClient(this).isEnvReady();
            task.addOnSuccessListener(result -> startPurchase()).addOnFailureListener(e -> {
                try {
                    if (e instanceof IapApiException) {
                        Status status = ((IapApiException) e).getStatus();
                        if (status != null && status.hasResolution()) {
                            // e.g. IAP not activated yet — HMS shows the fix dialog
                            status.startResolutionForResult(this, REQUEST_ENV_READY);
                            return;
                        }
                    }
                } catch (Throwable t) {
                    LOG.e(t);
                }
                LOG.d(TAG, "IAP env not ready");
                LOG.e(e);
                Toast.makeText(this, R.string.pro_iap_unavailable, Toast.LENGTH_SHORT).show();
                finish();
            });
        } catch (Throwable e) {
            LOG.e(e);
            Toast.makeText(this, R.string.pro_iap_unavailable, Toast.LENGTH_SHORT).show();
            finish();
        }
    }

    private void startPurchase() {
        try {
            PurchaseIntentReq req = new PurchaseIntentReq();
            req.setProductId(HuaweiBillingDelegate.getProductId(this));
            req.setPriceType(IapClient.PriceType.IN_APP_NONCONSUMABLE);
            Task<PurchaseIntentResult> task = Iap.getIapClient(this).createPurchaseIntent(req);
            task.addOnSuccessListener(result -> {
                try {
                    Status status = result == null ? null : result.getStatus();
                    if (status != null && status.hasResolution()) {
                        status.startResolutionForResult(this, REQUEST_PURCHASE);
                    } else {
                        finish();
                    }
                } catch (Throwable e) {
                    LOG.e(e);
                    finish();
                }
            }).addOnFailureListener(e -> {
                LOG.d(TAG, "createPurchaseIntent failed");
                LOG.e(e);
                Toast.makeText(this, R.string.pro_iap_unavailable, Toast.LENGTH_SHORT).show();
                finish();
            });
        } catch (Throwable e) {
            LOG.e(e);
            finish();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_ENV_READY) {
            if (resultCode == RESULT_OK) {
                startPurchase();
            } else {
                finish();
            }
            return;
        }
        if (requestCode != REQUEST_PURCHASE) {
            return;
        }
        try {
            if (data == null) {
                // user backed out of the cashier — nothing to record
                finish();
                return;
            }
            PurchaseResultInfo info = Iap.getIapClient(this).parsePurchaseResultInfoFromIntent(data);
            if (info == null || info.getReturnCode() != OrderStatusCode.ORDER_STATE_SUCCESS) {
                LOG.d(TAG, "purchase not completed", info == null ? -1 : info.getReturnCode());
                finish();
                return;
            }
            InAppPurchaseData d = new InAppPurchaseData(info.getInAppPurchaseData());
            if (d.getPurchaseState() == InAppPurchaseData.PurchaseState.PURCHASED) {
                AppSP.get().iapProUnlocked = true;
                AppSP.get().iapPurchaseTime = d.getPurchaseTime() > 0 ? d.getPurchaseTime() : System.currentTimeMillis();
                AppSP.get().iapOrderId = d.getOrderID() == null ? "" : d.getOrderID();
                AppProfile.save(this);
                Toast.makeText(this, R.string.pro_toast_purchased_real, Toast.LENGTH_SHORT).show();
                Runnable r = sPendingRefresh;
                sPendingRefresh = null;
                if (r != null) {
                    r.run();
                }
            }
        } catch (Throwable e) {
            LOG.e(e);
        }
        finish();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        sPendingRefresh = null;
    }
}
