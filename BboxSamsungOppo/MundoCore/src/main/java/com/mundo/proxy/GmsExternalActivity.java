package com.mundo.proxy;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.ResultReceiver;

/**
 * Host-process trampoline that launches real GMS/Google-Play intents on behalf
 * of a virtual guest app, then relays the activity result back into the virtual
 * space via the ResultReceiver supplied by ActivityManagerCommonProxy.
 *
 * Flow:
 *   guest startActivityForResult(gmsIntent, RC)
 *   → StartActivity.hook() wraps it here + attaches ResultReceiver
 *   → GmsExternalActivity.startActivityForResult(realGmsIntent, 1)
 *   → user completes Google sign-in
 *   → onActivityResult fires HERE (host process)
 *   → ResultReceiver.send() → Binder IPC back to guest process
 *   → BActivityManager.sendActivityResult() delivers to guest onActivityResult
 */
public class GmsExternalActivity extends Activity {

    public static final String KEY_TARGET   = "gea_target";
    public static final String KEY_RECEIVER = "gea_receiver";

    private ResultReceiver mReceiver;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Intent wrapped = getIntent();
        Intent target = wrapped.getParcelableExtra(KEY_TARGET);
        mReceiver = wrapped.getParcelableExtra(KEY_RECEIVER);

        if (target == null || mReceiver == null) {
            finish();
            return;
        }
        try {
            startActivityForResult(target, 1);
        } catch (Exception e) {
            mReceiver.send(RESULT_CANCELED, new Bundle());
            finish();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (mReceiver != null) {
            Bundle bundle = new Bundle();
            if (data != null) {
                bundle.putParcelable("data", data);
            }
            mReceiver.send(resultCode, bundle);
        }
        finish();
    }
}
