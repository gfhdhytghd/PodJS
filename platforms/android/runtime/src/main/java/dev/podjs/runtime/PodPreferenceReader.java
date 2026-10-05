package dev.podjs.runtime;

import java.io.*;
import java.nio.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.util.*;
import javax.xml.parsers.*;
import org.w3c.dom.*;

/** Strict, read-only decoders; never instantiate native preference stores. */
final class PodPreferenceReader {
    static final int LIMIT=16*1024*1024;
    static byte[] bytes(File f)throws Exception {
        if(Files.isSymbolicLink(f.toPath()) || !f.getCanonicalFile().equals(f.getAbsoluteFile()))throw new IOException("source_symlink");
        try(InputStream in=new FileInputStream(f);ByteArrayOutputStream out=new ByteArrayOutputStream()){
            byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1){if(out.size()+n>LIMIT)throw new IOException("store_too_large");out.write(b,0,n);}return out.toByteArray();
        }
    }
    static String utf(byte[] b)throws Exception{return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(b)).toString();}
    static Map<String,Object> xml(File f)throws Exception {
        DocumentBuilderFactory factory=DocumentBuilderFactory.newInstance();
        // Android's Harmony factory does not implement Xerces security feature URIs.
        // Native XML is UTF-8; decode explicitly and reject declarations before parsing.
        String source=utf(bytes(f));
        if(source.indexOf('\0')>=0||source.contains("<!DOCTYPE")||source.contains("<!ENTITY"))throw new IOException("xml_declaration_forbidden");
        factory.setExpandEntityReferences(false);
        DocumentBuilder builder=factory.newDocumentBuilder();
        builder.setEntityResolver((publicId,systemId)->{throw new org.xml.sax.SAXException("external_entity_forbidden");});
        builder.setErrorHandler(new org.xml.sax.helpers.DefaultHandler(){
            public void error(org.xml.sax.SAXParseException e)throws org.xml.sax.SAXException{throw new org.xml.sax.SAXException("xml_invalid");}
            public void fatalError(org.xml.sax.SAXParseException e)throws org.xml.sax.SAXException{throw new org.xml.sax.SAXException("xml_invalid");}
        });
        Document d=builder.parse(new org.xml.sax.InputSource(new StringReader(source)));
        if(!d.getDocumentElement().getTagName().equals("map"))throw new IOException("invalid_preferences");
        Map<String,Object> out=new TreeMap<>();NodeList nodes=d.getDocumentElement().getChildNodes();
        for(int i=0;i<nodes.getLength();i++)if(nodes.item(i) instanceof Element){
            Element e=(Element)nodes.item(i);String key=e.getAttribute("name"),v=e.getAttribute("value");Object value;
            switch(e.getTagName()){
                case "string":value=e.getTextContent();break;
                case "boolean":if(!v.equals("true")&&!v.equals("false"))throw new IOException("invalid_boolean");value=Boolean.valueOf(v);break;
                case "int":value=Integer.valueOf(v);break;
                case "long":value=Long.valueOf(v);break;
                case "float":value=Float.valueOf(v);if(!Float.isFinite((Float)value))throw new IOException("nonfinite");break;
                case "set":Set<String> set=new LinkedHashSet<>();NodeList children=e.getChildNodes();for(int j=0;j<children.getLength();j++)if(children.item(j) instanceof Element){Element child=(Element)children.item(j);if(!child.getTagName().equals("string")||!set.add(child.getTextContent()))throw new IOException("invalid_set");}value=set;break;
                default:throw new IOException("unsupported_xml_type");
            }
            if(!e.hasAttribute("name")||out.put(key,value)!=null)throw new IOException("duplicate_key");
        }return out;
    }
    static final class Proto {
        final ByteBuffer b;Proto(byte[] data){b=ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);}
        long var()throws IOException{long n=0;for(int s=0;s<70;s+=7){if(!b.hasRemaining())throw new IOException("truncated_proto");int v=b.get()&255;if(s==63&&(v&254)!=0)throw new IOException("invalid_varint");n|=(long)(v&127)<<s;if(v<128)return n;}throw new IOException("invalid_varint");}
        byte[] data()throws IOException{long n=var();if(n<0||n>b.remaining())throw new IOException("truncated_proto");byte[] out=new byte[(int)n];b.get(out);return out;}
    }
    static Map<String,Object> protobuf(File f)throws Exception {
        Proto p=new Proto(bytes(f));Map<String,Object> out=new TreeMap<>();
        while(p.b.hasRemaining()){
            if(p.var()!=10)throw new IOException("invalid_map");Proto pair=new Proto(p.data());String key=null;Object v=null;
            while(pair.b.hasRemaining()){long tag=pair.var();if(tag==10&&key==null)key=utf(pair.data());else if(tag==18&&v==null)v=value(new Proto(pair.data()));else throw new IOException("invalid_pair");}
            if(key==null||v==null||out.put(key,v)!=null)throw new IOException("duplicate_or_missing_key");
        }return out;
    }
    static Object value(Proto p)throws Exception {
        long tag=p.var();Object v;
        switch((int)tag){
            case 8:long bool=p.var();if(bool!=0&&bool!=1)throw new IOException("invalid_boolean");v=bool==1;break;
            case 21:float f=p.b.getFloat();if(!Float.isFinite(f))throw new IOException("nonfinite");v=f;break;
            case 24:v=(int)p.var();break;case 32:v=p.var();break;
            case 42:v=utf(p.data());break;
            case 50:Proto s=new Proto(p.data());Set<String> set=new LinkedHashSet<>();while(s.b.hasRemaining()){if(s.var()!=10||!set.add(utf(s.data())))throw new IOException("invalid_set");}v=set;break;
            case 57:double d=p.b.getDouble();if(!Double.isFinite(d))throw new IOException("nonfinite");v=d;break;
            case 66:v=p.data();break;
            default:throw new IOException("unsupported_proto_type");
        }
        if(p.b.hasRemaining())throw new IOException("invalid_oneof");return v;
    }
}
