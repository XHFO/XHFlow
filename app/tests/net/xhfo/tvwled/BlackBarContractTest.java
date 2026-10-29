package net.xhfo.tvwled;

/** Black-bar regressions checked against synthetic images, including the actual pixel sampling plan. */
public final class BlackBarContractTest {
    private static int checks;
    private interface Image { int[] pixel(double x,double y); }
    private static final int[] PICTURE={82,144,219};
    private static final Image FULL=(x,y)->PICTURE.clone();
    private static final Image DARK=(x,y)->new int[]{0,0,0};
    private static final double[] INSETS={0,.005,.01,.045,.2};
    private static final int[][] SIZES={{1920,1080},{3840,2160}};
    private static void check(boolean condition,String message){checks++;if(!condition)throw new AssertionError(message);}
    private static int maximum(int[] color){return Math.max(color[0],Math.max(color[1],color[2]));}
    private static Image bars(boolean horizontal,double thickness,int black){
        return (x,y)->{
            double across=horizontal?y:x;
            return across<thickness||across>1-thickness?new int[]{black,black,black}:PICTURE.clone();
        };
    }
    private static int[][] read(int[][] coordinates,Image image,int width,int height){
        int[][] colors=new int[coordinates.length][];
        for(int i=0;i<coordinates.length;i++){
            check(coordinates[i][0]>=0&&coordinates[i][0]<width&&coordinates[i][1]>=0&&coordinates[i][1]<height,"Capture coordinates must stay on the panel");
            colors[i]=image.pixel(coordinates[i][0]/(double)(width-1),coordinates[i][1]/(double)(height-1));
        }
        return colors;
    }
    private static void feed(BlackBarDetector detector,Image image,int width,int height,int rounds){
        for(int round=0;round<rounds;round++){
            BlackBarDetector.Probe probe=detector.nextProbe(width,height);
            check(probe.coordinates.length>0&&probe.coordinates.length<=20,"Each bar probe must fit the verified vendor limit of 20 points");
            detector.observe(probe,read(probe.coordinates,image,width,height));
        }
    }
    private static boolean full(EdgeColorEngine.Region region){return region.left==0&&region.top==0&&region.right==1&&region.bottom==1;}
    private static void checkSampling(BlackBarDetector detector,Image image,boolean horizontal,double thickness,int width,int height,String name){
        EdgeColorEngine.Region region=detector.region();
        check(horizontal?region.top>0&&region.bottom<1:region.left>0&&region.right<1,name+": stable bars must be detected");
        check(horizontal?region.left==0&&region.right==1:region.top==0&&region.bottom==1,name+": bars on one axis must not crop the other axis");
        for(int count:new int[]{5,10})for(double inset:INSETS){
            EdgeColorEngine.Point[] plan=EdgeColorEngine.plan(region,inset,count);
            int[][] pixels=EdgeColorEngine.coordinates(plan,width,height);
            int[][] colors=read(pixels,image,width,height);
            int[] indices=new int[plan.length];String[] edges=new String[plan.length];double[] positions=new double[plan.length];
            for(int i=0;i<plan.length;i++){
                double x=pixels[i][0]/(double)(width-1),y=pixels[i][1]/(double)(height-1),across=horizontal?y:x;
                String at=name+" points/edge="+count+" inset="+inset+" sample="+i;
                check(across>=thickness&&across<=1-thickness,at+": pixel coordinate still intersects the black bar");
                check(maximum(colors[i])>36,at+": actual image sample is black");
                indices[i]=i;edges[i]=plan[i].edge;positions[i]=plan[i].position;
            }
            EdgeColorEngine engine=new EdgeColorEngine(plan.length,indices,edges,positions,false,count);
            int[] mapped=engine.map(colors,1,false,0);
            for(int i=0;i<plan.length;i++)check(mapped[i*3]==PICTURE[0]&&mapped[i*3+1]==PICTURE[1]&&mapped[i*3+2]==PICTURE[2],name+": mapped LEDs must use picture colors at inset "+inset);
        }
    }
    private static void checkBoundary(BlackBarDetector detector,boolean horizontal,double thickness,String name){
        EdgeColorEngine.Region region=detector.region();double near=horizontal?region.top:region.left,far=horizontal?region.bottom:region.right;
        check(near>=thickness&&near<=thickness+.01,name+": crop must follow the new picture boundary rather than retain an older deeper crop");
        check(far<=1-thickness&&far>=1-thickness-.01,name+": opposite crop must follow the new picture boundary");
    }
    public static void main(String[] args){
        // The old detector cropped to the midpoint of two probes. At 0-1% inset, these common
        // cinema formats still sampled the bars even after detection had supposedly succeeded.
        for(int[] size:SIZES)for(int black:new int[]{0,16}){
            for(double aspect:new double[]{2.35,2.39,2.4,2.76}){
                double thickness=(1-(16.0/9)/aspect)/2;
                Image image=bars(true,thickness,black);BlackBarDetector detector=new BlackBarDetector();
                feed(detector,image,size[0],size[1],32);
                checkSampling(detector,image,true,thickness,size[0],size[1],"Cinema "+aspect+" black="+black+" panel="+size[0]);
            }
            for(double thickness:new double[]{.08,.12,.178}){
                Image image=bars(false,thickness,black);BlackBarDetector detector=new BlackBarDetector();
                feed(detector,image,size[0],size[1],32);
                checkSampling(detector,image,false,thickness,size[0],size[1],"Pillarbox "+thickness+" black="+black+" panel="+size[0]);
            }
        }
        // A narrow subtitle can contaminate one of the three along-edge probes. The other
        // two still see the bar; the picture boundary should remain detectable and accurate.
        for(int[] size:SIZES)for(int black:new int[]{0,16}){
            double thickness=(1-(16.0/9)/2.4)/2;Image clean=bars(true,thickness,black);
            Image subtitle=(x,y)->x>.48&&x<.52&&y>.9&&y<.96?new int[]{255,255,255}:clean.pixel(x,y);
            BlackBarDetector detector=new BlackBarDetector();feed(detector,subtitle,size[0],size[1],32);
            checkSampling(detector,subtitle,true,thickness,size[0],size[1],"Single subtitle probe black="+black+" panel="+size[0]);
            checkBoundary(detector,true,thickness,"Single subtitle probe");
        }
        for(int[] size:SIZES)for(int black:new int[]{0,16}){
            // Use one detector throughout: a thicker-bar scene must grow the crop, a subsequent
            // thinner-bar scene must shrink it, and full-frame content must remove it completely.
            BlackBarDetector detector=new BlackBarDetector();
            for(double aspect:new double[]{2.4,2.76,2.35}){
                double thickness=(1-(16.0/9)/aspect)/2;Image image=bars(true,thickness,black);
                feed(detector,image,size[0],size[1],32);
                String name="Dynamic cinema "+aspect+" black="+black+" panel="+size[0];
                checkSampling(detector,image,true,thickness,size[0],size[1],name);
                checkBoundary(detector,true,thickness,name);
            }
            feed(detector,FULL,size[0],size[1],32);
            check(full(detector.region()),"Full-frame picture must remove the crop after multiple aspect-ratio changes");
            for(boolean horizontal:new boolean[]{true,false}){
                Image thin=bars(horizontal,.02,black);BlackBarDetector thinDetector=new BlackBarDetector();
                feed(thinDetector,thin,size[0],size[1],32);
                String name="Thin 2% bars horizontal="+horizontal+" black="+black+" panel="+size[0];
                checkSampling(thinDetector,thin,horizontal,.02,size[0],size[1],name);
                checkBoundary(thinDetector,horizontal,.02,name);
            }
        }
        for(Image ambiguous:new Image[]{DARK,(x,y)->new int[]{16,16,16},(x,y)->new int[]{20,20,20}}){
            BlackBarDetector detector=new BlackBarDetector();feed(detector,ambiguous,3840,2160,32);
            check(full(detector.region()),"Uniform dark/gray frames must never invent a crop");
        }
        BlackBarDetector colored=new BlackBarDetector();
        Image coloredEdges=(x,y)->y<.125||y>.875?(x<.5?new int[]{13,0,0}:new int[]{0,0,13}):PICTURE.clone();
        feed(colored,coloredEdges,3840,2160,32);
        check(full(colored.region()),"Dark colored picture edges must not be treated as neutral black bars");
        BlackBarDetector asymmetric=new BlackBarDetector();
        feed(asymmetric,(x,y)->y<.125?new int[]{0,0,0}:PICTURE.clone(),3840,2160,32);
        check(full(asymmetric.region()),"A single dark band must not become a symmetric crop");
        BlackBarDetector transition=new BlackBarDetector();
        Image cinema=bars(true,(1-(16.0/9)/2.4)/2,16);
        feed(transition,cinema,3840,2160,32);EdgeColorEngine.Region accepted=transition.region();
        check(!full(accepted),"Limited-range black bars must establish an active picture region");
        feed(transition,DARK,3840,2160,32);
        EdgeColorEngine.Region held=transition.region();
        check(held.left==accepted.left&&held.top==accepted.top&&held.right==accepted.right&&held.bottom==accepted.bottom,"A black fade must hold the previous crop without growing it");
        feed(transition,FULL,3840,2160,32);
        check(full(transition.region()),"A restored full-frame picture must clear the old cinema crop");
        System.out.println("BlackBarContractTest PASS ("+checks+" checks)");
    }
}
