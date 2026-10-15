import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.security.*;
import java.security.cert.CertificateFactory;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.net.ssl.*;
import juno.Json;
import juno.runtime.*;
import juno.runtime.RuntimeSupport.Module;
import static juno.runtime.RuntimeSupport.*;

/** Loopback HTTP/TLS/proxy fixtures. Never forwards requests or contacts external endpoints. */
public final class HttpLibraryTests {
    static final Path CERTS=Path.of("tests/fixtures/tls");
    static void check(boolean ok,Object why){if(!ok)throw new AssertionError(why);}
    static RuntimeException fails(Runnable action){try{action.run();}catch(RuntimeException expected){return expected;}throw new AssertionError("Expected error");}
    static Object c(Object target,String name,Object...args){return call(target,name,args);}
    static Object g(String name,Object...args){return global(name,args);}
    static Object p(Object target,String name){return read(property(target,name));}
    static Object make(String name,Object...args){return construct(name,args);}
    static Object n(int value){return number(Integer.toString(value));}
    static Object conn(int port,int timeout){return make("HTTPConnection","localhost",n(port),"","",UNDEFINED,n(timeout));}
    static Object req(String resource){return make("HTTPRequest",resource);}
    static ApplicationContext context(Path directory){return new ApplicationContext(directory,Map.of(),InputStream.nullInputStream(),OutputStream.nullOutputStream(),OutputStream.nullOutputStream());}
    static Map<String,Object> json(Object response){return Json.object(Json.parse((String)c(response,"GetBodyAsString")));}
    static byte[] block=new byte[65536];
    static {new Random(73).nextBytes(block);}
    static final int BLOCKS=1024;
    static final class Fixture implements AutoCloseable {
        final HttpServer server;final ExecutorService executor=Executors.newVirtualThreadPerTaskExecutor();
        volatile int otherPort;volatile CountDownLatch headers=new CountDownLatch(1);final AtomicInteger hits=new AtomicInteger(),posts=new AtomicInteger();
        Fixture(SSLContext ssl,boolean mutual) throws Exception {
            if(ssl==null)server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            else {HttpsServer https=HttpsServer.create(new InetSocketAddress("127.0.0.1",0),0);https.setHttpsConfigurator(new HttpsConfigurator(ssl){public void configure(HttpsParameters params){SSLParameters p=ssl.getDefaultSSLParameters();p.setNeedClientAuth(mutual);params.setSSLParameters(p);}});server=https;}
            server.setExecutor(executor);server.createContext("/",this::serve);server.start();
        }
        int port(){return server.getAddress().getPort();}
        void serve(com.sun.net.httpserver.HttpExchange exchange){
            try(exchange){
                hits.incrementAndGet();if(exchange.getRequestMethod().equals("POST"))posts.incrementAndGet();
                String path=exchange.getRequestURI().getPath();MessageDigest digest=MessageDigest.getInstance("SHA-256");ByteArrayOutputStream prefix=new ByteArrayOutputStream();long size=0;byte[] b=new byte[65536];int count;
                try(InputStream input=exchange.getRequestBody()){while((count=input.read(b))>=0){digest.update(b,0,count);size+=count;if(prefix.size()<4096)prefix.write(b,0,Math.min(count,4096-prefix.size()));}}
                Headers out=exchange.getResponseHeaders();out.set("Connection","close");out.add("Set-Cookie","a=1; Expires=Wed, 21 Oct 2030 07:28:00 GMT");out.add("Set-Cookie","b=2; Path=/");out.set("X-Case","yes");
                if(path.equals("/headers-delay"))Thread.sleep(2200);
                if(path.equals("/body-stall")||path.equals("/body-trickle")){
                    exchange.sendResponseHeaders(200,path.equals("/body-stall")?2:10);OutputStream output=exchange.getResponseBody();output.write('A');output.flush();headers.countDown();
                    if(path.equals("/body-stall")){Thread.sleep(2500);output.write('B');}else for(int i=1;i<10;i++){Thread.sleep(300);output.write('A');output.flush();}return;
                }
                if(path.startsWith("/redirect")||path.startsWith("/post")||path.equals("/loop")){
                    String location=switch(path){case "/redirect-cross"->"http://localhost:"+otherPort+"/echo";case "/redirect-downgrade"->"http://localhost:"+otherPort+"/echo";case "/loop"->"/loop";case "/post303","/post307","/post302"->"/echo";default->"/text";};
                    out.set("Location",location);exchange.sendResponseHeaders(path.equals("/post303")?303:path.equals("/post307")?307:302,-1);return;
                }
                if(path.equals("/large")){
                    exchange.sendResponseHeaders(200,(long)block.length*BLOCKS+3);OutputStream output=exchange.getResponseBody();for(int i=0;i<BLOCKS;i++)output.write(block);output.write(new byte[]{0,(byte)255,13});return;
                }
                byte[] body;int status=path.equals("/error")?503:200;
                if(path.equals("/text")){out.set("Content-Type","text/plain; charset=utf-8");body="Привет".getBytes(StandardCharsets.UTF_8);}
                else if(path.equals("/cp")){out.set("Content-Type","text/plain; charset=\"windows-1251\"");body="Привет".getBytes(Charset.forName("windows-1251"));}
                else if(path.equals("/binary"))body=new byte[]{0,(byte)255,10};
                else if(path.equals("/empty"))body=new byte[0];
                else if(path.equals("/bad-charset")){out.set("Content-Type","text/plain; charset=not-a-codec");body=new byte[]{65};}
                else {Map<String,Object> result=new LinkedHashMap<>();result.put("method",exchange.getRequestMethod());result.put("uri",exchange.getRequestURI().toString());result.put("auth",Objects.toString(exchange.getRequestHeaders().getFirst("Authorization"),""));result.put("cookie",Objects.toString(exchange.getRequestHeaders().getFirst("Cookie"),""));result.put("case",Objects.toString(exchange.getRequestHeaders().getFirst("X-Test"),""));result.put("contentType",Objects.toString(exchange.getRequestHeaders().getFirst("Content-Type"),""));result.put("contentEncoding",Objects.toString(exchange.getRequestHeaders().getFirst("Content-Encoding"),""));result.put("size",size);result.put("sha",HexFormat.of().formatHex(digest.digest()));result.put("hex",HexFormat.of().formatHex(prefix.toByteArray()));body=Json.stringify(result).getBytes(StandardCharsets.UTF_8);out.set("Content-Type","application/json");}
                if(exchange.getRequestMethod().equals("HEAD")){out.set("Content-Length",Integer.toString(body.length));exchange.sendResponseHeaders(status,-1);}else {exchange.sendResponseHeaders(status,body.length==0?-1:body.length);exchange.getResponseBody().write(body);}
            }catch(IOException ignored){}catch(InterruptedException e){Thread.currentThread().interrupt();}catch(Exception e){throw new AssertionError(e);}
        }
        public void close(){server.stop(0);executor.shutdownNow();try{check(executor.awaitTermination(2,TimeUnit.SECONDS),"fixture threads stopped");}catch(InterruptedException e){throw new AssertionError(e);}}
    }
    /** Minimal CONNECT fixture restricted to this test's one loopback TLS endpoint. */
    static final class Tunnel implements AutoCloseable {
        final ServerSocket listener=new ServerSocket();final ExecutorService tasks=Executors.newVirtualThreadPerTaskExecutor();
        final Set<Socket> sockets=ConcurrentHashMap.newKeySet();final int target;final AtomicInteger connects=new AtomicInteger();
        Tunnel(int target) throws IOException {this.target=target;listener.bind(new InetSocketAddress("127.0.0.1",0));tasks.submit(()->{while(!listener.isClosed())try{Socket incoming=listener.accept();sockets.add(incoming);tasks.submit(()->serve(incoming));}catch(IOException e){if(!listener.isClosed())throw new UncheckedIOException(e);}});}
        int port(){return listener.getLocalPort();}
        void serve(Socket incoming){
            try(incoming){
                InputStream input=incoming.getInputStream();ByteArrayOutputStream header=new ByteArrayOutputStream();int state=0;
                while(state<4){int value=input.read();if(value<0)throw new EOFException();header.write(value);if(header.size()>8192)throw new IOException("proxy header limit");state=value==(state==0||state==2?13:10)?state+1:value==13?1:0;}
                String first=header.toString(StandardCharsets.US_ASCII).split("\r\n",2)[0];
                check(first.equals("CONNECT localhost:"+target+" HTTP/1.1")||first.equals("CONNECT 127.0.0.1:"+target+" HTTP/1.1"),"proxy only accepts fixture loopback authority: "+first);
                Socket remote=new Socket();sockets.add(remote);try(remote){remote.connect(new InetSocketAddress("127.0.0.1",target));connects.incrementAndGet();incoming.getOutputStream().write("HTTP/1.1 200 Connection Established\r\n\r\n".getBytes(StandardCharsets.US_ASCII));incoming.getOutputStream().flush();
                    Future<?> upload=tasks.submit(()->{try{input.transferTo(remote.getOutputStream());remote.shutdownOutput();}catch(IOException ignored){}});
                    try{remote.getInputStream().transferTo(incoming.getOutputStream());}finally{incoming.close();remote.close();upload.cancel(true);}
                }finally{sockets.remove(remote);}
            }catch(IOException ignored){}finally{sockets.remove(incoming);}
        }
        public void close() throws IOException {listener.close();for(Socket socket:sockets)socket.close();tasks.shutdownNow();try{check(tasks.awaitTermination(2,TimeUnit.SECONDS),"CONNECT tasks stopped");}catch(InterruptedException e){throw new AssertionError(e);}}
    }
    static SSLContext serverTls(String name) throws Exception {
        KeyStore keys=KeyStore.getInstance("PKCS12");try(InputStream input=Files.newInputStream(CERTS.resolve(name))){keys.load(input,"changeit".toCharArray());}
        KeyManagerFactory key=KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());key.init(keys,"changeit".toCharArray());
        KeyStore trust=KeyStore.getInstance(KeyStore.getDefaultType());trust.load(null,null);try(InputStream input=Files.newInputStream(CERTS.resolve("ca.pem"))){trust.setCertificateEntry("ca",CertificateFactory.getInstance("X.509").generateCertificate(input));}
        TrustManagerFactory manager=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());manager.init(trust);SSLContext context=SSLContext.getInstance("TLS");context.init(key.getKeyManagers(),manager.getTrustManagers(),null);return context;
    }
    static void basic(Path tmp,Fixture one,Fixture two) throws Exception {
        try(var owner=context(tmp);var scope=Execution.withContext(owner)){
            Object connection=conn(one.port(),3);check(valueType(connection).equals(type("HTTPConnection")),"connection type alias");check(p(connection,"AllowAutoRedirect").equals(true),"default redirect");
            Object r=req("/echo");check(p(r,"ResourceAddress").equals("/echo")&&c(r,"GetBodyAsString")==UNDEFINED,"unset request body");
            c(r,"SetBodyFromString","");check(c(c(r,"GetBodyAsBinaryData"),"Size").equals(n(0)),"empty distinct from unset");
            c(r,"SetBodyFromString","Привет",p(globalValue("textencoding"),"ANSI"));check(c(r,"GetBodyAsString").equals("Привет"),"explicit request codec is retained");
            c(r,"SetBodyFromString","Я");check(g("GetHexStringFromBinaryData",c(r,"GetBodyAsBinaryData")).equals("D0AF"),"request UTF8 noBOM");
            Object original=make("Map");c(original,"Insert","X-Test","before");Object copied=make("HTTPRequest","/echo",original);c(original,"Insert","X-Test","after");check(c(p(copied,"Headers"),"Get","x-test").equals("before"),"request headers own case-insensitive copy");
            assign(index(p(copied,"Headers"),"X-TEST"),"mutated");Object result=c(connection,"Post",copied);check(json(result).get("case").equals("mutated"),"headers mutable through request.Headers");c(result,"Close");
            for(String method:List.of("Get","Post","Put","Delete","Patch","Head")){
                Object response=c(connection,method,r);check(p(response,"StatusCode").equals(n(200)),method+" status");
                if(method.equals("Head"))check(c(response,"GetBodyAsString").equals(""),"HEAD empty");else{Map<String,Object> data=json(response);check(data.get("method").equals(method.toUpperCase(Locale.ROOT)),"method");check(data.get("hex").equals(method.equals("Get")?"":"d0af"),"exact body "+method);}
                check(c(p(response,"Headers"),"Get","X-CASE").equals("yes"),"case-insensitive response headers");Object cookies=c(p(response,"Headers"),"Get","Set-Cookie");check(valueType(cookies).equals(type("FixedArray"))&&c(cookies,"Count").equals(n(2)),"Set-Cookie repeated values preserved");fails(()->c(cookies,"Add","x"));c(response,"Close");c(response,"Close");fails(()->c(response,"GetBodyAsString"));
            }
            Object get=req("/echo");c(get,"SetBodyFromString","{}");c(p(get,"Headers"),"Insert","Content-Type","application/json");c(p(get,"Headers"),"Insert","Content-Encoding","identity");Object getReply=c(connection,"Get",get);Map<String,Object> getData=json(getReply);check(getData.get("hex").equals("")&&getData.get("contentType").equals("application/json")&&getData.get("contentEncoding").equals("identity"),"GET keeps explicit representation headers while omitting body");c(getReply,"Close");
            Object custom=c(connection,"CallHTTPMethod","PROPFIND",req("/echo"));check(json(custom).get("method").equals("PROPFIND"),"custom method");c(custom,"Close");
            for(String path:List.of("/cp","/text")){Object response=c(connection,"Get",req(path));check(c(response,"GetBodyAsString").equals("Привет")&&c(response,"GetBodyAsString").equals("Привет"),"repeatable charset body");c(response,"Close");}
            Object binary=c(connection,"Get",req("/binary"));check(g("GetHexStringFromBinaryData",c(binary,"GetBodyAsBinaryData")).equals("00FF0A"),"binary NUL255");c(binary,"Close");
            Object empty=c(connection,"Get",req("/empty"));check(c(empty,"GetBodyAsString").equals("")&&c(c(empty,"GetBodyAsBinaryData"),"Size").equals(n(0)),"empty response");c(empty,"Close");
            Object error=c(connection,"Get",req("/error"));check(p(error,"StatusCode").equals(n(503)),"5xx is response");c(error,"Close");
            Object invalid=c(connection,"Get",req("/bad-charset"));fails(()->c(invalid,"GetBodyAsString"));c(invalid,"Close");
            Object output=c(connection,"Get",req("/text"),"body.txt");Files.writeString(tmp.resolve("body.txt"),"user changed");check(c(output,"GetBodyAsString").equals("Привет"),"private spool independent from user output");c(output,"Close");check(Files.readString(tmp.resolve("body.txt")).equals("user changed"),"Close never deletes output");
            Object encoded=c(connection,"Get",req("/echo?name=%D1%91&x=%2B"));check(json(encoded).get("uri").equals("/echo?name=%D1%91&x=%2B"),"percent path preserved");c(encoded,"Close");
            int hits=two.hits.get();for(String path:List.of("http://localhost:"+two.port()+"/echo","//localhost:"+two.port()+"/echo"))fails(()->c(connection,"Get",req(path)));check(hits==two.hits.get(),"resource cannot change origin");
            for(String header:List.of("Host","Content-Length","Connection","Expect","Upgrade","Transfer-Encoding","Proxy-Authorization")){Object bad=req("/echo");c(p(bad,"Headers"),"Insert",header,"x");fails(()->c(connection,"Get",bad));}
            fails(()->c(connection,"Get",req("/echo"),""));check(owner.openResourceCount()==0,"basic resources released");
        }
    }
    static void redirects(Path tmp,Fixture one,Fixture two){
        try(var owner=context(tmp);var scope=Execution.withContext(owner)){
            one.otherPort=two.port();Object connection=make("HTTPConnection","localhost",n(one.port()),"u","p",UNDEFINED,n(3));Object r=req("/echo");Object result=c(connection,"Get",r);check(json(result).get("auth").equals("Basic dTpw"),"Basic origin auth");c(result,"Close");
            Object unicode=make("HTTPConnection","localhost",n(one.port()),"ю","я",UNDEFINED,n(3));
            Object unicodeResponse=c(unicode,"Get",req("/echo"));check(json(unicodeResponse).get("auth").equals("Basic 0Y460Y8="),"Unicode Basic credentials are UTF8, as in 1C");c(unicodeResponse,"Close");
            r=req("/redirect-cross");c(p(r,"Headers"),"Insert","Authorization","Bearer token");c(p(r,"Headers"),"Insert","Cookie","secret=1");Object response=c(connection,"Get",r);Map<String,Object> body=json(response);check(body.get("auth").equals("")&&body.get("cookie").equals(""),"cross-origin strips Auth/Cookie/connection credentials");c(response,"Close");check(c(p(r,"Headers"),"Get","Authorization").equals("Bearer token"),"redirect does not mutate request");
            Object same=c(connection,"Get",req("/redirect-same"));check(c(same,"GetBodyAsString").equals("Привет"),"bounded same-origin redirect");c(same,"Close");
            assign(property(connection,"AllowAutoRedirect"),false);Object stopped=c(connection,"Get",req("/redirect-same"));check(p(stopped,"StatusCode").equals(n(302)),"disabled redirect");c(stopped,"Close");assign(property(connection,"AllowAutoRedirect"),true);fails(()->c(connection,"Get",req("/loop")));
            for(String path:List.of("/post303","/post307")){Object request=req(path);c(request,"SetBodyFromString","Я");c(p(request,"Headers"),"Insert","Content-Type","application/json");c(p(request,"Headers"),"Insert","Content-Encoding","identity");Object reply=c(connection,"Post",request);Map<String,Object> value=json(reply);if(path.equals("/post303"))check(value.get("contentType").equals("")&&value.get("contentEncoding").equals(""),"303 clears body representation headers");check(value.get("method").equals(path.equals("/post303")?"GET":"POST")&&value.get("hex").equals(path.equals("/post303")?"":"d0af"),"explicit redirect method semantics");c(reply,"Close");}
            Object post=req("/post302");c(post,"SetBodyFromString","x");int before=one.posts.get();fails(()->c(connection,"Post",post));check(one.posts.get()==before+1,"no speculative POST retry/rewrite");check(owner.openResourceCount()==0,"redirect failures cleanup");
        }
    }
    static void deadlines(Path tmp,Fixture fixture){
        try(var owner=context(tmp);var scope=Execution.withContext(owner)){
            for(String path:List.of("/headers-delay","/body-stall","/body-trickle")){
                Files.writeString(tmp.resolve("unchanged.txt"),"keep");long begin=System.nanoTime();fails(()->c(conn(fixture.port(),1),"Get",req(path),"unchanged.txt"));
                check(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-begin)<2300,"end-to-end deadline "+path);check(owner.openResourceCount()==0&&Files.readString(tmp.resolve("unchanged.txt")).equals("keep"),"deadline cleanup/no partial target");
            }
            // 1C 8.3.27.1606: Timeout 0 waits for ever (35 s slow reply and 35 s trickled body both succeeded there).
            Object unlimited=conn(fixture.port(),0);
            Object slow=c(unlimited,"Get",req("/headers-delay"));check(p(slow,"StatusCode").equals(n(200)),"timeout 0 waits for slow headers");c(slow,"Close");
            Object trickled=c(unlimited,"Get",req("/body-trickle"));check(c(trickled,"GetBodyAsString").equals("AAAAAAAAAA"),"timeout 0 waits for a trickled body");c(trickled,"Close");
        }catch(IOException e){throw new AssertionError(e);}
    }
    static void tls(Path tmp,Fixture http) throws Exception {
        try(Fixture signed=new Fixture(serverTls("server.p12"),false);Fixture mutual=new Fixture(serverTls("server.p12"),true);Fixture self=new Fixture(serverTls("selfsigned.p12"),false);var owner=context(tmp);var scope=Execution.withContext(owner)){
            fails(()->c(make("HTTPConnection","https://localhost",n(self.port()),"","",UNDEFINED,n(2)),"Get",req("/text")));
            Object ca=make("FileCertificationAuthorityCertificates",CERTS.resolve("ca.pem").toAbsolutePath().toString());Object secure=make("OpenSSLSecureConnection",UNDEFINED,ca);
            fails(()->c(make("HTTPConnection","https://localhost",n(signed.port()),"","",UNDEFINED,n(2)),"Get",req("/text")));
            fails(()->c(make("HTTPConnection","https://localhost",n(self.port()),"","",UNDEFINED,n(2),secure),"Get",req("/text")));
            Object connection=make("HTTPConnection","HTTPS://localhost",n(signed.port()),"","",UNDEFINED,n(3),secure);Object result=c(connection,"Get",req("/text"));check(c(result,"GetBodyAsString").equals("Привет"),"custom CA validates chain/hostname");c(result,"Close");
            fails(()->c(make("HTTPConnection","https://127.0.0.1",n(signed.port()),"","",UNDEFINED,n(2),secure),"Get",req("/text")));
            fails(()->c(make("HTTPConnection","localhost",n(mutual.port()),"","",UNDEFINED,n(2),secure),"Get",req("/text")));
            Object certificate=make("FileClientCertificate",CERTS.resolve("client.p12").toAbsolutePath().toString(),"changeit");Object client=make("OpenSSLSecureConnection",certificate,ca);
            Object authenticated=c(make("HTTPConnection","localhost",n(mutual.port()),"","",UNDEFINED,n(3),client),"Get",req("/text"));check(c(authenticated,"GetBodyAsString").equals("Привет"),"mTLS client certificate works");c(authenticated,"Close");
            signed.otherPort=http.port();int before=http.hits.get();fails(()->c(connection,"Get",req("/redirect-downgrade")));check(http.hits.get()==before,"HTTPS downgrade blocked before HTTP request");
            fails(()->make("FileClientCertificate",CERTS.resolve("client.p12").toAbsolutePath().toString(),"wrong"));fails(()->make("FileClientCertificate",CERTS.resolve("ca.pem").toAbsolutePath().toString()));
            check(owner.openResourceCount()==0,"TLS failures cleanup");
        }
    }
    static void proxy(Path tmp,Fixture fixture) throws Exception {
        try(var owner=context(tmp);var scope=Execution.withContext(owner)){
            Object proxy=make("InternetProxy",false);c(proxy,"Set","http","127.0.0.1",n(fixture.port()),"","",false);
            Object connection=make("HTTPConnection","invalid.example",n(80),"","",proxy,n(3));Object response=c(connection,"Get",req("/echo"));URI proxyUri=URI.create((String)json(response).get("uri"));check(proxyUri.getScheme().equals("http")&&proxyUri.getHost().equals("invalid.example")&&(proxyUri.getPort()==80||proxyUri.getPort()==-1)&&proxyUri.getPath().equals("/echo"),"explicit local proxy, no target DNS/network");c(response,"Close");
            try(Fixture tls=new Fixture(serverTls("server.p12"),false);Tunnel tunnel=new Tunnel(tls.port())){
                Object tunnelProxy=make("InternetProxy");c(tunnelProxy,"Set","http","127.0.0.1",n(tunnel.port()),"","",false);
                Object ca=make("FileCertificationAuthorityCertificates",CERTS.resolve("ca.pem").toAbsolutePath().toString()),secure=make("OpenSSLSecureConnection",UNDEFINED,ca);
                Object https=make("HTTPConnection","https://localhost",n(tls.port()),"","",tunnelProxy,n(3),secure);Object reply=c(https,"Get",req("/text"));check(c(reply,"GetBodyAsString").equals("Привет")&&tunnel.connects.get()>0,"HTTPS CONNECT tunnel with custom trust");c(reply,"Close");
                fails(()->c(make("HTTPConnection","https://127.0.0.1",n(tls.port()),"","",tunnelProxy,n(2),secure),"Get",req("/text")));
                fails(()->c(make("HTTPConnection","https://localhost",n(tls.port()),"","",tunnelProxy,n(2)),"Get",req("/text")));
            }
            fails(()->make("InternetProxy",true));fails(()->c(proxy,"Set","http","localhost",n(80)));fails(()->c(proxy,"Set","socks","localhost",n(80),"","",false));
        }
    }
    static void url(){Object mode=p(globalValue("stringencodingmethod"),"URLEncoding");check(g("EncodeString","a b+/?&=ё",mode).equals("a%20b%2B%2F%3F%26%3D%D1%91"),"URL encode");check(g("DecodeString","a+b%20c%2B",mode).equals("a+b c+"),"raw plus preserved");check(g("DecodeString",g("EncodeString","ё",mode,"windows-1251"),mode,"windows-1251").equals("ё"),"explicit URL charset");for(String value:List.of("%","%A","%G0","%FF","%C0%AF"))fails(()->g("DecodeString",value,mode));check(g("EncodeString","https://e/x%20y?q=я&b=x+y",p(globalValue("stringencodingmethod"),"URLInURLEncoding")).equals("https://e/x%20y?q=%D1%8F&b=x+y"),"URL in URL encoding");}
    static volatile int jobPort;static volatile ApplicationContext jobContext;
    public static final class Worker implements Module {
        public Worker(){}public String __juno_metadata(){return "M\t0\trequest\t0\n";}public Object __juno_get(int n){throw new AssertionError();}public void __juno_set(int n,Object value){throw new AssertionError();}public Object __juno_call(int n,Object a,Object b,Object d){return __juno_invoke(n,new Object[0]);}
        public Object __juno_invoke(int n,Object[] args){jobContext=Execution.context();return c(conn(jobPort,10),"Get",req("/body-stall"));}
    }
    static void cancellation(Path tmp,Fixture fixture) throws Exception {
        jobPort=fixture.port();fixture.headers=new CountDownLatch(1);
        try(var app=new BackgroundJobs(Map.of("Worker",Worker.class),1,2,s->{},context(tmp))){app.run(()->{BackgroundJobs.Job job=app.execute("Worker.request",UNDEFINED,UNDEFINED,"");check(fixture.headers.await(3,TimeUnit.SECONDS),"headers delivered before cancel");long start=System.nanoTime();app.cancel(job);check(job.await(n(2))&&job.state().equals("Отменено"),"cancel stalled body after headers");check(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start)<2300&&jobContext.openResourceCount()==0,"bounded cancellation cleanup");return UNDEFINED;});}
    }
    /** HTTPОтвет has no Закрыть in 1C: an answer that is never closed must not keep a file or a resource. */
    static void unclosedAnswers(Path tmp,Fixture fixture) throws Exception {
        Set<String> before=temporary();
        try(var owner=context(tmp);var scope=Execution.withContext(owner)){
            Object connection=conn(fixture.port(),3);
            for(int i=0;i<50;i++){Object response=c(connection,"Get",req("/text"));check(p(response,"StatusCode").equals(n(200)),"status");check(!((String)c(response,"GetBodyAsString")).isEmpty(),"body");}
            check(temporary().equals(before),"no spool file outlives an answer that is never closed: "+temporary());
            check(owner.openResourceCount()==0,"an answer is not a resource");
        }
    }
    /** A downloaded file is the caller's output: it gets the permissions of any new file, not owner-only. */
    static void savedFileMode(Path tmp,Fixture fixture) throws Exception {
        if(Files.getFileAttributeView(tmp,java.nio.file.attribute.PosixFileAttributeView.class)==null)return;
        Path control=Files.createFile(tmp.resolve("mode-control.txt")),saved=tmp.resolve("mode-saved.txt");
        try(var owner=context(tmp);var scope=Execution.withContext(owner)){
            Object response=c(conn(fixture.port(),3),"Get",req("/text"),saved.toString());c(response,"Close");
        }
        check(Files.exists(saved)&&Files.getPosixFilePermissions(saved).equals(Files.getPosixFilePermissions(control)),"downloaded file keeps default permissions: "+Files.getPosixFilePermissions(saved)+" vs "+Files.getPosixFilePermissions(control));
    }
    static String digest(Path path) throws Exception {MessageDigest digest=MessageDigest.getInstance("SHA-256");try(InputStream input=Files.newInputStream(path)){byte[] block=new byte[65536];int n;while((n=input.read(block))>=0)digest.update(block,0,n);}return HexFormat.of().formatHex(digest.digest());}
    static void largeChild(int port,Path source,Path target) throws Exception {
        String before=digest(source);try(var owner=context(source.getParent());var scope=Execution.withContext(owner)){
            Object connection=conn(port,30),request=req("/digest");c(request,"SetBodyFileName",source.toString());Object reply=c(connection,"Post",request);Map<String,Object> value=json(reply);check(value.get("sha").equals(before),"large upload digest");c(reply,"Close");check(digest(source).equals(before),"upload source unchanged");
            fails(()->c(connection,"Post",request,source.toString()));check(digest(source).equals(before),"self target protection");Object response=c(connection,"Get",req("/large"),target.toString());check(c(response,"GetBodyFileName").equals(target.toString()),"large body file");fails(()->c(response,"GetBodyAsBinaryData"));c(response,"Close");check(Files.exists(target)&&digest(target).equals(before),"large download digest and persistent target");check(owner.openResourceCount()==0,"large resources closed");
        }
    }
    static void large(Path tmp,Fixture fixture) throws Exception {
        Path source=tmp.resolve("large-source.bin"),target=tmp.resolve("large-target.bin");try(OutputStream out=Files.newOutputStream(source)){for(int i=0;i<BLOCKS;i++)out.write(block);out.write(new byte[]{0,(byte)255,13});}
        Path errors=tmp.resolve("large-errors.txt");Process child=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-Xmx32m","-Djava.io.tmpdir="+System.getProperty("java.io.tmpdir"),"-cp",System.getProperty("java.class.path"),"HttpLibraryTests","--large",Integer.toString(fixture.port()),source.toString(),target.toString()).redirectError(errors.toFile()).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        if(!child.waitFor(35,TimeUnit.SECONDS)){child.destroyForcibly();throw new AssertionError("large HTTP timeout");}check(child.exitValue()==0,Files.readString(errors));Files.delete(source);Files.delete(target);
    }
    static Set<String> temporary() throws IOException {try(var paths=Files.list(Path.of(System.getProperty("java.io.tmpdir")))){Set<String> found=new HashSet<>();paths.filter(p->Files.isRegularFile(p)&&p.getFileName().toString().startsWith("juno-http-")).forEach(p->found.add(p.toString()));return found;}}
    public static void main(String[] args) throws Exception {
        if(args.length>0){largeChild(Integer.parseInt(args[1]),Path.of(args[2]),Path.of(args[3]));return;}
        if(!Boolean.getBoolean("juno.http.isolated")){
            Path isolated=Files.createTempDirectory("juno-http-isolated-");
            try{Process child=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-Djuno.http.isolated=true","-Djava.io.tmpdir="+isolated,"-cp",System.getProperty("java.class.path"),"HttpLibraryTests").inheritIO().start();if(!child.waitFor(60,TimeUnit.SECONDS)){child.destroyForcibly();throw new AssertionError("HTTP suite timeout");}check(child.exitValue()==0,"isolated HTTP suite");}
            finally{try(var paths=Files.walk(isolated)){for(Path path:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(path);}}return;
        }
        Path tmp=Files.createTempDirectory("juno-http-tests-");Set<String> before=temporary();
        try(Fixture one=new Fixture(null,false);Fixture two=new Fixture(null,false)){url();basic(tmp,one,two);redirects(tmp,one,two);deadlines(tmp,one);tls(tmp,one);proxy(tmp,two);cancellation(tmp,one);large(tmp,one);savedFileMode(tmp,one);unclosedAnswers(tmp,one);}
        finally{try(var paths=Files.walk(tmp)){for(Path path:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(path);}}
        check(temporary().equals(before),"private response spools all removed");
        long limit=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);while(Thread.getAllStackTraces().keySet().stream().anyMatch(t->t.getName().startsWith("HttpClient-")&&t.getName().contains("SelectorManager"))&&System.nanoTime()<limit)Thread.sleep(20);
        check(Thread.getAllStackTraces().keySet().stream().noneMatch(t->t.getName().startsWith("HttpClient-")&&t.getName().contains("SelectorManager")),"no HTTP client selector threads leaked");System.out.println("HTTP library PASS");
    }
}
