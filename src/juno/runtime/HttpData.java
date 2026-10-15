package juno.runtime;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import static juno.runtime.RuntimeSupport.*;
import static juno.runtime.IoSupport.*;

/** Request values and a repeatable, owned response spool. */
final class HttpData {
    static BMap headers(Object value) {
        BMap result=new BMap(false,true); if(omitted(value))return result;
        if(!(value instanceof BMap map))throw error("HTTP headers: ожидается Соответствие");
        for(Entry entry:map.values.values()) {
            if(!(entry.key instanceof String name))throw error("Имя HTTP header должно быть строкой");
            result.values.put(result.mapKey(name),new Entry(name,entry.value));
        }
        return result;
    }
    static List<String> headerValues(Object value) {
        if(value instanceof String s)return List.of(s);
        if(value instanceof BArray array) { List<String> result=new ArrayList<>();for(Object v:array.values) {if(!(v instanceof String s))throw error("Значения HTTP header должны быть строками");result.add(s);}if(result.isEmpty())throw error("Пустой массив HTTP header");return result; }
        throw error("HTTP header: ожидается строка или массив строк");
    }
    static Map<String,List<String>> snapshot(BMap headers) {
        Map<String,List<String>> result=new LinkedHashMap<>();
        Set<String> forbidden=Set.of("connection","content-length","expect","host","upgrade","transfer-encoding","proxy-authorization","proxy-connection");
        for(Entry entry:headers.values.values()) {
            if(!(entry.key instanceof String name)||!name.matches("[!#$%&'*+.^_`|~0-9A-Za-z-]+"))throw error("Недопустимое имя HTTP header");
            String normalized=name.toLowerCase(Locale.ROOT);if(forbidden.contains(normalized))throw error("HTTP header управляется транспортом: "+name);
            List<String> values=headerValues(entry.value);
            for(String value:values)for(int i=0;i<value.length();i++)if(value.charAt(i)!='\t'&&(value.charAt(i)<32||value.charAt(i)>126))throw error("HTTP header содержит недопустимые символы");
            if(result.putIfAbsent(normalized,List.copyOf(values))!=null)throw error("Повтор HTTP header с другим регистром");
        }
        return result;
    }
    static BMap responseHeaders(Map<String,List<String>> headers) {
        BMap result=new BMap(true,true);
        headers.forEach((name,values)-> {
            Object value;
            if(values.size()==1)value=values.getFirst();else {BArray array=new BArray(true);array.values.addAll(values);value=array;}
            result.values.put(result.mapKey(name),new Entry(name,value));
        });return result;
    }
    static String single(BMap headers,String name) {
        Entry entry=headers.values.get(headers.mapKey(name));if(entry==null)return "";
        List<String> values=headerValues(entry.value);if(values.size()!=1)throw error("Неоднозначный HTTP header: "+name);return values.getFirst();
    }
    static TextEncoding charset(BMap headers) {
        String type=single(headers,"Content-Type");Matcher matcher=Pattern.compile("(?i)(?:^|;)\\s*charset\\s*=\\s*(?:\"([^\"]+)\"|([^;\\s]+))").matcher(type);
        if(!matcher.find())return TextEncoding.value("UTF-8");String name=matcher.group(1)!=null?matcher.group(1):matcher.group(2);
        if(matcher.find())throw error("Несколько charset в Content-Type");return TextEncoding.value(name);
    }
    static byte[] readFile(Path path) {
        try(BinaryStream.File input=new BinaryStream.File(path,"Open","Read")) {
            if(input.size()>16L*1024*1024)throw error("HTTP whole-body getter ограничен 16 МиБ; используйте файл ответа");
            ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] block=new byte[65536];int count;
            while((count=input.readBytes(block,0,block.length))>0){Execution.checkCancellation();if(out.size()>16*1024*1024-count)throw error("HTTP whole-body getter превышает 16 МиБ");out.write(block,0,count);}return out.toByteArray();
        }catch(IOException e){throw failure("Чтение тела HTTP",e);}
    }
    static final class Request implements NativeObject,TypedValue {
        final ApplicationContext owner=Execution.context();
        String address="",fileName;
        Path file;
        byte[] bytes;
        TextEncoding textEncoding;
        BMap headers=new BMap(false,true);
        Request(Object[] args) { arity("HTTPЗапрос",args,0,2); if(args.length>0&&!omitted(args[0]))setProperty("resourceaddress",args[0]);if(args.length>1)headers=headers(args[1]); }
        public String bslTypeName(){return "HTTPЗапрос";}
        public String toString(){return bslTypeName();}
        public Object property(String name){owner.requireOpen();return switch(name){case "адресресурса","resourceaddress"->address;case "заголовки","headers"->headers;default->missingProperty(bslTypeName(),name);};}
        public void setProperty(String name,Object value){owner.requireOpen();switch(name){case "адресресурса","resourceaddress"->{if(!(value instanceof String s))throw error("ResourceAddress: ожидается строка");address=s;}case "заголовки","headers"->headers=headers(value);default->throw error("HTTPRequest: свойство недоступно");}}
        public Object invoke(String name,Object[] raw){Object[] args=values(raw);owner.requireOpen();Execution.checkCancellation();return switch(name){
            case "установитьтелоизстроки","setbodyfromstring"->{arity(name,args,1,3);if(!(args[0] instanceof String text))throw error("Тело HTTP должно быть строкой");TextEncoding codec=TextEncoding.value(optional(args,1,"UTF-8"));String bom=IoEnums.code(optional(args,2,HttpLibrary.BOM.property("авто")),HttpLibrary.BOM);if(bom.equals("Use"))throw error("HTTP body BOM.Use не поддерживается; используйте явные binary bytes");bytes=codec.encode(text);textEncoding=codec;file=null;fileName=null;yield UNDEFINED;}
            case "установитьтелоиздвоичныхданных","setbodyfrombinarydata"->{arity(name,args,1,1);bytes=IoLibrary.binary(args[0]).bytes.clone();file=null;fileName=null;textEncoding=null;yield UNDEFINED;}
            case "установитьимяфайлатела","setbodyfilename"->{arity(name,args,1,1);Path source=path(args[0]);if(!Files.isRegularFile(source))throw error("Тело HTTP: ожидается обычный существующий файл");file=source;fileName=source.toString();bytes=null;textEncoding=null;yield UNDEFINED;}
            case "получитьимяфайлатела","getbodyfilename"->{arity(name,args,0,0);yield fileName==null?UNDEFINED:fileName;}
            case "получитьтелокакдвоичныеданные","getbodyasbinarydata"->{arity(name,args,0,0);yield bytes==null&&file==null?UNDEFINED:new Binary(body());}
            case "получитьтелокакстроку","getbodyasstring"->{arity(name,args,0,0);yield bytes==null&&file==null?UNDEFINED:(textEncoding!=null?textEncoding:charset(headers)).decode(body());}
            default->missingMethod(bslTypeName(),name);
        };}
        private byte[] body(){return file==null?bytes.clone():readFile(file);}
    }
    /**
     * The answer of the server. HTTPОтвет has no Закрыть in 1C, so nothing here may wait for it: the body is taken
     * from the private spool when the answer arrives (kept in memory up to the size of the whole-body getters,
     * dropped beyond it, where only the output file has it) and the spool is deleted at once.
     */
    static final class Response implements NativeObject,TypedValue {
        static final long LIMIT=16L*1024*1024;
        private final String filename;
        private final BMap headers;
        private final int status;
        private final boolean tooLarge;
        private volatile byte[] body;
        private volatile boolean closed;
        Response(Path spool,String filename,int status,Map<String,List<String>> headers){
            this.filename=filename;this.status=status;this.headers=responseHeaders(headers);
            try{
                tooLarge=Files.size(spool)>LIMIT;
                body=tooLarge?null:readFile(spool);
            }catch(IOException e){throw failure("Чтение тела HTTP",e);}
            finally{try{Files.deleteIfExists(spool);}catch(IOException e){throw failure("Удаление временного HTTP тела",e);}}
        }
        public String bslTypeName(){return "HTTPОтвет";}
        public String toString(){return bslTypeName();}
        public Object property(String name){return switch(name){case "кодсостояния","statuscode"->number(Integer.toString(status));case "заголовки","headers"->headers;default->missingProperty(bslTypeName(),name);};}
        private byte[] data(){
            if(tooLarge)throw error("HTTP whole-body getter ограничен 16 МиБ; используйте файл ответа");
            byte[] bytes=body;if(bytes==null)throw error("HTTPОтвет закрыт");return bytes;
        }
        public Object invoke(String name,Object[] raw){Object[] args=values(raw);if(name.equals("закрыть")||name.equals("close")){arity(name,args,0,0);close();return UNDEFINED;}Execution.checkCancellation();if(closed)throw error("HTTPОтвет закрыт");return switch(name){
            case "получитьимяфайлатела","getbodyfilename"->{arity(name,args,0,0);yield filename==null?UNDEFINED:filename;}
            case "получитьтелокакдвоичныеданные","getbodyasbinarydata"->{arity(name,args,0,0);yield new Binary(data().clone());}
            case "получитьтелокакстроку","getbodyasstring"->{arity(name,args,0,1);String coding=single(headers,"Content-Encoding");if(!coding.isEmpty()&&!coding.equalsIgnoreCase("identity"))throw error("Content-Encoding не поддерживается: "+coding);TextEncoding encoding=args.length==0||omitted(args[0])?charset(headers):TextEncoding.value(args[0]);yield encoding.decode(data());}
            default->missingMethod(bslTypeName(),name);
        };}
        /** Not needed in 1C; it only lets a program drop the body early. */
        public void close(){closed=true;body=null;}
    }
}
