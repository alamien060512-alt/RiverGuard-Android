package com.riverguard.v2;

import org.json.JSONObject;
import org.junit.Test;
import java.time.Instant;
import static org.junit.Assert.*;

public class PhoneValidationTest {
    private JSONObject packet() {
        return PhoneValidation.object("{\"device_id\":\"riverguard-01\",\"mode\":\"hardware\",\"sample_id\":\"boot-1\",\"setup_protocol\":\"remote-v1\",\"config_revision\":0,\"raw\":{\"ph_mv\":1800,\"temperature_c\":27,\"distance_cm\":45},\"sensor_health\":{\"ph\":\"uncalibrated\",\"temperature\":\"ok\"}}");
    }
    private static void rejects(Runnable action,int status) {
        try {action.run();fail("Expected validation rejection");}catch(PhoneValidation.Problem error){assertEquals(status,error.status);}
    }
    @Test public void hardwareRequiresClockRawProtocolAndStationBoundIdentity() {
        JSONObject p=packet();PhoneValidation.put(p,"timestamp",Instant.now().toString());
        assertTrue(PhoneValidation.hardware(p,System.currentTimeMillis())>0);
        PhoneValidation.put(p,"timestamp",Instant.now().plusSeconds(10).toString());rejects(()->PhoneValidation.hardware(p,System.currentTimeMillis()),422);
        PhoneValidation.put(p,"timestamp","2026-10-22T08:00:00");rejects(()->PhoneValidation.hardware(p,System.currentTimeMillis()),422);
        PhoneValidation.put(p,"timestamp",Instant.now().toString());PhoneValidation.put(p.optJSONObject("raw"),"distance_cm",1001);rejects(()->PhoneValidation.hardware(p,System.currentTimeMillis()),422);
        PhoneValidation.put(p.optJSONObject("raw"),"distance_cm",null);PhoneValidation.put(p,"setup_protocol","legacy");rejects(()->PhoneValidation.hardware(p,System.currentTimeMillis()),422);
    }
    @Test public void calibrationRejectsAmbiguousReversingReferencesAndUnreadyVerification() {
        JSONObject cfg=PhoneValidation.defaults();PhoneValidation.put(cfg,"verified",true);rejects(()->PhoneValidation.settings(cfg),422);
        PhoneValidation.put(cfg,"verified",false);
        PhoneValidation.put(cfg,"turbidity",PhoneValidation.object("{\"points\":[{\"mv\":1000,\"value\":100},{\"mv\":1500,\"value\":0},{\"mv\":2000,\"value\":50}]}").optJSONArray("points"));
        rejects(()->PhoneValidation.settings(cfg),422);
        PhoneValidation.put(cfg,"turbidity",null);PhoneValidation.put(cfg,"level",PhoneValidation.json("reference",20,"minimum",25,"maximum",600));rejects(()->PhoneValidation.settings(cfg),422);
    }
    @Test public void validDefaultsAndExactNullsSurvive() {
        JSONObject cfg=PhoneValidation.settings(PhoneValidation.defaults());assertFalse(cfg.optBoolean("verified"));assertTrue(cfg.isNull("ph"));assertEquals(4,cfg.optJSONObject("enabled").length());
        JSONObject p=packet();PhoneValidation.put(p,"timestamp",Instant.now().minusSeconds(60).toString());assertTrue(PhoneValidation.hardware(p,System.currentTimeMillis())>0);
    }
    @Test public void demoPreservesSeedTimeAndCannotBecomeHardware() {
        String stamp=Instant.now().minusSeconds(20).toString();JSONObject sample=PhoneValidation.json("timestamp",stamp,"ph",7.1,"turbidity",12,"temperature",27,"water_level",35);
        JSONObject p=PhoneValidation.demo(sample);assertEquals(stamp,p.optString("timestamp"));assertEquals("simulation",p.optString("mode"));assertEquals("demo-station",p.optString("device_id"));
        PhoneValidation.put(sample,"device_id","sensor-01");rejects(()->PhoneValidation.demo(sample),422);
    }
    @Test public void csvOnlyEscapesFormulaTextAndPreservesNegativeNumericValues() {
        assertEquals("\"'=SUM(A1:A2)\"",PhoneValidation.csv("=SUM(A1:A2)"));assertEquals("\"'  @cmd\"",PhoneValidation.csv("  @cmd"));assertEquals("\"-2.5\"",PhoneValidation.csv(-2.5));assertEquals("\"a\"\"b\"",PhoneValidation.csv("a\"b"));
    }
    @Test public void credentialsAreRandomAndHashesNeverEqualTheirPlaintext() {String a=PhoneValidation.token(),b=PhoneValidation.token();assertEquals(64,a.length());assertNotEquals(a,b);assertNotEquals(a,PhoneValidation.digest(a));assertEquals(PhoneValidation.digest(a),PhoneValidation.digest(a));}
    @Test public void rejectsLenientJsonDuplicateKeysTrailingContentAndExcessiveNesting() {
        for(String text:new String[]{"{unquoted:1}","{'single':1}","{\"a\":1,}","{\"a\":1,\"a\":2}","{\"a\":1} trailing","{\"a\":01}"})rejects(()->PhoneValidation.object(text),422);
        StringBuilder deep=new StringBuilder("{\"a\":");for(int i=0;i<40;i++)deep.append('[');deep.append('0');for(int i=0;i<40;i++)deep.append(']');deep.append('}');rejects(()->PhoneValidation.object(deep.toString()),422);
        assertEquals(1,PhoneValidation.object("{\"a\":1,\"b\":[true,false,null,\"unicode \\u0061\"]}").optInt("a"));
    }
}
