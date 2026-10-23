package com.riverguard.v2;

import org.json.JSONArray;
import org.json.JSONObject;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import static com.riverguard.v2.PhoneValidation.*;

/** Native background counterpart of the shared visible rules; no WebView is needed. */
public final class SignalEvaluator {
    private SignalEvaluator() {}
    public static final class Reading {
        public final JSONObject record;
        public final String station, sample;
        public final long time, received;
        public final int revision;
        public final Double[] values = new Double[4];
        public final boolean fresh;
        Reading(JSONObject row,long now) {
            record=row;JSONObject packet=row.optJSONObject("payload"), settings=row.optJSONObject("settings");
            if(packet==null)packet=new JSONObject();
            station=row.optString("deviceId",packet.optString("device_id"));sample=packet.optString("sample_id",String.valueOf(row.optLong("id")));
            time=parseTime(row.optString("timestamp",packet.optString("timestamp")));received=parseTime(row.optString("receivedAt"));
            revision=packet.optInt("config_revision",-1);fresh=time>0&&received>0&&now-time>=-5000&&now-time<=30000&&now-received>=-5000&&now-received<=30000;
            JSONObject raw=packet.optJSONObject("raw"),health=packet.optJSONObject("sensor_health");
            if(raw==null)raw=new JSONObject();if(health==null)health=new JSONObject();
            if(settings==null||revision<0)return;
            JSONObject enabled=settings.optJSONObject("enabled");
            for(int i=0;i<FIELDS.length;i++) {
                String field=FIELDS[i],state=health.optString(field,"");
                if(enabled==null||!enabled.optBoolean(field)||!state.isEmpty()&&!state.equals("ok")&&!state.equals("uncalibrated")&&!state.equals("unknown"))continue;
                if(i<2) {
                    Double mv=finite(raw,field+"_mv"),adc=finite(raw,field+"_adc");
                    if(mv==null||mv<150||mv>3050||(raw.has(field+"_adc")&&!raw.isNull(field+"_adc")&&(adc==null||adc<=5||adc>=4090)))continue;
                    values[i]=interpolate(mv,settings.optJSONArray(field));
                } else if(i==2) {
                    Double temp=finite(raw,"temperature_c"),offset=finite(settings,"temperature_offset");
                    if(temp!=null&&offset!=null&&Math.abs(offset)<=5&&temp>=-55&&temp<=125&&temp+offset>=-55&&temp+offset<=125)values[i]=temp+offset;
                } else {
                    Double distance=finite(raw,"distance_cm");JSONObject level=settings.optJSONObject("level");
                    if(distance!=null&&level!=null) {
                        Double reference=finite(level,"reference"),min=finite(level,"minimum"),max=finite(level,"maximum");
                        if(reference!=null&&min!=null&&max!=null&&min>0&&max>min&&reference>min&&reference<=max&&distance>=min&&distance<=max&&distance<=reference)values[i]=reference-distance;
                    }
                }
            }
        }
    }
    public static Double finite(JSONObject object,String key) {Object v=object.opt(key);if(!(v instanceof Number))return null;double d=((Number)v).doubleValue();return Double.isNaN(d)||Double.isInfinite(d)?null:d;}
    static long parseTime(String value) {try{return Instant.parse(value).toEpochMilli();}catch(Exception e){return -1;}}
    private static Double interpolate(double value,JSONArray points) {
        if(points==null||points.length()<2)return null;
        for(int i=1;i<points.length();i++) {
            JSONObject a=points.optJSONObject(i-1),b=points.optJSONObject(i);if(a==null||b==null)return null;
            Double av=finite(a,"mv"),bv=finite(b,"mv"),ay=finite(a,"value"),by=finite(b,"value");
            if(av==null||bv==null||ay==null||by==null||bv-av<1)return null;
            if(value>=av&&value<=bv)return ay+(value-av)*(by-ay)/(bv-av);
        }return null;
    }
    public static JSONObject watch(JSONObject value) {
        keys(value,"elevated","warning","rise_limit");
        double elevated=number(value,"elevated",0,1000),warning=number(value,"warning",0,1000),rise=number(value,"rise_limit",0.000001,10000);
        if(warning<=elevated)throw new Problem(422,"Warning height must be above watch height.");
        return json("elevated",elevated,"warning",warning,"rise_limit",rise);
    }
    private static double setting(JSONObject watch,String key,double fallback) {Double n=watch==null?null:finite(watch,key);return n==null?fallback:n;}
    public static JSONObject evaluate(JSONArray newestFirst,JSONObject watch,long now) {
        JSONArray signals=new JSONArray();if(newestFirst.length()==0)return json("signals",signals,"evidence",new JSONObject(),"fresh",false,"sample",null,"time",null,"rate",null);
        List<Reading> rows=new ArrayList<>();for(int i=0;i<newestFirst.length();i++){JSONObject row=newestFirst.optJSONObject(i);if(row!=null)rows.add(new Reading(row,now));}
        rows.sort((a,b)->{int d=Long.compare(b.time,a.time);return d!=0?d:Long.compare(b.record.optLong("id"),a.record.optLong("id"));});
        if(rows.isEmpty())return json("signals",signals,"fresh",false,"sample",null,"time",null,"rate",null);
        Reading current=rows.get(0);Double rate=rate(rows);double elevated=setting(watch,"elevated",70),warning=setting(watch,"warning",100),rise=setting(watch,"rise_limit",10);
        // Never generate current environmental alerts from stale/future evidence.
        // The interface separately retains last-known conditions with their age.
        if(current.fresh) {
            Double height=current.values[3];
            if(height!=null&&height>=warning)add(signals,"water_warning",3,"Water height needs attention",fmt(height)+" cm is at or above your warning setting. Check the physical water height now.");
            if(rate!=null&&rate>=rise)add(signals,"water_rise",3,"Water is rising quickly",fmt(rate)+" cm/min across five same-calibration readings. Check the station and water height.");
            if(height!=null&&height>=elevated&&height<warning)add(signals,"water_watch",2,"Watch the water height",fmt(height)+" cm is at or above your watch setting.");
            double[] low={6.5,0,20},high={8.5,50,32};String[] names={"Acidity","Cloudiness","Temperature"};
            for(int i=0;i<3;i++)if(current.values[i]!=null&&(current.values[i]<low[i]||current.values[i]>high[i]))add(signals,FIELDS[i]+"_reference",1,names[i]+" has changed",names[i]+" is outside the prototype reference. Inspect the sensor and water conditions; this does not identify pollution.");
            int missing=0;for(Double value:current.values)if(value==null)missing++;
            if(missing>0)add(signals,"sensor_health",1,"Some sensors need checking",missing+" readings are unavailable. Check connections and calibration. Any known water-height warning still matters.");
        } else add(signals,"connection",1,"Fresh river readings are missing","Check that the station and phone share Wi-Fi and monitoring is active. Saved readings cannot describe current conditions.");
        JSONObject evidence=new JSONObject();
        evidence(evidence,"water_warning",current.fresh&&current.values[3]!=null,current.values[3]!=null&&current.values[3]>=warning);
        evidence(evidence,"water_watch",current.fresh&&current.values[3]!=null&&current.values[3]<warning,current.values[3]!=null&&current.values[3]>=elevated);
        evidence(evidence,"water_rise",current.fresh&&rate!=null,rate!=null&&rate>=rise);
        double[] lows={6.5,0,20}, highs={8.5,50,32};
        for(int i=0;i<3;i++) evidence(evidence,FIELDS[i]+"_reference",current.fresh&&current.values[i]!=null,current.values[i]!=null&&(current.values[i]<lows[i]||current.values[i]>highs[i]));
        boolean missing=false;for(Double value:current.values)missing|=value==null;
        evidence(evidence,"sensor_health",current.fresh,missing);evidence(evidence,"connection",true,!current.fresh);
        return json("signals",signals,"evidence",evidence,"fresh",current.fresh,"sample",current.sample,"time",current.time>0?Instant.ofEpochMilli(current.time).toString():null,"measuredAt",current.time>0?Instant.ofEpochMilli(current.time).toString():null,"calibrationRevision",current.revision,"segment",String.valueOf(current.revision)+":"+(watch==null?"unconfigured":watch.toString()),"rate",rate);
    }
    private static void evidence(JSONObject target,String kind,boolean known,boolean active){put(target,kind,!known?"unknown":active?"active":"clear");}
    private static String fmt(double v){return String.format(Locale.ROOT,"%.1f",v);}
    private static void add(JSONArray values,String kind,int level,String title,String detail){values.put(json("kind",kind,"level",level,"title",title,"detail",detail));}
    private static Double rate(List<Reading> readings) {
        if(readings.size()<5||!readings.get(0).fresh)return null;
        Reading latest=readings.get(0);double sx=0,sy=0,sxx=0,sxy=0;long first=readings.get(4).time;
        for(int i=0;i<5;i++) {
            Reading r=readings.get(i);if(r.values[3]==null||r.revision!=latest.revision||!r.station.equals(latest.station)||r.time<=0||(i>0&&readings.get(i-1).time-r.time>30000))return null;
            double x=(r.time-first)/1000.0,y=r.values[3];sx+=x;sy+=y;sxx+=x*x;sxy+=x*y;
        }
        if(latest.time-first<8000)return null;double denominator=5*sxx-sx*sx;
        return denominator>0?Math.round((5*sxy-sx*sy)/denominator*6000)/100.0:null;
    }
}
