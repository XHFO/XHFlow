package net.xhfo.tvwled;

import org.json.JSONArray;
import org.json.JSONObject;

/** Host-only contract for source-to-preset bindings and what each preset drives. */
public final class SceneContractTest {
    private static int checks;
    private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    interface Work {void run() throws Exception;}
    private static void rejects(Work work,String message) throws Exception {try{work.run();throw new AssertionError(message);}catch(IllegalArgumentException expected){checks++;}}
    private static final String BASE="{\"schema\":\"xh-tv-wled-layout/v1\",\"coordinateSystem\":\"screen-front-normalized\","
        +"\"controller\":{\"address\":\"192.168.1.50\",\"ledCount\":1},"
        +"\"leds\":[{\"wledIndex\":0,\"edge\":\"top\",\"sample\":{\"x\":.5,\"y\":.05}}]}";
    private static String withScenes(JSONArray bindings) throws Exception {
        return new JSONObject(BASE).put("scenes",new JSONObject().put("bindings",bindings)).toString();
    }
    private static JSONObject binding(String source,Object preset) throws Exception {
        return new JSONObject().put("source",source).put("preset",preset);
    }

    public static void main(String[] args) throws Exception {
        check(ScenePreset.IDS.length==ScenePreset.LABELS.length,"every preset has a label");
        for(String id:ScenePreset.IDS)check(ScenePreset.known(id),"known preset "+id);
        check(!ScenePreset.known("theatre"),"unknown presets are rejected");
        check(ScenePreset.strip(null)&&ScenePreset.ambient(null),"an unbound input keeps the strip running");
        check(!ScenePreset.strip("office")&&!ScenePreset.ambient("office")&&ScenePreset.front("office"),"office drives only the desk lamp");
        check(!ScenePreset.strip("night")&&!ScenePreset.ambient("night")&&ScenePreset.front("night"),"night keeps the room dark");
        check(!ScenePreset.strip("off")&&!ScenePreset.ambient("off")&&!ScenePreset.front("off"),"off drives nothing");
        check(ScenePreset.strip("cinema")&&ScenePreset.ambient("cinema")&&!ScenePreset.front("cinema"),"cinema runs the strip and back LED only");
        check(ScenePreset.MODE_SCREEN.equals(ScenePreset.ambientMode("cinema"))&&ScenePreset.MODE_SCREEN.equals(ScenePreset.ambientMode("game")),"cinema and game follow the picture");
        check(ScenePreset.MODE_SCREEN.equals(ScenePreset.ambientMode(null)),"an unbound input keeps the back LED following the picture");
        check(ScenePreset.MODE_OFF.equals(ScenePreset.ambientMode("night"))&&ScenePreset.MODE_OFF.equals(ScenePreset.ambientMode("off")),"night and off leave the back LED dark");
        check(ScenePreset.MODE_OFF.equals(ScenePreset.ambientMode("office")),"office leaves the back LED dark");
        check(ScenePreset.strip("game")&&ScenePreset.ambient("game")&&ScenePreset.front("game"),"game runs everything");
        check(ScenePreset.frontBrightness("night")<ScenePreset.frontBrightness("office"),"night is dimmer than office");
        check(ScenePreset.frontCt("night")==2700&&ScenePreset.frontCt("office")==4000,"presets carry their own colour temperature");

        LayoutConfig unbound=new LayoutConfig(BASE);
        check(unbound.presetFor("30")==null&&unbound.sceneSources.length==0,"no bindings means no preset");
        LayoutConfig bound=new LayoutConfig(withScenes(new JSONArray()
            .put(binding("30","office")).put(binding("23","cinema"))));
        check("office".equals(bound.presetFor("30")),"USB-C can be bound to office");
        check("cinema".equals(bound.presetFor("23")),"HDMI can be bound to cinema");
        check(bound.presetFor("29")==null,"an unbound input falls back to the default behaviour");
        check(bound.presetFor(null)==null,"a missing input never matches a binding");
        check(new JSONObject(bound.raw).getJSONObject("scenes").getJSONArray("bindings").length()==2,"bindings round-trip");

        rejects(()->new LayoutConfig(withScenes(new JSONArray().put(binding("30","theatre")))),"unknown preset rejected");
        rejects(()->new LayoutConfig(withScenes(new JSONArray().put(binding("30",1)))),"numeric preset rejected");
        rejects(()->new LayoutConfig(withScenes(new JSONArray().put(binding("abc","office")))),"non-numeric source rejected");
        rejects(()->new LayoutConfig(withScenes(new JSONArray().put(binding("30","office")).put(binding("30","game")))),"duplicate source rejected");
        rejects(()->new LayoutConfig(new JSONObject(BASE).put("scenes",new JSONArray()).toString()),"scenes must be an object");
        rejects(()->new LayoutConfig(new JSONObject(BASE).put("scenes",new JSONObject().put("bindings","x")).toString()),"bindings must be an array");

        System.out.println("SceneContractTest PASS ("+checks+" checks, no network or device access)");
    }
}
