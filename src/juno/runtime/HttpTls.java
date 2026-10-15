package juno.runtime;

import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.security.cert.*;
import java.util.*;
import javax.net.ssl.*;
import static juno.runtime.RuntimeSupport.*;
import static juno.runtime.IoSupport.*;

/** Named 1C file-certificate subset implemented by JSSE, never an OpenSSL binding. */
final class HttpTls {
    record Client(KeyManager[] managers) implements TypedValue { public String bslTypeName(){return "СертификатКлиентаФайл";}public String toString(){return bslTypeName();} }
    record Authorities(TrustManager[] managers) implements TypedValue {public String bslTypeName(){return "СертификатыУдостоверяющихЦентровФайл";}public String toString(){return bslTypeName();}}
    record Secure(SSLContext context) implements TypedValue {public String bslTypeName(){return "ЗащищенноеСоединениеOpenSSL";}public String toString(){return bslTypeName();}}
    static Secure connection(Object[] args) {
        arity("ЗащищенноеСоединениеOpenSSL",args,0,2);Object client=optional(args,0,UNDEFINED),authorities=optional(args,1,UNDEFINED);
        if(!omitted(client)&&!(client instanceof Client)||!omitted(authorities)&&!(authorities instanceof Authorities))throw error("TLS: ожидаются файловые certificate objects или Неопределено");
        try {
            TrustManager[] trust;
            if(authorities instanceof Authorities ca)trust=ca.managers;else{TrustManagerFactory factory=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());factory.init((KeyStore)null);trust=factory.getTrustManagers();}
            SSLContext context=SSLContext.getInstance("TLS");context.init(client instanceof Client c?c.managers:null,trust,null);return new Secure(context);
        }catch(GeneralSecurityException e){throw failure("Настройка TLS",e);}
    }
    static Client client(Object[] args) {
        arity("СертификатКлиентаФайл",args,1,2);Path path=path(args[0]);String filename=path.getFileName().toString().toLowerCase(Locale.ROOT);
        if(!filename.endsWith(".p12")&&!filename.endsWith(".pfx"))throw error("Client certificate: поддержан только PKCS12 .p12/.pfx с private key");
        Object password=optional(args,1,"");if(!(password instanceof String text))throw error("Пароль сертификата должен быть строкой");char[] secret=text.toCharArray();
        try {
            KeyStore store=KeyStore.getInstance("PKCS12");store.load(new ByteArrayInputStream(HttpData.readFile(path)),secret);
            int keys=0;for(String alias:Collections.list(store.aliases()))if(store.isKeyEntry(alias)){
                if(!store.entryInstanceOf(alias,KeyStore.PrivateKeyEntry.class)||store.getCertificateChain(alias)==null||store.getCertificateChain(alias).length==0)throw error("PKCS12 требует PrivateKeyEntry с certificate chain");keys++;
            }if(keys!=1)throw error("PKCS12 должен содержать ровно один private key");
            KeyManagerFactory factory=KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());factory.init(store,secret);return new Client(factory.getKeyManagers());
        }catch(GeneralSecurityException|IOException e){throw failure("Чтение client PKCS12",e);}finally{Arrays.fill(secret,'\0');}
    }
    static Authorities authorities(Object[] args) {
        arity("СертификатыУдостоверяющихЦентровФайл",args,1,1);
        try {
            byte[] bytes=HttpData.readFile(path(args[0]));if(bytes.length>4*1024*1024)throw error("CA bundle превышает 4 МиБ");
            var certificates=CertificateFactory.getInstance("X.509").generateCertificates(new ByteArrayInputStream(bytes));if(certificates.isEmpty())throw error("CA bundle пуст");
            KeyStore store=KeyStore.getInstance(KeyStore.getDefaultType());store.load(null,null);int index=0;
            for(var certificate:certificates){X509Certificate cert=(X509Certificate)certificate;cert.checkValidity();if(cert.getBasicConstraints()<0)throw error("Trust bundle требует CA certificates, не server leaf");store.setCertificateEntry("ca"+index++,cert);}
            TrustManagerFactory factory=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());factory.init(store);return new Authorities(factory.getTrustManagers());
        }catch(GeneralSecurityException|IOException e){throw failure("Чтение CA PEM/DER bundle",e);}
    }
}
