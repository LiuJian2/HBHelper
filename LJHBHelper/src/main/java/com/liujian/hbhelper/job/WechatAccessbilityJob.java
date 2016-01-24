package com.liujian.hbhelper.job;

import android.accessibilityservice.AccessibilityService;
import android.annotation.TargetApi;
import android.app.Notification;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.SoundPool;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import com.liujian.hbhelper.BuildConfig;
import com.liujian.hbhelper.Config;
import com.liujian.hbhelper.QiangHongBaoService;
import com.liujian.hbhelper.R;

import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.BlockingDeque;
import java.util.concurrent.LinkedBlockingQueue;

public class WechatAccessbilityJob extends BaseAccessbilityJob {

    public static boolean isScreenOff = false;

    private static final String TAG = "WechatAccessbilityJob";

    // 微信的包名
    private static final String WECHAT_PACKAGENAME = "com.tencent.mm";

    // 红包消息的关键字
    private static final String HONGBAO_TEXT_KEY = "[微信红包]";

    // 不能再使用文字匹配的最小版本号
    private static final int USE_ID_MIN_VERSION = 700; // 6.3.8 对应code为680,6.3.9对应code为700

    private boolean isSelf;
    private boolean isNotifi;

    private PackageInfo mWechatPackageInfo = null;
    private Handler mHandler = null;

    public static LinkedList<AccessibilityEvent> scrollQueue = new LinkedList<>();
    public static LinkedList<AccessibilityEvent> notifiQueue = new LinkedList<>();

    private int lastCount = 0;
    private int scrollCount = 0;
    private int lastAction;

//    private String lastActionClass;

    private Boolean isHBOpening = false;

    private boolean hasBack = false;

    private MyHBThread myHBThread;

    private SoundPool soundPool;
    private byte[] tempbytes;
    private AudioTrack trackplayer;
    private int bufsize;
    private int sourceIdGeneral;

    private BroadcastReceiver broadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            //更新安装包信息
            updatePackageInfo();
        }
    };

    @Override
    public void onCreateJob(QiangHongBaoService service) {
        super.onCreateJob(service);
        myHBThread = new MyHBThread();
        myHBThread.start();
        updatePackageInfo();
        IntentFilter filter = new IntentFilter();
        filter.addDataScheme("package");
        filter.addAction("android.intent.action.PACKAGE_ADDED");
        filter.addAction("android.intent.action.PACKAGE_REPLACED");
        filter.addAction("android.intent.action.PACKAGE_REMOVED");

        getContext().registerReceiver(broadcastReceiver, filter);

        if (getConfig().isEnableSound()) {
            bufsize = AudioTrack.getMinBufferSize(8000, AudioFormat.CHANNEL_CONFIGURATION_STEREO, AudioFormat.ENCODING_PCM_16BIT);
            trackplayer = new AudioTrack(AudioManager.STREAM_MUSIC,
                    8000,
                    AudioFormat.CHANNEL_CONFIGURATION_STEREO,
                    AudioFormat.ENCODING_PCM_16BIT, bufsize,
                    AudioTrack.MODE_STREAM);
            tempbytes = new byte[4096];

            soundPool = new SoundPool(1, AudioManager.STREAM_MUSIC, 0);
            sourceIdGeneral = soundPool.load(getService(), R.raw.diaoluo_da, 0);
        }
    }

    public void playSound() {
        if(getConfig().isEnableSound()) {
            soundPool.play(sourceIdGeneral, 1, 1, 0, 0, 1);
        }
    }

    @Override
    public void onStopJob() {
        try {
            getContext().unregisterReceiver(broadcastReceiver);
        } catch (Exception e) {
        }
    }

    @Override
    public boolean isEnable() {
        return getConfig().isEnableWechat();
    }

    @Override
    public String getTargetPackageName() {
        return WECHAT_PACKAGENAME;
    }

    @Override
    public synchronized void onReceiveJob(AccessibilityEvent event) {
        final int eventType = event.getEventType();
        boolean isHbEvent = isHBEvent(event);

        if (isHbEvent && isScreenOff) {
            PowerManager pm = (PowerManager)getService().getSystemService(Context.POWER_SERVICE);
            final PowerManager.WakeLock mWakelock = pm.newWakeLock(PowerManager.ACQUIRE_CAUSES_WAKEUP|PowerManager.SCREEN_DIM_WAKE_LOCK, "SimpleTimer");
            mWakelock.acquire();
            getHandler().postDelayed(new Runnable() {
                @Override
                public void run() {
                    mWakelock.release();
                }
            }, 3000);
        }
        if (isHbEvent) {
            playSound();
        }

        if (!myHBThread.isAlive()) {
            myHBThread = new MyHBThread();
            myHBThread.start();
        }

        Log.d("LJTAG", "isHbOpening " + isHBOpening + " isHBEvent " + isHbEvent + "  " + event);
        if (isHbEvent && eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
            if (lastAction == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED){
                isHBOpening = false;
                isNotifi = false;
            }
            if (isHBOpening) {   // 正在打开红包 当前红包入队列
                scrollQueue.push(event);
            } else {             // 直接打开红包
                openScrolledHongBao(event);
            }
        } else if (isHbEvent && eventType == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED) {
            if (isHBOpening) {
                notifiQueue.push(event);
            } else {
                lastCount = 0;
                isHBOpening = true;
                openNotify(event);
            }
        } else if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            openHongBao(event);
        }
//        else if (lastAction == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && "com.tencent.mm.plugin.luckymoney.ui.LuckyMoneyDetailUI".equals(lastActionClass)
//                && eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED && event.getItemCount() == 2) {
//            hasBack = true;
//            getHandler().postDelayed(new Runnable() {
//                @Override
//                public void run() {
//                    getService().performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK);
//                    hasBack = false;
//                }
//            }, 500);
//        }
        lastAction = eventType;
//        lastActionClass = event.getClassName().toString();
    }

    public boolean isHBEvent(AccessibilityEvent event) {
        boolean isHbEvent = false;
        switch (event.getEventType()) {
            case AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED: {
                List<CharSequence> texts = event.getText();
                if (!texts.isEmpty()) {
                    for (CharSequence t : texts) {
                        String text = String.valueOf(t);
                        if (text.equals("已发送")) {
                            isSelf = true;
                            openHongBao(event);
                        }
                        if (text.contains(HONGBAO_TEXT_KEY)) {
                            isHbEvent = true;
                            break;
                        }
                    }
                }
                break;
            }
            case AccessibilityEvent.TYPE_VIEW_SCROLLED: {
                AccessibilityNodeInfo nodeInfo = event.getSource();
                int tmpCount = event.getItemCount();
                if (nodeInfo != null && tmpCount != lastCount && "android.widget.ListView".equals(nodeInfo.getClassName())) {
                    AccessibilityNodeInfo possibleNode = nodeInfo.getChild(nodeInfo.getChildCount() - 1);
                    List<AccessibilityNodeInfo> list = possibleNode.findAccessibilityNodeInfosByText("领取红包");
                    AccessibilityNodeInfo tmpNode = null;
                    for (int i = 0; i < list.size(); i++) {
                        tmpNode = list.get(i);
                        if (tmpNode.getParent() != null && tmpNode.getParent().getChildCount() == 1) {
                            list.remove(i);
                            i--;
                        }
                    }
                    isHbEvent = !list.isEmpty();
                }
                break;
            }
        }
        return isHbEvent;
    }

    private void openScrolledHongBao(AccessibilityEvent event) {
        AccessibilityNodeInfo nodeInfo = event.getSource();
        int tmpCount = event.getItemCount();
        if (nodeInfo != null && tmpCount != lastCount && "android.widget.ListView".equals(nodeInfo.getClassName())) {
            lastCount = tmpCount;
            AccessibilityNodeInfo possibleNode = nodeInfo.getChild(nodeInfo.getChildCount() - 1);
            List<AccessibilityNodeInfo> list = possibleNode.findAccessibilityNodeInfosByText("领取红包");
            AccessibilityNodeInfo tmpNode = null;
            for (int i = 0; i < list.size(); i++) {
                tmpNode = list.get(i);
                if (tmpNode.getParent() != null && tmpNode.getParent().getChildCount() == 1) {
                    list.remove(i);
                    i--;
                }
            }
            if (!list.isEmpty()) {
                AccessibilityNodeInfo targetNode = list.get(0);
                if (targetNode != null) {
                    isHBOpening = true;
                    final AccessibilityNodeInfo n = targetNode.getParent();
                    long sDelayTime = getConfig().getWechatClickDelayTime();
                    if (sDelayTime != 0) {
                        getHandler().postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                n.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                            }
                        }, sDelayTime);
                    } else {
                        n.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                    }
                }
            }
        }
    }

    /**
     * 打开通知栏消息
     */
    @TargetApi(Build.VERSION_CODES.JELLY_BEAN)
    private void openNotify(AccessibilityEvent event) {
        if (event.getParcelableData() == null || !(event.getParcelableData() instanceof Notification)) {
            return;
        }

        //以下是精华，将微信的通知栏消息打开
        Notification notification = (Notification) event.getParcelableData();
        PendingIntent pendingIntent = notification.contentIntent;
        isNotifi = true;
        try {
            pendingIntent.send();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @TargetApi(Build.VERSION_CODES.JELLY_BEAN)
    private void openHongBao(AccessibilityEvent event) {
        if ("com.tencent.mm.plugin.luckymoney.ui.LuckyMoneyReceiveUI".equals(event.getClassName())) {
            handleLuckyMoneyReceive(event);
            long bDelayTime = getConfig().getWechatBackDelayTime() + 1000;
            hasBack = true;
            getHandler().postDelayed(new Runnable() {
                @Override
                public void run() {
                    getService().performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK);
                    hasBack = false;
                    Log.d("LJTAG", "Back Press");
                }
            }, bDelayTime);
        } else if ("com.tencent.mm.plugin.luckymoney.ui.LuckyMoneyDetailUI".equals(event.getClassName())) {
            if (!hasBack) {
                hasBack = true;
                getHandler().postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        getService().performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK);
                        hasBack = false;
                        Log.d("LJTAG", "Back Press");
                    }
                }, 500);
            }
            isHBOpening = false;
        } else if ("com.tencent.mm.ui.LauncherUI".equals(event.getClassName())) {
            handleChatListHongBao();
        }
    }

//    @TargetApi(Build.VERSION_CODES.JELLY_BEAN)
//    private void checkIsHongBaoScrolled(AccessibilityEvent event) {
//        int tmpCount = event.getItemCount();
//        AccessibilityNodeInfo nodeInfo = event.getSource();
//        if (nodeInfo != null && tmpCount != lastCount && "android.widget.ListView".equals(nodeInfo.getClassName())) {
//            lastCount = tmpCount;
//            AccessibilityNodeInfo possibleNode = nodeInfo.getChild(nodeInfo.getChildCount() - 1);
//            List<AccessibilityNodeInfo> list = possibleNode.findAccessibilityNodeInfosByText("领取红包");
//
//            AccessibilityNodeInfo targetNode = null, tmpNode = null;
//
//            //TODO 优化过滤
//            for (int i = 0; i < list.size(); i++) {
//                tmpNode = list.get(i);
//                if (tmpNode.getParent() != null && tmpNode.getParent().getChildCount() == 1) {
//                    list.remove(i);
//                    i--;
//                }
//            }
//
//            if (!list.isEmpty()) {
//                targetNode = list.get(list.size() - 1);
//            }
//            if (targetNode != null) {
//                final AccessibilityNodeInfo n = targetNode.getParent();
//                long sDelayTime = getConfig().getWechatOpenDelayTime();
//                if (sDelayTime != 0) {
//                    getHandler().postDelayed(new Runnable() {
//                        @Override
//                        public void run() {
//                            n.performAction(AccessibilityNodeInfo.ACTION_CLICK);
//                        }
//                    }, sDelayTime);
//                } else {
//                    n.performAction(AccessibilityNodeInfo.ACTION_CLICK);
//                }
//            }
//        }
//    }

    /**
     * 点击聊天里的红包后，显示的界面
     */
    @TargetApi(Build.VERSION_CODES.JELLY_BEAN)
    private void handleLuckyMoneyReceive(final AccessibilityEvent mEvent) {
        AccessibilityNodeInfo nodeInfo = getService().getRootInActiveWindow();
        //TODO 根据Event去查找按钮
        if (nodeInfo == null) {
            Log.w(TAG, "rootWindow为空");
            return;
        }

        AccessibilityNodeInfo targetNode = null;

        List<AccessibilityNodeInfo> list = null;
        int event = getConfig().getWechatAfterOpenHongBaoEvent();
        if (event == Config.WX_AFTER_OPEN_HONGBAO) { //拆红包
            if (getWechatVersion() < USE_ID_MIN_VERSION) {
                list = nodeInfo.findAccessibilityNodeInfosByText("拆红包");
            } else {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
                    list = nodeInfo.findAccessibilityNodeInfosByViewId("com.tencent.mm:id/b2c");
                }
                if (list == null || list.isEmpty()) {
                    List<AccessibilityNodeInfo> l = nodeInfo.findAccessibilityNodeInfosByText("给你发了一个红包");
                    if (l != null && !l.isEmpty()) {
                        AccessibilityNodeInfo p = l.get(0).getParent();
                        if (p != null) {
                            for (int i = 0; i < p.getChildCount(); i++) {
                                AccessibilityNodeInfo node = p.getChild(i);
                                if ("android.widget.Button".equals(node.getClassName())) {
                                    targetNode = node;
                                    break;
                                }
                            }
                        }
                    }
                }
            }
        } else if (event == Config.WX_AFTER_OPEN_SEE) { //看一看
            if (getWechatVersion() < USE_ID_MIN_VERSION) { //低版本才有 看大家手气的功能
                list = nodeInfo.findAccessibilityNodeInfosByText("看看大家的手气");
            }
        }

        if (list != null && !list.isEmpty()) {
            targetNode = list.get(0);
        }

        if (targetNode != null) {
            final AccessibilityNodeInfo n = targetNode;
            long sDelayTime = getConfig().getWechatOpenDelayTime();
            if (sDelayTime != 0) {
                getHandler().postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        n.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                    }
                }, sDelayTime);
            } else {
                n.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            }
        }
    }

    class MyHBThread extends Thread {
        @Override
        public void run() {
            while (true) {
                if (isHBOpening) {
                    try {
                        sleep(15);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                } else {
                    if (!scrollQueue.isEmpty()) {
                        scrollCount = scrollQueue.size() + 1;
                        scrollQueue.pop();
                        handleChatListHongBao();
                        continue;
                    }
                    if (!notifiQueue.isEmpty()) {
                        onReceiveJob(notifiQueue.pop());
                    }
                }
            }
        }
    }

    /**
     * 收到聊天里的红包
     */
    @TargetApi(Build.VERSION_CODES.JELLY_BEAN_MR2)
    private void handleChatListHongBao() {
        AccessibilityNodeInfo nodeInfo = getService().getRootInActiveWindow();
        if (nodeInfo == null) {
            Log.w(TAG, "rootWindow为空");
            return;
        }

        List<AccessibilityNodeInfo> list = null;
        if (isSelf) {
            list = nodeInfo.findAccessibilityNodeInfosByText("查看红包");
        } else {
            list = nodeInfo.findAccessibilityNodeInfosByText("领取红包");
        }

        if (list != null && list.isEmpty()) {
            // 从消息列表查找红包
            list = nodeInfo.findAccessibilityNodeInfosByText("[微信红包]");

            if (list == null || list.isEmpty()) {
                return;
            }

            for (AccessibilityNodeInfo n : list) {
                if (BuildConfig.DEBUG) {
                    Log.i(TAG, "-->微信红包:" + n);
                }
                n.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                break;
            }
        } else if (list != null) {
            if (isSelf) {
                scrollCount = 1;
                isSelf = false;
            }
            if (isNotifi) {
                scrollCount = 1;
                isNotifi = false;
            }
            if (scrollCount > list.size()) {
                scrollCount = list.size();
            }

            Log.d("LJTAG", "ListSize " + list.size() + "  Count: " + scrollCount);
            for (int i = list.size() - scrollCount; i < list.size(); i++) {
                AccessibilityNodeInfo parent = list.get(i).getParent();
                if (parent != null) {

                    final AccessibilityNodeInfo n = parent;
                    long sDelayTime = getConfig().getWechatClickDelayTime();
                    if (sDelayTime != 0) {
                        getHandler().postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                n.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                            }
                        }, sDelayTime);
                    } else {
                        n.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                    }

                    if (BuildConfig.DEBUG) {
                        Log.i(TAG, "-->领取红包:" + parent);
                    }
                    scrollCount = 0;
                    isHBOpening = true;
                    break;
                }
            }
        } else {
            isHBOpening = false;
        }
    }

    private Handler getHandler() {
        if (mHandler == null) {
            mHandler = new Handler(Looper.getMainLooper());
        }
        return mHandler;
    }

    // 获取微信的版本
    private int getWechatVersion() {
        if (mWechatPackageInfo == null) {
            return 0;
        }
        return mWechatPackageInfo.versionCode;
    }

    // 更新微信包信息
    private void updatePackageInfo() {
        try {
            mWechatPackageInfo = getContext().getPackageManager().getPackageInfo(WECHAT_PACKAGENAME, 0);
        } catch (PackageManager.NameNotFoundException e) {
            e.printStackTrace();
        }
    }
}
