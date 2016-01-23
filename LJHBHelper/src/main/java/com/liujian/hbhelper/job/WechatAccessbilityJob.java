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
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import com.liujian.hbhelper.BuildConfig;
import com.liujian.hbhelper.Config;
import com.liujian.hbhelper.QiangHongBaoService;

import java.util.List;
import java.util.concurrent.BlockingDeque;
import java.util.concurrent.LinkedBlockingQueue;

public class WechatAccessbilityJob extends BaseAccessbilityJob {

    private static final String TAG = "WechatAccessbilityJob";

    /**
     * 微信的包名
     */
    private static final String WECHAT_PACKAGENAME = "com.tencent.mm";

    /**
     * 红包消息的关键字
     */
    private static final String HONGBAO_TEXT_KEY = "[微信红包]";

    /**
     * 不能再使用文字匹配的最小版本号
     */
    private static final int USE_ID_MIN_VERSION = 700;// 6.3.8 对应code为680,6.3.9对应code为700

    private boolean isFirstChecked;
    private boolean isSelf;

    private PackageInfo mWechatPackageInfo = null;
    private Handler mHandler = null;

    private int lastCount = 0;

    public static LinkedBlockingQueue<AccessibilityEvent> queue = new LinkedBlockingQueue<>();

    private Integer count = 0;

    private Integer lock = 0;

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

        updatePackageInfo();

        IntentFilter filter = new IntentFilter();
        filter.addDataScheme("package");
        filter.addAction("android.intent.action.PACKAGE_ADDED");
        filter.addAction("android.intent.action.PACKAGE_REPLACED");
        filter.addAction("android.intent.action.PACKAGE_REMOVED");

        getContext().registerReceiver(broadcastReceiver, filter);
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
        //通知栏事件
        if (eventType == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED) {
            List<CharSequence> texts = event.getText();
            if (!texts.isEmpty()) {
                for (CharSequence t : texts) {
                    String text = String.valueOf(t);
                    if (text.equals("已发送")) {
                        count++;
                        isSelf = true;
                        isFirstChecked = true;
                    }
                    if (text.contains(HONGBAO_TEXT_KEY)) {
                        addToHongBaoEvent(event);
                        count++;
                        break;
                    }
                }
            }
        } else if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            openHongBao(event);
        } else if (eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
            checkIsHongBaoScrolled(event);
        }
    }

    private void addToHongBaoEvent(AccessibilityEvent event) {
        try {
            queue.put(event);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    private void getHongBao() {
        while (true) {
            try {
                if (lock == 0) {
                    lock = 1;
                    AccessibilityEvent event = queue.take();
                    doJob(event);
                } else {
                    Thread.sleep(10);
                }
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }

    private void doJob(AccessibilityEvent event) {
        if (event.getAction() == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED) {
            openNotify(event);
        } else if (event.getAction() == AccessibilityEvent.TYPE_VIEW_SCROLLED) {

        } else if (event.getAction() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED){

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

        isFirstChecked = true;
        try {
            pendingIntent.send();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @TargetApi(Build.VERSION_CODES.JELLY_BEAN)
    private void openHongBao(AccessibilityEvent event) {
        if ("com.tencent.mm.plugin.luckymoney.ui.LuckyMoneyReceiveUI".equals(event.getClassName())) {
            //点中了红包，下一步就是去拆红包
            handleLuckyMoneyReceive(event);
        } else if ("com.tencent.mm.plugin.luckymoney.ui.LuckyMoneyDetailUI".equals(event.getClassName())) {
            //拆完红包后看详细的纪录界面
            long bDelayTime = getConfig().getWechatBackDelayTime();
            getHandler().postDelayed(new Runnable() {
                @Override
                public void run() {
                    getService().performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK);
                    Log.d("LJTAG", "Back Press");
                }
            }, bDelayTime);
        } else if ("com.tencent.mm.ui.LauncherUI".equals(event.getClassName())) {
            //在聊天界面,去点中红包
            handleChatListHongBao();
        }
    }

    @TargetApi(Build.VERSION_CODES.JELLY_BEAN)
    private void checkIsHongBaoScrolled(AccessibilityEvent event) {
        int tmpCount = event.getItemCount();
        AccessibilityNodeInfo nodeInfo = event.getSource();
        if (nodeInfo != null && tmpCount != lastCount && "android.widget.ListView".equals(nodeInfo.getClassName())) {
            lastCount = tmpCount;
            AccessibilityNodeInfo possibleNode = nodeInfo.getChild(nodeInfo.getChildCount() - 1);
            List<AccessibilityNodeInfo> list = possibleNode.findAccessibilityNodeInfosByText("领取红包");

            AccessibilityNodeInfo targetNode = null, tmpNode = null;

            //TODO 优化过滤
            for (int i = 0; i < list.size(); i++) {
                tmpNode = list.get(i);
                if (tmpNode.getParent() != null && tmpNode.getParent().getChildCount() == 1) {
                    list.remove(i);
                    i--;
                }
            }

            if (!list.isEmpty()) {
                targetNode = list.get(list.size() - 1);
            }
            if (targetNode != null) {
                count++;
                final AccessibilityNodeInfo n = targetNode.getParent();
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
    }

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
        isSelf = false;

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
            //最新的红包领起
            if (count >= list.size()) {
                count = list.size() - 1;
            }
            Log.d("LJTAG", "ListSize " + list.size() + "  Count: " + count);
            for (int i = list.size() - count; i < list.size(); i++) {
//                for (int i = list.size() - count - 1; i >= 0; i--) {
                AccessibilityNodeInfo parent = list.get(i).getParent();
                if (parent != null) {
                    if (isFirstChecked) {
                        parent.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                        if (count == 0)
                            isFirstChecked = false;
                        count--;
                        if (count < 0)
                            count = 0;
                        if (BuildConfig.DEBUG) {
                            Log.i(TAG, "-->领取红包:" + parent);
                        }
                    }
                    break;
                }
            }
        }
    }

    private Handler getHandler() {
        if (mHandler == null) {
            mHandler = new Handler(Looper.getMainLooper());
        }
        return mHandler;
    }

    /**
     * 获取微信的版本
     */
    private int getWechatVersion() {
        if (mWechatPackageInfo == null) {
            return 0;
        }
        return mWechatPackageInfo.versionCode;
    }

    /**
     * 更新微信包信息
     */
    private void updatePackageInfo() {
        try {
            mWechatPackageInfo = getContext().getPackageManager().getPackageInfo(WECHAT_PACKAGENAME, 0);
        } catch (PackageManager.NameNotFoundException e) {
            e.printStackTrace();
        }
    }
}
