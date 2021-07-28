import java.util.*;

public record GGResponse(int status, Map<String, List<String>> headers, byte[] response, Long createTime)
{
}
