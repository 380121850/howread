package com.foobnix;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.widget.Toast;

import com.foobnix.android.utils.Cursors;
import com.foobnix.android.utils.LOG;
import com.foobnix.android.utils.MyMath;
import com.foobnix.android.utils.TxtUtils;
import com.foobnix.dao2.FileMeta;
import com.foobnix.mobi.parser.IOUtils;
import com.foobnix.model.AppProfile;
import com.foobnix.model.AppData;
import com.foobnix.model.AppSP;
import com.foobnix.model.SimpleMeta;
import com.foobnix.pdf.info.Clouds;
import com.foobnix.ui2.AppDB;
import com.foobnix.model.AppState;
import com.foobnix.pdf.info.Android6;
import com.foobnix.pdf.info.ExtUtils;
import com.foobnix.pdf.info.R;
import com.foobnix.ui2.MyContextWrapper;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

public class OpenerActivity extends Activity {
    public static String TAG = "OpenerActivity";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        LOG.bench("op01 onCreate begin");
        // Plan C: Opener is a pure trampoline (no UI of its own). Give it a
        // fully transparent window so the previous app stays visible until the
        // reader's first frame, instead of an opaque HowRead beat + transition.
        setTheme(android.R.style.Theme_Translucent_NoTitleBar);

        LOG.bench("op02 theme+AppState done");
        super.onCreate(savedInstanceState);


//        if (!Android6.canWrite(this)) {
//            Android6.checkPermissions(this, true);
//            return;
//        }

        LOG.bench("op03 after-super");
        handleIntent();
    }

    /**
     * singleTask reuse: a VIEW intent delivered while this instance is still
     * alive (finish is pending in onPause/onStop) arrives here, not in
     * onCreate — without this the warm-state open was silently ignored.
     */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent();
    }

    private void handleIntent() {
        LOG.bench("op04 handleIntent begin");
        if (getIntent() == null) {
            Toast.makeText(this, R.string.msg_unexpected_error, Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        LOG.d(TAG, getIntent());
        // file resolution lives in OpenerIntents since batch 2 (2026-10-21):
        // VerticalViewActivity resolves system VIEW intents through the very
        // same code, so the two entries cannot drift apart
        final File file = OpenerIntents.resolve(this, getIntent());
        if (file == null) {
            finish();
            return;
        }
        LOG.d(TAG, "Open file", file);
        OpenerIntents.autoAddToLibrary(this, file);
        ExtUtils.openFile(this, new FileMeta(file.getPath()));
    }

    @Override
    protected void attachBaseContext(Context context) {
        super.attachBaseContext(MyContextWrapper.wrap(context));
    }


    @Override
    public void onRequestPermissionsResult(int requestCode, String permissions[],
                                           int[] grantResults) {
        Android6.onRequestPermissionsResult(this, requestCode, permissions, grantResults);
    }

    @Override
    protected void onPause() {
        super.onPause();
        finish();
    }

    @Override
    protected void onStop() {
        super.onStop();
        finish();
    }
}
