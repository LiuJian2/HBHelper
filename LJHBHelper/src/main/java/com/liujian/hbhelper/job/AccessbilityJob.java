package com.liujian.hbhelper.job;

import android.view.accessibility.AccessibilityEvent;

import com.liujian.hbhelper.QiangHongBaoService;

public interface AccessbilityJob {
    String getTargetPackageName();

    void onCreateJob(QiangHongBaoService service);

    void onReceiveJob(AccessibilityEvent event);

    void onStopJob();

    boolean isEnable();
}
