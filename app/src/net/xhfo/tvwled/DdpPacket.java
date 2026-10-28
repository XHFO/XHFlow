package net.xhfo.tvwled;

/** DDP v1 RGB24. WLED ESPAsyncE131.h: DDP_PORT=4048, DDP_TYPE_RGB24=0x0B. */
final class DdpPacket {
    static final int PORT=4048;
    static byte[] encode(int[] rgb,int sequence) {
        if(rgb.length<3 || rgb.length%3!=0 || rgb.length>1440) throw new IllegalArgumentException("DDP payload length");
        if(sequence<1||sequence>15)throw new IllegalArgumentException("DDP sequence");
        byte[] packet=new byte[10+rgb.length];
        packet[0]=0x41;packet[1]=(byte)sequence;packet[2]=0x0b;packet[3]=1;
        // Byte offset is zero; this packet updates all configured LED indices.
        packet[8]=(byte)(rgb.length>>>8);packet[9]=(byte)rgb.length;
        for(int i=0;i<rgb.length;i++){if(rgb[i]<0||rgb[i]>255)throw new IllegalArgumentException("DDP channel");packet[10+i]=(byte)rgb[i];}
        return packet;
    }
}
