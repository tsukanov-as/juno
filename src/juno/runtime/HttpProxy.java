package juno.runtime;

import java.net.*;
import java.util.*;
import static juno.runtime.RuntimeSupport.*;
import static juno.runtime.IoSupport.*;

/** Explicit unauthenticated HTTP proxy; no ambient JVM/system proxy settings. */
final class HttpProxy implements NativeObject,TypedValue {
    private String host=""; private int port;
    private boolean bypassLocal;
    private BArray bypass=new BArray();
    HttpProxy(Object[] args) { arity("ИнтернетПрокси",args,0,1); if(JsonOptions.bool(optional(args,0,false))) throw error("Системные настройки прокси не поддерживаются; задайте явный proxy"); }
    public String bslTypeName() { return "ИнтернетПрокси"; }
    public String toString() { return bslTypeName(); }
    public Object property(String name) { return switch(name) { case "неиспользоватьпроксидлялокальныхадресов","bypassproxyonlocal" -> bypassLocal; case "неиспользоватьпроксидляадресов","bypassproxyonaddresses" -> bypass; default -> missingProperty(bslTypeName(),name); }; }
    public void setProperty(String name,Object value) {
        switch(name) {
            case "неиспользоватьпроксидлялокальныхадресов","bypassproxyonlocal" -> bypassLocal=JsonOptions.bool(value);
            case "неиспользоватьпроксидляадресов","bypassproxyonaddresses" -> { if(!(value instanceof BArray array)||!array.values.isEmpty()) throw error("Поддержан только пустой список bypass addresses"); bypass=array; }
            default -> throw error("Свойство proxy недоступно: "+name);
        }
    }
    public Object invoke(String name,Object[] raw) {
        Object[] args=values(raw);
        switch(name) {
            case "установить","set": {
                arity(name,args,2,6); protocol(string(args[0]));
                if(JsonOptions.bool(optional(args,5,true)) || !string(optional(args,3,"")).isEmpty() || !string(optional(args,4,"")).isEmpty()) throw error("Поддержан proxy без аутентификации; задайте Set(http,server,port,\"\",\"\",Ложь)");
                String server=string(args[1]); if(server.isEmpty()||server.contains("/")||server.contains("@")) throw error("Proxy server: ожидается hostname без схемы");
                int value=integer(optional(args,2,number("0"))); if(value==0)value=80; if(value<1||value>65535)throw error("Неверный порт proxy"); host=server;port=value;return UNDEFINED;
            }
            case "сервер","server","порт","port","пользователь","user","пароль","password": {
                arity(name,args,1,1);protocol(string(args[0]));
                return switch(name) { case "сервер","server" -> host.isEmpty()?"":"http://"+host+"/"; case "порт","port" -> number(Integer.toString(port)); default -> ""; };
            }
            default:return missingMethod(bslTypeName(),name);
        }
    }
    private static void protocol(String name) { if(!name.equalsIgnoreCase("http"))throw error("Proxy: поддержан только протокол http"); }
    ProxySelector snapshot() {
        if(!bypass.values.isEmpty())throw error("Непустой bypass addresses пока не поддерживается");
        String server=host;int serverPort=port;boolean local=bypassLocal;
        return new ProxySelector() {
            public List<Proxy> select(URI uri) {
                String h=uri.getHost();boolean skip=local&&(h.equalsIgnoreCase("localhost")||h.equals("127.0.0.1")||h.equals("[::1]")||!h.contains("."));
                return List.of(server.isEmpty()||skip?Proxy.NO_PROXY:new Proxy(Proxy.Type.HTTP,InetSocketAddress.createUnresolved(server,serverPort)));
            }
            public void connectFailed(URI uri,SocketAddress address,java.io.IOException error) { }
        };
    }
    static ProxySelector direct() { return new ProxySelector() { public List<Proxy> select(URI uri){return List.of(Proxy.NO_PROXY);}public void connectFailed(URI u,SocketAddress a,java.io.IOException e){} }; }
}
