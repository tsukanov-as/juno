package juno.runtime;

import java.io.*;
import java.util.*;
import static juno.runtime.RuntimeSupport.*;
import static juno.runtime.IoSupport.*;

/** HTTP constructors and URL percent coding, independent of form encoding. */
final class HttpLibrary {
    static final IoEnums.Namespace ENCODING=new IoEnums.Namespace("СпособКодированияСтроки",new String[][]{{"КодировкаURL","URLEncoding"},{"URLВКодировкеURL","URLInURLEncoding"}});
    static final IoEnums.Namespace BOM=new IoEnums.Namespace("ИспользованиеByteOrderMark",new String[][]{{"Авто","Auto"},{"Использовать","Use"},{"НеИспользовать","DontUse"}});
    static Object global(String name) { return switch(name) { case "способкодированиястроки","stringencodingmethod" -> ENCODING; case "использованиеbyteordermark","byteordermarkusage" -> BOM; default -> null; }; }
    static Object construct(String name,Object[] raw) {
        return switch(name) {
            case "httpсоединение","httpconnection" -> new HttpConnection(values(raw));
            case "httpзапрос","httprequest" -> new HttpData.Request(values(raw));
            case "httpответ","httpresponse" -> throw error("HTTPОтвет создаётся соединением");
            case "интернетпрокси","internetproxy" -> new HttpProxy(values(raw));
            case "защищенноесоединениеopenssl","opensslsecureconnection" -> HttpTls.connection(values(raw));
            case "сертификатклиентафайл","fileclientcertificate" -> HttpTls.client(values(raw));
            case "сертификатыудостоверяющихцентровфайл","filecertificationauthoritycertificates" -> HttpTls.authorities(values(raw));
            default -> null;
        };
    }
    static void register(Map<String,GlobalFunctions.Entry> entries) {
        GlobalFunctions.add(entries,"КодироватьСтроку","EncodeString",2,3,a -> url(a,false));
        GlobalFunctions.add(entries,"РаскодироватьСтроку","DecodeString",2,3,a -> url(a,true));
    }
    private static Object url(Object[] args,boolean decode) {
        if(!(args[0] instanceof String text)) throw error("URL: ожидается строка");
        boolean fullURL=IoEnums.code(args[1],ENCODING).equals("URLInURLEncoding");
        TextEncoding encoding=TextEncoding.value(optional(args,2,"UTF-8"));
        if(!Set.of("UTF-8","windows-1251","IBM866","US-ASCII","ISO-8859-1").contains(encoding.charset().name()))throw error("URL требует ASCII-совместимую кодировку UTF-8/1251/866/ASCII/Latin1");
        if(!decode) {
            byte[] bytes=encoding.encode(text); StringBuilder out=new StringBuilder();
            for(byte b:bytes) { int n=b&255; if(n>='a'&&n<='z'||n>='A'&&n<='Z'||n>='0'&&n<='9'||"-._~".indexOf(n)>=0||fullURL&&":/?#[]@!$&'()*+,;=%".indexOf(n)>=0) out.append((char)n); else out.append('%').append("0123456789ABCDEF".charAt(n>>4)).append("0123456789ABCDEF".charAt(n&15)); } return out.toString();
        }
        StringBuilder out=new StringBuilder();
        for(int i=0;i<text.length();) {
            if(text.charAt(i)!='%') { out.append(text.charAt(i++)); continue; }
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();
            while(i<text.length()&&text.charAt(i)=='%') {
                if(i+2>=text.length()) throw error("Незавершенный percent escape URL");
                int hi=hex(text.charAt(i+1)),lo=hex(text.charAt(i+2)); if(hi<0||lo<0) throw error("Недопустимый percent escape URL"); bytes.write((hi<<4)|lo); i+=3;
            }
            // URL payload is not a BOM-bearing text file.
            try { out.append(encoding.decoder().decode(java.nio.ByteBuffer.wrap(bytes.toByteArray()))); }
            catch(java.nio.charset.CharacterCodingException e) { throw failure("Декодирование URL",e); }
        }
        TextEncoding.value("UTF-8").encode(out.toString()); return out.toString();
    }
    private static int hex(char c) { return c>='0'&&c<='9'?c-'0':c>='a'&&c<='f'?c-'a'+10:c>='A'&&c<='F'?c-'A'+10:-1; }
}
