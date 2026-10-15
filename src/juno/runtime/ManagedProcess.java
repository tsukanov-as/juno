package juno.runtime;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static juno.runtime.RuntimeSupport.*;
import static juno.runtime.IoSupport.*;

/** Owned child with native file redirection: neither output can fill an unread pipe. */
final class ManagedProcess implements NativeObject,TypedValue,ApplicationContext.Resource {
    final ApplicationContext owner=Execution.context();
    private final Object lock=new Object();
    private final List<String> arguments;
    private final String directory;
    private final Map<String,String> overlay;
    final boolean redirectOutput,redirectInput;
    final TextEncoding encoding;
    private final Object encodingValue;
    private final Set<ProcessHandle> descendants=ConcurrentHashMap.newKeySet();
    private volatile Process process;
    private volatile Thread watcher;
    private volatile boolean closed,starting,started;
    private volatile IOException asynchronousFailure;
    private Path stdoutPath,stderrPath;
    private ProcessText.Output stdout,stderr;
    private ProcessText.Input stdin;
    ManagedProcess(Object[] args,boolean array) {
        arity("CreateProcess",args,1,6);
        if(array){if(!(args[0] instanceof BArray a))throw error("CreateProcessWithArguments требует Массив argv");List<String> strings=new ArrayList<>();for(Object value:a.values){if(!(value instanceof String s))throw error("argv требует строки");strings.add(s);}arguments=ProcessArguments.checked(strings);}
        else {if(!(args[0] instanceof String s))throw error("CreateProcess требует строку command; для argv используйте CreateProcessWithArguments");arguments=System.getProperty("os.name").startsWith("Windows")?ProcessArguments.windows(s):ProcessArguments.unix(s);}
        Object cwd=optional(args,1,"");if(!(cwd instanceof String s))throw error("Process cwd требует строку");directory=s;
        redirectOutput=JsonOptions.bool(optional(args,2,false));redirectInput=JsonOptions.bool(optional(args,3,false));
        encodingValue=optional(args,4,UNDEFINED);encoding=TextEncoding.value(encodingValue);
        if(!Set.of("UTF-8","UTF-16LE","windows-1251","IBM866").contains(encoding.charset().name()))throw error("Process codecs: UTF-8/UTF-16LE/windows-1251/IBM866");
        Object env=optional(args,5,UNDEFINED);overlay=environment();
        if(!omitted(env)){if(!(env instanceof BMap map))throw error("Process env требует Соответствие");for(Entry item:map.values.values()){
            if(!(item.key instanceof String key)||key.isEmpty()||key.contains("=")||key.indexOf('\0')>=0||!(item.value instanceof String value)||value.indexOf('\0')>=0)throw error("Process env: непустое имя без =/NUL и строковое значение без NUL");overlay.put(key,value);
        }}owner.own(this);
    }
    private static Map<String,String> environment(){return System.getProperty("os.name").startsWith("Windows")?new TreeMap<>(String.CASE_INSENSITIVE_ORDER):new LinkedHashMap<>();}
    private void check(){owner.requireOpen();Execution.checkCancellation();if(closed)throw error("Процесс закрыт");if(asynchronousFailure!=null){if(ConsoleIo.closedStandardOutput(owner.output()))throw new Exit(0);throw failure("Process output forwarding",asynchronousFailure);}}
    private Process child(){check();Process p=process;if(p==null)throw error("Процесс ещё не запущен");return p;}
    public String bslTypeName(){return "Процесс";}
    public String toString(){return bslTypeName();}
    public Object property(String name){check();return switch(name){
        case "кодировкавывода","outputencoding"->encodingValue;
        case "потоквывода","stdout"->redirectOutput&&stdout!=null?stdout:UNDEFINED;
        case "потокошибок","stderr"->redirectOutput&&stderr!=null?stderr:UNDEFINED;
        case "потокввода","stdin"->{if(!redirectInput)throw error("stdin не перенаправлен");yield stdin==null?UNDEFINED:stdin;}
        case "завершен","hasexited"->!child().isAlive();
        case "кодвозврата","exitcode"->number(Integer.toString(exitCode()));
        case "идентификатор","processid"->number(Long.toString(child().pid()));
        case "имя","name"->{child();yield Path.of(arguments.getFirst()).getFileName().toString();}
        default->missingProperty(bslTypeName(),name);
    };}
    public Object invoke(String name,Object[] supplied){Object[] args=values(supplied);if(name.equals("закрыть")||name.equals("close")){arity(name,args,0,0);close();return UNDEFINED;}check();return switch(name){
        case "запустить","start"->{arity(name,args,0,0);start();yield UNDEFINED;}
        case "ожидатьзавершения","waitforexit"->{arity(name,args,0,1);yield waitFor(longInteger(optional(args,0,number("-1"))));}
        case "завершить","stop"->{arity(name,args,0,1);terminate(child(),JsonOptions.bool(optional(args,0,false)));yield UNDEFINED;}
        default->missingMethod(bslTypeName(),name);
    };}
    void start(){
        check();synchronized(lock){if(started||starting)throw error("Процесс можно запустить только один раз");starting=true;}
        Process launched=null;Path out=null,err=null;ProcessText.Output output=null,errors=null;ProcessText.Input input=null;
        try {
            Path cwd=directory.isEmpty()?owner.directory():owner.resolve(Path.of(directory));if(!Files.isDirectory(cwd))throw error("Process cwd не существует: "+cwd);
            Map<String,String> env=environment();env.putAll(owner.environment());env.putAll(overlay);
            ProcessArguments.nativeEncoding(arguments,cwd,env);
            List<String> argv=new ArrayList<>(arguments);argv.set(0,ProcessArguments.executable(argv.getFirst(),cwd,env).toString());
            ProcessArguments.nativeEncoding(argv,cwd,env);
            out=Files.createTempFile("juno-process-",".stdout");err=Files.createTempFile("juno-process-",".stderr");
            synchronized(lock){if(closed)throw error("Процесс закрыт");stdoutPath=out;stderrPath=err;}
            ProcessBuilder builder=new ProcessBuilder(argv).directory(cwd.toFile());builder.environment().clear();builder.environment().putAll(env);
            builder.redirectOutput(out.toFile());builder.redirectError(err.toFile());
            launched=builder.start();output=new ProcessText.Output(this,out);errors=new ProcessText.Output(this,err);input=new ProcessText.Input(this,launched.getOutputStream());
            synchronized(lock){if(!closed){process=launched;stdout=output;stderr=errors;stdin=input;started=true;}}
            if(!started){launched.destroyForcibly();launched.waitFor(2,TimeUnit.SECONDS);input.close();output.close();errors.close();throw error("Процесс закрыт во время Start");}
            if(!redirectInput)input.close();
            Thread worker=Thread.ofVirtual().name("juno-process-"+launched.pid()).unstarted(this::observe);
            synchronized(lock){watcher=worker;}worker.start();if(closed){worker.interrupt();terminate(launched,true);}
        }catch(IOException|InterruptedException|RuntimeException|Error e){
            if(e instanceof InterruptedException)Thread.currentThread().interrupt();
            if(launched!=null&&process==null){launched.destroyForcibly();try{launched.waitFor(2,TimeUnit.SECONDS);}catch(InterruptedException interrupt){Thread.currentThread().interrupt();}}
            try{close();}catch(RuntimeException|Error cleanup){e.addSuppressed(cleanup);}
            for(AutoCloseable partial:new AutoCloseable[]{input,output,errors})if(partial!=null)try{partial.close();}catch(Exception cleanup){e.addSuppressed(cleanup);}
            if(out!=null)try{Files.deleteIfExists(out);}catch(IOException cleanup){e.addSuppressed(cleanup);}if(err!=null)try{Files.deleteIfExists(err);}catch(IOException cleanup){e.addSuppressed(cleanup);}
            if(e instanceof RuntimeException r)throw r;if(e instanceof Error r)throw r;throw failure("Запуск процесса",(Exception)e);
        }finally{starting=false;}
    }
    private void capture(){Process p=process;if(p!=null&&p.isAlive())try{p.descendants().forEach(descendants::add);}catch(RuntimeException restricted){/* OS permissions may deny tree enumeration; the owned root is still killed. */}}
    private void observe(){
        try{while(!closed){capture();if(!redirectOutput){stdout.forward(owner.output());stderr.forward(owner.error());}
            if(!active()&&(redirectOutput||stdout.caughtUp()&&stderr.caughtUp())){settle();return;}Thread.sleep(10);
        }}catch(InterruptedException e){Thread.currentThread().interrupt();}catch(IOException e){asynchronousFailure=e;Process p=process;if(p!=null)p.destroyForcibly();}
    }
    /**
     * A program cannot close a process in 1C, so the child must not keep resources once it is over: the output that is
     * kept for reading moves into memory (up to 16 MiB each), the spool files, the pipe to stdin and the registration
     * with the application go. A larger output keeps its spool until Close or the end of the program.
     */
    private void settle(){
        try{
            if(closed)return;
            if(redirectOutput){if(!(stdout.freeze(HttpData.Response.LIMIT)&stderr.freeze(HttpData.Response.LIMIT)))return;}
            else{stdout.close();stderr.close();}
            try{if(stdin!=null)stdin.close();}catch(IOException closing){/* the pipe of an ended child */}
            for(Path path:new Path[]{stdoutPath,stderrPath})if(path!=null)Files.deleteIfExists(path);
            owner.release(this);
        }catch(IOException|RuntimeException e){/* Close and the end of the program still clean up */}
    }
    boolean active(){Process p=process;return p!=null&&(p.isAlive()||descendants.stream().anyMatch(ProcessHandle::isAlive));}
    boolean waitFor(long timeout){
        if(timeout< -1||timeout>Integer.MAX_VALUE)throw error("WaitForExit: timeout -1..2147483647 миллисекунд");Process p=child();long end=timeout<0?Long.MAX_VALUE:System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(timeout);
        try {
            while(p.isAlive()){Execution.checkCancellation();capture();long left=timeout<0?Long.MAX_VALUE:end-System.nanoTime();if(left<=0)return false;p.waitFor(Math.min(50,Math.max(1,TimeUnit.NANOSECONDS.toMillis(left))),TimeUnit.MILLISECONDS);}
            if(!redirectOutput){
                long out=stdout.length(),err=stderr.length();
                while(stdout.cursor()<out||stderr.cursor()<err){check();if(timeout>=0&&end-System.nanoTime()<=0)return false;Thread thread=watcher;if(thread==null||!thread.isAlive())throw error("Forwarding завершился до полного вывода child");Thread.sleep(10);}
            }
            check();return true;
        }catch(InterruptedException e){Thread.currentThread().interrupt();Execution.checkCancellation();throw failure("Ожидание процесса прервано",e);}catch(IOException e){throw failure("Чтение process spool",e);}
    }
    int exitCode(){Process p=child();if(p.isAlive())throw error("Код возврата доступен только после завершения");return p.exitValue();}
    private void terminate(Process p,boolean tree){
        capture();List<ProcessHandle> targets=new ArrayList<>();if(tree)targets.addAll(descendants);targets.add(p.toHandle());
        for(ProcessHandle handle:targets)if(handle.isAlive())handle.destroy();
        boolean interrupted=Thread.interrupted();try{
            long gentle=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(200);while(targets.stream().anyMatch(ProcessHandle::isAlive)&&System.nanoTime()<gentle)try{Thread.sleep(10);}catch(InterruptedException e){interrupted=true;}
            for(ProcessHandle handle:targets)if(handle.isAlive())handle.destroyForcibly();
            try{if(!p.waitFor(2,TimeUnit.SECONDS))throw error("Процесс не завершился после kill");}catch(InterruptedException e){interrupted=true;p.destroyForcibly();}
        }finally{if(interrupted)Thread.currentThread().interrupt();}
    }
    public void close(){
        synchronized(lock){if(closed)return;closed=true;}
        // Kill first: ProcessPipeOutputStream.close can flush into a child that never reads stdin.
        Throwable failure=null;Process p=process;
        try{if(p!=null)terminate(p,true);}catch(RuntimeException|Error e){failure=e;}
        Thread thread=watcher;if(thread!=null)thread.interrupt();
        for(AutoCloseable stream:new AutoCloseable[]{stdin,stdout,stderr})if(stream!=null)try{stream.close();}catch(Exception e){if(failure==null)failure=e;else failure.addSuppressed(e);}
        if(thread!=null&&thread!=Thread.currentThread()){boolean interrupted=Thread.interrupted();try{thread.join(1000);if(thread.isAlive())throw error("Process forwarding не завершился (host output может блокировать)");}catch(InterruptedException e){interrupted=true;}catch(RuntimeException e){if(failure==null)failure=e;else failure.addSuppressed(e);}finally{if(interrupted)Thread.currentThread().interrupt();}}
        for(Path path:new Path[]{stdoutPath,stderrPath})if(path!=null)try{Files.deleteIfExists(path);}catch(IOException e){if(failure==null)failure=e;else failure.addSuppressed(e);}
        owner.release(this);if(failure instanceof RuntimeException e)throw e;if(failure instanceof Error e)throw e;if(failure!=null)throw failure("Закрытие процесса",(Exception)failure);
    }
    public void cancel(){close();}
}
