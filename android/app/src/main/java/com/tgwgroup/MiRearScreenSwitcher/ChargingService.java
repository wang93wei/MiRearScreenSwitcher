/*
 * Author: AntiOblivionis
 * QQ: 319641317
 * Github: https://github.com/GoldenglowSusie/
 * Bilibili: 罗德岛T0驭械术师澄闪
 * 
 * Chief Tester: 汐木泽
 * 
 * Co-developed with AI assistants:
 * - Cursor
 * - Claude-4.5-Sonnet
 * - GPT-5
 * - Gemini-2.5-Pro
 */

package com.tgwgroup.MiRearScreenSwitcher;

import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.os.BatteryManager;
import android.os.PowerManager;
import android.os.IBinder;
import android.util.Log;

import rikka.shizuku.Shizuku;

/**
 * 充电状态监听服务
 * 监听电源连接事件，插电后在背屏显示充电电量动画
 */
public class ChargingService extends Service {
    private static final String TAG = "ChargingService";
    private SharedPreferences prefs;
    private ITaskService taskService;
    private PowerManager.WakeLock wakeLock;
    
    // 静态实例，供RearScreenChargingActivity访问
    private static ChargingService instance;
    
    // 防止重复触发动画（冷却时间）
    private long lastChargingAnimationTime = 0;
    private static final long CHARGING_ANIMATION_COOLDOWN_MS = 6000; // 6秒冷却时间    
    public static ITaskService getTaskService() {
        return instance != null ? instance.taskService : null;
    }
    
    private final Shizuku.UserServiceArgs serviceArgs = 
        new Shizuku.UserServiceArgs(new ComponentName("com.tgwgroup.MiRearScreenSwitcher", TaskService.class.getName()))
            .daemon(false)
            .processNameSuffix("charging_task_service")
            .debuggable(false)
            .version(1);
    
    private final ServiceConnection taskServiceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            Log.d(TAG, "✓ TaskService connected");
            taskService = ITaskService.Stub.asInterface(binder);
            
            // 初始化显示屏信息缓存
            try {
                DisplayInfoCache.getInstance().initialize(taskService);
            } catch (Exception e) {
                Log.w(TAG, "初始化显示屏缓存失败: " + e.getMessage());
            }
        }
        
        @Override
        public void onServiceDisconnected(ComponentName name) {
            Log.d(TAG, "✗ TaskService disconnected");
            taskService = null;
            // 自动重连
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                if (taskService == null) {
                    bindTaskService();
                }
            }, 1000);
        }
    };
    
    // Shizuku监听器
    private final Shizuku.OnBinderReceivedListener binderReceivedListener = 
        () -> {
            Log.d(TAG, "Shizuku binder received");
            bindTaskService();
        };
    
    private final Shizuku.OnBinderDeadListener binderDeadListener = 
        () -> {
            Log.d(TAG, "Shizuku binder dead");
            taskService = null;
            // 尝试重连
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                bindTaskService();
            }, 1000);
        };
    
    private BroadcastReceiver batteryReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            
            if (Intent.ACTION_POWER_CONNECTED.equals(action)) {
                // 检查开关状态
                boolean enabled = prefs.getBoolean("charging_animation_enabled", true);
                if (!enabled) {
                    Log.d(TAG, "Charging animation disabled");
                    return;
                }
                
                // 检查冷却时间（防止重复触发）
                long currentTime = System.currentTimeMillis();
                if (currentTime - lastChargingAnimationTime < CHARGING_ANIMATION_COOLDOWN_MS) {
                    Log.d(TAG, "⏸ Charging animation in cooldown, skipping");
                    return;
                }
                
                // 检查屏幕锁定状态
                android.app.KeyguardManager km = (android.app.KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
                boolean isLocked = km != null && km.isKeyguardLocked();
                
                if (isLocked) {
                    Log.d(TAG, "🔓 Screen is locked, will show charging animation with screen sleep");
                } else {
                    Log.d(TAG, "🔓 Screen is unlocked, will show charging animation without screen sleep");
                }
                
                int batteryLevel = getBatteryLevel(context);
                Log.d(TAG, "🔌 Power connected, battery: " + batteryLevel + "%");
                
                // 记录触发时间
                lastChargingAnimationTime = currentTime;
                
                // 通知动画管理器：开始充电动画（返回被打断的旧动画）
                RearAnimationManager.AnimationType oldAnim = RearAnimationManager.startAnimation(RearAnimationManager.AnimationType.CHARGING);
                
                // 如果有旧动画需要打断，发送打断广播
                if (oldAnim == RearAnimationManager.AnimationType.NOTIFICATION) {
                    Log.d(TAG, "检测到通知动画正在播放，发送打断广播");
                    RearAnimationManager.sendInterruptBroadcast(ChargingService.this, RearAnimationManager.AnimationType.NOTIFICATION);
                }
                
                showChargingOnRearScreen(batteryLevel, isLocked);
            } else if (Intent.ACTION_POWER_DISCONNECTED.equals(action)) {
                // 拔掉充电器，立即销毁充电动画
                Log.d(TAG, "🔌 Power disconnected, finishing charging animation");
                finishChargingAnimation();
            }
        }
    };
    
    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "ChargingService created");
        
        // 保存实例
        instance = this;
        
        prefs = getSharedPreferences("mrss_settings", Context.MODE_PRIVATE);
        
        // 添加Shizuku监听器
        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener);
        Shizuku.addBinderDeadListener(binderDeadListener);
        
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_POWER_CONNECTED);
        filter.addAction(Intent.ACTION_POWER_DISCONNECTED);  // 监听拔电事件
        registerReceiver(batteryReceiver, filter);
        
        // 绑定TaskService
        bindTaskService();
        
        // 不再单独展示前台通知（统一由 NotificationService 提供"MRSS 后台服务"通知）
    }
    
    // 取消 ChargingService 独立的前台通知，统一由全局后台通知保活

    private void acquireWakeLock(long timeoutMs) {
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                if (wakeLock == null) {
                    wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MRSS:ChargingWake");
                    wakeLock.setReferenceCounted(false);
                }
                if (!wakeLock.isHeld()) {
                    wakeLock.acquire(timeoutMs);
                    Log.d(TAG, "🔒 PARTIAL_WAKE_LOCK acquired for " + timeoutMs + "ms");
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "Failed to acquire wakelock: " + t.getMessage());
        }
    }

    private void releaseWakeLock() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) {
                wakeLock.release();
                Log.d(TAG, "🔓 PARTIAL_WAKE_LOCK released");
            }
        } catch (Throwable t) {
            Log.w(TAG, "Failed to release wakelock: " + t.getMessage());
        }
    }
    
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "ChargingService started");
        
        // 确保TaskService已绑定
        if (taskService == null) {
            bindTaskService();
        }
        
        return START_STICKY;
    }
    
    private void bindTaskService() {
        try {
            if (!Shizuku.pingBinder()) {
                Log.w(TAG, "Shizuku not available");
                return;
            }
            
            if (Shizuku.checkSelfPermission() != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                Log.w(TAG, "No Shizuku permission");
                return;
            }
            
            Shizuku.bindUserService(serviceArgs, taskServiceConnection);
            Log.d(TAG, "Binding TaskService...");
        } catch (Exception e) {
            Log.e(TAG, "Failed to bind TaskService", e);
        }
    }
    
    private int getBatteryLevel(Context context) {
        BatteryManager bm = (BatteryManager) context.getSystemService(Context.BATTERY_SERVICE);
        return bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
    }
    
    /**
     * 立即结束充电动画
     */
    private void finishChargingAnimation() {
        try {
            // 通过广播通知RearScreenChargingActivity立即结束
            Intent finishIntent = new Intent("com.tgwgroup.MiRearScreenSwitcher.FINISH_CHARGING_ANIMATION");
            finishIntent.setPackage(getPackageName());
            sendBroadcast(finishIntent);
                Log.d(TAG, "已发送结束充电动画的广播");
        } catch (Exception e) {
            Log.e(TAG, "Failed to finish charging animation", e);
        }
    }
    
    private void showChargingOnRearScreen(int level, boolean isLocked) {
        showChargingOnRearScreenWithRetry(level, isLocked, 0);
    }
    
    private void showChargingOnRearScreenWithRetry(int level, boolean isLocked, int retryCount) {
        if (taskService == null) {
            if (retryCount < 10) {  // 最多重试10次（总共1秒）
                Log.w(TAG, "TaskService not available, retry " + (retryCount + 1) + "/10");
                // 延迟重试
                new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                    showChargingOnRearScreenWithRetry(level, isLocked, retryCount + 1);
                }, 100);
                return;
            } else {
                Log.e(TAG, "TaskService still not available after 10 retries, aborting");
                return;
            }
        }
        
        acquireWakeLock(8000);
        try {
            // 步骤1: 检查背屏是否有投送的应用
            String lastTask = SwitchToRearTileService.getLastMovedTask();
            int rearTaskId = -1;
            
            if (lastTask != null && lastTask.contains(":")) {
                try {
                    String rearForegroundApp = taskService.getForegroundAppOnDisplay(1);
                    
                    // 如果当前背屏前台是充电动画，说明上一次动画还没完全销毁，使用lastTask
                    if (rearForegroundApp != null && rearForegroundApp.contains("RearScreenChargingActivity")) {
                        Log.d(TAG, "充电动画正在显示，使用lastTask: " + lastTask);
                        String[] parts = lastTask.split(":");
                        rearTaskId = Integer.parseInt(parts[1]);
                    } else if (rearForegroundApp != null && rearForegroundApp.equals(lastTask)) {
                        // 背屏确实有投送的app在运行
                        String[] parts = lastTask.split(":");
                        rearTaskId = Integer.parseInt(parts[1]);
                        Log.d(TAG, "背屏有投送app: " + lastTask);
                    }
                } catch (Exception e) {
                    Log.w(TAG, "检查背屏app失败", e);
                }
            }
            
            // 步骤2: 如果有投送app，暂停RearScreenKeeperService的监控
            if (rearTaskId > 0) {
                RearScreenKeeperService.pauseMonitoring();
            }
            
            // 步骤3: 禁用官方Launcher
            taskService.disableSubScreenLauncher();
            
            long startTime = System.currentTimeMillis();
            Log.d(TAG, String.format("[%tT.%tL] 开始启动充电动画", startTime, startTime));
            
            // 步骤3.5: 先唤醒背屏（双唤醒，锁屏更稳）
            try {
                taskService.executeShellCommand("input -d 1 keyevent KEYCODE_WAKEUP");
                Thread.sleep(80);
                taskService.executeShellCommand("input -d 1 keyevent KEYCODE_WAKEUP");
                Thread.sleep(60); // 等待背屏点亮
                Log.d(TAG, String.format("[%tT.%tL] 背屏已唤醒(2x)", System.currentTimeMillis(), System.currentTimeMillis()));
            } catch (Exception e) {
                Log.w(TAG, "唤醒背屏失败: " + e.getMessage());
            }

            // 额外尝试请求解锁界面（不依赖）
            try{
                taskService.executeShellCommand("wm dismiss-keyguard");
            } catch (Throwable ignored) {}
            
            // 步骤4: 使用MRSN的策略 - 先在主屏隐形启动，然后移动到背屏
            try {
                // 4.1: 先在主屏启动（Activity会在onCreate自动隐藏）
                String componentName = getPackageName() + "/" + RearScreenChargingActivity.class.getName();
                String mainCmd = String.format(
                    "am start -n %s --ei batteryLevel %d --ei rearTaskId %d",
                    componentName,
                    level,
                    rearTaskId
                );
                
                Log.d(TAG, String.format("[%tT.%tL] 🔵 在主屏启动Activity", System.currentTimeMillis(), System.currentTimeMillis()));
                taskService.executeShellCommand(mainCmd);
                
                // 4.2: 轮询获取taskId（最多60次 x 30ms = 1800ms，期间重发命令）
                String chargingTaskId = null;
                int attempts = 0;
                int maxAttempts = 60;
                
                while (chargingTaskId == null && attempts < maxAttempts) {
                    Thread.sleep(30);
                    String result = taskService.executeShellCommandWithResult("am stack list | grep RearScreenChargingActivity");
                    if (result != null && !result.trim().isEmpty()) {
                        // 解析 taskId=XXX
                        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("taskId=(\\d+)");
                        java.util.regex.Matcher matcher = pattern.matcher(result);
                        if (matcher.find()) {
                            chargingTaskId = matcher.group(1);
                            Log.d(TAG, String.format("[%tT.%tL] 找到taskId=%s (尝试%d次)", 
                                System.currentTimeMillis(), System.currentTimeMillis(), chargingTaskId, attempts + 1));
                            break;
                        }
                    }
                    attempts++;
                    if (attempts == 20 || attempts == 40) { // 中途重发一到两次启动命令
                        Log.d(TAG, String.format("[%tT.%tL] 重新发送主屏启动命令", System.currentTimeMillis(), System.currentTimeMillis()));
                        taskService.executeShellCommand(mainCmd);
                    }
                }
                
                if (chargingTaskId != null) {
                    // 4.3: 移动到背屏
                    String moveCmd = "service call activity_task 50 i32 " + chargingTaskId + " i32 1";
                    taskService.executeShellCommand(moveCmd);
                    Thread.sleep(40); // 等待移动完成
                    
                    // 4.4: 只在锁屏时关闭主屏（亮屏时不需要关闭）
                    if (isLocked) {
                        // 主屏休眠功能已移除
                        Log.d(TAG, String.format("[%tT.%tL] 锁屏状态，主屏已关闭", 
                            System.currentTimeMillis(), System.currentTimeMillis()));
                    } else {
                        Log.d(TAG, String.format("[%tT.%tL] 亮屏状态，保持主屏开启", 
                            System.currentTimeMillis(), System.currentTimeMillis()));
                    }
                    
                    long endTime = System.currentTimeMillis();
                    Log.d(TAG, String.format("[%tT.%tL] 充电动画已移动到背屏 (总耗时%dms)", 
                        endTime, endTime, endTime - startTime));
                } else {
                    Log.e(TAG, String.format("[%tT.%tL] 未能找到taskId, 尝试了%d次", 
                        System.currentTimeMillis(), System.currentTimeMillis(), attempts));
                }
            } catch (Exception e) {
                long errorTime = System.currentTimeMillis();
                Log.e(TAG, String.format("[%tT.%tL] 启动充电动画失败", errorTime, errorTime), e);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error showing charging", e);
        } finally {
            releaseWakeLock();
        }
    }
    
    @Override
    public void onDestroy() {
        super.onDestroy();
        
        // 停止前台服务
        stopForeground(true);
        
        // 清除静态实例
        instance = null;
        
        // 移除Shizuku监听器
        try {
            Shizuku.removeBinderReceivedListener(binderReceivedListener);
            Shizuku.removeBinderDeadListener(binderDeadListener);
        } catch (Exception e) {
            Log.e(TAG, "Error removing Shizuku listeners", e);
        }
        
        try {
            unregisterReceiver(batteryReceiver);
        } catch (Exception e) {
            Log.e(TAG, "Error unregistering receiver", e);
        }
        
        // 解绑TaskService
        try {
            if (taskService != null) {
                Shizuku.unbindUserService(serviceArgs, taskServiceConnection, true);
                taskService = null;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error unbinding TaskService", e);
        }
    }
    
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}

