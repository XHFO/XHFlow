package net.xhfo.tvwled;

import java.math.BigDecimal;
import java.math.BigInteger;
import org.json.JSONArray;
import org.json.JSONObject;

/** Host-only canonical configuration identity; no Android classes or network access. */
public final class ConfigFingerprintContractTest {
    private static int checks;
    private interface Work {void run() throws Exception;}
    private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    private static void rejects(Work work,String message) throws Exception {
        try{work.run();throw new AssertionError(message);}catch(IllegalArgumentException expected){checks++;}
        catch(org.json.JSONException expected){checks++;}
    }
    private static void same(String left,String right,String message) throws Exception {
        check(ConfigFingerprint.of(left).equals(ConfigFingerprint.of(right)),message);
    }
    private static void different(String left,String right,String message) throws Exception {
        check(!ConfigFingerprint.of(left).equals(ConfigFingerprint.of(right)),message);
    }
    private static JSONObject base() throws Exception {
        return new JSONObject("{\"schema\":\"xh-tv-wled-layout/v1\",\"coordinateSystem\":\"screen-front-normalized\","
            +"\"controller\":{\"address\":\"192.168.1.50\",\"ledCount\":126},"
            +"\"runtime\":{\"sleepOff\":true,\"sourceFilter\":{\"enabled\":false,\"allowed\":[\"23\",\"30\"]}},"
            +"\"layout\":{\"geometry\":{\"screen\":{\"x\":0.1,\"y\":0.1,\"w\":0.8,\"h\":0.8},\"strip\":{\"x\":0,\"y\":0,\"w\":1,\"h\":1}},"
            +"\"sampling\":{\"inset\":0.06,\"quality\":\"standard\"}},"
            +"\"effects\":{\"smoothing\":0.3,\"blackBarSkip\":true,\"color\":{\"saturation\":1,\"gain\":1,\"offset\":0,\"gamma\":1,\"redGain\":1,\"greenGain\":1,\"blueGain\":1}},"
            +"\"leds\":[{\"wledIndex\":0,\"edge\":\"top\",\"sample\":{\"x\":0.1,\"y\":0.05}},{\"wledIndex\":1,\"edge\":\"right\",\"sample\":{\"x\":0.95,\"y\":0.1}}]}");
    }
    private static void changed(JSONObject value,String message) throws Exception {
        check(!ConfigFingerprint.of(base()).equals(ConfigFingerprint.of(value)),message);
    }
    public static void main(String[] args) throws Exception {
        check(ConfigFingerprint.of("{}").equals("44136fa355b3678a1146ad16f7e8649e94fb4fc21fe77e8310c060f61caaff8a"),"known empty-object SHA256");
        check(ConfigFingerprint.of("{\"b\":2,\"a\":1}").equals("43258cff783fe7036d8a43033f830adfc60ec037382473548ac742b888292777"),"known sorted-object SHA256");
        check(ConfigFingerprint.of("{\"s\":\"中文🎨\",\"array\":[1,true,null,\"1\"]}").equals("363ac947a6eaec37421dd3dd4f78201ce29af8b26440cae02dec699c8adfb9b4"),"known typed unicode canonical SHA256");
        same("{\"b\":2,\"a\":1}"," { \"a\" : 1, \"b\" : 2 } \n\t","object order and whitespace ignored");
        same("{\"z\":{\"b\":2,\"a\":1},\"a\":[{\"d\":4,\"c\":3}]}","{\"a\":[{\"c\":3,\"d\":4}],\"z\":{\"a\":1,\"b\":2}}","recursive object order ignored through arrays");
        different("{\"a\":[1,2]}","{\"a\":[2,1]}","array order retained");
        different("{\"a\":[1]}","{\"a\":[1,1]}","array length retained");
        different("{\"a\":{}}","{\"a\":[]}","empty container types retained");
        different("{}","{\"enabled\":false}","missing differs from explicit default");
        different("{}","{\"a\":null}","missing differs from explicit null");
        different("{\"a\":\"\"}","{\"a\":null}","empty string differs from null");
        String[] values={"1","\"1\"","true","\"true\"","null","\"null\"","false","0","\"\"","[]","{}"};
        for(int i=0;i<values.length;i++)for(int j=i+1;j<values.length;j++)
            different("{\"a\":"+values[i]+"}","{\"a\":"+values[j]+"}","JSON primitive and container types kept distinct "+i+"/"+j);
        same("{\"a\":1}","{\"a\":1.000e0}","numeric representations normalized");
        same("{\"a\":1000}","{\"a\":1e3}","numeric exponents normalized");
        same("{\"a\":-0.0}","{\"a\":0}","zero normalized");
        different("{\"a\":0.1}","{\"a\":0.10000000000000001}","distinct decimal values retained by real JSON parser");
        JSONObject direct=new JSONObject().put("a",new BigDecimal("1.000")).put("b",new BigInteger("100000000000000000000000001"));
        same(direct.toString(),"{\"b\":100000000000000000000000001,\"a\":1}","large integral precision retained");
        check(ConfigFingerprint.of(direct).equals(ConfigFingerprint.of(direct.toString())),"JSONObject and raw overload agree");
        same("{\"a\":\"中文🎨\"}","{\"a\":\"\\u4e2d\\u6587\\ud83c\\udfa8\"}","unicode escapes normalize to actual text");
        same("{\"\\u4e2d\":\"\\u0041\"}","{\"中\":\"A\"}","unicode keys and ASCII escapes normalized");
        same("{\"a\":\"x\\/y\"}","{\"a\":\"x/y\"}","slash escape normalized");
        same("{\"a\":\"\\b\\f\\n\\r\\t\"}","{\"a\":\"\\u0008\\u000c\\u000a\\u000d\\u0009\"}","control escape forms normalized");
        different("{\"a\":\"é\"}","{\"a\":\"é\"}","distinct unicode codepoint sequences retained");
        different("{\"a\":\"a\\\"b\"}","{\"a\":\"a\\\\b\"}","quote and backslash remain distinct");
        different("{\"a\":\"\\ud800\"}","{\"a\":\"?\"}","unpaired surrogate never collapses to UTF8 replacement");
        different("{\"a\":\"\\ud800\"}","{\"a\":\"\\ud801\"}","unpaired surrogate identity retained");
        for(String text:new String[]{"","plain","中文🎨","\"\\\b\f\n\r\t","\u0000\u007f\u2028\u2029"}){
            JSONObject object=new JSONObject().put("text",text);
            check(ConfigFingerprint.of(object).equals(ConfigFingerprint.of(object.toString())),"string JSON round trip "+text.length());
        }
        JSONObject value=base();value.getJSONObject("runtime").put("sleepOff",false);changed(value,"sleep policy affects identity");
        value=base();value.getJSONObject("runtime").getJSONObject("sourceFilter").put("enabled",true);changed(value,"source filter enabled affects identity");
        value=base();value.getJSONObject("runtime").getJSONObject("sourceFilter").put("allowed",new JSONArray().put("30").put("23"));changed(value,"source array order affects identity");
        value=base();value.getJSONObject("layout").getJSONObject("geometry").getJSONObject("screen").put("x",.12);changed(value,"screen geometry affects identity");
        value=base();value.getJSONObject("layout").getJSONObject("geometry").getJSONObject("strip").put("w",1.1);changed(value,"strip geometry affects identity");
        value=base();value.getJSONObject("layout").getJSONObject("sampling").put("quality","fine");changed(value,"sampling quality affects identity");
        value=base();value.getJSONObject("layout").getJSONObject("sampling").put("inset",.12);changed(value,"sampling inset affects identity");
        for(String field:new String[]{"saturation","gain","offset","gamma","redGain","greenGain","blueGain"}){
            value=base();value.getJSONObject("effects").getJSONObject("color").put(field,.8);changed(value,"color "+field+" affects identity");
        }
        value=base();value.getJSONObject("effects").put("smoothing",.9);changed(value,"smoothing affects identity");
        value=base();value.getJSONObject("effects").put("blackBarSkip",false);changed(value,"black bar policy affects identity");
        value=base();value.getJSONArray("leds").getJSONObject(0).put("wledIndex",5);changed(value,"physical LED index affects identity");
        value=base();value.getJSONArray("leds").getJSONObject(0).put("edge","left");changed(value,"LED edge affects identity");
        value=base();value.getJSONArray("leds").getJSONObject(0).getJSONObject("sample").put("x",.3);changed(value,"LED sample affects identity");
        value=base();JSONArray leds=value.getJSONArray("leds");value.put("leds",new JSONArray().put(leds.get(1)).put(leds.get(0)));changed(value,"LED array order affects identity");
        value=base();value.getJSONObject("controller").put("address","192.168.1.51");changed(value,"WLED address affects identity");
        value=base();value.getJSONObject("controller").put("ledCount",127);changed(value,"WLED count affects identity");
        value=base().put("futureField",new JSONObject().put("keep",true));changed(value,"unknown metadata is included");
        String raw=base().toString();ConfigFingerprint.of(raw);check(base().toString().equals(raw),"raw input unchanged");
        JSONObject original=base();String before=original.toString();String first=ConfigFingerprint.of(original);
        check(original.toString().equals(before),"JSONObject input unchanged");
        for(int i=0;i<10;i++)check(first.equals(ConfigFingerprint.of(original)),"repeatable identity "+i);
        check(first.matches("[0-9a-f]{64}"),"fingerprint is64lowercasehex");
        JSONObject shared=new JSONObject().put("ok",true);
        check(ConfigFingerprint.of(new JSONObject().put("a",shared).put("b",shared)).equals(ConfigFingerprint.of("{\"a\":{\"ok\":true},\"b\":{\"ok\":true}}")),"shared acyclic objects allowed");
        rejects(()->ConfigFingerprint.of((String)null),"null raw rejected");
        rejects(()->ConfigFingerprint.of((JSONObject)null),"null object rejected");
        for(String invalid:new String[]{""," ","null","true","1","\"x\"","[]","{} trailing","{}{}","{\"a\":"})
            rejects(()->ConfigFingerprint.of(invalid),"invalid root or trailing content rejected: "+invalid);
        rejects(()->ConfigFingerprint.of(new JSONObject().put("unknown",new Object())),"non-JSON objects rejected");
        Number invalidNumber=new Number(){public int intValue(){return 0;}public long longValue(){return 0;}public float floatValue(){return 0;}public double doubleValue(){return 0;}public String toString(){return "NaN";}};
        rejects(()->ConfigFingerprint.of(new JSONObject().put("number",invalidNumber)),"non-JSON numbers rejected");
        JSONObject cyclic=new JSONObject();cyclic.put("self",cyclic);
        rejects(()->ConfigFingerprint.of(cyclic),"cyclic objects rejected");
        JSONArray cyclicArray=new JSONArray();cyclicArray.put(cyclicArray);
        rejects(()->ConfigFingerprint.of(new JSONObject().put("array",cyclicArray)),"cyclic arrays rejected");
        System.out.println("ConfigFingerprintContractTest PASS ("+checks+" checks)");
    }
}
