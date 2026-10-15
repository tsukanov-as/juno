package juno.runtime;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.time.*;
import java.util.*;
import static juno.runtime.RuntimeSupport.*;
import static juno.runtime.IoSupport.*;

/** Lexical file name; relative metadata paths follow the current application directory. */
final class NativeFile implements NativeObject, TypedValue {
    private final String original;
    NativeFile(String original) { this.original=original; IoSupport.path(original); }
    NativeFile(Path path) { original=path.toAbsolutePath().normalize().toString(); }
    Path path() { return IoSupport.path(original); }
    public String bslTypeName() { return "Файл"; }
    public Object property(String name) {
        String file=Path.of(original).getFileName()==null ? "" : Path.of(original).getFileName().toString();
        int dot=file.lastIndexOf('.');
        return switch(name) {
            case "имя", "name" -> file;
            case "имябезрасширения", "basename", "namewithoutextension" -> dot<0 ? file : file.substring(0,dot);
            case "расширение", "extension" -> dot<0 ? "" : file.substring(dot);
            case "полноеимя", "fullname" -> path().toString();
            case "путь", "path" -> parent();
            default -> missingProperty(bslTypeName(),name);
        };
    }
    public Object invoke(String name,Object[] args) {
        args=values(args);
        Path path=path();
        try {
            return switch(name) {
                case "существует", "exist" -> { arity(name,args,0,0); yield Files.exists(path,LinkOption.NOFOLLOW_LINKS); }
                case "этофайл", "isfile" -> { arity(name,args,0,0); requireExists(); yield Files.isRegularFile(path); }
                case "этокаталог", "isdirectory" -> { arity(name,args,0,0); requireExists(); yield Files.isDirectory(path); }
                case "размер", "size" -> { arity(name,args,0,0); IoSupport.regular(path); yield number(Long.toString(Files.size(path))); }
                case "получитьвремяизменения", "getmodificationtime" -> { arity(name,args,0,0); yield LocalDateTime.ofInstant(Files.getLastModifiedTime(path).toInstant(),ZoneId.systemDefault()); }
                case "установитьвремяизменения", "setmodificationtime" -> {
                    arity(name,args,1,1); Files.setLastModifiedTime(path,FileTime.from(BslDates.value(args[0]).atZone(ZoneId.systemDefault()).toInstant())); yield UNDEFINED;
                }
                case "получитьтолькочтение", "getreadonly" -> { arity(name,args,0,0); requireExists(); yield readonly(); }
                case "установитьтолькочтение", "setreadonly" -> {
                    arity(name,args,1,1); requireExists(); boolean set=truth(args[0]);
                    if (Files.getFileAttributeView(path,DosFileAttributeView.class)!=null && System.getProperty("os.name").startsWith("Windows")) Files.setAttribute(path,"dos:readonly",set);
                    else {
                        Set<PosixFilePermission> permissions=Files.getPosixFilePermissions(path);
                        if(set) permissions.removeAll(Set.of(PosixFilePermission.OWNER_WRITE,PosixFilePermission.GROUP_WRITE,PosixFilePermission.OTHERS_WRITE));
                        else permissions.add(PosixFilePermission.OWNER_WRITE);
                        Files.setPosixFilePermissions(path,permissions);
                    }
                    yield UNDEFINED;
                }
                default -> missingMethod(bslTypeName(),name);
            };
        } catch(IOException | UnsupportedOperationException e) { throw failure("Файл "+path,e); }
    }
    private boolean readonly() throws IOException {
        Path path=path();
        if(System.getProperty("os.name").startsWith("Windows")) return (boolean)Files.getAttribute(path,"dos:readonly");
        Set<PosixFilePermission> p=Files.getPosixFilePermissions(path);
        return Collections.disjoint(p,Set.of(PosixFilePermission.OWNER_WRITE,PosixFilePermission.GROUP_WRITE,PosixFilePermission.OTHERS_WRITE));
    }
    private void requireExists() throws IOException { Path path=path(); if(!Files.exists(path)) throw new NoSuchFileException(path.toString()); }
    private String parent() { Path parent=Path.of(original).getParent(); if(parent==null) return ""; String text=parent.toString(); return text.endsWith(java.io.File.separator) ? text : text+java.io.File.separator; }
    public String toString() { return path().toString(); }
}
