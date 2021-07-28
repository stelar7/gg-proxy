import com.sun.net.httpserver.Headers;

import java.util.*;

public record GGRequest(String method, String uri, Headers headers, byte[] body)
{
    @Override
    public boolean equals(Object o)
    {
        if (this == o)
        {
            return true;
        }
        if (o == null || getClass() != o.getClass())
        {
            return false;
        }
        GGRequest ggRequest = (GGRequest) o;
        return Objects.equals(method, ggRequest.method) && Objects.equals(uri, ggRequest.uri) && Arrays.equals(body, ggRequest.body);
    }
    
    @Override
    public int hashCode()
    {
        int result = Objects.hash(method, uri);
        result = 31 * result + Arrays.hashCode(body);
        return result;
    }
}
