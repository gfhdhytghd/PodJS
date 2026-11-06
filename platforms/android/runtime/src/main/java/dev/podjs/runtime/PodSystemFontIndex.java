package dev.podjs.runtime;

import java.io.*;
import java.util.*;
import java.util.zip.CRC32;

/** Bounded, disposable XML parse result; no font bytes or glyph pixels. */
final class PodSystemFontIndex {
    private static final int MAGIC=0x50464931;
    static final class Value {
        final List<PodSystemFonts.Family> families;final String identity;
        Value(List<PodSystemFonts.Family> f,String i){families=f;identity=i;}
    }
    static byte[] encode(String xmlHash,String build,String language,List<PodSystemFonts.Family> families,File fontRoot,String identity)throws IOException {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();DataOutputStream out=new DataOutputStream(bytes);
        out.writeInt(MAGIC);out.writeUTF(xmlHash);out.writeUTF(build);out.writeUTF(language);out.writeUTF(identity);out.writeInt(families.size());
        TreeSet<String> files=new TreeSet<>();
        for(PodSystemFonts.Family family:families) {
            out.writeUTF(family.name);out.writeUTF(family.lang);out.writeInt(family.faces.size());
            for(PodSystemFonts.Face face:family.faces) {
                out.writeUTF(face.file==null?"":face.file);out.writeUTF(face.style);out.writeInt(face.index);out.writeInt(face.weight);out.writeUTF(face.fallbackFor);out.writeInt(face.axes.size());
                for(String axis:face.axes)out.writeUTF(axis);
                if(validFile(face.file))files.add(face.file);
            }
        }
        out.writeInt(files.size());for(String name:files){File file=new File(fontRoot,name);out.writeUTF(name);out.writeLong(file.length());out.writeLong(file.lastModified());}
        out.flush();CRC32 crc=new CRC32();crc.update(bytes.toByteArray());out.writeLong(crc.getValue());out.flush();
        if(bytes.size()>128*1024)throw new IOException("font index bound");return bytes.toByteArray();
    }
    static Value decode(byte[] raw,String xmlHash,String build,String language,File fontRoot) {
        if(raw==null||raw.length<16||raw.length>128*1024)return null;
        try {
            CRC32 crc=new CRC32();crc.update(raw,0,raw.length-8);DataInputStream input=new DataInputStream(new ByteArrayInputStream(raw));
            if(input.readInt()!=MAGIC||!input.readUTF().equals(xmlHash)||!input.readUTF().equals(build)||!input.readUTF().equals(language))return null;
            String identity=input.readUTF();if(!identity.matches("[0-9a-f]{64}"))return null;
            int count=bounded(input.readInt(),1,512);ArrayList<PodSystemFonts.Family> families=new ArrayList<>();
            for(int i=0;i<count;i++) {
                PodSystemFonts.Family family=new PodSystemFonts.Family();family.name=input.readUTF();family.lang=input.readUTF();int faces=bounded(input.readInt(),0,64);
                for(int j=0;j<faces;j++) {
                    PodSystemFonts.Face face=new PodSystemFonts.Face();face.file=input.readUTF();face.style=input.readUTF();face.index=bounded(input.readInt(),0,65535);face.weight=bounded(input.readInt(),1,1000);face.fallbackFor=input.readUTF();int axes=bounded(input.readInt(),0,32);
                    for(int k=0;k<axes;k++)face.axes.add(input.readUTF());family.faces.add(face);
                }
                families.add(family);
            }
            int files=bounded(input.readInt(),1,2048);
            for(int i=0;i<files;i++){String name=input.readUTF();if(!validFile(name))return null;File file=new File(fontRoot,name);long length=input.readLong(),mtime=input.readLong();if(file.length()!=length||file.lastModified()!=mtime)return null;}
            if(input.readLong()!=crc.getValue()||input.read()!=-1)return null;return new Value(families,identity);
        }catch(IOException|RuntimeException invalid){return null;}
    }
    private static int bounded(int value,int min,int max)throws IOException{if(value<min||value>max)throw new IOException("font index count");return value;}
    static boolean validFile(String name){return name!=null&&name.matches("[A-Za-z0-9][A-Za-z0-9_.-]*");}
}
