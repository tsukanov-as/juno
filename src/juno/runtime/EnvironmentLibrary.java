package juno.runtime;

import java.util.*;
import static juno.runtime.RuntimeSupport.*;
import static juno.runtime.IoSupport.*;

/** Process target is the application's snapshot, never JVM-global operating-system state. */
final class EnvironmentLibrary {
    private EnvironmentLibrary() {}
    static void register(Map<String,GlobalFunctions.Entry> entries) {
        GlobalFunctions.add(entries,"ПолучитьПеременнуюСреды","GetEnvironmentVariable",1,2,a -> {
            target(a,1); String value=Execution.context().environment(name(a[0])); return value==null ? UNDEFINED : value;
        });
        GlobalFunctions.add(entries,"УстановитьПеременнуюСреды","SetEnvironmentVariable",2,3,a -> {
            target(a,2); String name=name(a[0]); Object value=a[1];
            if(value!=UNDEFINED && value!=Special.NULL && !(value instanceof String)) throw error("Значение переменной среды должно быть строкой или Неопределено");
            String text=string(value); if(text.indexOf('\0')>=0) throw error("Переменная среды не может содержать NUL");
            Execution.context().environment(name,text.isEmpty() ? null : text); return UNDEFINED;
        });
        GlobalFunctions.add(entries,"ПеременныеСреды","EnvironmentVariables",0,1,a -> {
            target(a,0); BMap result=new BMap();
            Execution.context().environment().forEach((name,value) -> call(result,"Вставить",new Object[]{name,value})); return result;
        });
    }
    private static String name(Object value) {
        if(!(value instanceof String name) || name.isEmpty() || name.indexOf('=')>=0 || name.indexOf('\0')>=0)
            throw error("Ожидалось непустое имя переменной среды без '=' и NUL");
        return name;
    }
    private static void target(Object[] args,int index) {
        String target=IoEnums.code(optional(args,index,IoEnums.ENV_TARGET.property("процесс")),IoEnums.ENV_TARGET);
        if(!target.equals("Process")) throw error("Поддерживается только РасположениеПеременнойСреды.Процесс (контекст приложения)");
    }
}
