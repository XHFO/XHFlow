import io.nayuki.qrcodegen.QrCode;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import javax.imageio.ImageIO;
public final class QrCodeFixture {
    public static void main(String[] args)throws Exception {
        String value="http://192.168.1.80:8768/?token=0123456789abcdef0123456789abcdef";
        QrCode code=QrCode.encodeText(value,QrCode.Ecc.MEDIUM);int border=4,scale=8,size=(code.size+border*2)*scale;
        BufferedImage image=new BufferedImage(size,size,BufferedImage.TYPE_INT_RGB);
        for(int y=0;y<size;y++)for(int x=0;x<size;x++)image.setRGB(x,y,code.getModule(x/scale-border,y/scale-border)?0:0xffffff);
        ImageIO.write(image,"png",Paths.get(args[0]).toFile());Files.write(Paths.get(args[1]),value.getBytes("UTF-8"));
        System.out.println("QR fixture "+size+"x"+size+" version "+code.version+" with synthetic token");
    }
}
