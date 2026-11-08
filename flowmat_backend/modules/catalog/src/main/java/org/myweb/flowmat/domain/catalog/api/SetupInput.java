package org.myweb.flowmat.domain.catalog.api;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.myweb.flowmat.global.exception.BusinessException;
import org.myweb.flowmat.global.exception.ErrorCode;

/** Strict JSON boundaries shared by the two setup APIs. No numeric-to-string coercion. */
final class SetupInput {
    private static final Set<String> RESERVED=Set.of("__proto__","prototype","constructor");
    private SetupInput() { }
    static JsonNode field(JsonNode body,String key) {
        if(body==null || !body.isObject() || !body.has(key))throw bad(key+" is required.");
        return body.get(key);
    }
    static Map<String,String> attributes(JsonNode body,String key) {
        JsonNode node=field(body,key);
        if(!node.isObject() || node.size()>20)throw bad(key+" requires an object with at most 20 string attributes.");
        Map<String,String> result=new TreeMap<>();
        node.fields().forEachRemaining(entry->{
            String name=entry.getKey().trim();
            if(name.isBlank() || name.length()>50 || RESERVED.contains(name) || controls(entry.getKey()) || !storable(entry.getKey())
                || !entry.getValue().isTextual())throw bad(key+" requires safe attribute names and string values.");
            String value=entry.getValue().textValue().trim();
            if(value.isBlank() || value.length()>100 || controls(entry.getValue().textValue()) || !storable(entry.getValue().textValue()) || result.putIfAbsent(name,value)!=null)
                throw bad(key+" requires unique names (50 characters) and nonblank values (100 characters).");
        });
        return result;
    }
    private static boolean controls(String value) { return value.chars().anyMatch(c->c<32 || c==127); }
    private static boolean storable(String value) { return value.indexOf('\0') < 0 && StandardCharsets.UTF_8.newEncoder().canEncode(value); }
    static long version(JsonNode body) {
        JsonNode value=field(body,"expectedVersion");
        if(!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue()<0)throw bad("expectedVersion requires a nonnegative integer.");
        return value.longValue();
    }
    static long version(String text) {
        try { if(text==null || !text.matches("[0-9]+"))throw new NumberFormatException();return Long.parseLong(text); }
        catch(NumberFormatException error){throw bad("expectedVersion requires a nonnegative integer.");}
    }
    static int integer(JsonNode body,String key,int max) {
        JsonNode value=field(body,key);
        if(!value.isIntegralNumber() || !value.canConvertToInt() || value.intValue()<1 || value.intValue()>max)
            throw bad(key+" requires an integer from 1 to "+max+".");
        return value.intValue();
    }
    static String note(JsonNode body) {
        JsonNode value=body.get("note");
        if(value==null || value.isNull())return null;
        if(!value.isTextual() || value.textValue().length()>500 || !storable(value.textValue()))throw bad("note requires storable text of at most 500 characters.");
        return value.textValue().isBlank()?null:value.textValue().trim();
    }
    static String id(String value) {
        try { String canonical=UUID.fromString(value).toString();if(!canonical.equalsIgnoreCase(value))throw new IllegalArgumentException();return canonical; }
        catch(IllegalArgumentException error){throw bad("changeoverId requires a UUID.");}
    }
    static BusinessException bad(String message){return new BusinessException(ErrorCode.BAD_REQUEST,message);}
}
