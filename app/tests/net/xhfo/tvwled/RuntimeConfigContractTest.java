package net.xhfo.tvwled;

import org.json.JSONObject;
import org.json.JSONArray;
import java.util.Arrays;

/** Host-only config and all-or-nothing vendor batching; never opens a network or Android service. */
public final class RuntimeConfigContractTest {
    private static int checks;
    private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    interface Work {void run() throws Exception;}
    private static void rejects(Work work,String message) throws Exception {try{work.run();throw new AssertionError(message);}catch(IllegalArgumentException expected){checks++;}}
    private static JSONObject base() throws Exception {
        return new JSONObject().put("schema","xh-tv-wled-layout/v1").put("coordinateSystem","screen-front-normalized")
            .put("controller",new JSONObject().put("address","192.168.1.50").put("ledCount",1))
            .put("leds",new JSONArray().put(new JSONObject().put("wledIndex",0).put("edge","top").put("sample",new JSONObject().put("x",.1).put("y",.05))));
    }
    private static JSONObject filter(Object enabled,JSONArray allowed) throws Exception {return new JSONObject().put("sourceFilter",new JSONObject().put("enabled",enabled).put("allowed",allowed));}
    public static void main(String[]args) throws Exception {
        LayoutConfig defaults=new LayoutConfig(base().toString());
        check(defaults.sleepOff&&!defaults.sourceFilterEnabled&&!defaults.sourceOffWhenExcluded&&defaults.allowedSources.length==0,"safe backward-compatible runtime defaults");
        check(defaults.fingerprint.matches("[0-9a-f]{64}"),"layout exposes canonical saved/applied fingerprint");
        check(defaults.pointsPerEdge==5&&defaults.samplingQuality.equals("standard"),"old config uses standard sampling");
        JSONObject fine=base().put("layout",new JSONObject().put("geometry",new JSONObject().put("kept",123)).put("sampling",new JSONObject().put("inset",.123).put("quality","fine")));
        LayoutConfig detail=new LayoutConfig(fine.toString());check(detail.pointsPerEdge==10&&detail.samplingInset==.123,"fine and inset applied together");
        check(new JSONObject(detail.raw).getJSONObject("layout").getJSONObject("geometry").getInt("kept")==123,"save preserves geometry metadata");
        detail.newEngine().map(new int[40][3],1,false,0);checks++;
        rejects(()->new LayoutConfig(base().put("layout",new JSONObject().put("sampling",new JSONObject().put("quality","ultra"))).toString()),"unknown quality rejected");
        rejects(()->new LayoutConfig(base().put("layout",new JSONObject().put("sampling",new JSONObject().put("quality",1))).toString()),"numeric quality rejected");
        rejects(()->new LayoutConfig(base().put("layout",new JSONObject().put("sampling",new JSONObject().put("quality",JSONObject.NULL))).toString()),"null quality rejected");
        LayoutConfig restricted=new LayoutConfig(base().put("runtime",filter(true,new JSONArray().put("23").put("30")).put("sleepOff",false)).toString());
        check(!restricted.sleepOff&&restricted.sourceFilterEnabled&&restricted.allows("30")&&!restricted.allows("34"),"explicit input IDs and sleep policy applied");
        LayoutConfig excludedOff=new LayoutConfig(base().put("runtime",new JSONObject().put("sourceFilter",new JSONObject().put("enabled",true).put("allowed",new JSONArray().put("23")).put("offWhenExcluded",true))).toString());
        check(excludedOff.sourceOffWhenExcluded&&excludedOff.sourceFilterEnabled,"explicit source-excluded off applied");
        LayoutConfig offWhileDisabled=new LayoutConfig(base().put("runtime",new JSONObject().put("sourceFilter",new JSONObject().put("enabled",false).put("offWhenExcluded",true).put("allowed",new JSONArray()))).toString());
        check(offWhileDisabled.sourceOffWhenExcluded&&!offWhileDisabled.sourceFilterEnabled,"retained off preference does not implicitly enable filtering");
        rejects(()->new LayoutConfig(base().put("runtime",new JSONObject().put("sourceFilter",new JSONObject().put("enabled",true).put("allowed",new JSONArray().put("23")).put("offWhenExcluded",1))).toString()),"source off flag must be boolean");
        rejects(()->new LayoutConfig(base().put("runtime",new JSONObject().put("sourceFilter",new JSONObject().put("enabled",true).put("allowed",new JSONArray().put("23")).put("offWhenExcluded",JSONObject.NULL))).toString()),"null source off flag rejected");
        check(!excludedOff.fingerprint.equals(restricted.fingerprint),"runtime changes alter actual config fingerprint");
        DisplayEnvironment.Source[] supported={new DisplayEnvironment.Source("23","HDMI 1"),new DisplayEnvironment.Source("30","USB-C")};
        ConfigServer.validateSourceFilter(restricted,supported);checks++;
        rejects(()->ConfigServer.validateSourceFilter(restricted,new DisplayEnvironment.Source[0]),"unknown ports rejected");
        rejects(()->ConfigServer.validateSourceFilter(restricted,new DisplayEnvironment.Source[]{supported[0]}),"any unsupported selected port rejected");
        ConfigServer.validateSourceFilter(defaults,new DisplayEnvironment.Source[0]);checks++;
        rejects(()->new LayoutConfig(base().put("runtime",filter(true,new JSONArray())).toString()),"enabled empty filter rejected");
        rejects(()->new LayoutConfig(base().put("runtime",filter(true,new JSONArray().put(23))).toString()),"input IDs must be strings");
        rejects(()->new LayoutConfig(base().put("runtime",filter(true,new JSONArray().put("23").put("23"))).toString()),"duplicate input IDs rejected");
        rejects(()->new LayoutConfig(base().put("runtime",filter("true",new JSONArray().put("23"))).toString()),"filter flag must be boolean");
        rejects(()->new LayoutConfig(base().put("runtime",new JSONObject().put("sleepOff",1)).toString()),"sleep flag must be boolean");
        rejects(()->new LayoutConfig(base().put("runtime",1).toString()),"runtime object required");
        DisplayEnvironment.Snapshot usbC=new DisplayEnvironment.Snapshot(true,2,0,"30",supported,"");
        DisplayEnvironment.Snapshot hdmi1=new DisplayEnvironment.Snapshot(true,2,0,"23",supported,"");
        DisplayEnvironment.Snapshot unknown=new DisplayEnvironment.Snapshot(true,2,0,null,supported,"");
        check(AmbientService.sourceBlockReason(defaults,unknown).isEmpty(),"global capture remains allowed with source unknown");
        check(AmbientService.sourceBlockReason(restricted,usbC).isEmpty(),"allowed selected interface accepted");
        check(AmbientService.sourceBlockReason(excludedOff,usbC).equals("input_not_allowed"),"selected TypeC is excluded by HDMI-only config");
        check(AmbientService.sourceBlockReason(excludedOff,unknown).equals("input_unknown"),"unknown selected interface suspends filtered output");
        check(AmbientService.sourceOffRequested(excludedOff,"input_not_allowed"),"explicit off option permits excluded-interface OFF");
        check(AmbientService.sourceOffRequested(excludedOff,"input_unknown"),"explicit off option permits unknown-interface OFF");
        check(!AmbientService.sourceOffRequested(restricted,"input_not_allowed"),"legacy missing off option never authorizes source OFF");
        check(!AmbientService.sourceOffRequested(offWhileDisabled,"input_unknown"),"filter disabled never authorizes source OFF");
        check(!AmbientService.sourceOffRequested(excludedOff,""),"allowed interface cannot request source OFF");
        check(!AmbientService.sourceOffRequested(excludedOff,"config_invalid"),"invalid config cannot request source OFF");
        check(AmbientService.sourceBlockReason(excludedOff,hdmi1).isEmpty(),"returning to allowed selected interface clears source suspension");
        LayoutConfig unsupported=new LayoutConfig(base().put("runtime",filter(true,new JSONArray().put("25"))).toString());
        check(AmbientService.sourceBlockReason(unsupported,usbC).equals("config_invalid"),"unsupported saved source list is invalid rather than allowed");
        int[][] points=new int[40][2];for(int i=0;i<points.length;i++){points[i][0]=i;points[i][1]=100+i;}
        final int[] calls={0};final int[][][] returned={null};
        int[][] colors=ColorBatches.sample(points,batch->{
            int at=calls[0]++*20;check(batch.length==20,"vendor request limited to20");int[][] rgb=new int[batch.length][3];
            for(int i=0;i<batch.length;i++){check(batch[i][0]==at+i&&batch[i][1]==100+at+i,"batch preserves full capture order");rgb[i]=new int[]{at+i,80,90};}
            if(at==0)returned[0]=rgb;return rgb;
        });
        check(calls[0]==2&&colors.length==40,"fine is two serial20 requests");
        for(int i=0;i<40;i++)check(colors[i][0]==i,"merged output follows canonical40-point order");
        returned[0][0][0]=255;check(colors[0][0]==0,"capture frame owns copied color arrays");
        check(points[0][0]==0&&points[20][0]==20,"caller coordinates preserved");
        final int[] standardCalls={0};ColorBatches.sample(Arrays.copyOf(points,20),batch->{standardCalls[0]++;return new int[20][3];});
        check(standardCalls[0]==1,"standard uses one vendor transaction");
        final int[] failureCalls={0};try{ColorBatches.sample(points,batch->{if(++failureCalls[0]==2)throw new Exception("second batch failed");return new int[20][3];});throw new AssertionError("partial frame returned");}catch(Exception expected){check(expected.getMessage().equals("second batch failed"),"second-batch failure aborts the entire frame");}
        rejects(()->ColorBatches.sample(points,batch->new int[19][3]),"wrong batch result count rejected");
        rejects(()->ColorBatches.sample(points,batch->{int[][] rgb=new int[20][3];rgb[0][0]=256;return rgb;}),"invalid batch RGB rejected");
        rejects(()->ColorBatches.sample(new int[41][2],batch->new int[20][3]),"capture count bounded to40");
        rejects(()->ColorBatches.sample(new int[][]{{-1,0}},batch->new int[1][3]),"negative capture coordinates rejected");
        System.out.println("RuntimeConfigContractTest PASS ("+checks+" checks)");
    }
}
