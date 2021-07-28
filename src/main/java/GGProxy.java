import com.sun.net.httpserver.*;

import javax.naming.NamingEnumeration;
import javax.naming.directory.*;
import javax.net.ssl.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.*;

public class GGProxy
{
    private       String                     ip;
    private final Map<GGRequest, GGResponse> cache         = new HashMap<>();
    private final Map<String, Integer>       cacheDuration = new HashMap<>()
    {{
        // 5 hours
        put("/api/get_vip_status", 1000 * 60 * 60 * 5);
        put("/api/statistics", 1000 * 60 * 60 * 5);
        
        // 5 min
        put("/api/sys", 1000 * 60 * 5);
    }};
    
    public GGProxy()
    {
        if (getGameIP() == null)
        {
            System.out.println("Unable to determine game IP.. :C");
            System.exit(0);
        }
        
        startProxyServer();
    }
    
    private void startProxyServer()
    {
        try
        {
            var address = new InetSocketAddress(InetAddress.getLoopbackAddress(), 443);
            var server  = HttpsServer.create(address, 0);
            
            var password = "totsugeki".toCharArray();
            var certFile = new FileInputStream("C:\\Program Files (x86)\\gg-struggle\\gg-struggle-cert.pfx");
            
            var keyStore = KeyStore.getInstance("JKS");
            keyStore.load(certFile, password);
            
            var keyFactory = KeyManagerFactory.getInstance("SunX509");
            keyFactory.init(keyStore, password);
            
            var trustFactory = TrustManagerFactory.getInstance("SunX509");
            trustFactory.init(keyStore);
            
            var ssl = SSLContext.getInstance("TLS");
            ssl.init(keyFactory.getKeyManagers(), trustFactory.getTrustManagers(), null);
            
            server.setHttpsConfigurator(new HttpsConfigurator(ssl)
            {
                @Override
                public void configure(HttpsParameters params)
                {
                    try
                    {
                        var context = SSLContext.getDefault();
                        var engine  = context.createSSLEngine();
                        
                        params.setNeedClientAuth(false);
                        params.setCipherSuites(engine.getEnabledCipherSuites());
                        params.setProtocols(engine.getEnabledProtocols());
                        
                        var defaultParameters = context.getDefaultSSLParameters();
                        params.setSSLParameters(defaultParameters);
                    } catch (Exception e)
                    {
                        e.printStackTrace();
                    }
                }
            });
            
            server.createContext("/", this::handleRequest);
            server.setExecutor(null);
            server.start();
            System.out.println("Server started!");
        } catch (Exception e)
        {
            e.printStackTrace();
        }
    }
    
    boolean modified = false;
    
    private void handleRequest(HttpExchange exchange) throws IOException
    {
        var    gameRequest = parseGameRequest(exchange);
        String shouldCache = null;
        
        for (String key : cacheDuration.keySet())
        {
            if (gameRequest.uri().startsWith(key))
            {
                shouldCache = key;
            }
        }
        
        GGResponse response = null;
        
        if (shouldCache != null)
        {
            System.out.println("Request should be cached!");
            response = cache.computeIfAbsent(gameRequest, (req) -> {
                System.out.println("Request not in cache, fetching!");
                return makeRealRequest(req);
            });
        } else
        {
            System.out.println("Request should not be cached, fetching fresh!");
            response = makeRealRequest(gameRequest);
        }
        
        if (response == null)
        {
            exchange.sendResponseHeaders(502, 0);
            return;
        }
        
        writeGameResponse(exchange, response);
        
        if (response.createTime() + cacheDuration.get(shouldCache) < System.currentTimeMillis())
        {
            System.out.println("Data in cache for path is outdated, refreshing...");
            cache.compute(gameRequest, (k, v) -> makeRealRequest(k));
        }
    }
    
    private void writeGameResponse(HttpExchange exchange, GGResponse response) throws IOException
    {
        System.out.println("Writing response to game");
        
        response.headers().forEach((k, v) -> {
            if (k != null)
            {
                exchange.getResponseHeaders().put(k, v);
            }
        });
        
        var length = Long.parseLong(response.headers().getOrDefault("Content-Length", List.of("0")).get(0));
        exchange.sendResponseHeaders(response.status(), length);
        
        var output = new DataOutputStream(exchange.getResponseBody());
        output.write(response.response(), 0, response.response().length - 5);
        output.flush();
        output.close();
        
        System.out.println("Writing done, waiting for next request...");
    }
    
    private GGRequest parseGameRequest(HttpExchange exchange) throws IOException
    {
        var body = readRequestBody(exchange.getRequestBody());
        
        System.out.println("Got request for " + exchange.getRequestURI());
        System.out.println("With content " + new String(body, StandardCharsets.UTF_8));
        
        return new GGRequest(exchange.getRequestMethod(), exchange.getRequestURI().toString(), exchange.getRequestHeaders(), body);
    }
    
    private GGResponse makeRealRequest(GGRequest request)
    {
        // FIXME: Somehow this request returns different data than the game, and gg-struggle, and totsugeki...?????
        
        System.out.println("Fetching updated data from servers...");
        
        try
        {
            var url        = new URL("https://" + getGameIP() + request.uri());
            var connection = (HttpsURLConnection) url.openConnection();
            connection.setHostnameVerifier((hostname, session) -> true);
            connection.setRequestMethod(request.method());
            connection.setDoOutput(true);
            connection.setDoInput(true);
            request.headers().forEach((k, v) -> {
                if (k != null)
                {
                    connection.setRequestProperty(k, v.get(0));
                }
            });
            
            System.out.println("Writing data to " + url + " via proxy...");
            var output = new DataOutputStream(connection.getOutputStream());
            output.write(request.body());
            output.flush();
            output.close();
            
            var response = readRequestBody(connection.getInputStream());
            System.out.println("Got response from proxy " + new String(response, StandardCharsets.UTF_8));
            
            return new GGResponse(connection.getResponseCode(), connection.getHeaderFields(), response, System.currentTimeMillis());
        } catch (Exception e)
        {
            e.printStackTrace();
            return null;
        }
    }
    
    private byte[] readRequestBody(InputStream stream) throws IOException
    {
        var input  = new BufferedInputStream(stream);
        var output = new ByteArrayOutputStream();
        var buffer = new byte[4096];
        
        var bytesRead = 0;
        while ((bytesRead = input.read(buffer)) > -1)
        {
            output.write(buffer, 0, bytesRead);
        }
        output.close();
        
        return output.toByteArray();
    }
    
    private String getGameIP()
    {
        if (ip != null)
        {
            return ip;
        }
        
        try
        {
            Hashtable<String, Object> env = new Hashtable<>();
            env.put("java.naming.factory.initial", "com.sun.jndi.dns.DnsContextFactory");
            env.put("java.naming.provider.url", "dns://8.8.8.8");
            
            var ictx  = new InitialDirContext(env);
            var attrs = ictx.getAttributes("ggst-game.guiltygear.com", new String[]{"A"});
            
            NamingEnumeration<? extends Attribute> e = attrs.getAll();
            
            ip = (String) attrs.get("A").get();
            
            return ip;
        } catch (Exception e)
        {
            e.printStackTrace();
        }
        
        return null;
    }
}
