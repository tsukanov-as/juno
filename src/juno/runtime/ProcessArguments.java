package juno.runtime;

import java.nio.file.*;
import java.nio.charset.*;
import java.util.*;
import static juno.runtime.IoSupport.*;

/** Explicit command-line subset; never invokes a shell or expands variables/globs. */
public final class ProcessArguments {
    private ProcessArguments() {}
    public static List<String> unix(String text) {
        List<String> result=new ArrayList<>();StringBuilder token=new StringBuilder();char quote=0;boolean present=false;
        for(int i=0;i<text.length();i++) {
            char c=text.charAt(i);
            if(quote==0&&Character.isWhitespace(c)){if(present){result.add(token.toString());token.setLength(0);present=false;}continue;}
            present=true;
            if(c=='\\'&&quote!='\''&&i+1<text.length()){
                char next=text.charAt(i+1);if(next=='\\'||next=='"'||quote==0&&(next=='\''||Character.isWhitespace(next))){token.append(next);i++;continue;}
            }
            if(c=='\''||c=='"'){if(quote==0){quote=c;continue;}if(quote==c){quote=0;continue;}}
            token.append(c);
        }
        if(quote!=0)throw error("Незакрытая кавычка command line");if(present)result.add(token.toString());return checked(result);
    }
    /** CRT-style arguments; argv0 has the documented simpler quote grouping. */
    public static List<String> windows(String text) {
        List<String> result=new ArrayList<>();int i=0;
        while(i<text.length()&&(text.charAt(i)==' '||text.charAt(i)=='\t'))i++;
        StringBuilder exe=new StringBuilder();boolean quote=false;
        while(i<text.length()) {char c=text.charAt(i);if(c=='"'){quote=!quote;i++;continue;}if(!quote&&(c==' '||c=='\t'))break;exe.append(c);i++;}
        if(quote)throw error("Незакрытая кавычка executable");result.add(exe.toString());
        while(i<text.length()) {
            while(i<text.length()&&(text.charAt(i)==' '||text.charAt(i)=='\t'))i++;if(i==text.length())break;
            StringBuilder token=new StringBuilder();quote=false;
            while(i<text.length()) {
                char c=text.charAt(i);if(!quote&&(c==' '||c=='\t'))break;
                int slashes=0;while(i<text.length()&&text.charAt(i)=='\\'){slashes++;i++;}
                if(i<text.length()&&text.charAt(i)=='"') {
                    token.append("\\".repeat(slashes/2));
                    if(slashes%2==1){token.append('"');i++;}
                    else if(quote&&i+1<text.length()&&text.charAt(i+1)=='"'){token.append('"');i+=2;}
                    else {quote=!quote;i++;}
                } else {token.append("\\".repeat(slashes));if(i<text.length()){c=text.charAt(i);if(!quote&&(c==' '||c=='\t'))break;token.append(c);i++;}}
            }
            if(quote)throw error("Незакрытая кавычка command line");result.add(token.toString());
        }
        return checked(result);
    }
    static List<String> checked(List<String> args) {
        if(args.isEmpty()||args.getFirst().isEmpty())throw error("Пустой executable");
        for(String arg:args){if(arg.indexOf('\0')>=0)throw error("NUL в аргументе процесса");if(!StandardCharsets.UTF_8.newEncoder().canEncode(arg))throw error("Непарный Unicode surrogate в argv");}return List.copyOf(args);
    }
    static void nativeEncoding(List<String> argv,Path cwd,Map<String,String> environment) {
        if(System.getProperty("os.name").startsWith("Windows"))return;
        String name=System.getProperty("sun.jnu.encoding",System.getProperty("native.encoding",Charset.defaultCharset().name()));
        CharsetEncoder encoder=Charset.forName(name).newEncoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT);
        List<String> strings=new ArrayList<>(argv);strings.add(cwd.toString());environment.forEach((key,value)->{strings.add(key);strings.add(value);});
        for(String value:strings)if(!encoder.canEncode(value))throw error("Native process encoding "+name+" не представляет argv/cwd/env; запустите host JVM с UTF-8 locale");
    }
    static Path executable(String name,Path cwd,Map<String,String> env) {
        boolean windows=System.getProperty("os.name").startsWith("Windows");
        if(name.contains("/")||windows&&name.contains("\\"))return checkFile(cwd.resolve(name).normalize().toAbsolutePath(),windows);
        String path=env.getOrDefault("PATH",windows?"":"/usr/bin:/bin");List<Path> dirs=new ArrayList<>();if(windows)dirs.add(cwd);
        for(String part:path.split(java.util.regex.Pattern.quote(java.io.File.pathSeparator),-1)){
            if(windows&&part.length()>=2&&part.startsWith("\"")&&part.endsWith("\""))part=part.substring(1,part.length()-1);
            dirs.add(cwd.resolve(part));
        }
        List<String> extensions=new ArrayList<>(List.of(""));
        if(windows)for(String ext:env.getOrDefault("PATHEXT",".COM;.EXE").split(";"))if(Set.of(".exe",".com").contains(ext.toLowerCase(Locale.ROOT)))extensions.add(ext);
        for(Path dir:dirs)for(String ext:extensions){Path target=dir.resolve(name+ext).toAbsolutePath().normalize();if(Files.isRegularFile(target)&&Files.isExecutable(target))return checkFile(target,windows);}
        throw error("Executable не найден в snapshot PATH: "+name);
    }
    private static Path checkFile(Path path,boolean windows){String lower=path.toString().toLowerCase(Locale.ROOT);if(windows&&(lower.endsWith(".cmd")||lower.endsWith(".bat")))throw error("Batch требует явного cmd.exe /c");if(!Files.isRegularFile(path)||!Files.isExecutable(path))throw error("Executable не является исполняемым обычным файлом: "+path);return path;}
}
