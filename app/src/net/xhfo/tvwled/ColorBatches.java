package net.xhfo.tvwled;

/** One completed capture round; each vendor transaction stays within the verified 20-point limit. */
final class ColorBatches {
    interface Reader {int[][] read(int[][] points) throws Exception;}
    static int[][] sample(int[][] points,Reader reader) throws Exception {
        if(points==null||points.length<1||points.length>40||reader==null)throw new IllegalArgumentException("采样点数无效");
        for(int[] point:points)if(point==null||point.length!=2||point[0]<0||point[1]<0)throw new IllegalArgumentException("采样坐标无效");
        int[][] complete=new int[points.length][3];
        for(int offset=0;offset<points.length;offset+=20){
            int count=Math.min(20,points.length-offset);int[][] batch=new int[count][2];
            for(int i=0;i<count;i++)batch[i]=points[offset+i].clone();
            int[][] colors=reader.read(batch);EdgeColorEngine.validateColors(colors,count);
            for(int i=0;i<count;i++)complete[offset+i]=colors[i].clone();
        }
        return complete;
    }
}
