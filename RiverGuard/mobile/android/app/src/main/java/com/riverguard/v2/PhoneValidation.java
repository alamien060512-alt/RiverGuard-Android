package com.riverguard.v2;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/** Pure validation shared by the private bridge and bounded LAN receiver. */
public final class PhoneValidation {
    public static final int BODY_LIMIT = 32768;
    public static final String[] FIELDS = {"ph", "turbidity", "temperature", "water_level"};
    private static final SecureRandom RANDOM = new SecureRandom();
    private PhoneValidation() {}

    public static final class Problem extends RuntimeException {
        private static final long serialVersionUID = 1L;
        public final int status;
        public Problem(int status, String message) { super(message); this.status = status; }
    }

    public static JSONObject object(String value) {
        new StrictJson(value).validate();
        try { return new JSONObject(value); }
        catch (JSONException | StackOverflowError error) { throw new Problem(422, "Send a valid JSON object."); }
    }

    /** Android's org.json deliberately accepts non-JSON syntax; reject it at the boundary. */
    private static final class StrictJson {
        final String text;int position;
        StrictJson(String text){this.text=text==null?"":text;}
        void validate(){space();if(position>=text.length()||text.charAt(position)!='{')bad();value(0);space();if(position!=text.length())bad();}
        void bad(){throw new Problem(422,"Send one valid JSON object with nesting no deeper than32 levels.");}
        void space(){while(position<text.length()&&" \t\r\n".indexOf(text.charAt(position))>=0)position++;}
        boolean eat(char c){space();if(position<text.length()&&text.charAt(position)==c){position++;return true;}return false;}
        void value(int depth){
            space();if(depth>32||position>=text.length())bad();char c=text.charAt(position);
            if(c=='{'){position++;Set<String> names=new HashSet<>();if(eat('}'))return;do{space();if(position>=text.length()||text.charAt(position)!='"')bad();int start=position;quoted();String name;
                try{name=(String)new org.json.JSONTokener(text.substring(start,position)).nextValue();}catch(JSONException error){bad();return;}
                if(!names.add(name)||!eat(':'))bad();value(depth+1);if(eat('}'))return;}while(eat(','));bad();}
            else if(c=='['){position++;if(eat(']'))return;do{value(depth+1);if(eat(']'))return;}while(eat(','));bad();}
            else if(c=='"')quoted();
            else if(text.startsWith("true",position))position+=4;
            else if(text.startsWith("false",position))position+=5;
            else if(text.startsWith("null",position))position+=4;
            else {
                java.util.regex.Matcher number=java.util.regex.Pattern.compile("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?").matcher(text);number.region(position,text.length());if(!number.lookingAt())bad();position=number.end();
            }
        }
        void quoted(){
            position++;
            while(position<text.length()){
                char c=text.charAt(position++);if(c=='"')return;if(c<32)bad();
                if(c=='\\'){
                    if(position>=text.length())bad();char escape=text.charAt(position++);
                    if(escape=='u'){for(int i=0;i<4;i++){if(position>=text.length()||Character.digit(text.charAt(position++),16)<0)bad();}}
                    else if("\"\\/bfnrt".indexOf(escape)<0)bad();
                }
            }bad();
        }
    }

    public static JSONObject copy(JSONObject value) { return object(value.toString()); }
    public static String station(String id) {
        if (id == null || !id.matches("[A-Za-z0-9_-]{1,80}")) throw new Problem(422, "Use 1–80 letters, numbers, hyphens or underscores for the station ID.");
        return id;
    }
    public static String source(String source) {
        if (!"simulation".equals(source) && !"esp32".equals(source)) throw new Problem(422, "Choose demonstration or sensor readings.");
        return source;
    }
    public static String string(JSONObject value, String key, int min, int max) {
        Object item = value.opt(key);
        if (!(item instanceof String) || ((String)item).length() < min || ((String)item).length() > max) throw new Problem(422, "Invalid " + key + ".");
        return (String)item;
    }
    public static boolean bool(JSONObject value, String key) {
        Object item = value.opt(key);
        if (!(item instanceof Boolean)) throw new Problem(422, "Specify " + key + " as true or false.");
        return (Boolean)item;
    }
    public static long integer(JSONObject value, String key, long min, long max) {
        Object item = value.opt(key);
        if (!(item instanceof Number)) throw new Problem(422, "Invalid " + key + ".");
        double n = ((Number)item).doubleValue();
        if (Double.isNaN(n) || Double.isInfinite(n) || n != Math.rint(n) || n < min || n > max) throw new Problem(422, "Invalid " + key + ".");
        return ((Number)item).longValue();
    }
    public static double number(JSONObject value, String key, double min, double max) {
        Object item = value.opt(key);
        if (!(item instanceof Number)) throw new Problem(422, "Enter a number for " + key + ".");
        double n = ((Number)item).doubleValue();
        if (Double.isNaN(n) || Double.isInfinite(n) || n < min || n > max) throw new Problem(422, key + " is outside its supported range.");
        return n;
    }
    public static void keys(JSONObject value, String... allowed) {
        Set<String> names = new HashSet<>(Arrays.asList(allowed));
        java.util.Iterator<String> iterator = value.keys();
        while (iterator.hasNext()) if (!names.contains(iterator.next())) throw new Problem(422, "Unexpected field in this configuration or sample.");
    }
    public static JSONObject requireObject(JSONObject value, String key) {
        JSONObject object = value.optJSONObject(key);
        if (object == null) throw new Problem(422, "Specify " + key + " as an object.");
        return object;
    }
    public static void put(JSONObject value, String key, Object item) {
        try { value.put(key, item == null ? JSONObject.NULL : item); }
        catch (JSONException error) { throw new Problem(422, "Cannot store an invalid JSON value."); }
    }
    public static JSONObject json(Object... pairs) {
        JSONObject object = new JSONObject();
        for (int i = 0; i < pairs.length; i += 2) put(object, (String)pairs[i], pairs[i+1]);
        return object;
    }

    /** Drafts may contain unfinished/null numeric inputs, but not malformed form shapes. */
    public static void draft(Object incoming){
        if(incoming==null||incoming==JSONObject.NULL)return;
        if(!(incoming instanceof JSONObject))throw new Problem(422,"Specify a calibration draft.");JSONObject value=(JSONObject)incoming;
        requiredKeys(value,"revision","settings");integer(value,"revision",0,Integer.MAX_VALUE);JSONObject cfg=requireObject(value,"settings");requiredKeys(cfg,"enabled","ph","turbidity","level","temperature_offset","verified");JSONObject enabled=requireObject(cfg,"enabled");requiredKeys(enabled,FIELDS);for(String field:FIELDS)bool(enabled,field);bool(cfg,"verified");incomplete(cfg,"temperature_offset");
        if(!cfg.isNull("level")){JSONObject level=requireObject(cfg,"level");requiredKeys(level,"reference","minimum","maximum");for(String key:new String[]{"reference","minimum","maximum"})incomplete(level,key);}
        for(String field:new String[]{"ph","turbidity"})if(!cfg.isNull(field)){JSONArray points=cfg.optJSONArray(field);if(points==null||points.length()>5)throw new Problem(422,"Invalid calibration draft points.");for(int i=0;i<points.length();i++){JSONObject point=points.optJSONObject(i);if(point==null)throw new Problem(422,"Invalid calibration draft point.");requiredKeys(point,"mv","value");incomplete(point,"mv");incomplete(point,"value");}}
    }
    private static void requiredKeys(JSONObject value,String... allowed){keys(value,allowed);for(String key:allowed)if(!value.has(key))throw new Problem(422,"A saved draft field is missing.");}
    private static void incomplete(JSONObject value,String key){if(!value.isNull(key))number(value,key,-1e12,1e12);}

    public static JSONObject defaults() {
        JSONObject enabled = new JSONObject();
        for (String field : FIELDS) put(enabled, field, false);
        return json("enabled", enabled, "ph", null, "turbidity", null, "level", null, "temperature_offset", 0, "verified", false);
    }

    public static JSONObject settings(JSONObject incoming) {
        JSONObject result = copy(incoming);
        keys(result, "enabled", "ph", "turbidity", "level", "temperature_offset", "verified");
        JSONObject enabled = requireObject(result, "enabled");
        keys(enabled, FIELDS);
        boolean allEnabled = true;
        for (String field : FIELDS) allEnabled &= bool(enabled, field);
        for (String field : new String[]{"ph", "turbidity"}) {
            if (result.isNull(field)) continue;
            JSONArray points = result.optJSONArray(field);
            int max = field.equals("ph") ? 2 : 5;
            if (points == null || points.length() < 2 || points.length() > max) throw new Problem(422, "Use two pH buffers or two to five NTU standards.");
            JSONObject[] sorted = new JSONObject[points.length()];
            Set<Double> known = new HashSet<>();
            for (int i = 0; i < points.length(); i++) {
                JSONObject point = points.optJSONObject(i);
                if (point == null) throw new Problem(422, "Enter both the measured mV and known reference value.");
                keys(point, "mv", "value");
                number(point, "mv", 150, 3050);
                double reference = number(point, "value", 0, field.equals("ph") ? 14 : 10000);
                if (!known.add(reference)) throw new Problem(422, "Each reference needs a different known value.");
                sorted[i] = point;
            }
            Arrays.sort(sorted, (a, b) -> Double.compare(a.optDouble("mv"), b.optDouble("mv")));
            double direction = 0;
            for (int i = 1; i < sorted.length; i++) {
                if (sorted[i].optDouble("mv") - sorted[i-1].optDouble("mv") < 1) throw new Problem(422, "Reference signals must be at least 1 mV apart.");
                double delta = sorted[i].optDouble("value") - sorted[i-1].optDouble("value");
                if (field.equals("ph") && Math.abs(delta) < .1) throw new Problem(422, "Use different pH buffers at least 0.1 pH apart.");
                if (direction != 0 && Math.signum(delta) != direction) throw new Problem(422, "The cloudiness references reverse direction. Recheck the standards and settled signals.");
                direction = Math.signum(delta);
            }
            JSONArray normalized = new JSONArray();
            for (JSONObject point : sorted) normalized.put(point);
            put(result, field, normalized);
        }
        if (!result.isNull("level")) {
            JSONObject level = requireObject(result, "level");
            keys(level, "reference", "minimum", "maximum");
            double reference = number(level, "reference", .001, 1000);
            double minimum = number(level, "minimum", .001, 1000);
            double maximum = number(level, "maximum", .001, 1000);
            if (!(minimum < reference && reference <= maximum)) throw new Problem(422, "Zero-level distance must be above the verified minimum and within the maximum.");
        }
        number(result, "temperature_offset", -5, 5);
        if (bool(result, "verified") && (!allEnabled || result.isNull("ph") || result.isNull("turbidity") || result.isNull("level"))) throw new Problem(422, "Verify after enabling and calibrating all four channels.");
        return result;
    }

    /** Offset is required; no timezone or clock is invented for sensor evidence. */
    public static long timestamp(JSONObject packet, long now) {
        String text = string(packet, "timestamp", 20, 64);
        final Instant stamp;
        try { stamp = OffsetDateTime.parse(text).toInstant(); }
        catch (RuntimeException error) { throw new Problem(422, "Include a valid ISO measurement time with timezone."); }
        long measured;
        try { measured = stamp.toEpochMilli(); }
        catch (ArithmeticException error) { throw new Problem(422, "Measurement time is outside the supported range."); }
        if (measured < 946684800000L || measured > now + 5000) throw new Problem(422, "Check the station clock. Measurement time must be from 2000 onward and no more than five seconds ahead.");
        return measured;
    }

    public static long hardware(JSONObject packet, long now) {
        keys(packet, "device_id", "mode", "sample_id", "timestamp", "setup_protocol", "config_revision", "raw", "sensor_health", "calibrated", "calibration_id", "tds_ppm", "ph", "turbidity", "temperature", "temperature_c", "water_level", "water_level_cm", "ph_unit", "turbidity_unit", "temperature_unit", "water_level_unit");
        station(string(packet, "device_id", 1, 80));
        String sample = string(packet, "sample_id", 1, 100);
        if (!sample.matches("[A-Za-z0-9_.:-]+")) throw new Problem(422, "Invalid sample identifier.");
        if (!"remote-v1".equals(packet.optString("setup_protocol"))) throw new Problem(422, "Upload the remote-v1 firmware first.");
        if (packet.has("mode") && !"hardware".equals(packet.optString("mode"))) throw new Problem(422, "Sensor packets must use hardware mode.");
        integer(packet, "config_revision", 0, Integer.MAX_VALUE);
        if (packet.has("calibrated")) bool(packet, "calibrated");
        if (!packet.isNull("calibration_id")) string(packet, "calibration_id", 1, 80);
        if (!packet.isNull("tds_ppm")) throw new Problem(422, "TDS is not supported without a confirmed sensor.");
        JSONObject raw = requireObject(packet, "raw");
        keys(raw, "ph_mv", "ph_adc", "turbidity_mv", "turbidity_adc", "temperature_c", "distance_cm");
        for (String key : new String[]{"ph_mv", "turbidity_mv"}) if (!raw.isNull(key)) number(raw, key, 0, 3300);
        for (String key : new String[]{"ph_adc", "turbidity_adc"}) if (!raw.isNull(key)) integer(raw, key, 0, 4095);
        if (!raw.isNull("temperature_c")) number(raw, "temperature_c", -55, 125);
        if (!raw.isNull("distance_cm")) number(raw, "distance_cm", 0, 1000);
        JSONObject health = requireObject(packet, "sensor_health");
        keys(health, FIELDS);
        Set<String> states = new HashSet<>(Arrays.asList("ok", "missing", "uncalibrated", "out_of_range", "disabled", "timeout", "adc_limit"));
        for (String field : FIELDS) if (health.has(field) && !states.contains(string(health, field, 1, 30))) throw new Problem(422, "Unknown sensor health state.");
        for (String key : new String[]{"ph_unit", "turbidity_unit", "temperature_unit", "water_level_unit"}) {
            if (!packet.has(key)) continue;
            String expected = key.equals("ph_unit") ? "pH" : key.equals("turbidity_unit") ? "NTU" : key.equals("temperature_unit") ? "C" : "cm";
            if (!expected.equals(packet.optString(key))) throw new Problem(422, "Unsupported measurement unit.");
        }
        for (String key : new String[]{"ph", "turbidity", "temperature", "temperature_c", "water_level", "water_level_cm"}) {
            if (packet.isNull(key)) continue;
            number(packet,key,key.equals("temperature")||key.equals("temperature_c")?-55:0,key.equals("ph")?14:key.equals("turbidity")?10000:key.startsWith("temperature")?125:1000);
        }
        return timestamp(packet, now);
    }

    public static JSONObject demo(JSONObject payload) {
        keys(payload, "timestamp", "ph", "turbidity", "temperature", "water_level", "scenario");
        number(payload, "ph", 0, 14); number(payload, "turbidity", 0, 10000);
        number(payload, "temperature", -55, 125); number(payload, "water_level", 0, 1000);
        if (payload.has("scenario")) string(payload, "scenario", 1, 80);
        JSONObject copy = copy(payload);
        if (!copy.has("timestamp")) put(copy, "timestamp", Instant.now().toString());
        timestamp(copy, System.currentTimeMillis());
        put(copy, "device_id", "demo-station"); put(copy, "mode", "simulation");
        return copy;
    }

    public static String token() {
        byte[] bytes = new byte[32]; RANDOM.nextBytes(bytes);
        StringBuilder result = new StringBuilder();
        for (byte b : bytes) result.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
        return result.toString();
    }
    public static String digest(String token) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder();
            for (byte b : bytes) result.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
            return result.toString();
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    public static String csv(Object value) {
        if (value == null || value == JSONObject.NULL) return "";
        String text = String.valueOf(value);
        if (!(value instanceof Number)) {
            String trimmed = text.replaceFirst("^[ \\t\\r\\n]+", "");
            if ((!text.isEmpty() && "\t\r\n".indexOf(text.charAt(0)) >= 0) || (!trimmed.isEmpty() && "=+-@".indexOf(trimmed.charAt(0)) >= 0)) text = "'" + text;
        }
        return "\"" + text.replace("\"", "\"\"") + "\"";
    }
}
