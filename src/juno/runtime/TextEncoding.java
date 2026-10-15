package juno.runtime;

import java.nio.*;
import java.nio.charset.*;
import java.util.*;
import static juno.runtime.RuntimeSupport.*;
import static juno.runtime.IoSupport.*;

/** Explicit JDK codecs with the selected 1C file/BOM policy. */
record TextEncoding(Charset charset, byte[] preamble) {
    static TextEncoding value(Object value) {
        String name;
        if(omitted(value)) name="UTF8";
        else if(value instanceof IoEnums.Value) name=IoEnums.code(value,IoEnums.ENCODING);
        else if(value instanceof String s) name=s;
        else throw error("Ожидалась КодировкаТекста или имя кодировки");
        String normalized=name.replace("-","").replace("_","").toLowerCase(Locale.ROOT);
        return switch(normalized) {
            case "system", "системная", "utf8" -> new TextEncoding(StandardCharsets.UTF_8,new byte[]{(byte)0xef,(byte)0xbb,(byte)0xbf});
            case "utf8nobom", "utf8безbom" -> new TextEncoding(StandardCharsets.UTF_8,new byte[0]);
            case "utf16", "utf16le" -> new TextEncoding(StandardCharsets.UTF_16LE,new byte[]{(byte)0xff,(byte)0xfe});
            case "utf16be" -> new TextEncoding(StandardCharsets.UTF_16BE,new byte[]{(byte)0xfe,(byte)0xff});
            case "ansi" -> new TextEncoding(Charset.forName("windows-1251"),new byte[0]);
            case "oem" -> new TextEncoding(Charset.forName("IBM866"),new byte[0]);
            default -> {
                try { yield new TextEncoding(Charset.forName(name),new byte[0]); }
                catch(IllegalArgumentException e) { throw error("Неподдерживаемая кодировка: "+name); }
            }
        };
    }
    CharsetEncoder encoder() { return charset.newEncoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT); }
    CharsetDecoder decoder() { return charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT); }
    byte[] encode(String text) {
        try { ByteBuffer b=encoder().encode(CharBuffer.wrap(text)); byte[] result=new byte[b.remaining()]; b.get(result); return result; }
        catch(CharacterCodingException e) { throw failure("Кодирование "+charset.name(),e); }
    }
    String decodeReplacing(byte[] bytes) {
        Bom bom=detect(bytes,bytes.length,this);
        return new String(bytes,bom.skip,bytes.length-bom.skip,bom.encoding.charset());
    }
    byte[] encodeReplacing(String text) {
        CharsetEncoder encoder=charset.newEncoder().onMalformedInput(CodingErrorAction.REPLACE).onUnmappableCharacter(CodingErrorAction.REPLACE);
        if(charset.equals(StandardCharsets.UTF_8))encoder.replaceWith("\ufffd".getBytes(StandardCharsets.UTF_8));
        try{ByteBuffer b=encoder.encode(CharBuffer.wrap(text));byte[] result=new byte[b.remaining()];b.get(result);return result;}
        catch(CharacterCodingException e){throw failure("Кодирование "+charset.name(),e);}
    }
    String decode(byte[] bytes) {
        Bom bom=detect(bytes,bytes.length,this);
        try { return bom.encoding.decoder().decode(ByteBuffer.wrap(bytes,bom.skip,bytes.length-bom.skip)).toString(); }
        catch(CharacterCodingException e) { throw failure("Декодирование "+charset.name(),e); }
    }
    record Bom(TextEncoding encoding,int skip) {}
    static Bom detect(byte[] b,int length,TextEncoding fallback) {
        if(length>=4 && ((b[0]==0 && b[1]==0 && b[2]==(byte)0xfe && b[3]==(byte)0xff) || (b[0]==(byte)0xff && b[1]==(byte)0xfe && b[2]==0 && b[3]==0))) throw error("BOM UTF-32 не поддерживается");
        if(length>=3 && b[0]==(byte)0xef && b[1]==(byte)0xbb && b[2]==(byte)0xbf) return new Bom(value("UTF-8"),3);
        if(length>=2 && b[0]==(byte)0xff && b[1]==(byte)0xfe) return new Bom(value("UTF-16LE"),2);
        if(length>=2 && b[0]==(byte)0xfe && b[1]==(byte)0xff) return new Bom(value("UTF-16BE"),2);
        return new Bom(fallback,0);
    }
}
