package com.riverguard.v2;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Build;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import static com.riverguard.v2.PhoneValidation.*;

/** One application-scoped database/receiver. The Service, never an Activity, owns monitoring. */
final class PhoneRuntime {
    private static PhoneRuntime instance;
    static synchronized PhoneRuntime get(Context context){if(instance==null)instance=new PhoneRuntime(context.getApplicationContext());return instance;}
    final PhoneStore store;
    private final Context context;
    private final PhoneReceiver receiver;
    private ScheduledExecutorService monitor;
    private volatile boolean enabled;
    private volatile String error;
    private volatile MonitoringService service;
    private long startedAt;
    private final ScheduledExecutorService demo=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"riverguard-v2-demo");t.setDaemon(true);return t;});
    private PhoneRuntime(Context context){this.context=context;store=new PhoneStore(context);receiver=new PhoneReceiver(store,8000);store.receiverEnabled(false);MonitoringService.createChannels(context);demo.scheduleWithFixedDelay(()->{try{store.tickDemo(System.currentTimeMillis());}catch(Exception e){try{store.demoFailed();}catch(Exception ignored){}}},0,2,TimeUnit.SECONDS);}
    private boolean notificationPermissionAllowed(){return (Build.VERSION.SDK_INT<33||ContextCompat.checkSelfPermission(context,Manifest.permission.POST_NOTIFICATIONS)==PackageManager.PERMISSION_GRANTED)&&NotificationManagerCompat.from(context).areNotificationsEnabled();}
    private boolean channelAllowed(String id){if(Build.VERSION.SDK_INT<26)return true;NotificationChannel channel=context.getSystemService(NotificationManager.class).getNotificationChannel(id);return channel!=null&&channel.getImportance()!=NotificationManager.IMPORTANCE_NONE;}
    boolean notificationsAllowed(){return notificationPermissionAllowed()&&channelAllowed(MonitoringService.ALERTS)&&channelAllowed(MonitoringService.REVIEWS);}
    boolean canNotify(int level){return notificationPermissionAllowed()&&channelAllowed(level>=3?MonitoringService.ALERTS:MonitoringService.REVIEWS);}
    boolean wifiConnected(){ConnectivityManager cm=(ConnectivityManager)context.getSystemService(Context.CONNECTIVITY_SERVICE);if(cm==null)return false;for(Network n:cm.getAllNetworks()){NetworkCapabilities caps=cm.getNetworkCapabilities(n);if(caps!=null&&caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI))return true;}return false;}
    synchronized JSONObject info(){JSONObject info=receiver.info(enabled);put(info,"notificationsAllowed",notificationsAllowed());put(info,"wifiConnected",wifiConnected());String problem=error;if(enabled&&!wifiConnected())problem="Wi-Fi is disconnected. Monitoring cannot receive the station until both devices share Wi-Fi.";if(problem!=null)put(info,"error",problem);return info;}
    synchronized void requested(boolean requested){enabled=requested;store.receiverEnabled(requested);}
    synchronized boolean isRequested(){return enabled;}
    synchronized void failed(String message){error=message;enabled=false;store.receiverEnabled(false);}
    synchronized void start(MonitoringService owner) {
        service=owner;
        if(monitor!=null)return;
        enabled=true;store.receiverEnabled(true);error=null;startedAt=System.currentTimeMillis();
        monitor=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"riverguard-v2-monitor");t.setDaemon(true);return t;});
        monitor.scheduleWithFixedDelay(()->tick(owner),0,3,TimeUnit.SECONDS);
    }
    private void tick(MonitoringService owner){
        try {
            synchronized(this){if(!enabled||service!=owner)return;if(!receiver.isRunning())receiver.start();}
            owner.renewLocks();JSONArray stations=store.stations();boolean failed=false;
            for(int i=0;i<stations.length();i++){
                try {
                    String device=stations.getJSONObject(i).getString("deviceId");long now=System.currentTimeMillis();store.evaluateAlerts(device,now,startedAt);
                    JSONArray pending=store.pendingNotifications(device,now);
                    for(int j=0;j<pending.length();j++){
                        JSONObject event=pending.getJSONObject(j);
                        synchronized(this){if(!enabled||service!=owner)return;if(canNotify(event.optInt("level"))&&owner.notifyAlert(device,event))store.notificationDelivered(event.optLong("eventId"));}
                    }
                }catch(Exception stationFailure){failed=true;}
            }
            synchronized(this){if(!enabled||service!=owner)return;if(failed||store.hasMonitoringIssue()){error="A station could not be evaluated. Other stations continue monitoring; reopen its setup without clearing data.";owner.showRuntimeError();}else{boolean recovered=error!=null;error=null;if(recovered)owner.showRuntimeHealthy();}}
        }catch(Exception failure){synchronized(this){if(!enabled||service!=owner)return;error="Monitoring could not read or store sensor data. Check port 8000, reopen RiverGuard and retry; do not clear app data.";owner.showRuntimeError();}}
    }
    synchronized void stop(MonitoringService owner){if(owner!=null&&service!=null&&service!=owner)return;enabled=false;store.receiverEnabled(false);receiver.stop();ScheduledExecutorService old=monitor;monitor=null;service=null;if(old!=null)old.shutdownNow();}
}
