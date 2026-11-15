package dev.podjs.runtime;

import java.util.function.UnaryOperator;
import java.util.regex.*;

/** JVM port of framework/logging createLogRedactor; policy comes from the app's shared constants. */
final class PodFeedbackLogRedactor implements UnaryOperator<String> {
    final Pattern headers,fields;
    PodFeedbackLogRedactor(String sensitive,String headerPattern) {
        headers=Pattern.compile("\\b("+headerPattern+")[\"']?\\s*([:=])\\s*[^\\r\\n]*",Pattern.CASE_INSENSITIVE);
        fields=Pattern.compile("\\b("+sensitive+")[\"']?\\s*[:=]\\s*(?:\"[^\"\\r\\n]*\"|'[^'\\r\\n]*'|[^;\\s,\\]\\)}]+)",Pattern.CASE_INSENSITIVE);
    }
    public String apply(String raw) {
        String value=headers.matcher(raw).replaceAll("$1$2<redacted>")
            .replaceAll("(?i)\\b(Bearer\\s+)[A-Za-z0-9._~+/\\-=]+","$1<redacted>");
        Matcher urls=Pattern.compile("\\b[a-z][a-z0-9+.-]*://(?:<redacted>|[^\\s<>\"'])+",Pattern.CASE_INSENSITIVE).matcher(value);
        StringBuffer out=new StringBuffer();
        while(urls.find())urls.appendReplacement(out,Matcher.quoteReplacement(urls.group().replaceFirst("//[^/@]+@","//<redacted>@").replaceFirst("[?#].*$","?<redacted>")));
        urls.appendTail(out);
        value=out.toString().replaceAll("(/(?:<redacted>|[^\\s<>\"'?])+)[?](?:<redacted>|[^\\s<>\"'])*","$1?<redacted>");
        return fields.matcher(value).replaceAll("$1=<redacted>");
    }
}
