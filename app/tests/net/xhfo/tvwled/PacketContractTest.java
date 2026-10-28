package net.xhfo.tvwled;
import java.util.Arrays;
public final class PacketContractTest {
 private static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
 private static void rejected(Runnable action){try{action.run();throw new AssertionError("invalid input accepted");}catch(IllegalArgumentException expected){}}
 public static void main(String[] args){
  int[] rgb=new int[126*3];rgb[0]=255;rgb[1]=128;rgb[2]=64;rgb[375]=1;rgb[376]=2;rgb[377]=3;
  byte[] p=DdpPacket.encode(rgb,15);
  check(p.length==388,"126 RGB LEDs must produce 388 bytes");
  byte[] expected={(byte)0x41,15,11,1,0,0,0,0,1,122};
  check(Arrays.equals(Arrays.copyOf(p,10),expected),"DDP header must match official 126-LED RGB24 packet");
  check((p[10]&255)==255&&(p[11]&255)==128&&(p[12]&255)==64,"first pixel RGB order");
  check((p[385]&255)==1&&(p[386]&255)==2&&(p[387]&255)==3,"last pixel RGB order");
  check((DdpPacket.encode(rgb,1)[1]&255)==1,"sequence 1");
  rejected(()->DdpPacket.encode(rgb,0));rejected(()->DdpPacket.encode(rgb,16));
  rejected(()->DdpPacket.encode(new int[4],1));rejected(()->DdpPacket.encode(new int[1443],1));
  rejected(()->DdpPacket.encode(new int[]{256,0,0},1));rejected(()->DdpPacket.encode(new int[]{-1,0,0},1));
  System.out.println("PASS: official DDP RGB24 header, byte order, payload bounds and sequence contract");
 }
}
