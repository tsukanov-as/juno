package juno.runtime;

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import static juno.runtime.RuntimeSupport.*;
import static juno.runtime.IoSupport.*;

/** Constructors and globals for files, text, byte values and streaming I/O. */
final class IoLibrary {
    private IoLibrary() {}
    static Object construct(String name,Object[] supplied) {
        Object[] args=values(supplied);
        return switch(name) {
            case "файл", "file" -> { arity(name,args,1,1); yield new NativeFile(string(args[0])); }
            case "чтениетекста", "textreader" -> new TextIo.Input(args);
            case "записьтекста", "textwriter" -> new TextIo.Output(args);
            case "текстовыйдокумент", "textdocument" -> { arity(name,args,0,0); yield new TextDocument(); }
            case "двоичныеданные", "binarydata" -> {
                arity(name,args,1,1); try { Path path=path(args[0]); IoSupport.regular(path); yield new Binary(Files.readAllBytes(path)); }
                catch(IOException e) { throw failure("Чтение двоичных данных",e); }
            }
            case "буфердвоичныхданных", "binarydatabuffer" -> {
                arity(name,args,1,2); yield new BinaryBuffer(nonnegative(args[0]),optional(args,1,IoEnums.BYTE_ORDER.property("littleendian")));
            }
            case "потоквпамяти", "memorystream" -> { arity(name,args,0,1); yield new BinaryStream.Memory(nonnegative(optional(args,0,number("0")))); }
            case "файловыйпоток", "filestream" -> {
                arity(name,args,2,3); String mode=IoEnums.code(args[1],IoEnums.OPEN);
                String access=IoEnums.code(optional(args,2,IoEnums.ACCESS.property(mode.equals("Append") ? "write" : "readandwrite")),IoEnums.ACCESS);
                yield new BinaryStream.File(path(args[0]),mode,access);
            }
            case "поток", "stream" -> throw error("Поток — базовый тип; создайте ФайловыйПоток или ПотокВПамяти");
            default -> null;
        };
    }
    static void register(Map<String,GlobalFunctions.Entry> entries) {
        GlobalFunctions.add(entries,"ТекущийКаталог","CurrentDirectory",0,0,a -> Execution.context().directory().toString());
        GlobalFunctions.add(entries,"УстановитьТекущийКаталог","SetCurrentDirectory",1,1,a -> { Execution.context().directory(path(a[0])); return UNDEFINED; });
        GlobalFunctions.add(entries,"ПолучитьРазделительПути","GetPathSeparator",0,0,a -> java.io.File.separator);
        GlobalFunctions.add(entries,"ОбъединитьПути","CombinePath",1,16,IoLibrary::combine);
        GlobalFunctions.add(entries,"КаталогВременныхФайлов","TempFilesDir",0,0,a -> Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize()+java.io.File.separator);
        GlobalFunctions.add(entries,"ПолучитьИмяВременногоФайла","GetTempFileName",0,1,a -> {
            String suffix=string(optional(a,0,"tmp")); if(suffix.startsWith(".")) suffix=suffix.substring(1);
            if(suffix.contains("/") || suffix.contains("\\") || suffix.indexOf('\0')>=0) throw error("Расширение временного файла не должно содержать путь");
            return Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize().resolve("juno-"+UUID.randomUUID()+(suffix.isEmpty() ? "" : "."+suffix)).toString();
        });
        GlobalFunctions.add(entries,"СоздатьКаталог","CreateDirectory",1,1,a -> io(() -> { Files.createDirectories(path(a[0])); return UNDEFINED; }));
        GlobalFunctions.add(entries,"КопироватьФайл","FileCopy",2,2,a -> copy(path(a[0]),path(a[1])));
        GlobalFunctions.alias(entries,"КопироватьФайл","CopyFile");
        GlobalFunctions.add(entries,"ПереместитьФайл","MoveFile",2,2,a -> move(path(a[0]),path(a[1])));
        GlobalFunctions.add(entries,"НайтиФайлы","FindFiles",1,3,a -> find(path(a[0]),optional(a,1,UNDEFINED),truth(optional(a,2,false))));
        GlobalFunctions.add(entries,"УдалитьФайлы","DeleteFiles",1,2,a -> {
            Path target=path(a[0]); Object mask=optional(a,1,UNDEFINED);
            if(omitted(mask)) delete(target);
            else for(Object item:((BArray)find(target,mask,false)).values) delete(((NativeFile)item).path());
            return UNDEFINED;
        });
        GlobalFunctions.add(entries,"ПолучитьДвоичныеДанныеИзСтроки","GetBinaryDataFromString",1,3,IoLibrary::stringBinary);
        GlobalFunctions.add(entries,"ПолучитьСтрокуИзДвоичныхДанных","GetStringFromBinaryData",1,2,a -> TextEncoding.value(optional(a,1,UNDEFINED)).decodeReplacing(binary(a[0]).bytes));
        GlobalFunctions.add(entries,"Base64Строка","Base64String",1,1,a -> omitted(a[0])?UNDEFINED:Base64.getEncoder().encodeToString(binary(a[0]).bytes));
        GlobalFunctions.add(entries,"Base64Значение","Base64Value",1,1,a -> base64Value(string(a[0])));
        GlobalFunctions.add(entries,"ПолучитьBase64СтрокуИзДвоичныхДанных","GetBase64StringFromBinaryData",1,1,a -> Base64.getMimeEncoder(76,new byte[]{13,10}).encodeToString(binary(a[0]).bytes));
        GlobalFunctions.add(entries,"ПолучитьДвоичныеДанныеИзBase64Строки","GetBinaryDataFromBase64String",1,1,a -> base64(string(a[0])));
        GlobalFunctions.add(entries,"ПолучитьHexСтрокуИзДвоичныхДанных","GetHexStringFromBinaryData",1,1,a -> HexFormat.of().withUpperCase().formatHex(binary(a[0]).bytes));
        GlobalFunctions.add(entries,"ПолучитьДвоичныеДанныеИзHexСтроки","GetBinaryDataFromHexString",1,1,a -> {
            try { return new Binary(HexFormat.of().parseHex(string(a[0]).replaceAll("[\\t\\r\\n ]", ""))); }
            catch(IllegalArgumentException e) { throw failure("Неверная строка Hex",e); }
        });
    }
    private static Object stringBinary(Object[] args) {
        TextEncoding encoding=TextEncoding.value(optional(args,1,UNDEFINED));byte[] bytes=encoding.encode(string(args[0]));
        boolean bom=JsonOptions.bool(optional(args,2,false));
        if(!bom||encoding.preamble().length==0)return new Binary(bytes);
        byte[] all=java.util.Arrays.copyOf(encoding.preamble(),encoding.preamble().length+bytes.length);
        System.arraycopy(bytes,0,all,encoding.preamble().length,bytes.length);return new Binary(all);
    }
    private static Object base64Value(String text) {
        int end=0; while(end<text.length() && "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/=".indexOf(text.charAt(end))>=0)end++;
        String prefix=text.substring(0,end);
        if(prefix.isEmpty()||prefix.chars().allMatch(c->c=='='))return new Binary(new byte[0]);
        if(prefix.length()%4!=0)return UNDEFINED;
        try{return new Binary(Base64.getDecoder().decode(prefix));}catch(IllegalArgumentException e){return UNDEFINED;}
    }
    private static Binary base64(String text) {
        try {
            String lexical=text.replaceAll("[\\t\\r\\n ]", "");
            if(lexical.length()%4!=0) throw new IllegalArgumentException("требуется padding Base64");
            return new Binary(Base64.getDecoder().decode(lexical));
        }
        catch(IllegalArgumentException e) { throw failure("Неверная строка Base64",e); }
    }
    static Binary binary(Object value) { if(value instanceof Binary b) return b; throw error("Ожидались ДвоичныеДанные"); }
    private interface Operation { Object run() throws IOException; }
    private static Object io(Operation operation) { try { Execution.checkCancellation(); return operation.run(); } catch(IOException e) { throw failure("Операция с файлом",e); } }
    private static Object combine(Object[] args) {
        Path result=Path.of(string(args[0]));
        for(int i=1;i<args.length;i++) { if(omitted(args[i])) throw error("Пропущена часть пути"); Path next=Path.of(string(args[i])); result=next.isAbsolute() ? next : result.resolve(next); }
        return result.toString();
    }
    private static Object copy(Path source,Path target) {
        return copy(source,target,true);
    }
    private static Object copy(Path source,Path target,boolean replace) {
        IoSupport.regular(source); IoSupport.regular(target);
        return io(() -> {
            if(replace && Files.exists(target) && Files.isSameFile(source,target)) return UNDEFINED;
            java.nio.file.attribute.FileTime modified=Files.getLastModifiedTime(source);
            Set<java.nio.file.attribute.PosixFilePermission> permissions=!Files.getFileStore(source).supportsFileAttributeView(java.nio.file.attribute.PosixFileAttributeView.class)
                ? null : Files.getPosixFilePermissions(source);
            Boolean readonly=System.getProperty("os.name").startsWith("Windows") ? (Boolean)Files.getAttribute(source,"dos:readonly") : null;
            try(BinaryStream.File input=new BinaryStream.File(source,"Open","Read");
                BinaryStream.File output=new BinaryStream.File(target,replace ? "Create" : "CreateNew","Write")) {
                byte[] chunk=new byte[65536]; int count;
                while(true) { Execution.checkCancellation(); count=input.readBytes(chunk,0,chunk.length); if(count<0) break; if(count>0) output.writeBytes(chunk,0,count); }
            }
            Execution.checkCancellation();
            Files.setLastModifiedTime(target,modified);
            if(permissions!=null && Files.getFileStore(target).supportsFileAttributeView(java.nio.file.attribute.PosixFileAttributeView.class)) Files.setPosixFilePermissions(target,permissions);
            if(readonly!=null) Files.setAttribute(target,"dos:readonly",readonly);
            return UNDEFINED;
        });
    }
    private static Object move(Path source,Path target) {
        return io(() -> {
            if(Files.isSymbolicLink(source) || Files.getFileStore(source).equals(Files.getFileStore(target.getParent()))) Files.move(source,target);
            else {
                // Avoid the JDK's non-interruptible cross-filesystem data-copy fallback.
                IoSupport.regular(source); copy(source,target,false); Execution.checkCancellation(); Files.delete(source);
            }
            return UNDEFINED;
        });
    }
    private static boolean match(String pattern,String name) {
        if(pattern.isEmpty() || pattern.equals("*.*")) pattern="*";
        pattern=pattern.toLowerCase(Locale.ROOT); name=name.toLowerCase(Locale.ROOT);
        return match(pattern,0,name,0,new HashMap<>());
    }
    private static boolean match(String p,int pi,String s,int si,Map<Long,Boolean> memo) {
        long key=((long)pi<<32)|si; Boolean cached=memo.get(key); if(cached!=null) return cached;
        boolean result;
        if(pi==p.length()) result=si==s.length();
        else if(p.charAt(pi)=='*') result=match(p,pi+1,s,si,memo) || si<s.length() && match(p,pi,s,si+1,memo);
        else if(p.charAt(pi)=='?') result=match(p,pi+1,s,si<s.length() && s.charAt(si)!='.' ? si+1 : si,memo);
        else if(p.charAt(pi)=='.' && si==s.length()) result=pi+1==p.length() || pi+2==p.length() && p.charAt(pi+1)=='*';
        else result=si<s.length() && p.charAt(pi)==s.charAt(si) && match(p,pi+1,s,si+1,memo);
        memo.put(key,result); return result;
    }
    private static Object find(Path directory,Object mask,boolean recurse) {
        BArray result=new BArray();
        if(omitted(mask)) { if(Files.exists(directory,LinkOption.NOFOLLOW_LINKS)) result.values.add(new NativeFile(directory)); return result; }
        if(!Files.exists(directory)) return result;
        if(!Files.isDirectory(directory)) throw error("НайтиФайлы: ожидался каталог");
        String pattern=string(mask); if(pattern.contains("/") || pattern.contains("\\")) throw error("Маска не должна содержать путь");
        List<Path> found=new ArrayList<>();
        io(() -> {
            Files.walkFileTree(directory,Set.of(),recurse ? Integer.MAX_VALUE : 1,new SimpleFileVisitor<>() {
                public FileVisitResult preVisitDirectory(Path path,BasicFileAttributes attrs) { Execution.checkCancellation(); if(!path.equals(directory) && match(pattern,path.getFileName().toString())) found.add(path); return FileVisitResult.CONTINUE; }
                public FileVisitResult visitFile(Path path,BasicFileAttributes attrs) { Execution.checkCancellation(); if(match(pattern,path.getFileName().toString())) found.add(path); return FileVisitResult.CONTINUE; }
            }); return UNDEFINED;
        });
        found.sort(Comparator.comparing(Path::toString)); for(Path path:found) result.values.add(new NativeFile(path)); return result;
    }
    private static void delete(Path target) {
        if(!Files.exists(target,LinkOption.NOFOLLOW_LINKS)) return;
        io(() -> { Files.walkFileTree(target,new SimpleFileVisitor<>() {
            public FileVisitResult visitFile(Path file,BasicFileAttributes attributes) throws IOException { Execution.checkCancellation(); Files.delete(file); return FileVisitResult.CONTINUE; }
            public FileVisitResult postVisitDirectory(Path directory,IOException failure) throws IOException { Execution.checkCancellation(); if(failure!=null) throw failure; Files.delete(directory); return FileVisitResult.CONTINUE; }
        }); return UNDEFINED; });
    }
}
