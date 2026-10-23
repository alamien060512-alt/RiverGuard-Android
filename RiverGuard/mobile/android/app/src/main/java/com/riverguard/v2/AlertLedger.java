package com.riverguard.v2;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.Iterator;
import static com.riverguard.v2.PhoneValidation.*;

/** Only positive, distinct, same-segment evidence may recover a warning. */
public final class AlertLedger {
    private AlertLedger() {}
    private static boolean environmental(String kind){return !kind.equals("connection")&&!kind.equals("sensor_health");}
    public static boolean validState(JSONObject state){
        JSONObject conditions=state==null?null:state.optJSONObject("conditions");if(conditions==null)return false;
        Iterator<String> check=conditions.keys();while(check.hasNext()){JSONObject condition=conditions.optJSONObject(check.next());if(condition==null||!(condition.opt("active") instanceof Boolean))return false;try{integer(condition,"level",0,3);integer(condition,"clearCount",0,3);}catch(Problem invalid){return false;}}return true;
    }
    public static JSONObject apply(JSONObject previous,JSONObject evaluation,long now) {
        JSONObject state=previous==null?json("conditions",new JSONObject()):copy(previous);
        JSONObject conditions=state.optJSONObject("conditions");
        if(!validState(state))throw new Problem(500,"Saved monitoring state is invalid; preserve data and review station health.");
        JSONArray events=new JSONArray(),signals=evaluation.optJSONArray("signals");if(signals==null)signals=new JSONArray();
        JSONObject evidence=evaluation.optJSONObject("evidence");if(evidence==null)evidence=new JSONObject();
        String sample=evaluation.optString("sample",""),segment=evaluation.optString("segment","unknown");
        boolean newSample=!sample.isEmpty()&&!sample.equals(state.optString("sample",""));
        boolean changed=state.has("segment")&&!segment.equals(state.optString("segment"));
        if(changed) {
            Iterator<String> names=conditions.keys();while(names.hasNext()) {String kind=names.next();JSONObject old=conditions.optJSONObject(kind);if(old!=null&&environmental(kind)){put(old,"clearCount",0);if(old.optBoolean("active"))put(old,"recheck",true);}}
            boolean anyActive=false;Iterator<String> activeNames=conditions.keys();while(activeNames.hasNext()){JSONObject old=conditions.optJSONObject(activeNames.next());anyActive|=old!=null&&old.optBoolean("active");}
            if(anyActive)events.put(event("settings", "settings_changed",1,"Settings changed: recheck needed","Calibration or watch settings changed. This is not evidence that an environmental condition recovered.",evaluation,now));
        }
        for(int i=0;i<signals.length();i++) {
            JSONObject signal=signals.optJSONObject(i);if(signal==null)continue;String kind=signal.optString("kind");
            JSONObject old=conditions.optJSONObject(kind);int level=signal.optInt("level");
            if(old==null||!old.optBoolean("active")||old.optInt("level")<level||(changed&&environmental(kind)))events.put(event(kind,"active",level,signal.optString("title"),signal.optString("detail"),evaluation,now));
            put(conditions,kind,json("active",true,"level",level,"clearCount",0,"recheck",old!=null&&old.optBoolean("active")&&old.optBoolean("recheck"),"lastKnownAt",evaluation.opt("time"),"title",signal.optString("title"),"detail",signal.optString("detail")));
        }
        if(newSample&&evaluation.optBoolean("fresh")) {
            Iterator<String> names=conditions.keys();
            while(names.hasNext()) {
                String kind=names.next();JSONObject old=conditions.optJSONObject(kind);
                if(old==null||!old.optBoolean("active"))continue;
                if(!"clear".equals(evidence.optString(kind,"unknown"))){put(old,"clearCount",0);continue;}
                // A new segment is rechecked explicitly; it never claims measured recovery.
                int count=old.optInt("clearCount")+1;put(old,"clearCount",count);
                if(count>=3) {boolean rechecked=old.optBoolean("recheck");put(old,"active",false);put(old,"recheck",false);events.put(event(kind,rechecked?"rechecked":"recovered",0,rechecked?"Fresh readings checked against updated settings":"Reading check returned within its reference",rechecked?"Three usable measurements meet the updated settings. This records a recheck, not an improvement in water conditions.":"Three distinct usable readings in the same calibration and policy segment are within reference. This does not establish water safety.",evaluation,now));}
            }
        }
        put(state,"sample",sample);put(state,"segment",segment);return json("state",state,"events",events);
    }
    private static JSONObject event(String kind,String phase,int level,String title,String detail,JSONObject evaluation,long now) {
        return json("kind",kind,"phase",phase,"level",level,"title",title,"detail",detail,"timestamp",java.time.Instant.ofEpochMilli(now).toString(),"evaluatedAt",java.time.Instant.ofEpochMilli(now).toString(),"measuredAt",evaluation.opt("time"),"sample",evaluation.opt("sample"),"calibrationRevision",evaluation.opt("calibrationRevision"),"watchRevision",evaluation.opt("watchRevision"),"watchSettings",evaluation.opt("watchSettings"),"provenance","captured");
    }
}
