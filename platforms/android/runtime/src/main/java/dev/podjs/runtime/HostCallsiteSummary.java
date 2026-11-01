package dev.podjs.runtime;

/** Numeric-only, bounded release summary. Never echo guest text or identifiers. */
final class HostCallsiteSummary {
    private static boolean integer(double n,int max){return Double.isFinite(n)&&n>=0&&n<=max&&n==Math.floor(n);}
    static String format(double[][] counts,double[] bands,double last,double receipts){
        if(counts==null||counts.length>128||bands==null||bands.length!=6||!integer(last,127)||!integer(receipts,1000000))return null;
        boolean[] seen=new boolean[128];long total=0,bandTotal=0;StringBuilder out=new StringBuilder("hostCallsiteSummary counts=[");
        for(int i=0;i<counts.length;i++){
            double[] row=counts[i];if(row==null||row.length!=2||!integer(row[0],127)||!integer(row[1],1000000)||seen[(int)row[0]])return null;
            seen[(int)row[0]]=true;total+=(int)row[1];if(i>0)out.append(',');out.append('[').append((int)row[0]).append(',').append((int)row[1]).append(']');
        }
        out.append("] bands=[");for(int i=0;i<6;i++){if(!integer(bands[i],1000000))return null;bandTotal+=(int)bands[i];if(i>0)out.append(',');out.append((int)bands[i]);}
        if(total!=bandTotal)return null;
        return out.append("] lastReceiptClass=").append((int)last).append(" receipts=").append((int)receipts).toString();
    }
}
