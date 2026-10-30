package com.locknow;

import android.app.Activity;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

public class LockActivity extends Activity {
    private static final int REQ_ADMIN = 1;
    private DevicePolicyManager dpm;
    private ComponentName admin;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        dpm = (DevicePolicyManager) getSystemService(Context.DEVICE_POLICY_SERVICE);
        admin = new ComponentName(this, AdminReceiver.class);
        if (dpm.isAdminActive(admin)) {
            lockAndFinish();
        } else {
            Intent intent = new Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN);
            intent.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, admin);
            intent.putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                    "Lock needs device admin permission so it can lock the screen the moment you open it. It does nothing else.");
            startActivityForResult(intent, REQ_ADMIN);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_ADMIN) {
            if (dpm.isAdminActive(admin)) {
                lockAndFinish();
            } else {
                finish();
            }
        }
    }

    private void lockAndFinish() {
        try {
            dpm.lockNow();
        } catch (Exception ignored) {
        }
        finish();
    }
}
