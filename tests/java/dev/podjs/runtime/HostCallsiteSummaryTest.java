package dev.podjs.runtime;
public class HostCallsiteSummaryTest {
    public static void main(String[] args){
        String s=HostCallsiteSummary.format(new double[][]{{53,51},{71,2}},new double[]{0,51,0,2,0,0},2,10);
        assert "hostCallsiteSummary counts=[[53,51],[71,2]] bands=[0,51,0,2,0,0] lastReceiptClass=2 receipts=10".equals(s);
        for(double bad:new double[]{-1,128,Double.NaN,Double.POSITIVE_INFINITY,1.5})assert HostCallsiteSummary.format(new double[][]{{bad,1}},new double[]{1,0,0,0,0,0},0,0)==null;
        assert HostCallsiteSummary.format(new double[][]{{1,1},{1,1}},new double[]{2,0,0,0,0,0},0,0)==null;
        assert HostCallsiteSummary.format(new double[][]{{1,1}},new double[]{0,0,0,0,0,0},0,0)==null;
        assert HostCallsiteSummary.format(new double[][]{{1,1}},new double[]{1,0,0,0,0,0},0,-1)==null;
        System.out.println("HostCallsiteSummary: PASS numeric bounds, duplicate IDs, totals, exact output");
    }
}
