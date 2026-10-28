package net.xhfo.tvwled;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

/** SHA-256 of every JSON configuration field, independent of object insertion order. */
final class ConfigFingerprint {
    private static final char[] HEX="0123456789abcdef".toCharArray();
    private ConfigFingerprint() {}

    static String of(String raw) throws Exception {
        if(raw==null)throw new IllegalArgumentException("Configuration JSON required");
        JSONTokener input=new JSONTokener(raw);
        Object parsed=input.nextValue();
        if(!(parsed instanceof JSONObject)||input.nextClean()!=0)
            throw new IllegalArgumentException("Configuration must be one JSON object");
        return of((JSONObject)parsed);
    }

    static String of(JSONObject object) throws Exception {
        if(object==null)throw new IllegalArgumentException("Configuration object required");
        StringBuilder canonical=new StringBuilder();
        Set<Object> ancestors=Collections.newSetFromMap(new IdentityHashMap<Object,Boolean>());
        append(object,canonical,ancestors);
        byte[] digest=MessageDigest.getInstance("SHA-256").digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
        char[] encoded=new char[digest.length*2];
        for(int i=0;i<digest.length;i++){
            int value=digest[i]&255;encoded[i*2]=HEX[value>>>4];encoded[i*2+1]=HEX[value&15];
        }
        return new String(encoded);
    }

    private static void append(Object value,StringBuilder output,Set<Object> ancestors) throws Exception {
        if(value==null||value==JSONObject.NULL){output.append("null");return;}
        if(value instanceof String){quote((String)value,output);return;}
        if(value instanceof Boolean){output.append((Boolean)value?"true":"false");return;}
        if(value instanceof Number){
            String number=value.toString();
            if(!number.matches("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?"))
                throw new IllegalArgumentException("Non-JSON number in configuration");
            // Strip representation differences such as integer 1 versus floating point 1.0.
            output.append(new BigDecimal(number).stripTrailingZeros().toString());
            return;
        }
        if(!(value instanceof JSONObject)&&!(value instanceof JSONArray))
            throw new IllegalArgumentException("Non-JSON value in configuration");
        if(!ancestors.add(value))throw new IllegalArgumentException("Cyclic configuration JSON");
        try{
            if(value instanceof JSONObject){
                JSONObject object=(JSONObject)value;ArrayList<String> keys=new ArrayList<>();
                Iterator<String> iterator=object.keys();while(iterator.hasNext())keys.add(iterator.next());
                Collections.sort(keys);output.append('{');boolean first=true;
                for(String key:keys){
                    if(!first)output.append(',');first=false;
                    quote(key,output);output.append(':');append(object.get(key),output,ancestors);
                }
                output.append('}');
            }else{
                JSONArray array=(JSONArray)value;output.append('[');
                for(int i=0;i<array.length();i++){
                    if(i!=0)output.append(',');append(array.get(i),output,ancestors);
                }
                output.append(']');
            }
        }finally{ancestors.remove(value);}
    }

    private static void quote(String value,StringBuilder output){
        output.append('"');
        for(int i=0;i<value.length();i++){
            char character=value.charAt(i);
            if(character=='"'||character=='\\'){output.append('\\').append(character);}
            else if(character<32||character>=127){
                // ASCII output also preserves lone UTF-16 surrogates without UTF-8 replacement.
                output.append('\\').append('u');
                output.append(HEX[(character>>>12)&15]).append(HEX[(character>>>8)&15])
                    .append(HEX[(character>>>4)&15]).append(HEX[character&15]);
            }else output.append(character);
        }
        output.append('"');
    }
}
