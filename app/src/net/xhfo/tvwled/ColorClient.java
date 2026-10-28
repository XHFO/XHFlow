package net.xhfo.tvwled;

import android.os.IBinder;
import android.os.Parcel;
import java.lang.reflect.Method;

final class ColorClient {
    private static final String DESCRIPTOR="mitv.internal.ITvService";
    private IBinder binder;
    private IBinder service() throws Exception {
        if(binder==null || !binder.isBinderAlive()) {
            Method getService=Class.forName("android.os.ServiceManager").getMethod("getService",String.class);
            Object value=getService.invoke(null,"TvService");
            if(!(value instanceof IBinder)) throw new IllegalStateException("普通应用无法访问原厂取色服务");
            binder=(IBinder)value;
        }
        return binder;
    }
    void reset(){binder=null;}
    private void call(int code,Parcel input,Parcel output) throws Exception {
        if(!service().transact(code,input,output,0)) throw new IllegalStateException("原厂接口拒绝请求: "+code);
        output.readException();
    }
    String configuration(String key) throws Exception {
        if(!"source_ports_list".equals(key))throw new IllegalArgumentException("Unsupported read-only configuration key");
        Parcel in=Parcel.obtain(),out=Parcel.obtain();
        try{in.writeInterfaceToken(DESCRIPTOR);in.writeString(key);call(82,in,out);String result=out.readString();if(out.dataAvail()!=0)throw new IllegalStateException("配置返回格式错误");return result;}
        finally{out.recycle();in.recycle();}
    }
    boolean supported() throws Exception {
        Parcel in=Parcel.obtain(),out=Parcel.obtain();
        try {in.writeInterfaceToken(DESCRIPTOR);call(89,in,out);int n=out.readInt();if(n!=0&&n!=1)throw new IllegalStateException("支持标志无效");return n==1;}
        finally{out.recycle();in.recycle();}
    }
    int[] resolution() throws Exception {
        Parcel in=Parcel.obtain(),out=Parcel.obtain();
        try {
            in.writeInterfaceToken(DESCRIPTOR);call(88,in,out);String s=out.readString();
            if(s==null||!s.matches("[0-9]+x[0-9]+"))throw new IllegalStateException("无法读取面板分辨率");
            String[] parts=s.split("x");int w=Integer.parseInt(parts[0]),h=Integer.parseInt(parts[1]);
            if(w<4||h<4||w>16384||h>16384)throw new IllegalStateException("面板分辨率越界");return new int[]{w,h};
        } finally{out.recycle();in.recycle();}
    }
    int[][] sample(int[][] points) throws Exception {return ColorBatches.sample(points,this::sampleBatch);}
    private int[][] sampleBatch(int[][] points) throws Exception {
        if(points.length<1||points.length>20)throw new IllegalArgumentException("采样点数无效");
        Parcel in=Parcel.obtain(),out=Parcel.obtain();
        try {
            in.writeInterfaceToken(DESCRIPTOR);in.writeInt(points.length);
            for(int[] p:points){in.writeInt(1);in.writeIntArray(new int[]{p[0],p[0]+1,p[1],p[1]+1});}
            call(87,in,out);int count=out.readInt();
            if(count!=points.length)throw new IllegalStateException("取色失败：返回点数不一致，暂停灯光输出");
            int[][] rgb=new int[count][3];
            for(int i=0;i<count;i++){
                if(out.readInt()!=1)throw new IllegalStateException("取色结果缺失");
                int[] value=out.createIntArray();
                if(value==null||value.length!=10)throw new IllegalStateException("取色结果格式不正确");
                for(int c=0;c<3;c++){int channel=value[c+1];if(channel<0||channel>255)throw new IllegalStateException("RGB数据越界");rgb[i][c]=channel;}
            }
            if(out.dataAvail()!=0)throw new IllegalStateException("原厂接口返回额外数据");
            return rgb;
        } finally{out.recycle();in.recycle();}
    }
}
