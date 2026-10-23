package com.riverguard.v2;
import org.json.JSONObject;
import java.time.Instant;
import static com.riverguard.v2.PhoneValidation.*;
/** Bounded, deterministic scenarios. Only the application runtime writes samples. */
public final class DemoState {
    private DemoState() {}
    public static final String[] SCENARIOS={"Normal river","Cloudier water","Sudden change","Rising water level"};
    public static String scenario(String value){for(String s:SCENARIOS)if(s.equals(value))return value;throw new Problem(422,"Choose an available demonstration scenario.");}
    public static JSONObject initial(){return json("revision",0,"scenario",SCENARIOS[0],"running",true,"step",0,"error",null);}
    public static JSONObject sample(String scenario,long step,long now){
        scenario(scenario);long phase=((step%120)+120)%120;double wave=Math.sin(phase*.7);
        JSONObject p=json("timestamp",Instant.ofEpochMilli(now).toString(),"ph",7.2+.08*wave,"turbidity",14+2*wave,"temperature",27+.2*wave,"water_level",30+.15*wave,"scenario",scenario);
        if(phase>=10){if(scenario.equals("Cloudier water"))put(p,"turbidity",Math.min(250,65+(phase-10)*2));if(scenario.equals("Sudden change")){put(p,"ph",5.8);put(p,"turbidity",80);}if(scenario.equals("Rising water level"))put(p,"water_level",Math.min(150,45+(phase-10)*4));}
        return p;
    }
}
