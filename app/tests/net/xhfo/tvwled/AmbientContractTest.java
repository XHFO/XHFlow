package net.xhfo.tvwled;

import org.json.JSONArray;
import org.json.JSONObject;

/** Host-only contract for the Yeelight ambient configuration and its LAN command shape. */
public final class AmbientContractTest {
    private static int checks;
    private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    interface Work {void run() throws Exception;}
    private static void rejects(Work work,String message) throws Exception {try{work.run();throw new AssertionError(message);}catch(IllegalArgumentException expected){checks++;}}
    private static JSONObject base() throws Exception {
        return new JSONObject().put("schema","xh-tv-wled-layout/v1").put("coordinateSystem","screen-front-normalized")
            .put("controller",new JSONObject().put("address","192.168.1.50").put("ledCount",4))
            .put("leds",new JSONArray()
                .put(new JSONObject().put("wledIndex",0).put("edge","top").put("sample",new JSONObject().put("x",.1).put("y",.05)))
                .put(new JSONObject().put("wledIndex",1).put("edge","top").put("sample",new JSONObject().put("x",.5).put("y",.05)))
                .put(new JSONObject().put("wledIndex",2).put("edge","bottom").put("sample",new JSONObject().put("x",.5).put("y",.95))));
    }
    private static JSONObject ambient(Object enabled,Object address,Object brightness,JSONObject mainLight) throws Exception {
        return new JSONObject().put("enabled",enabled).put("address",address).put("brightness",brightness).put("mainLight",mainLight);
    }
    private static JSONObject mainLight(Object enabled,Object max) throws Exception {
        return new JSONObject().put("enabled",enabled).put("maxBrightness",max);
    }

    public static void main(String[] args) throws Exception {
        LayoutConfig legacy = new LayoutConfig(base().toString());
        check(!legacy.ambientEnabled,"older configurations keep the ambient output off");
        check(legacy.ambientAddress.isEmpty()&&legacy.ambientModel.isEmpty(),"ambient address and model stay empty by default");
        check(legacy.ambientBrightness==.8,"ambient brightness defaults to 80%");
        check(legacy.ambientMainLightEnabled&&legacy.ambientMainMaxBrightness==60,"main-light linking defaults on at 60%");
        check(!new JSONObject(legacy.raw).has("ambient"),"implicit ambient defaults never rewrite stored JSON");

        JSONObject enabled = base().put("ambient",ambient(true,"192.168.1.60",.55,mainLight(true,45)).put("model","lamp15"));
        LayoutConfig parsed = new LayoutConfig(enabled.toString());
        check(parsed.ambientEnabled,"enabled ambient output is parsed");
        check("192.168.1.60".equals(parsed.ambientAddress)&&"lamp15".equals(parsed.ambientModel),"ambient address and model round-trip");
        check(parsed.ambientBrightness==.55&&parsed.ambientMainMaxBrightness==45,"ambient levels are preserved exactly");
        check(!parsed.fingerprint.equals(legacy.fingerprint),"ambient settings change the configuration fingerprint");

        LayoutConfig disabledWithAddress = new LayoutConfig(base().put("ambient",ambient(false,"10.0.0.7",0,mainLight(false,100))).toString());
        check(!disabledWithAddress.ambientEnabled&&"10.0.0.7".equals(disabledWithAddress.ambientAddress),"a disabled ambient block still validates and keeps its address");
        check(!disabledWithAddress.ambientMainLightEnabled&&disabledWithAddress.ambientMainMaxBrightness==100,"main-light limits accept the documented bounds");

        rejects(() -> new LayoutConfig(base().put("ambient",ambient(true,"",.8,mainLight(true,60))).toString()),"enabling ambient requires an address");
        rejects(() -> new LayoutConfig(base().put("ambient",ambient(true,"8.8.8.8",.8,mainLight(true,60))).toString()),"ambient rejects public addresses");
        rejects(() -> new LayoutConfig(base().put("ambient",ambient(true,"http://192.168.1.60",.8,mainLight(true,60))).toString()),"ambient rejects host names");
        rejects(() -> new LayoutConfig(base().put("ambient",ambient(false,"192.168.1.999",.8,mainLight(true,60))).toString()),"ambient validates an address even when disabled");
        for (Object bad : new Object[]{JSONObject.NULL, "true", 1}) {
            final Object value = bad;
            rejects(() -> new LayoutConfig(base().put("ambient",ambient(value,"",.8,mainLight(true,60))).toString()),"ambient switch must be boolean");
        }
        for (Object bad : new Object[]{JSONObject.NULL, -0.1, 1.01, "0.5"}) {
            final Object value = bad;
            rejects(() -> new LayoutConfig(base().put("ambient",ambient(false,"",value,mainLight(true,60))).toString()),"ambient brightness stays inside 0-1");
        }
        for (Object bad : new Object[]{0, 101, 60.5, "60"}) {
            final Object value = bad;
            rejects(() -> new LayoutConfig(base().put("ambient",ambient(false,"",.8,mainLight(true,value))).toString()),"main-light limit must be an integer percentage");
        }
        rejects(() -> new LayoutConfig(base().put("ambient",ambient(false,"",.8,new JSONObject().put("enabled","yes"))).toString()),"main-light switch must be boolean");
        rejects(() -> new LayoutConfig(base().put("ambient",new JSONArray()).toString()),"ambient block must be an object");
        rejects(() -> new LayoutConfig(base().put("ambient",new JSONObject().put("mainLight",new JSONArray())).toString()),"main-light block must be an object");

        LayoutConfig edges = new LayoutConfig(enabled.toString());
        int[] top = edges.edgeIndices("top"), bottom = edges.edgeIndices("bottom");
        check(top.length == 2 && top[0] == 0 && top[1] == 1,"top-edge indices follow the configured LED numbering");
        check(bottom.length == 1 && bottom[0] == 2,"other edges keep their own indices");
        check(edges.edgeIndices("left").length == 0,"an unused edge yields no indices");

        check("{\"id\":7,\"method\":\"bg_set_rgb\",\"params\":[16711680,\"sudden\",0],\"token\":\"t\"}".equals(
                compact(YeelightAmbient.ambientColorCommand(7,0xFF0000,"t"))),"ambient colour keeps the 0xRRGGBB payload");
        check(compact(YeelightAmbient.ambientColorCommand(1,0x123456,"t")).contains("[1193046"),
                "ambient colour is sent as one integer");
        check(compact(YeelightAmbient.ambientBrightnessCommand(2,175,"t")).contains("bg_set_bright"),
                "ambient brightness uses the bg namespace");
        check(compact(YeelightAmbient.ambientBrightnessCommand(2,175,"t")).contains("[100,"),
                "ambient brightness is clamped to 100");
        check(compact(YeelightAmbient.ambientBrightnessCommand(2,0,"t")).contains("[1,"),
                "ambient brightness never drops to zero");
        check(compact(YeelightAmbient.mainBrightnessCommand(3,42,"t")).contains("set_bright"),
                "main-light linking uses the front-light method");
        check(compact(YeelightAmbient.mainBrightnessCommand(3,42,"t")).contains("[42,"),
                "main-light brightness passes through");
        check(YeelightAmbient.ambientColorCommand(1,0x00AABB,"t").endsWith("\r\n"),
                "LAN commands stay CRLF terminated like the vendor sample");

        System.out.println("AmbientContractTest PASS (" + checks + " checks, no network or device access)");
    }

    private static String compact(String command) {
        return command.replace("\r\n", "").replace(" ", "");
    }
}
