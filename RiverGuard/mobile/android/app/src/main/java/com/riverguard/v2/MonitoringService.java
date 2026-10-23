package com.riverguard.v2;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import androidx.core.app.NotificationCompat;
import org.json.JSONObject;

/** Explicit user-started ESP32 network monitoring with a visible Stop action. */
public final class MonitoringService extends Service {
    public static final String STOP="com.riverguard.v2.STOP_MONITORING";
    private static final int ONGOING_ID=20201;
    static final String MONITOR="riverguard_monitor",ALERTS="riverguard_alerts",REVIEWS="riverguard_reviews";
    private PhoneRuntime runtime;
    private PowerManager.WakeLock wake;
    private WifiManager.WifiLock wifi;
    private long lockTime;
    @Override public void onCreate(){super.onCreate();runtime=PhoneRuntime.get(this);createChannels(this);}
    static void createChannels(android.content.Context context){if(Build.VERSION.SDK_INT>=26){NotificationManager nm=context.getSystemService(NotificationManager.class);nm.createNotificationChannel(new NotificationChannel(MONITOR,"Active station monitoring",NotificationManager.IMPORTANCE_LOW));nm.createNotificationChannel(new NotificationChannel(ALERTS,"Water-level warnings",NotificationManager.IMPORTANCE_HIGH));nm.createNotificationChannel(new NotificationChannel(REVIEWS,"Sensor and water-quality checks",NotificationManager.IMPORTANCE_DEFAULT));}}
    private PendingIntent open(){return PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);}
    private Notification ongoing(String detail){PendingIntent stop=PendingIntent.getService(this,1,new Intent(this,MonitoringService.class).setAction(STOP),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);return new NotificationCompat.Builder(this,MONITOR).setSmallIcon(R.drawable.ic_monitor).setContentTitle("RiverGuard is monitoring your station").setContentText(detail).setContentIntent(open()).setOngoing(true).setOnlyAlertOnce(true).setCategory(NotificationCompat.CATEGORY_SERVICE).setPriority(NotificationCompat.PRIORITY_LOW).addAction(0,"Stop monitoring",stop).build();}
    @Override public int onStartCommand(Intent intent,int flags,int startId){
        if(intent==null){stopSelf();return START_NOT_STICKY;}
        if(STOP.equals(intent.getAction())){runtime.stop(this);stopForeground(true);stopSelf();return START_NOT_STICKY;}
        if(!runtime.isRequested()){stopSelf();return START_NOT_STICKY;}
        try {
            Notification n=ongoing("ESP32 reception on this Wi-Fi · tap to view");
            if(Build.VERSION.SDK_INT>=29)startForeground(ONGOING_ID,n,ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);else startForeground(ONGOING_ID,n);
            if(wake==null)acquireLocks();runtime.start(this);
        }catch(Exception failure){runtime.failed("Android could not start monitoring. Open the app and tap Start monitoring again.");stopForeground(true);stopSelf();}
        return START_NOT_STICKY;
    }
    @SuppressWarnings("deprecation") private void acquireLocks(){
        PowerManager pm=(PowerManager)getSystemService(POWER_SERVICE);wake=pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"RiverGuardV2:Monitoring");wake.setReferenceCounted(false);
        WifiManager wm=(WifiManager)getApplicationContext().getSystemService(WIFI_SERVICE);if(wm!=null){wifi=wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF,"RiverGuardV2:StationWiFi");wifi.setReferenceCounted(false);wifi.acquire();}
        renewLocks();
    }
    synchronized void renewLocks(){if(wake!=null&&(!wake.isHeld()||android.os.SystemClock.elapsedRealtime()-lockTime>240000)){wake.acquire(600000);lockTime=android.os.SystemClock.elapsedRealtime();}}
    boolean notifyAlert(String device,JSONObject event){
        if(!runtime.canNotify(event.optInt("level")))return false;
        boolean warning=event.optInt("level")>=3;String detail=event.optString("detail");
        Notification n=new NotificationCompat.Builder(this,warning?ALERTS:REVIEWS).setSmallIcon(R.drawable.ic_monitor).setContentTitle(event.optString("title")+" · "+device).setContentText(detail).setStyle(new NotificationCompat.BigTextStyle().bigText(detail+"\n"+event.optString("timestamp"))).setContentIntent(open()).setAutoCancel(true).setOnlyAlertOnce(false).setCategory(warning?NotificationCompat.CATEGORY_ALARM:NotificationCompat.CATEGORY_STATUS).setPriority(warning?NotificationCompat.PRIORITY_HIGH:NotificationCompat.PRIORITY_DEFAULT).build();
        getSystemService(NotificationManager.class).notify(("alert:"+device+":"+event.optString("kind")).hashCode()&0x7fffffff,n);return true;
    }
    void showRuntimeHealthy(){try{getSystemService(NotificationManager.class).notify(ONGOING_ID,ongoing("ESP32 reception on this Wi-Fi \u00b7 tap to view"));}catch(SecurityException ignored){}}
    void showRuntimeError(){try{getSystemService(NotificationManager.class).notify(ONGOING_ID,ongoing("Monitoring needs attention · open RiverGuard"));}catch(SecurityException ignored){}}
    @Override public void onDestroy(){if(runtime!=null)runtime.stop(this);synchronized(this){if(wake!=null&&wake.isHeld())wake.release();wake=null;}if(wifi!=null&&wifi.isHeld())wifi.release();wifi=null;stopForeground(true);super.onDestroy();}
    @Override public IBinder onBind(Intent intent){return null;}
}
