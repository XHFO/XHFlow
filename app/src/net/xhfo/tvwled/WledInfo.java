package net.xhfo.tvwled;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import org.json.JSONObject;

final class WledInfo {
    static JSONObject read(String address) throws Exception {
        LayoutConfig.validateAddress(address);
        HttpURLConnection connection=(HttpURLConnection)new URL("http://"+address+"/json/info").openConnection();
        connection.setConnectTimeout(2500);connection.setReadTimeout(2500);connection.setInstanceFollowRedirects(false);
        try {
            if(connection.getResponseCode()!=200)throw new IllegalStateException("WLED未返回有效信息");
            ByteArrayOutputStream data=new ByteArrayOutputStream();
            try(InputStream stream=connection.getInputStream()){
                byte[] buffer=new byte[4096];int n;
                while((n=stream.read(buffer))!=-1){if(data.size()+n>131072)throw new IllegalStateException("WLED响应过大");data.write(buffer,0,n);}
            }
            JSONObject raw=new JSONObject(data.toString("UTF-8")),result=new JSONObject();
            result.put("name",raw.optString("name","WLED"));result.put("version",raw.getString("ver"));result.put("ledCount",raw.getJSONObject("leds").getInt("count"));
            return result;
        } finally{connection.disconnect();}
    }
}
