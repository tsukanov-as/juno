package juno.runtime;

import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.Flow;
import javax.net.ssl.*;
import static juno.runtime.RuntimeSupport.*;
import static juno.runtime.IoSupport.*;

/** One owned exchange, end-to-end deadline, bounded body spool and explicit redirect policy. */
final class HttpExchange implements ApplicationContext.Resource {
    private final ApplicationContext owner;
    /** Timeout 0 means no limit, as in 1C; otherwise one deadline covers the whole exchange. */
    private final boolean limited;
    private final long deadline;
    private final Object lock=new Object();
    private final List<AutoCloseable> handles=new ArrayList<>();
    private final List<Path> temporary=new ArrayList<>();
    private volatile boolean stopped;
    private volatile CompletableFuture<?> pending;
    private volatile Flow.Subscription subscription;
    private HttpClient client;
    private ExecutorService executor;
    private HttpExchange(HttpConnection connection) throws IOException {
        owner=connection.owner;int seconds=connection.timeout;limited=seconds>0;
        deadline=limited?System.nanoTime()+TimeUnit.SECONDS.toNanos(seconds):0;owner.own(this);
        try {
            ExecutorService created=Executors.newVirtualThreadPerTaskExecutor();
            synchronized(lock){if(!stopped)executor=created;}
            if(executor==null){created.shutdownNow();throw new IOException("HTTP scope закрыт");}
            SSLParameters ssl=new SSLParameters();ssl.setEndpointIdentificationAlgorithm("HTTPS");ssl.setProtocols(new String[]{"TLSv1.3","TLSv1.2"});
            HttpClient.Builder builder=HttpClient.newBuilder().executor(executor).version(HttpClient.Version.HTTP_1_1).followRedirects(HttpClient.Redirect.NEVER)
                .sslParameters(ssl).proxy(connection.proxy==null?HttpProxy.direct():connection.proxy.snapshot());
            if(limited)builder.connectTimeout(Duration.ofSeconds(seconds));
            builder.sslContext(connection.tls==null?HttpTls.connection(new Object[0]).context():connection.tls.context());
            HttpClient built=builder.build();
            synchronized(lock){if(!stopped)client=built;}
            if(client==null){built.shutdownNow();throw new IOException("HTTP scope закрыт");}check();
        }catch(RuntimeException|Error|IOException e){cancel();throw e;}
    }
    static Object execute(HttpConnection connection,String method,HttpData.Request request,String filename) {
        URI uri=connection.resource(request.address);Map<String,List<String>> original=HttpData.snapshot(request.headers);
        Path target=filename==null?null:connection.owner.resolve(Path.of(filename));
        if(target!=null){regular(target);if(request.file!=null)try{if(target.equals(request.file)||Files.exists(target)&&Files.isSameFile(target,request.file))throw error("HTTP response file совпадает с upload source");}catch(IOException e){throw failure("Проверка output HTTP",e);}}
        try(HttpExchange transfer=new HttpExchange(connection)) {
            boolean body=!Set.of("GET","HEAD").contains(method), rewritten=false;
            for(int hop=0;;hop++) {
                transfer.check();Map<String,List<String>> headers=new LinkedHashMap<>(original);
                boolean origin=HttpConnection.sameOrigin(connection.origin,uri);
                if(!origin){headers.remove("authorization");headers.remove("cookie");}
                if(origin&&!connection.user.isEmpty()&&!headers.containsKey("authorization"))headers.put("authorization",List.of("Basic "+Base64.getEncoder().encodeToString((connection.user+":"+connection.password).getBytes(java.nio.charset.StandardCharsets.UTF_8))));
                if(rewritten){headers.remove("content-type");headers.remove("content-encoding");}
                HttpRequest.Builder builder=HttpRequest.newBuilder(uri);if(transfer.limited)builder.timeout(Duration.ofNanos(transfer.remaining()));
                headers.forEach((name,values)->values.forEach(value->builder.header(name,value)));
                builder.method(method,transfer.publisher(body?request.file:null,body?request.bytes:null));
                Path spool=transfer.temp(null);FileChannel output=transfer.track(FileChannel.open(spool,StandardOpenOption.WRITE,StandardOpenOption.TRUNCATE_EXISTING));
                CompletableFuture<java.net.http.HttpResponse<Path>> future=transfer.client.sendAsync(builder.build(),info->transfer.new Subscriber(spool,output));
                transfer.pending=future;if(transfer.stopped)future.cancel(true);
                java.net.http.HttpResponse<Path> response=transfer.limited?future.get(transfer.remaining(),TimeUnit.NANOSECONDS):future.get();transfer.check();
                int status=response.statusCode();String location=response.headers().firstValue("location").orElse("");
                if(connection.redirect&&Set.of(301,302,303,307,308).contains(status)&&!location.isEmpty()) {
                    if(hop>=5)throw error("HTTP redirect limit 5 превышен");URI next;
                    try{next=uri.resolve(location);}catch(IllegalArgumentException e){throw error("Недопустимый redirect Location");}
                    if(next.getHost()==null||next.getRawUserInfo()!=null||next.getRawFragment()!=null||!Set.of("http","https").contains(next.getScheme().toLowerCase(Locale.ROOT)))throw error("Неподдерживаемый redirect URI");
                    next=URI.create(next.getScheme().toLowerCase(Locale.ROOT)+":"+next.getRawSchemeSpecificPart());
                    if(uri.getScheme().equals("https")&&next.getScheme().equals("http"))throw error("HTTPS -> HTTP redirect запрещён");
                    if(status==303&&!method.equals("HEAD")){method="GET";body=false;rewritten=true;}
                    else if((status==301||status==302)&&!Set.of("GET","HEAD").contains(method))throw error("301/302 для изменяющего метода требует явной обработки");
                    if(!HttpConnection.sameOrigin(uri,next)&&!Set.of("GET","HEAD").contains(method))throw error("Перенос HTTP body на другой origin запрещён");
                    uri=next;Files.deleteIfExists(spool);transfer.detach(spool);continue;
                }
                if(target!=null)transfer.export(spool,target);
                transfer.detach(spool);return new HttpData.Response(spool,filename,status,response.headers().map());
            }
        }catch(InterruptedException e){Thread.currentThread().interrupt();Execution.checkCancellation();throw failure("HTTP ожидание прервано",e);}
        catch(TimeoutException e){throw failure("HTTP deadline истёк (headers и полное тело)",e);}
        catch(ExecutionException e){Execution.checkCancellation();Throwable cause=e.getCause();throw failure("HTTP exchange",cause instanceof Exception ex?ex:new IOException("Ошибка HTTP body",cause));}
        catch(CancellationException e){Execution.checkCancellation();throw failure("HTTP передача отменена",e);}
        catch(IOException|IllegalArgumentException e){throw failure("HTTP exchange",e);}
    }
    private long remaining() throws HttpTimeoutException {if(!limited)return Long.MAX_VALUE;long time=deadline-System.nanoTime();if(time<=0)throw new HttpTimeoutException("HTTP deadline headers/body истёк");return time;}
    private void check() throws IOException {if(stopped)throw new IOException("HTTP передача отменена");remaining();}
    private <T extends AutoCloseable>T track(T handle) throws IOException {
        synchronized(lock){if(!stopped){handles.add(handle);return handle;}}
        try{handle.close();}catch(Exception close){throw new IOException("HTTP scope закрыт",close);}throw new IOException("HTTP scope закрыт");
    }
    private Path temp(Path directory) throws IOException {
        check();Path path=directory==null?Files.createTempFile("juno-http-",".body"):Files.createFile(directory.resolve(".juno-http-"+UUID.randomUUID()+".tmp"));/* the staged file becomes the caller's output: default permissions, not createTempFile's owner-only */
        synchronized(lock){if(!stopped){temporary.add(path);return path;}}
        Files.deleteIfExists(path);throw new IOException("HTTP scope закрыт");
    }
    private void detach(Path path){synchronized(lock){temporary.remove(path);}}
    private HttpRequest.BodyPublisher publisher(Path file,byte[] bytes) throws IOException {
        HttpRequest.BodyPublisher source;long length;
        if(file!=null){regular(file);FileChannel input=track(FileChannel.open(file,StandardOpenOption.READ));length=input.size();source=HttpRequest.BodyPublishers.ofInputStream(()->Channels.newInputStream(input));}
        else {byte[] payload=bytes==null?new byte[0]:bytes;source=HttpRequest.BodyPublishers.ofByteArray(payload);length=payload.length;}
        AtomicBoolean used=new AtomicBoolean();long size=length;
        return new HttpRequest.BodyPublisher(){public long contentLength(){return size;}public void subscribe(Flow.Subscriber<? super ByteBuffer> subscriber){
            if(!used.compareAndSet(false,true)){subscriber.onSubscribe(new Flow.Subscription(){public void request(long n){}public void cancel(){}});subscriber.onError(new IOException("Повторная отправка HTTP body запрещена"));return;}source.subscribe(subscriber);
        }};
    }
    private final class Subscriber implements java.net.http.HttpResponse.BodySubscriber<Path> {
        final CompletableFuture<Path> body=new CompletableFuture<>();final Path path;final FileChannel output;Flow.Subscription upstream;
        Subscriber(Path path,FileChannel output){this.path=path;this.output=output;}
        public CompletionStage<Path> getBody(){return body;}
        public void onSubscribe(Flow.Subscription value){upstream=value;subscription=value;if(stopped){value.cancel();body.completeExceptionally(new IOException("HTTP scope закрыт"));}else value.request(1);}
        public void onNext(List<ByteBuffer> buffers){try{check();for(ByteBuffer buffer:buffers)while(buffer.hasRemaining()){check();output.write(buffer);}upstream.request(1);}catch(IOException|RuntimeException e){upstream.cancel();onError(e);}}
        public void onError(Throwable error){try{output.close();}catch(IOException close){error.addSuppressed(close);}body.completeExceptionally(error);}
        public void onComplete(){try{output.close();body.complete(path);}catch(IOException e){body.completeExceptionally(e);}}
    }
    private void export(Path source,Path target) throws IOException {
        Path parent=target.getParent();if(!Files.isDirectory(parent))throw new IOException("Каталог output HTTP не существует");Path staged=temp(parent);
        FileChannel input=track(FileChannel.open(source,StandardOpenOption.READ)),output=track(FileChannel.open(staged,StandardOpenOption.WRITE,StandardOpenOption.TRUNCATE_EXISTING));
        ByteBuffer block=ByteBuffer.allocate(65536);
        while(true){check();block.clear();int count=input.read(block);if(count<0)break;block.flip();while(block.hasRemaining()){check();output.write(block);}}
        input.close();output.close();check();
        try{Files.move(staged,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}catch(AtomicMoveNotSupportedException e){Files.move(staged,target,StandardCopyOption.REPLACE_EXISTING);}detach(staged);
    }
    public void cancel(){close();}
    public void close(){
        List<AutoCloseable> owned;List<Path> paths;
        synchronized(lock){if(stopped)return;stopped=true;owned=new ArrayList<>(handles);handles.clear();paths=new ArrayList<>(temporary);temporary.clear();}
        Throwable primary=null;
        try {
            CompletableFuture<?> future=pending;if(future!=null)future.cancel(true);Flow.Subscription active=subscription;if(active!=null)active.cancel();
            Collections.reverse(owned);for(AutoCloseable handle:owned)try{handle.close();}catch(Exception e){if(primary==null)primary=e;else primary.addSuppressed(e);}
            for(Path path:paths)try{Files.deleteIfExists(path);}catch(IOException e){if(primary==null)primary=e;else primary.addSuppressed(e);}
        }finally{
            try{if(client!=null)client.shutdownNow();}finally{if(executor!=null)executor.shutdownNow();owner.release(this);}
            boolean interrupted=Thread.interrupted();try{if(client!=null&&!client.awaitTermination(Duration.ofSeconds(1)))throw error("HTTP client не завершился после отмены");}catch(InterruptedException e){interrupted=true;}finally{if(interrupted)Thread.currentThread().interrupt();}
        }
        if(primary!=null)throw failure("Закрытие HTTP ресурсов",primary instanceof Exception e?e:new IOException(primary));
    }
}
