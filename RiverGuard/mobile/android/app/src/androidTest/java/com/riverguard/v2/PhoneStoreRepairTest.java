package com.riverguard.v2;

import android.content.Context;
import android.content.ContextWrapper;
import android.database.DatabaseErrorHandler;
import android.database.sqlite.SQLiteDatabase;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Before;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.StringWriter;
import java.time.Instant;
import static org.junit.Assert.*;
import static com.riverguard.v2.PhoneValidation.*;

/** Runs only on Android/emulator, against unique private test databases, never user data. */
@RunWith(AndroidJUnit4.class)
public class PhoneStoreRepairTest {
    private Context context;
    private PhoneStore store;
    private long base;
    private int sequence;
    @Before public void before(){
        Context app=InstrumentationRegistry.getInstrumentation().getTargetContext();final File directory=new File(app.getCacheDir(),"repair-test-"+java.util.UUID.randomUUID());assertTrue(directory.mkdirs());
        context=new ContextWrapper(app){
            @Override public File getDatabasePath(String name){return new File(directory,name);}
            @Override public SQLiteDatabase openOrCreateDatabase(String name,int mode,SQLiteDatabase.CursorFactory factory){return SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name),factory);}
            @Override public SQLiteDatabase openOrCreateDatabase(String name,int mode,SQLiteDatabase.CursorFactory factory,DatabaseErrorHandler handler){return SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name).getPath(),factory,handler);}
        };
        store=new PhoneStore(context);base=System.currentTimeMillis()-10000;sequence=0;
    }
    @After public void after(){if(store!=null)store.close();}
    private JSONObject settings(){return json("enabled",json("ph",true,"turbidity",true,"temperature",true,"water_level",true),"ph",new JSONArray().put(json("mv",1500,"value",4)).put(json("mv",2000,"value",7)),"turbidity",new JSONArray().put(json("mv",1500,"value",100)).put(json("mv",2500,"value",0)),"level",json("reference",200,"minimum",25,"maximum",450),"temperature_offset",0,"verified",false);}
    private void setup(){store.register("one",false);store.saveSettings("one",0,settings());store.saveWatch("esp32","one",0,WatchPolicy.defaults());}
    private JSONObject packet(double height,boolean fault){int n=sequence++;return json("device_id","one","mode","hardware","setup_protocol","remote-v1","config_revision",1,"sample_id","boot-test-"+n,"timestamp",Instant.ofEpochMilli(base+n*1000).toString(),"raw",json("ph_mv",2000,"ph_adc",2200,"turbidity_mv",2400,"turbidity_adc",2500,"temperature_c",27,"distance_cm",200-height),"sensor_health",json("ph","ok","turbidity","ok","temperature","ok","water_level",fault?"timeout":"ok"));}
    private int eventCount(String kind,String phase){JSONArray events=store.getState("alerts:one").optJSONArray("value");int count=0;for(int i=0;i<events.length();i++){JSONObject e=events.optJSONObject(i);if(kind.equals(e.optString("kind"))&&phase.equals(e.optString("phase")))count++;}return count;}
    @Test public void everyAcceptedExcursionIsSavedWithoutMonitorTick(){setup();store.ingest("one",packet(40,false));JSONObject high=packet(120,false);store.ingest("one",high);store.ingest("one",packet(40,false));assertEquals(1,eventCount("water_warning","active"));store.ingest("one",high);assertEquals(1,eventCount("water_warning","active"));assertEquals(3,store.readings("esp32","one",50,null).optInt("total"));}
    @Test public void faultsDoNotRecoverAndLatePacketsDoNotRegressLedger(){setup();store.ingest("one",packet(120,false));for(int i=0;i<3;i++)store.ingest("one",packet(40,true));assertEquals(0,eventCount("water_warning","recovered"));JSONObject old=packet(40,false);put(old,"timestamp",Instant.ofEpochMilli(base-1000).toString());store.ingest("one",old);assertEquals(0,eventCount("water_warning","recovered"));JSONArray conditions=store.getAlertState("esp32","one").optJSONArray("conditions");boolean retained=false;for(int i=0;i<conditions.length();i++)if(conditions.optJSONObject(i).optString("kind").equals("water_warning")){retained=true;assertTrue(conditions.optJSONObject(i).optBoolean("lastKnown"));}assertTrue(retained);}
    @Test public void watchCasAndGeometryAreEnforced(){setup();try{store.saveWatch("esp32","one",0,WatchPolicy.defaults());fail();}catch(Problem e){assertEquals(409,e.status);}JSONObject changed=settings();put(changed,"level",json("reference",50,"minimum",25,"maximum",450));store.saveSettings("one",1,changed);assertFalse(store.getWatch("esp32","one").optBoolean("valid"));put(changed,"verified",true);try{store.saveSettings("one",2,changed);fail();}catch(Problem e){assertEquals(422,e.status);}assertTrue(store.getWatch("simulation","demo-station").optBoolean("valid"));}
    @Test public void outboxSurvivesBlockedDeliveryAndSuppressesAfterRecovery(){setup();store.ingest("one",packet(120,false));JSONArray pending=store.pendingNotifications("one",System.currentTimeMillis());assertEquals(1,pending.length());assertEquals(1,store.pendingNotifications("one",System.currentTimeMillis()).length());long id=pending.optJSONObject(0).optLong("eventId");store.notificationDelivered(id);assertEquals(0,store.pendingNotifications("one",System.currentTimeMillis()).length());for(int i=0;i<3;i++)store.ingest("one",packet(40,false));assertEquals(1,eventCount("water_warning","recovered"));}
    @Test public void policyChangeKeepsActiveUndeliveredWarningPending(){setup();store.ingest("one",packet(120,false));assertEquals(1,store.pendingNotifications("one",System.currentTimeMillis()).length());store.saveWatch("esp32","one",1,json("elevated",60,"warning",90,"rise_limit",10));store.evaluateAlerts("one",System.currentTimeMillis(),base);JSONArray pending=store.pendingNotifications("one",System.currentTimeMillis());assertEquals(1,pending.length());assertEquals(2,pending.optJSONObject(0).optInt("watchRevision"));}
    @Test public void firstReadingTimeoutHasSixtySecondGrace(){store.register("one",false);long start=System.currentTimeMillis();store.evaluateAlerts("one",start,start);store.evaluateAlerts("one",start+59999,start);assertEquals(0,eventCount("connection","active"));store.evaluateAlerts("one",start+60000,start);assertEquals(1,eventCount("connection","active"));}
    @Test public void exportUsesCapturedCalibrationAndIncludesPolicyEvidence()throws Exception{setup();store.ingest("one",packet(120,false));JSONObject changed=settings();put(changed,"temperature_offset",2);store.saveSettings("one",1,changed);StringWriter writer=new StringWriter();store.export("esp32","one",writer);String csv=writer.toString();assertTrue(csv.contains(",7.0,10.0,27.0,120.0,"));assertTrue(csv.contains("export_watch_settings_json"));assertTrue(csv.contains("watchRevision"));}
    @Test public void internalPreferencesCannotBeModified(){for(String key:new String[]{"alert-status:esp32:one","runtime.demo","receiver.enabled","watch:one"})try{store.publicSetState(key,json("bad",true));fail();}catch(Problem e){assertEquals(403,e.status);}}
    @Test public void migrationBacksUpAndPreservesRowsAndDrafts(){
        store.getWritableDatabase();store.close();SQLiteDatabase db=SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath("riverguard-v2.db"),null);
        // Construct the previous schema from a fresh schema without altering any production DB.
        db.execSQL("DROP TABLE watch_settings");db.execSQL("DROP TABLE alert_processing");db.execSQL("DROP TABLE notification_outbox");db.execSQL("DROP TABLE alert_event_samples");db.execSQL("DROP TABLE quarantined_state");
        db.execSQL("INSERT INTO state(key,value) VALUES (?,?)",new Object[]{"draft:one",json("revision",0,"settings",settings()).toString()});
        db.execSQL("INSERT INTO state(key,value) VALUES (?,?)",new Object[]{"watch:demo-station",WatchPolicy.defaults().toString()});db.setVersion(1);db.close();
        store=new PhoneStore(context);assertNotNull(store.publicGetState("draft:esp32:one").optJSONObject("value"));assertEquals(1,store.getWatch("simulation","demo-station").optInt("revision"));File[] backups=context.getDatabasePath("riverguard-v2.db").getParentFile().listFiles((directory,name)->name.startsWith("riverguard-v2.before-schema-2."));assertNotNull(backups);assertTrue(backups.length>0);assertTrue(backups[0].length()>0);
    }
}
