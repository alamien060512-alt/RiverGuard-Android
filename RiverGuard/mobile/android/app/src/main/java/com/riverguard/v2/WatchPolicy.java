package com.riverguard.v2;
import org.json.JSONObject;
import static com.riverguard.v2.PhoneValidation.*;
public final class WatchPolicy {
    private WatchPolicy() {}
    public static JSONObject defaults(){return json("elevated",70,"warning",100,"rise_limit",10);}
    public static boolean valid(String source,JSONObject watch,JSONObject calibration,boolean needsReview){
        if(needsReview)return false;
        try{SignalEvaluator.watch(watch);}catch(RuntimeException error){return false;}
        if("simulation".equals(source))return true;
        JSONObject level=calibration==null?null:calibration.optJSONObject("level");
        if(level==null)return false;
        Double reference=SignalEvaluator.finite(level,"reference"),minimum=SignalEvaluator.finite(level,"minimum"),maximum=SignalEvaluator.finite(level,"maximum");
        return reference!=null&&minimum!=null&&maximum!=null&&minimum>0&&reference>minimum&&reference<=maximum&&watch.optDouble("warning")<=reference-minimum;
    }
}
