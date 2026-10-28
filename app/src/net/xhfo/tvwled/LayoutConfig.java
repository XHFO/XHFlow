package net.xhfo.tvwled;

import org.json.JSONArray;
import org.json.JSONObject;
import java.net.InetAddress;
import java.util.Arrays;
import java.util.Comparator;

final class LayoutConfig {
    final String raw, address, samplingQuality, fingerprint;
    final int ledCount, pointsPerEdge;
    final Led[] leds;
    final float brightness;
    final boolean softeningEnabled,blackBarsEnabled,closed,sleepOff,sourceFilterEnabled,sourceOffWhenExcluded;
    final String[] allowedSources;
    final double softeningStrength,samplingInset;
    final double colorSaturation,colorGain,colorOffset,colorGamma,colorRedGain,colorGreenGain,colorBlueGain;
    static final class Led {
        final int index, order;
        final double x,y,u;
        final String edge;
        Led(int index,int order,double x,double y,String edge,double u) {this.index=index;this.order=order;this.x=x;this.y=y;this.edge=edge;this.u=u;}
    }
    LayoutConfig(String json) throws Exception {
        if(json==null || json.length()>131072) throw new IllegalArgumentException("配置大小不正确");
        JSONObject root=new JSONObject(json);
        if(!"xh-tv-wled-layout/v1".equals(root.getString("schema"))) throw new IllegalArgumentException("不支持的布局格式");
        if(!"screen-front-normalized".equals(root.getString("coordinateSystem"))) throw new IllegalArgumentException("取色坐标必须以屏幕正面为准");
        JSONObject controller=root.getJSONObject("controller");
        address=controller.getString("address").trim();
        validateAddress(address);
        ledCount=controller.getInt("ledCount");
        if(ledCount<1 || ledCount>480) throw new IllegalArgumentException("此版本支持1至480颗RGB灯珠");
        JSONArray input=root.getJSONArray("leds");
        if(input.length()<1 || input.length()>ledCount) throw new IllegalArgumentException("至少分配一颗灯，且不能超过控制器灯数");
        leds=new Led[input.length()]; boolean[] used=new boolean[ledCount];
        for(int i=0;i<leds.length;i++) {
            JSONObject item=input.getJSONObject(i), sample=item.getJSONObject("sample");
            int index=item.getInt("wledIndex");
            double x=sample.getDouble("x"),y=sample.getDouble("y");
            if(index<0 || index>=ledCount || used[index]) throw new IllegalArgumentException("灯号越界或重复: "+index);
            if(!Double.isFinite(x)||!Double.isFinite(y)||x<0||x>1||y<0||y>1) throw new IllegalArgumentException("取色坐标越界");
            String edge=item.optString("edge","");
            if(!edge.equals("top")&&!edge.equals("right")&&!edge.equals("bottom")&&!edge.equals("left"))throw new IllegalArgumentException("灯珠边位置无效");
            // Web geometry calibration is already applied to sample; preserve its along-edge projection.
            double u=edge.equals("top")?x:edge.equals("right")?y:edge.equals("bottom")?1-x:1-y;
            used[index]=true; leds[i]=new Led(index,item.optInt("order",i+1),x,y,edge,u);
        }
        Arrays.sort(leds,Comparator.comparingInt(a->a.order));
        JSONObject output=root.optJSONObject("output");
        double gain=output==null?0.60:output.optDouble("brightness",0.60);
        if(!Double.isFinite(gain)||gain<0||gain>1) throw new IllegalArgumentException("亮度比例应在0%至100%之间");
        brightness=(float)gain;
        JSONObject softening=output==null?null:output.optJSONObject("softening"),blackBars=output==null?null:output.optJSONObject("blackBars");
        softeningEnabled=softening==null||softening.optBoolean("enabled",true);
        softeningStrength=softening==null?0.35:softening.optDouble("strength",0.35);
        if(!Double.isFinite(softeningStrength)||softeningStrength<0||softeningStrength>1)throw new IllegalArgumentException("柔化强度应在0至1之间");
        blackBarsEnabled=blackBars==null||blackBars.optBoolean("enabled",true);
        JSONObject color=output==null?null:output.optJSONObject("color");
        if(output!=null&&output.has("color")&&!output.isNull("color")&&color==null)throw new IllegalArgumentException("颜色校正应为对象");
        colorSaturation=colorValue(color,"saturation",1,0,2);colorGain=colorValue(color,"gain",1,0,2);
        colorOffset=colorValue(color,"offset",0,-64,64);colorGamma=colorValue(color,"gamma",1,0.5,3);
        colorRedGain=colorValue(color,"redGain",1,0,2);colorGreenGain=colorValue(color,"greenGain",1,0,2);colorBlueGain=colorValue(color,"blueGain",1,0,2);
        JSONObject layout=root.optJSONObject("layout");closed=layout!=null&&"closed".equals(layout.optString("shape","c"));
        JSONObject sampling=layout==null?null:layout.optJSONObject("sampling");
        Object quality=sampling==null?"standard":sampling.opt("quality");
        if(quality==null)quality="standard";
        if(!(quality instanceof String)||(!"standard".equals(quality)&&!"fine".equals(quality)))throw new IllegalArgumentException("采样质量应为标准或细腻");
        samplingQuality=(String)quality;pointsPerEdge="fine".equals(samplingQuality)?10:5;
        samplingInset=sampling==null?0.045:sampling.optDouble("inset",0.045);
        if(!Double.isFinite(samplingInset)||samplingInset<0||samplingInset>0.2)throw new IllegalArgumentException("取色缩进应在0%至20%之间");
        JSONObject runtime=root.optJSONObject("runtime");
        if(root.has("runtime")&&!root.isNull("runtime")&&runtime==null)throw new IllegalArgumentException("运行条件应为对象");
        sleepOff=booleanValue(runtime,"sleepOff",true);
        JSONObject filter=runtime==null?null:runtime.optJSONObject("sourceFilter");
        if(runtime!=null&&runtime.has("sourceFilter")&&!runtime.isNull("sourceFilter")&&filter==null)throw new IllegalArgumentException("接口筛选应为对象");
        sourceFilterEnabled=booleanValue(filter,"enabled",false);
        sourceOffWhenExcluded=booleanValue(filter,"offWhenExcluded",false);
        JSONArray allowed=filter==null?null:filter.optJSONArray("allowed");
        if(filter!=null&&filter.has("allowed")&&allowed==null)throw new IllegalArgumentException("允许接口应为数组");
        if(allowed!=null&&allowed.length()>16)throw new IllegalArgumentException("允许接口数量过多");
        allowedSources=new String[allowed==null?0:allowed.length()];java.util.HashSet<String> seen=new java.util.HashSet<>();
        for(int i=0;i<allowedSources.length;i++){Object id=allowed.get(i);if(!(id instanceof String)||!((String)id).matches("[0-9]{1,3}")||!seen.add((String)id))throw new IllegalArgumentException("接口ID必须为不重复的字符串");allowedSources[i]=(String)id;}
        if(sourceFilterEnabled&&allowedSources.length==0)throw new IllegalArgumentException("开启接口筛选时至少选择一个接口");
        raw=root.toString();fingerprint=ConfigFingerprint.of(root);
    }
    EdgeColorEngine newEngine(){
        int[] indices=new int[leds.length];String[] edges=new String[leds.length];double[] positions=new double[leds.length];
        for(int i=0;i<leds.length;i++){indices[i]=leds[i].index;edges[i]=leds[i].edge;positions[i]=leds[i].u;}
        return new EdgeColorEngine(ledCount,indices,edges,positions,closed,pointsPerEdge);
    }
    boolean allows(String source){for(String allowed:allowedSources)if(allowed.equals(source))return true;return false;}
    private static boolean booleanValue(JSONObject object,String name,boolean fallback) throws Exception {
        if(object==null||!object.has(name))return fallback;Object raw=object.get(name);
        if(!(raw instanceof Boolean))throw new IllegalArgumentException(name+"必须为布尔值");return (Boolean)raw;
    }
    ColorCorrection newCorrection(){
        int[] indices=new int[leds.length];for(int i=0;i<leds.length;i++)indices[i]=leds[i].index;
        return new ColorCorrection(ledCount,indices,colorSaturation,colorGain,colorOffset,colorGamma,colorRedGain,colorGreenGain,colorBlueGain);
    }
    private static double colorValue(JSONObject object,String name,double fallback,double minimum,double maximum) throws Exception {
        if(object==null||!object.has(name))return fallback;
        Object raw=object.get(name);
        if(!(raw instanceof Number))throw new IllegalArgumentException("颜色校正"+name+"必须为数字");
        double value=((Number)raw).doubleValue();
        if(!Double.isFinite(value)||value<minimum||value>maximum)throw new IllegalArgumentException("颜色校正"+name+"超出范围");
        return value;
    }
    static InetAddress validateAddress(String value) throws Exception {
        if(!value.matches("(?:[0-9]{1,3}\\.){3}[0-9]{1,3}")) throw new IllegalArgumentException("请填写局域网IPv4地址");
        for(String part:value.split("\\.")) if(Integer.parseInt(part)>255) throw new IllegalArgumentException("IP地址无效");
        InetAddress ip=InetAddress.getByName(value);
        if(!ip.isSiteLocalAddress() || ip.isMulticastAddress()) throw new IllegalArgumentException("只连接局域网私有地址");
        return ip;
    }
}
