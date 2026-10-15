package juno.runtime;

import java.net.*;
import java.util.*;
import static juno.runtime.RuntimeSupport.*;
import static juno.runtime.IoSupport.*;

/** One origin per connection; redirects are checked explicitly, never delegated to the JDK. */
final class HttpConnection implements NativeObject,TypedValue {
    final ApplicationContext owner=Execution.context();
    final URI origin;
    final String user,password;
    final int timeout,port;
    final HttpProxy proxy;
    final HttpTls.Secure tls;
    boolean redirect=true;
    HttpConnection(Object[] args) {
        arity("HTTPСоединение",args,1,8);if(!(args[0] instanceof String host)||host.isEmpty())throw error("HTTP host: ожидается непустая строка");
        Object secure=optional(args,6,UNDEFINED);if(!omitted(secure)&&!(secure instanceof HttpTls.Secure))throw error("Седьмой аргумент HTTPConnection требует ЗащищенноеСоединениеOpenSSL, не Булево");
        if(JsonOptions.bool(optional(args,7,false)))throw error("UseOSAuthentication не поддерживается");
        tls=secure instanceof HttpTls.Secure s?s:null;
        URI address;
        try{address=URI.create(host.contains("://")?host:(tls==null?"http://":"https://")+host);}catch(IllegalArgumentException e){throw error("Неверный HTTP host");}
        String scheme=address.getScheme().toLowerCase(Locale.ROOT);
        if(address.getHost()==null||address.getRawUserInfo()!=null||address.getRawQuery()!=null||address.getRawFragment()!=null||!Set.of("http","https").contains(scheme)||!(address.getRawPath()==null||address.getRawPath().isEmpty()||address.getRawPath().equals("/")))throw error("HTTP host: только http(s) origin без userinfo/path/query/fragment");
        if(tls!=null&&!scheme.equals("https"))throw error("TLS object несовместим с явной схемой http");
        int given=integer(optional(args,1,number("0")));if(given<0||given>65535)throw error("HTTP port вне диапазона");
        if(given!=0&&address.getPort()!=-1&&given!=address.getPort())throw error("Конфликт портов HTTP URI и аргумента");
        port=given!=0?given:address.getPort()!=-1?address.getPort():scheme.equals("https")?443:80;
        origin=URI.create(scheme+"://"+address.getHost()+":"+port);
        user=string(optional(args,2,""));password=string(optional(args,3,""));if(user.contains(":"))throw error("Basic username не может содержать ':'");
        if(user.chars().anyMatch(c->c<32||c==127)||password.chars().anyMatch(c->c<32||c==127))throw error("Basic credentials не могут содержать управляющие символы");
        Object prox=optional(args,4,UNDEFINED);if(!omitted(prox)&&!(prox instanceof HttpProxy))throw error("Ожидался ИнтернетПрокси");proxy=prox instanceof HttpProxy p?p:null;
        timeout=integer(optional(args,5,number("0")));if(timeout<0||timeout>86400)throw error("HTTP timeout: целые секунды 0..86400");
    }
    public String bslTypeName(){return "HTTPСоединение";}
    public String toString(){return bslTypeName();}
    public Object property(String name){owner.requireOpen();return switch(name){case "сервер","host"->origin.getHost();case "порт","port"->number(Integer.toString(port));case "пользователь","user"->user;case "пароль","password"->password;case "таймаут","timeout"->number(Integer.toString(timeout));case "прокси","proxy"->proxy==null?UNDEFINED:proxy;case "использоватьаутентификациюос","useosauthentication"->false;case "разрешитьавтоматическоеперенаправление","allowautoredirect"->redirect;default->missingProperty(bslTypeName(),name);};}
    public void setProperty(String name,Object value){owner.requireOpen();if(!name.equals("разрешитьавтоматическоеперенаправление")&&!name.equals("allowautoredirect"))throw error("HTTPConnection readonly property");redirect=JsonOptions.bool(value);}
    public Object invoke(String name,Object[] raw){Object[] args=values(raw);owner.requireOpen();String method=switch(name){case "получить","get"->"GET";case "отправитьдляобработки","post"->"POST";case "записать","put"->"PUT";case "удалить","delete"->"DELETE";case "изменить","patch"->"PATCH";case "получитьзаголовки","head"->"HEAD";case "вызватьhttpметод","callhttpmethod"->"";default->null;};if(method==null)return missingMethod(bslTypeName(),name);
        int index=method.isEmpty()?1:0;arity(name,args,index+1,index+(Set.of("PUT","PATCH","DELETE","HEAD").contains(method)?1:2));
        if(index==1){if(!(args[0] instanceof String s)||!s.matches("[!#$%&'*+.^_`|~0-9A-Za-z-]+")||s.equalsIgnoreCase("CONNECT"))throw error("Неподдерживаемый HTTP method");method=s;}
        if(!(args[index] instanceof HttpData.Request request))throw error("Ожидался HTTPЗапрос");request.owner.requireOpen();
        Object output=optional(args,index+1,UNDEFINED);String filename=omitted(output)?null:output instanceof String s&&!s.isEmpty()?s:null;
        if(!omitted(output)&&filename==null)throw error("Имя файла HTTP ответа должно быть непустой строкой");
        return HttpExchange.execute(this,method,request,filename);
    }
    URI resource(String resource){
        try{URI relative=URI.create(resource);if(relative.isAbsolute()||relative.getRawAuthority()!=null||resource.startsWith("//")||relative.getRawFragment()!=null)throw error("ResourceAddress не может менять origin или задавать fragment");String path=relative.toASCIIString();return URI.create(origin+(path.startsWith("/")?path:"/"+path));}
        catch(IllegalArgumentException e){throw error("Недопустимый ResourceAddress");}
    }
    static boolean sameOrigin(URI a,URI b){return a.getScheme().equalsIgnoreCase(b.getScheme())&&a.getHost().equalsIgnoreCase(b.getHost())&&effectivePort(a)==effectivePort(b);}
    static int effectivePort(URI uri){return uri.getPort()!=-1?uri.getPort():uri.getScheme().equalsIgnoreCase("https")?443:80;}
}
