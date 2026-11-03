package net.xhfo.tvwled;

/** Host-only contract for the sample median filter and its dead band. */
public final class StabilizerContractTest {
    private static int checks;
    private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    interface Work {void run() throws Exception;}
    private static void rejects(Work work,String message) throws Exception {try{work.run();throw new AssertionError(message);}catch(IllegalArgumentException expected){checks++;}}
    private static int[][] frame(int r,int g,int b){return new int[][]{{r,g,b}};}
    private static int[] out(int[][] value){return value[0];}

    public static void main(String[] args) throws Exception {
        // A still picture is reproduced exactly, forever.
        SampleStabilizer still=new SampleStabilizer(3);
        int[] steady=null;
        for(int i=0;i<50;i++)steady=out(still.push(frame(120,80,40)));
        check(steady[0]==120&&steady[1]==80&&steady[2]==40,"a still picture is stable");

        // One wildly wrong frame is rejected by the median.
        SampleStabilizer spike=new SampleStabilizer(3);
        still(spike,frame(120,80,40));
        spike.push(frame(0,0,0));
        int[] after=out(spike.push(frame(120,80,40)));
        check(after[0]==120&&after[1]==80&&after[2]==40,"a single zeroed frame never reaches the lights");

        // A real change still lands, within two frames.
        SampleStabilizer step=new SampleStabilizer(3);
        still(step,frame(10,10,10));
        step.push(frame(200,200,200));
        int[] first=out(step.push(frame(200,200,200)));
        int[] second=out(step.push(frame(200,200,200)));
        check(first[0]!=200||second[0]==200,"a step change converges within two frames");
        check(second[0]==200&&second[1]==200&&second[2]==200,"the median follows a sustained change");

        // A one-level difference never moves the output (dead band).
        SampleStabilizer dead=new SampleStabilizer(3);
        still(dead,frame(64,64,64));
        int[] nudged=out(dead.push(frame(65,64,65)));
        check(nudged[0]==64&&nudged[2]==64,"one-level jitter is absorbed");

        // Every edge of the frame is filtered independently.
        SampleStabilizer edges=new SampleStabilizer(3);
        int[][] base={{10,10,10},{20,20,20},{30,30,30}};
        edges.push(base);edges.push(base);
        int[][] broken={{10,10,10},{0,0,0},{30,30,30}};
        int[][] fixed=edges.push(broken);
        check(fixed[1][0]==20,"one bad point among many is repaired");

        // Switching sample count resets history instead of mixing layouts.
        SampleStabilizer resize=new SampleStabilizer(3);
        resize.push(new int[][]{{1,1,1},{2,2,2}});
        int[][] grown=resize.push(new int[][]{{9,9,9},{8,8,8},{7,7,7}});
        check(grown.length==3,"a changed sample count is accepted");
        check(grown[0][0]==9&&grown[2][0]==7,"the first frame of a new layout passes through");

        rejects(()->new SampleStabilizer(2),"two frames cannot form a median");
        rejects(()->new SampleStabilizer(3).push(null),"null samples rejected");
        rejects(()->new SampleStabilizer(3).push(new int[][]{{1,2}}),"short RGB rejected");

        System.out.println("StabilizerContractTest PASS ("+checks+" checks, no network or device access)");
    }

    private static void still(SampleStabilizer stabilizer,int[][] value){
        stabilizer.push(value);stabilizer.push(value);stabilizer.push(value);
    }
}
