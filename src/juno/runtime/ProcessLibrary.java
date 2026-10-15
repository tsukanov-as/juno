package juno.runtime;

import java.util.*;
import static juno.runtime.RuntimeSupport.*;
import static juno.runtime.IoSupport.*;

/** OneScript process facade plus a separately named argv-array extension. */
final class ProcessLibrary {
    static void register(Map<String,GlobalFunctions.Entry> entries) {
        GlobalFunctions.add(entries,"СоздатьПроцесс","CreateProcess",1,6,a->new ManagedProcess(a,false));
        GlobalFunctions.add(entries,"СоздатьПроцессСАргументами","CreateProcessWithArguments",1,6,a->new ManagedProcess(a,true));
        GlobalFunctions.addOutput(entries,"ЗапуститьПриложение","RunApp",1,4,3,a->{
            ManagedProcess process=new ManagedProcess(new Object[]{a[0],optional(a,1,""),false,false},false);
            boolean wait=JsonOptions.bool(optional(a,2,false));
            if(wait&&a.length>3&&!omitted(read(a[3]))&&!(a[3] instanceof Ref)){process.close();throw error("RunApp exitCode требует выходной параметр-переменную");}
            try{process.start();if(wait){process.waitFor(-1);if(a.length>3&&a[3] instanceof Ref ref)ref.set(number(Integer.toString(process.exitCode())));process.close();}return UNDEFINED;}
            catch(RuntimeException|Error e){try{process.close();}catch(RuntimeException|Error close){e.addSuppressed(close);}throw e;}
        });
        GlobalFunctions.add(entries,"Приостановить","Sleep",1,1,a->{long millis=longInteger(a[0]);if(millis<0)throw error("Sleep требует неотрицательные миллисекунды");try{Thread.sleep(millis);Execution.checkCancellation();return UNDEFINED;}catch(InterruptedException e){Thread.currentThread().interrupt();Execution.checkCancellation();throw failure("Sleep прерван",e);}});
    }
}
