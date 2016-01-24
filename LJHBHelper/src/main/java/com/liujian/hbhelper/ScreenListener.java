package com.liujian.hbhelper;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import com.liujian.hbhelper.job.WechatAccessbilityJob;

/**
 * Created by LJ on 2016/1/23.
 */
public class ScreenListener extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent.getAction().equals(Intent.ACTION_SCREEN_OFF)) {
            WechatAccessbilityJob.isScreenOff = true;
        }
        if (intent.getAction().equals(Intent.ACTION_SCREEN_ON)) {
            WechatAccessbilityJob.isScreenOff = false;
        }
    }
}
