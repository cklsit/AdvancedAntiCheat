package com.anticheat.web.util;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonSerializationContext;
import com.google.gson.JsonSerializer;

import java.lang.reflect.Type;
import java.util.Collection;
import java.util.Map;

/**
 * Gson 单例。所有 Handler 共享同一个 Gson 实例。
 * <p>
 * 使用宽松策略：不转义 HTML、不序列化 null 字段、不格式化（紧凑）。
 *
 * <h3>为什么必须注册 Collection / Map 的层级适配器</h3>
 * Handler 大量使用 {@code ok(ctx, Map.of("items", Collections.emptyList(), ...))} 这类写法，
 * 集合被放进 {@code Object} 类型位置，Gson 会按<b>运行时类型</b>取适配器：
 * <ul>
 *   <li>{@code Collections.emptyList()} → {@code java.util.Collections$EmptyList}</li>
 *   <li>{@code List.of()} → {@code java.util.ImmutableCollections$ListN}</li>
 *   <li>{@code Map.of()} → {@code java.util.ImmutableCollections$MapN}</li>
 * </ul>
 * 这些 JDK 内部类没有可见构造器，Gson 的 Collection/Map 工厂在<b>建适配器</b>阶段就去反射
 * 取构造器，于是在 JDK 9+ 模块化下直接抛：
 * <pre>
 * Unable to make private java.util.Collections$EmptyList() accessible:
 * module java.base does not "opens java.util" to unnamed module
 * </pre>
 * 表现为该接口 HTTP 500。注意这在 JDK 8 上不复现、JDK 17/21（生产用的 Temurin 21）必现。
 * <p>
 * 层级适配器在 Gson 工厂链里优先于内置 Collection/Map 工厂命中，只做「遍历写出」，完全不碰
 * 反射，一次性覆盖全部历史与未来的调用点。
 */
public final class JsonMapper {

    /** 只做遍历写出的集合适配器（不做任何反射/构造）。 */
    private static final JsonSerializer<Collection<?>> COLLECTION_SERIALIZER =
            new JsonSerializer<Collection<?>>() {
                @Override
                public JsonElement serialize(Collection<?> src, Type type, JsonSerializationContext ctx) {
                    JsonArray arr = new JsonArray();
                    for (Object item : src) {
                        JsonElement el = ctx.serialize(item);
                        arr.add(el == null ? JsonNull.INSTANCE : el);
                    }
                    return arr;
                }
            };

    /** 只做遍历写出的 Map 适配器；键统一按字符串写出（与 JSON 语义一致）。 */
    private static final JsonSerializer<Map<?, ?>> MAP_SERIALIZER =
            new JsonSerializer<Map<?, ?>>() {
                @Override
                public JsonElement serialize(Map<?, ?> src, Type type, JsonSerializationContext ctx) {
                    JsonObject obj = new JsonObject();
                    for (Map.Entry<?, ?> e : src.entrySet()) {
                        if (e.getKey() == null) {
                            continue;
                        }
                        JsonElement el = ctx.serialize(e.getValue());
                        obj.add(String.valueOf(e.getKey()), el == null ? JsonNull.INSTANCE : el);
                    }
                    return obj;
                }
            };

    private static final Gson GSON = new GsonBuilder()
            .disableHtmlEscaping()
            .serializeNulls()
            .registerTypeHierarchyAdapter(Collection.class, COLLECTION_SERIALIZER)
            .registerTypeHierarchyAdapter(Map.class, MAP_SERIALIZER)
            .create();

    private JsonMapper() {
    }

    public static Gson get() {
        return GSON;
    }

    public static String toJson(Object obj) {
        return GSON.toJson(obj);
    }

    public static <T> T fromJson(String json, Class<T> clazz) {
        if (json == null || json.isEmpty()) {
            return null;
        }
        return GSON.fromJson(json, clazz);
    }
}
