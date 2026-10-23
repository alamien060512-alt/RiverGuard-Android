package com.riverguard.v2;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.util.Base64;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import static com.riverguard.v2.PhoneValidation.*;

/** Private append-only readings and immutable versioned calibration snapshots. */
public final class PhoneStore extends SQLiteOpenHelper implements PhoneReceiver.Backend {
    public PhoneStore(Context context) { super(context, prepareBackup(context), null, 2); setWriteAheadLoggingEnabled(true); }
    /** Startup is application-singleton; checkpoint and close before a byte-complete private backup. */
    private static synchronized String prepareBackup(Context context) {
        java.io.File file=context.getDatabasePath("riverguard-v2.db");
        if(!file.isFile())return "riverguard-v2.db";
        SQLiteDatabase old=null;
        try {
            old=SQLiteDatabase.openDatabase(file.getPath(),null,SQLiteDatabase.OPEN_READWRITE);
            if(old.getVersion()!=1)return "riverguard-v2.db";
            try(Cursor check=old.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)",null)){if(check.moveToFirst()&&check.getInt(0)!=0)throw new IllegalStateException("Close RiverGuard before upgrading its database.");}
            old.close();old=null;
            java.io.File backup=new java.io.File(file.getParentFile(),"riverguard-v2.before-schema-2."+System.currentTimeMillis()+".db");
            try(java.io.FileInputStream input=new java.io.FileInputStream(file);java.io.FileOutputStream output=new java.io.FileOutputStream(backup)) {byte[] bytes=new byte[65536];int count;while((count=input.read(bytes))!=-1)output.write(bytes,0,count);output.getFD().sync();}
        }catch(IOException failure){throw new IllegalStateException("Database backup failed. Preserve app data and free phone storage before updating.",failure);}
        finally {if(old!=null)old.close();}
        return "riverguard-v2.db";
    }
    @Override public void onConfigure(SQLiteDatabase db) { db.setForeignKeyConstraintsEnabled(true); }
    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE state(key TEXT PRIMARY KEY,value TEXT NOT NULL)");
        db.execSQL("CREATE TABLE stations(device_id TEXT PRIMARY KEY,token_hash TEXT UNIQUE NOT NULL,last_received TEXT,last_measured TEXT,ack_revision INTEGER)");
        db.execSQL("CREATE TABLE configurations(device_id TEXT NOT NULL,revision INTEGER NOT NULL,settings TEXT NOT NULL,created_at TEXT NOT NULL,PRIMARY KEY(device_id,revision),FOREIGN KEY(device_id) REFERENCES stations(device_id))");
        db.execSQL("CREATE TABLE readings(id INTEGER PRIMARY KEY AUTOINCREMENT,source TEXT NOT NULL,device_id TEXT NOT NULL,timestamp TEXT NOT NULL,measured_ms INTEGER NOT NULL,received_at TEXT NOT NULL,payload TEXT NOT NULL,settings_json TEXT,sample_id TEXT,UNIQUE(source,device_id,sample_id))");
        db.execSQL("CREATE TABLE alert_events(id INTEGER PRIMARY KEY AUTOINCREMENT,device_id TEXT NOT NULL,event_json TEXT NOT NULL)");
        db.execSQL("CREATE INDEX alert_station ON alert_events(device_id,id DESC)");
        db.execSQL("CREATE INDEX station_measurement ON readings(source,device_id,measured_ms DESC,id DESC)");
        createRepairTables(db);
    }
    private void createRepairTables(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE watch_settings(source TEXT NOT NULL,device_id TEXT NOT NULL,revision INTEGER NOT NULL,settings TEXT NOT NULL,needs_review INTEGER NOT NULL DEFAULT 0,PRIMARY KEY(source,device_id))");
        db.execSQL("CREATE TABLE alert_processing(device_id TEXT PRIMARY KEY,last_id INTEGER NOT NULL,last_measured_ms INTEGER NOT NULL,last_measurement_id INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE notification_outbox(event_id INTEGER PRIMARY KEY,device_id TEXT NOT NULL,kind TEXT NOT NULL,event_json TEXT NOT NULL,status TEXT NOT NULL)");
        db.execSQL("CREATE INDEX pending_notifications ON notification_outbox(device_id,status)");
        db.execSQL("CREATE TABLE alert_event_samples(event_id INTEGER PRIMARY KEY,device_id TEXT NOT NULL,sample_id TEXT NOT NULL)");
        db.execSQL("CREATE INDEX event_sample ON alert_event_samples(device_id,sample_id)");
        db.execSQL("CREATE TABLE quarantined_state(id INTEGER PRIMARY KEY AUTOINCREMENT,key TEXT NOT NULL,value TEXT NOT NULL,created_at TEXT NOT NULL)");
    }
    @Override public void onUpgrade(SQLiteDatabase db,int oldVersion,int newVersion) {
        if(oldVersion!=1||newVersion!=2)throw new IllegalStateException("Unsupported phone database upgrade. Preserve this app's data.");
        // SQLiteOpenHelper wraps this complete migration in one transaction.
        createRepairTables(db);
        try(Cursor c=db.rawQuery("SELECT key,value FROM state WHERE key LIKE 'watch:%'",null)) {
            while(c.moveToNext()) {
                String device=c.getString(0).substring(6);JSONObject watch;
                try{watch=SignalEvaluator.watch(object(c.getString(1)));}catch(RuntimeException e){quarantine(db,c.getString(0),c.getString(1));continue;}
                boolean ambiguous="demo-station".equals(device)&&exists(db,"SELECT 1 FROM stations WHERE device_id=?",device);
                if(!"demo-station".equals(device)||ambiguous)writeWatch(db,"esp32",device,1,watch,ambiguous);
                if("demo-station".equals(device))writeWatch(db,"simulation",device,1,watch,ambiguous);
            }
        }
        try(Cursor c=db.rawQuery("SELECT key,value FROM state WHERE key LIKE 'draft:%' AND key NOT LIKE 'draft:esp32:%'",null)){while(c.moveToNext())writeState(db,"draft:esp32:"+c.getString(0).substring(6),new org.json.JSONTokener(c.getString(1)).nextValue());}catch(org.json.JSONException error){throw new IllegalStateException("Saved drafts cannot be migrated; the backup is preserved.",error);}
        try(Cursor c=db.rawQuery("SELECT device_id FROM stations",null)){while(c.moveToNext()){String device=c.getString(0);long ceiling=singleLong(db,"SELECT COALESCE(MAX(id),0) FROM readings WHERE source='esp32' AND device_id=?",new String[]{device});try(Cursor tip=db.rawQuery("SELECT id,measured_ms FROM readings WHERE source='esp32' AND device_id=? ORDER BY measured_ms DESC,id DESC LIMIT 1",new String[]{device})){if(tip.moveToFirst())writeCursor(db,device,ceiling,tip.getLong(1),tip.getLong(0));}}}
        try(Cursor c=db.rawQuery("SELECT key,value FROM state WHERE key LIKE 'alert-status:%' AND key NOT LIKE 'alert-status:esp32:%'",null)) {
            while(c.moveToNext()) {
                String device=c.getString(0).substring(13);JSONObject legacy;
                try{legacy=object(c.getString(1));if(!AlertLedger.validState(legacy))throw new IllegalArgumentException();}catch(RuntimeException invalid){quarantine(db,c.getString(0),c.getString(1));writeState(db,"monitor-error:"+device,"Legacy monitoring state needs a settings review.");continue;}
                JSONObject conditions=legacy.optJSONObject("conditions");java.util.Iterator<String> names=conditions.keys();while(names.hasNext()){String kind=names.next();JSONObject condition=conditions.optJSONObject(kind);if(condition!=null){put(condition,"clearCount",0);put(condition,"recheck",condition.optBoolean("active")&&!kind.equals("connection")&&!kind.equals("sensor_health"));}}
                put(legacy,"segment","legacy-unverified");writeState(db,"alert-status:esp32:"+device,legacy);
            }
        }
        try(Cursor c=db.rawQuery("SELECT id,device_id,event_json FROM alert_events",null)){while(c.moveToNext()){JSONObject event=object(c.getString(2));if(event.has("sample")&&!event.isNull("sample"))indexEvent(db,c.getLong(0),c.getString(1),event.optString("sample"));}}
        try(Cursor c=db.rawQuery("SELECT value FROM state WHERE key='v2-preferences'",null)){if(c.moveToFirst()){JSONObject preferences=object(c.getString(0)),demo=DemoState.initial();String scenario=preferences.optString("scenario","Normal river");try{DemoState.scenario(scenario);put(demo,"scenario",scenario);}catch(Problem invalid){/* Keep a usable labelled default for an invalid legacy scenario. */}if(preferences.opt("running") instanceof Boolean)put(demo,"running",preferences.optBoolean("running"));writeState(db,"runtime.demo",demo);}}
        // Existing event JSON remains verbatim; old policy provenance must not be invented.
    }
    private static void quarantine(SQLiteDatabase db,String key,String value){ContentValues row=new ContentValues();row.put("key",key);row.put("value",value);row.put("created_at",Instant.now().toString());db.insertOrThrow("quarantined_state",null,row);}
    private static void writeState(SQLiteDatabase db,String key,Object value){String wrapped=new JSONArray().put(value==null?JSONObject.NULL:value).toString();if(wrapped==null)throw new Problem(422,"Invalid saved preference.");ContentValues row=new ContentValues();row.put("key",key);row.put("value",wrapped.substring(1,wrapped.length()-1));db.insertWithOnConflict("state",null,row,SQLiteDatabase.CONFLICT_REPLACE);}
    private static void writeWatch(SQLiteDatabase db,String source,String device,long revision,JSONObject settings,boolean review){ContentValues row=new ContentValues();row.put("source",source);row.put("device_id",device);row.put("revision",revision);row.put("settings",settings.toString());row.put("needs_review",review?1:0);db.insertWithOnConflict("watch_settings",null,row,SQLiteDatabase.CONFLICT_REPLACE);}
    private static void indexEvent(SQLiteDatabase db,long id,String device,String sample){ContentValues row=new ContentValues();row.put("event_id",id);row.put("device_id",device);row.put("sample_id",sample);db.insertWithOnConflict("alert_event_samples",null,row,SQLiteDatabase.CONFLICT_IGNORE);}
    private static void writeCursor(SQLiteDatabase db,String device,long id,long time,long measurementId){ContentValues row=new ContentValues();row.put("device_id",device);row.put("last_id",id);row.put("last_measured_ms",time);row.put("last_measurement_id",measurementId);db.insertWithOnConflict("alert_processing",null,row,SQLiteDatabase.CONFLICT_REPLACE);}


    public synchronized JSONObject getState(String key) {
        stateKey(key);
        if(key.startsWith("alerts:")) {
            String device=station(key.substring(7));JSONArray events=new JSONArray();
            try(Cursor c=getReadableDatabase().rawQuery("SELECT event_json FROM alert_events WHERE device_id=? ORDER BY id DESC LIMIT 100",new String[]{device})){while(c.moveToNext()){JSONObject event=object(c.getString(0));if(!event.has("watchRevision"))put(event,"provenance","Legacy interpretation unavailable");events.put(event);}}
            return json("value",events);
        }
        try (Cursor c = getReadableDatabase().rawQuery("SELECT value FROM state WHERE key=?", new String[]{key})) {
            Object value = JSONObject.NULL;
            if (c.moveToFirst()) {
                try { value = new org.json.JSONTokener(c.getString(0)).nextValue(); }
                catch (org.json.JSONException error) { throw new Problem(500,"Saved phone preferences cannot be read."); }
            }
            return json("value",value);
        }
    }
    public synchronized JSONObject setState(String key, Object value) {
        stateKey(key);
        if(key.startsWith("watch:")){station(key.substring(6));if(!(value instanceof JSONObject))throw new Problem(422,"Specify watch settings.");value=SignalEvaluator.watch((JSONObject)value);}
        String encoded;
        String wrapped = new JSONArray().put(value == null ? JSONObject.NULL : value).toString();
        if (wrapped == null) throw new Problem(422,"Invalid saved preference.");
        encoded = wrapped.substring(1, wrapped.length()-1);
        if (encoded.getBytes(StandardCharsets.UTF_8).length > 65536) throw new Problem(413,"This phone preference is too large.");
        ContentValues row=new ContentValues(); row.put("key",key); row.put("value",encoded);
        getWritableDatabase().insertWithOnConflict("state",null,row,SQLiteDatabase.CONFLICT_REPLACE);
        return json("saved",true);
    }
    private static void stateKey(String key) { if(key==null || !key.matches("[A-Za-z0-9_.:-]{1,128}")) throw new Problem(422,"Invalid preference key."); }
    public synchronized boolean receiverEnabled() { return Boolean.TRUE.equals(getState("receiver.enabled").opt("value")); }
    public synchronized void receiverEnabled(boolean enabled) { setState("receiver.enabled",enabled); }

    public synchronized JSONObject publicGetState(String key){publicKey(key,false);return getState(key);}
    public synchronized JSONObject publicSetState(String key,Object value){
        publicKey(key,true);
        if(key.equals("v2-preferences")) {
            if(!(value instanceof JSONObject))throw new Problem(422,"Specify view preferences.");JSONObject v=(JSONObject)value;keys(v,"source","deviceId","scenario","running");source(v.optString("source"));station(v.optString("deviceId"));
            if(v.has("scenario"))DemoState.scenario(v.optString("scenario"));if(v.has("running"))bool(v,"running");
        } else if(value!=null&&value!=JSONObject.NULL) {
            PhoneValidation.draft(value);
        }
        return setState(key,value);
    }
    private static void publicKey(String key,boolean write){stateKey(key);if(key.equals("v2-preferences")||key.matches("draft:esp32:[A-Za-z0-9_-]{1,80}"))return;if(!write&&key.matches("alerts:[A-Za-z0-9_-]{1,80}"))return;throw new Problem(403,"This state is managed by RiverGuard. Use the dedicated settings controls.");}
    public synchronized JSONObject getWatch(String source,String device){
        source(source);station(device);SQLiteDatabase db=getReadableDatabase();long revision=0;boolean review=false;JSONObject settings=WatchPolicy.defaults();
        try(Cursor c=db.rawQuery("SELECT revision,settings,needs_review FROM watch_settings WHERE source=? AND device_id=?",new String[]{source,device})){if(c.moveToFirst()){revision=c.getLong(0);settings=object(c.getString(1));review=c.getInt(2)!=0;}}
        return json("revision",revision,"settings",settings,"needsReview",review,"valid",WatchPolicy.valid(source,settings,source.equals("esp32")?settings(db,device,null).optJSONObject("settings"):null,review));
    }
    public synchronized JSONObject saveWatch(String source,String device,long expected,JSONObject incoming){
        source(source);station(device);JSONObject settings=SignalEvaluator.watch(incoming);SQLiteDatabase db=getWritableDatabase();db.beginTransaction();
        try {JSONObject old=getWatch(source,device);if(old.optLong("revision")!=expected)throw new Problem(409,"Watch settings changed. Reload them before saving; keep your draft.");
            if(source.equals("esp32")&&!exists(db,"SELECT 1 FROM stations WHERE device_id=?",device))throw new Problem(404,"Register this station first.");
            if(!WatchPolicy.valid(source,settings,source.equals("esp32")?settings(db,device,null).optJSONObject("settings"):null,false))throw new Problem(422,"Configure water geometry first, then keep watch and warning heights within its observable range.");
            writeWatch(db,source,device,expected+1,settings,false);db.setTransactionSuccessful();return getWatch(source,device);
        }finally{db.endTransaction();}
    }
    public synchronized JSONObject getDemo(){JSONObject value=getState("runtime.demo").optJSONObject("value");return value==null?DemoState.initial():value;}
    public synchronized JSONObject saveDemo(long expected,String scenario,boolean running){
        DemoState.scenario(scenario);SQLiteDatabase db=getWritableDatabase();db.beginTransaction();try{JSONObject current=getDemo();if(current.optLong("revision")!=expected)throw new Problem(409,"Demo controls changed in another view. Refresh before trying again.");long step=scenario.equals(current.optString("scenario"))?current.optLong("step"):0;JSONObject next=json("revision",expected+1,"scenario",scenario,"running",running,"step",step,"error",null);writeState(db,"runtime.demo",next);db.setTransactionSuccessful();return next;}finally{db.endTransaction();}
    }
    public synchronized void tickDemo(long now){JSONObject state=getDemo();if(!state.optBoolean("running"))return;SQLiteDatabase db=getWritableDatabase();db.beginTransaction();try{long step=state.optLong("step");appendDemo(DemoState.sample(state.optString("scenario"),step,now));put(state,"step",(step+1)%120);put(state,"error",null);writeState(db,"runtime.demo",state);db.setTransactionSuccessful();}finally{db.endTransaction();}}
    public synchronized void demoFailed(){JSONObject state=getDemo();put(state,"error","Demonstration could not save a reading. Pause and resume to retry.");put(state,"running",false);put(state,"revision",state.optLong("revision")+1);writeState(getWritableDatabase(),"runtime.demo",state);}

    public synchronized JSONObject register(String device,boolean rotate) {
        station(device); SQLiteDatabase db=getWritableDatabase(); String token=token();
        db.beginTransaction();
        try {
            boolean exists=exists(db,"SELECT 1 FROM stations WHERE device_id=?",device);
            if(!exists&&"demo-station".equals(device))throw new Problem(422,"Choose a hardware station ID other than the reserved demo-station.");
            if(exists&&!rotate) throw new Problem(409,"Station already registered. Replace its credential only if you can upload the new private configuration.");
            ContentValues row=new ContentValues();row.put("token_hash",digest(token));
            if(exists) db.update("stations",row,"device_id=?",new String[]{device});
            else { row.put("device_id",device); db.insertOrThrow("stations",null,row); }
            db.setTransactionSuccessful();return json("deviceId",device,"token",token);
        } finally { db.endTransaction(); }
    }
    public synchronized String authenticate(String header) {
        if(header==null||!header.matches("Bearer [A-Za-z0-9_-]{32,200}")) throw new Problem(401,"Station credential required.");
        String hash=digest(header.substring(7));
        try(Cursor c=getReadableDatabase().rawQuery("SELECT device_id FROM stations WHERE token_hash=?",new String[]{hash})) {
            if(!c.moveToFirst()) throw new Problem(401,"Station credential is invalid or revoked.");
            return c.getString(0);
        }
    }
    public synchronized JSONObject settings(String device) { station(device); return settings(getReadableDatabase(),device,null); }
    private JSONObject settings(SQLiteDatabase db,String device,Integer revision) {
        String sql="SELECT revision,settings FROM configurations WHERE device_id=?"+(revision==null?" ORDER BY revision DESC LIMIT 1":" AND revision=?");
        String[] args=revision==null?new String[]{device}:new String[]{device,String.valueOf(revision)};
        try(Cursor c=db.rawQuery(sql,args)) {
            if(c.moveToFirst()) return json("revision",c.getInt(0),"settings",object(c.getString(1)));
        }
        if(revision!=null&&revision!=0) throw new Problem(409,"Unknown calibration revision. Resync the station configuration.");
        return json("revision",0,"settings",defaults());
    }
    public synchronized JSONObject saveSettings(String device,long expected,JSONObject incoming) {
        station(device); if(expected<0||expected>=Integer.MAX_VALUE) throw new Problem(422,"Invalid configuration revision.");
        JSONObject normalized=PhoneValidation.settings(incoming);SQLiteDatabase db=getWritableDatabase();db.beginTransaction();
        try {
            if(!exists(db,"SELECT 1 FROM stations WHERE device_id=?",device)) throw new Problem(404,"Register this station first.");
            JSONObject previous=settings(db,device,null);
            if(previous.optLong("revision")!=expected) throw new Problem(409,"Settings changed. Reload the saved configuration before saving again.");
            JSONObject before=copy(previous.optJSONObject("settings")),after=copy(normalized);
            put(before,"verified",false);put(after,"verified",false);
            if(!equivalent(before,after)) put(normalized,"verified",false);
            JSONObject policy=getWatch("esp32",device);
            if(normalized.optBoolean("verified")) {
                if(!WatchPolicy.valid("esp32",policy.optJSONObject("settings"),normalized,policy.optBoolean("needsReview")))throw new Problem(422,"Save watch levels within the calibrated observable height before verification.");
                try(Cursor ack=db.rawQuery("SELECT ack_revision FROM stations WHERE device_id=?",new String[]{device})){if(!ack.moveToFirst()||ack.isNull(0)||ack.getLong(0)!=expected)throw new Problem(409,"Wait for the station to acknowledge this calibration before verification.");}
            }
            ContentValues row=new ContentValues();row.put("device_id",device);row.put("revision",expected+1);row.put("settings",normalized.toString());row.put("created_at",Instant.now().toString());
            db.insertOrThrow("configurations",null,row);db.delete("state","key=?",new String[]{"monitor-error:"+device});db.setTransactionSuccessful();return json("revision",expected+1,"settings",normalized);
        } finally { db.endTransaction(); }
    }
    private static boolean equivalent(Object a,Object b) {
        if(a==JSONObject.NULL||a==null) return b==null||b==JSONObject.NULL;
        if(a instanceof JSONObject&&b instanceof JSONObject) {
            JSONObject aa=(JSONObject)a,bb=(JSONObject)b;if(aa.length()!=bb.length())return false;
            java.util.Iterator<String> keys=aa.keys();while(keys.hasNext()){String k=keys.next();if(!bb.has(k)||!equivalent(aa.opt(k),bb.opt(k)))return false;}return true;
        }
        if(a instanceof JSONArray&&b instanceof JSONArray) {JSONArray aa=(JSONArray)a,bb=(JSONArray)b;if(aa.length()!=bb.length())return false;for(int i=0;i<aa.length();i++)if(!equivalent(aa.opt(i),bb.opt(i)))return false;return true;}
        if(a instanceof Number&&b instanceof Number)return Double.compare(((Number)a).doubleValue(),((Number)b).doubleValue())==0;
        return a.equals(b);
    }
    private static boolean exists(SQLiteDatabase db,String sql,String value) {try(Cursor c=db.rawQuery(sql,new String[]{value})){return c.moveToFirst();}}
    public synchronized JSONObject configuration(String device) {
        JSONObject cfg=settings(device);long now=System.currentTimeMillis();
        return json("device_id",device,"protocol","remote-v1","revision",cfg.optInt("revision"),"enabled",cfg.optJSONObject("settings").optJSONObject("enabled"),"server_time",Instant.ofEpochMilli(now).toString(),"server_epoch_seconds",now/1000);
    }
    public synchronized JSONObject ingest(String authenticated,JSONObject packet) {
        long now=System.currentTimeMillis(),measured=hardware(packet,now);String device=packet.optString("device_id");
        if(!authenticated.equals(device)) throw new Problem(403,"A station can submit only its own readings.");
        SQLiteDatabase db=getWritableDatabase();db.beginTransaction();
        try {
            int revision=packet.optInt("config_revision");JSONObject cfg=settings(db,device,revision).optJSONObject("settings");
            String received=Instant.ofEpochMilli(now).toString();
            ContentValues row=new ContentValues();row.put("source","esp32");row.put("device_id",device);row.put("timestamp",Instant.ofEpochMilli(measured).toString());row.put("measured_ms",measured);row.put("received_at",received);row.put("payload",packet.toString());row.put("settings_json",cfg.toString());row.put("sample_id",packet.optString("sample_id"));
            long id=db.insertWithOnConflict("readings",null,row,SQLiteDatabase.CONFLICT_IGNORE);
            if(id==-1) {
                try(Cursor existing=db.rawQuery("SELECT payload FROM readings WHERE source='esp32' AND device_id=? AND sample_id=?",new String[]{device,packet.optString("sample_id")})) {
                    if(!existing.moveToFirst()||!equivalent(object(existing.getString(0)),packet))throw new Problem(409,"This sample ID was already used for different readings. Keep each retry unchanged.");
                }
            }
            ContentValues heartbeat=new ContentValues();heartbeat.put("last_received",received);
            try(Cursor latest=db.rawQuery("SELECT timestamp,payload FROM readings WHERE source='esp32' AND device_id=? ORDER BY measured_ms DESC,id DESC LIMIT 1",new String[]{device})) {
                if(latest.moveToFirst()){heartbeat.put("last_measured",latest.getString(0));heartbeat.put("ack_revision",object(latest.getString(1)).optInt("config_revision"));}
            }
            db.update("stations",heartbeat,"device_id=?",new String[]{device});if(id!=-1)processSamples(db,device,now);db.setTransactionSuccessful();return json("stored",true,"duplicate",id==-1);
        } finally {db.endTransaction();}
    }
    public synchronized JSONObject appendDemo(JSONObject incoming) {
        JSONObject payload=demo(incoming);long measured=timestamp(payload,System.currentTimeMillis());
        ContentValues row=new ContentValues();row.put("source","simulation");row.put("device_id","demo-station");row.put("timestamp",Instant.ofEpochMilli(measured).toString());row.put("measured_ms",measured);row.put("received_at",Instant.now().toString());row.put("payload",payload.toString());row.put("sample_id",UUID.randomUUID().toString());
        getWritableDatabase().insertOrThrow("readings",null,row);return json("stored",true);
    }
    public synchronized JSONArray stations() {
        JSONArray items=new JSONArray();
        try(Cursor c=getReadableDatabase().rawQuery("SELECT device_id,last_received,last_measured,ack_revision,(SELECT MAX(revision) FROM configurations WHERE configurations.device_id=stations.device_id) FROM stations ORDER BY device_id",null)) {
            while(c.moveToNext())items.put(json("deviceId",c.getString(0),"lastReceived",c.isNull(1)?null:c.getString(1),"lastMeasured",c.isNull(2)?null:c.getString(2),"acknowledgedRevision",c.isNull(3)?null:c.getLong(3),"revision",c.isNull(4)?0:c.getLong(4)));
        }return items;
    }
    private static long singleLong(SQLiteDatabase db,String sql,String[] args) {try(Cursor c=db.rawQuery(sql,args)){return c.moveToFirst()?c.getLong(0):0;}}
    public synchronized JSONObject readings(String source,String device,int limit,String cursor) {
        source(source);station(device);if(limit<1||limit>500)throw new Problem(422,"Page size must be from1 to500.");
        SQLiteDatabase db=getReadableDatabase();long ceiling=singleLong(db,"SELECT COALESCE(MAX(id),0) FROM readings WHERE source=? AND device_id=?",new String[]{source,device});
        Long lastMs=null,lastId=null;
        if(cursor!=null&&!cursor.isEmpty()) {
            if(cursor.length()>2048)throw new Problem(422,"Invalid history cursor.");
            JSONObject p;
            try {p=object(new String(Base64.decode(cursor,Base64.URL_SAFE|Base64.NO_WRAP),StandardCharsets.UTF_8));}catch(IllegalArgumentException error){throw new Problem(422,"Invalid history cursor.");}
            if(!source.equals(p.optString("source"))||!device.equals(p.optString("deviceId")))throw new Problem(422,"This history cursor belongs to another station.");
            ceiling=integer(p,"ceiling",0,Long.MAX_VALUE);lastMs=integer(p,"ms",0,Long.MAX_VALUE);lastId=integer(p,"id",1,Long.MAX_VALUE);
        }
        String where="source=? AND device_id=? AND id<=?";String[] base={source,device,String.valueOf(ceiling)};
        long total=singleLong(db,"SELECT COUNT(*) FROM readings WHERE "+where,base);
        String[] args=base;
        if(lastMs!=null){where+=" AND (measured_ms<? OR (measured_ms=? AND id<?))";args=new String[]{source,device,String.valueOf(ceiling),String.valueOf(lastMs),String.valueOf(lastMs),String.valueOf(lastId)};}
        JSONArray items=new JSONArray();String next=null;
        try(Cursor c=db.rawQuery("SELECT id,source,device_id,timestamp,received_at,payload,settings_json,measured_ms FROM readings WHERE "+where+" ORDER BY measured_ms DESC,id DESC LIMIT "+(limit+1),args)) {
            JSONObject last=null;long ms=0;
            while(c.moveToNext()) {
                if(items.length()==limit){JSONObject token=json("source",source,"deviceId",device,"ceiling",ceiling,"ms",ms,"id",last.optLong("id"));next=Base64.encodeToString(token.toString().getBytes(StandardCharsets.UTF_8),Base64.URL_SAFE|Base64.NO_WRAP|Base64.NO_PADDING);break;}
                last=json("id",c.getLong(0),"source",c.getString(1),"deviceId",c.getString(2),"timestamp",c.getString(3),"receivedAt",c.getString(4),"payload",object(c.getString(5)),"settings",c.isNull(6)?null:object(c.getString(6)));items.put(last);ms=c.getLong(7);
            }
        }return json("items",items,"nextCursor",next,"total",total);
    }
    private JSONArray context(SQLiteDatabase db,String device,long measured,long id) {
        JSONArray rows=new JSONArray();
        try(Cursor c=db.rawQuery("SELECT id,source,device_id,timestamp,received_at,payload,settings_json FROM readings WHERE source='esp32' AND device_id=? AND (measured_ms<? OR (measured_ms=? AND id<=?)) ORDER BY measured_ms DESC,id DESC LIMIT 5",new String[]{device,String.valueOf(measured),String.valueOf(measured),String.valueOf(id)})) {
            while(c.moveToNext())rows.put(json("id",c.getLong(0),"source",c.getString(1),"deviceId",c.getString(2),"timestamp",c.getString(3),"receivedAt",c.getString(4),"payload",object(c.getString(5)),"settings",c.isNull(6)?null:object(c.getString(6))));
        }return rows;
    }
    private JSONObject ledger(SQLiteDatabase db,String device) {
        String key="alert-status:esp32:"+device;Object stored=getState(key).opt("value");JSONObject value=stored instanceof JSONObject?(JSONObject)stored:null;
        if(stored!=null&&stored!=JSONObject.NULL&&(value==null||!AlertLedger.validState(value))){quarantine(db,key,stored.toString());writeState(db,"monitor-error:"+device,"Monitoring state was damaged. Saved events remain available; the current station is being rechecked.");db.delete("state","key=?",new String[]{key});return null;}
        return value;
    }
    private JSONObject evaluation(JSONArray rows,JSONObject policy,long now) {
        JSONObject result=SignalEvaluator.evaluate(rows,policy.optJSONObject("settings"),now);
        put(result,"watchRevision",policy.optLong("revision"));put(result,"watchSettings",policy.optJSONObject("settings"));
        put(result,"segment",result.optInt("calibrationRevision",-1)+":"+policy.optLong("revision"));
        if(!policy.optBoolean("valid")){
            JSONObject evidence=result.optJSONObject("evidence");if(evidence==null){evidence=new JSONObject();put(result,"evidence",evidence);}
            for(String kind:new String[]{"water_warning","water_watch","water_rise"})if("clear".equals(evidence.optString(kind)))put(evidence,kind,"unknown");
        }
        return result;
    }
    private JSONArray commitEvaluation(SQLiteDatabase db,String device,JSONObject evaluation,long now) {
        JSONObject change=AlertLedger.apply(ledger(db,device),evaluation,now),state=change.optJSONObject("state");JSONArray events=change.optJSONArray("events");writeState(db,"alert-status:esp32:"+device,state);
        for(int i=0;i<events.length();i++) {
            JSONObject event=events.optJSONObject(i);put(event,"deviceId",device);put(event,"source","esp32");ContentValues row=new ContentValues();row.put("device_id",device);row.put("event_json",event.toString());long eventId=db.insertOrThrow("alert_events",null,row);indexEvent(db,eventId,device,event.optString("sample",""));
            String kind=event.optString("kind");ContentValues obsolete=new ContentValues();obsolete.put("status","obsolete");
            if(event.optString("phase").equals("settings_changed"))db.update("notification_outbox",obsolete,"device_id=? AND status='pending' AND kind NOT IN ('connection','sensor_health')",new String[]{device});
            else db.update("notification_outbox",obsolete,"device_id=? AND kind=? AND status='pending'",new String[]{device,kind});
            if("active".equals(event.optString("phase"))){ContentValues pending=new ContentValues();pending.put("event_id",eventId);pending.put("device_id",device);pending.put("kind",kind);pending.put("event_json",event.toString());pending.put("status","pending");db.insertOrThrow("notification_outbox",null,pending);}
        }
        return events;
    }
    /** Reading insertion, processing position and events share the ingest transaction. */
    private JSONArray processSamples(SQLiteDatabase db,String device,long now) {
        long cursor=0,lastTime=-1,lastId=0;
        try(Cursor c=db.rawQuery("SELECT last_id,last_measured_ms,last_measurement_id FROM alert_processing WHERE device_id=?",new String[]{device})){if(c.moveToFirst()){cursor=c.getLong(0);lastTime=c.getLong(1);lastId=c.getLong(2);}}
        JSONArray emitted=new JSONArray();JSONObject policy=getWatch("esp32",device);
        try(Cursor c=db.rawQuery("SELECT id,measured_ms,received_at FROM readings WHERE source='esp32' AND device_id=? AND id>? ORDER BY id LIMIT 500",new String[]{device,String.valueOf(cursor)})) {
            while(c.moveToNext()) {
                long id=c.getLong(0),measured=c.getLong(1),received=SignalEvaluator.parseTime(c.getString(2));
                if(measured>lastTime||(measured==lastTime&&id>lastId)) {
                    // Interpret the accepted sample at receipt time, not a later monitor tick.
                    JSONObject result=evaluation(context(db,device,measured,id),policy,received);
                    JSONArray events=commitEvaluation(db,device,result,received);for(int i=0;i<events.length();i++)emitted.put(events.optJSONObject(i));lastTime=measured;lastId=id;
                }
                cursor=id;writeCursor(db,device,cursor,lastTime,lastId);
            }
        }return emitted;
    }
    public synchronized JSONArray evaluateAlerts(String device,long now,long startedAt) {
        SQLiteDatabase db=getWritableDatabase();db.beginTransaction();
        try {
            JSONArray events=processSamples(db,device,now);JSONObject policy=getWatch("esp32",device);JSONArray rows=readings("esp32",device,5,null).optJSONArray("items");
            JSONObject result;
            if(rows.length()==0) {
                String key="first-reading:"+device;Object saved=getState(key).opt("value");long since=saved instanceof Number?((Number)saved).longValue():Math.max(startedAt,now);if(!(saved instanceof Number)||since<startedAt){since=Math.max(startedAt,now);writeState(db,key,since);}
                if(now-since<60000){db.setTransactionSuccessful();return events;}
                result=json("signals",new JSONArray().put(json("kind","connection","level",1,"title","No first reading received","detail","The station has sent no measurements after 60 seconds. Check power, Wi-Fi and its receiver address.")),"evidence",json("connection","active"),"fresh",false,"sample","","time",null,"segment","no-readings:"+policy.optLong("revision"),"watchRevision",policy.optLong("revision"),"watchSettings",policy.optJSONObject("settings"));
            }else result=evaluation(rows,policy,now);
            JSONArray clockEvents=commitEvaluation(db,device,result,now);for(int i=0;i<clockEvents.length();i++)events.put(clockEvents.optJSONObject(i));
            db.setTransactionSuccessful();return events;
        }finally{db.endTransaction();}
    }
    public synchronized JSONArray evaluateAlerts(String device,long now){return evaluateAlerts(device,now,now);}
    public synchronized JSONObject getAlertState(String source,String device){
        source(source);station(device);JSONArray items=new JSONArray();if(!"esp32".equals(source))return json("conditions",items);
        JSONObject state=ledger(getWritableDatabase(),device),conditions=state==null?null:state.optJSONObject("conditions");if(conditions==null)return json("conditions",items);
        JSONObject current=evaluation(readings("esp32",device,5,null).optJSONArray("items"),getWatch(source,device),System.currentTimeMillis());JSONObject evidence=current.optJSONObject("evidence");
        java.util.Iterator<String> names=conditions.keys();while(names.hasNext()){String kind=names.next();JSONObject condition=conditions.optJSONObject(kind);if(condition==null||!condition.optBoolean("active"))continue;boolean lastKnown=!current.optBoolean("fresh")||evidence==null||!"active".equals(evidence.optString(kind));items.put(json("kind",kind,"level",condition.optInt("level"),"title",condition.optString("title",kind.replace('_',' ')),"detail",condition.optString("detail","A previous condition needs a fresh measurement or settings review."),"measuredAt",condition.opt("lastKnownAt"),"lastKnown",lastKnown));}
        return json("conditions",items);
    }
    public synchronized boolean hasMonitoringIssue(){try(Cursor c=getReadableDatabase().rawQuery("SELECT 1 FROM state WHERE key LIKE 'monitor-error:%' LIMIT 1",null)){return c.moveToFirst();}}
    public synchronized JSONArray pendingNotifications(String device,long now) {
        JSONArray result=new JSONArray();JSONObject state=ledger(getWritableDatabase(),device),conditions=state==null?null:state.optJSONObject("conditions");
        JSONArray latest=readings("esp32",device,5,null).optJSONArray("items");JSONObject current=evaluation(latest,getWatch("esp32",device),now);JSONObject evidence=current.optJSONObject("evidence");
        try(Cursor c=getReadableDatabase().rawQuery("SELECT event_id,kind,event_json FROM notification_outbox WHERE device_id=? AND status='pending' ORDER BY event_id",new String[]{device})) {
            while(c.moveToNext()){
                String kind=c.getString(1);JSONObject condition=conditions==null?null:conditions.optJSONObject(kind);boolean active=condition!=null&&condition.optBoolean("active");
                if(!active){notificationStatus(c.getLong(0),"obsolete");continue;}
                // Never deliver an old environmental warning after it becomes stale or unavailable.
                boolean relevant=kind.equals("connection")?latest.length()==0||!current.optBoolean("fresh"):evidence!=null&&"active".equals(evidence.optString(kind));
                if(relevant){JSONObject item=object(c.getString(2));put(item,"eventId",c.getLong(0));result.put(item);}
            }
        }return result;
    }
    public synchronized void notificationDelivered(long eventId){notificationStatus(eventId,"delivered");}
    private void notificationStatus(long eventId,String status){ContentValues values=new ContentValues();values.put("status",status);getWritableDatabase().update("notification_outbox",values,"event_id=?",new String[]{String.valueOf(eventId)});}
    /** No delete/update exists for readings: ceiling-ID produces an immutable export snapshot. */
    public void export(String source,String device,Writer writer) throws IOException {
        source(source);station(device);final SQLiteDatabase db;final long ceiling,eventCeiling;final JSONObject exportPolicy;
        synchronized(this) {db=getReadableDatabase();ceiling=singleLong(db,"SELECT COALESCE(MAX(id),0) FROM readings WHERE source=? AND device_id=?",new String[]{source,device});eventCeiling=singleLong(db,"SELECT COALESCE(MAX(id),0) FROM alert_events",null);exportPolicy=json("interpretation","Settings at export time; not historical policy", "record",getWatch(source,device));}
        writer.write("id,source,device_id,timestamp,received_at,sample_id,config_revision,ph,turbidity,temperature,water_level,raw_json,settings_json,payload_json,export_watch_settings_json,alert_events_json\r\n");
        try(Cursor c=db.rawQuery("SELECT id,source,device_id,timestamp,received_at,sample_id,payload,settings_json FROM readings WHERE source=? AND device_id=? AND id<=? ORDER BY measured_ms,id",new String[]{source,device,String.valueOf(ceiling)})) {
            while(c.moveToNext()) {
                JSONObject p=object(c.getString(6));boolean simulation="simulation".equals(c.getString(1));
                SignalEvaluator.Reading derived=new SignalEvaluator.Reading(json("id",c.getLong(0),"deviceId",device,"timestamp",c.getString(3),"receivedAt",c.getString(4),"payload",p,"settings",c.isNull(7)?null:object(c.getString(7))),System.currentTimeMillis());
                JSONArray events=new JSONArray();if(!simulation)try(Cursor e=db.rawQuery("SELECT event_json FROM alert_events e JOIN alert_event_samples s ON s.event_id=e.id WHERE s.device_id=? AND s.sample_id=? AND e.id<=? ORDER BY e.id",new String[]{device,c.getString(5),String.valueOf(eventCeiling)})){while(e.moveToNext()){JSONObject event=object(e.getString(0));if(!event.has("watchRevision"))put(event,"provenance","Legacy interpretation unavailable");events.put(event);}}
                Object[] cells={c.getLong(0),c.getString(1),c.getString(2),c.getString(3),c.getString(4),c.getString(5),p.opt("config_revision"),simulation?p.opt("ph"):derived.values[0],simulation?p.opt("turbidity"):derived.values[1],simulation?p.opt("temperature"):derived.values[2],simulation?p.opt("water_level"):derived.values[3],p.optJSONObject("raw"),c.isNull(7)?null:c.getString(7),p,exportPolicy,events};
                for(int i=0;i<cells.length;i++){if(i>0)writer.write(',');writer.write(csv(cells[i]));}writer.write("\r\n");
            }
        }writer.flush();
    }
}
