package com.hmdp.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hmdp.entity.Shop;
import lombok.extern.slf4j.Slf4j;
import org.elasticsearch.client.Request;
import org.elasticsearch.client.Response;
import org.elasticsearch.client.ResponseException;
import org.elasticsearch.client.RestClient;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * ES 商铺索引服务。
 * <p>
 * 底层用 7.17.x {@link RestClient}（纯 HTTP 传输），
 * JSON 构造/解析用 Jackson {@link ObjectMapper}，
 * 不绑定 ES 服务端版本，兼容 7.x ~ 9.x。
 */
@Slf4j
@Service
public class ShopIndexService {

    private static final String INDEX = "shop";

    @Resource
    private RestClient restClient;

    @Resource
    private ObjectMapper mapper;

    /**
     * 应用启动时自动创建索引（幂等），ES 不可用不阻塞启动。
     */
    @PostConstruct
    public void init() {
        try {
            createIndexIfNotExists();
            log.info("ES 索引 [{}] 就绪", INDEX);
        } catch (Exception e) {
            log.warn("ES 索引创建失败，将在首次搜索时降级 MySQL LIKE: {}", e.getMessage());
        }
    }

    // ==================== 索引管理 ====================

    /**
     * 创建索引，设置 ik_smart 分词器。
     * 已存在则忽略（ES 不抛异常，返回 200 with acknowledged=false）。
     */
    public void createIndexIfNotExists() throws IOException {
        ObjectNode root = mapper.createObjectNode();
        // settings
        root.putObject("settings")
                .put("number_of_shards", 1)
                .put("number_of_replicas", 0);
        // mappings
        ObjectNode props = root.putObject("mappings").putObject("properties");
        props.putObject("id").put("type", "long");
        props.putObject("name").put("type", "text")
                .put("analyzer", "ik_smart").put("search_analyzer", "ik_smart");
        props.putObject("area").put("type", "text")
                .put("analyzer", "ik_smart").put("search_analyzer", "ik_smart");
        props.putObject("address").put("type", "text")
                .put("analyzer", "ik_smart").put("search_analyzer", "ik_smart");
        props.putObject("typeId").put("type", "long");
        props.putObject("avgPrice").put("type", "long");
        props.putObject("sold").put("type", "integer");
        props.putObject("comments").put("type", "integer");
        props.putObject("score").put("type", "integer");
        props.putObject("openHours").put("type", "keyword");
        props.putObject("x").put("type", "double");
        props.putObject("y").put("type", "double");
        props.putObject("images").put("type", "keyword").put("index", false);

        Request request = new Request("PUT", "/" + INDEX);
        request.setJsonEntity(mapper.writeValueAsString(root));
        try {
            restClient.performRequest(request);
        } catch (ResponseException e) {
            if (e.getMessage() != null && e.getMessage().contains("resource_already_exists_exception")) {
                return; // ES 的 PUT /shop 在索引已存在时返回 400，解决幂等
            }
            throw e;
        }
    }

    // ==================== 单条同步 ====================

    /**
     * 索引一条商铺文档（PUT /shop/_doc/{id}），幂等。
     */
    public void indexShop(Shop shop) throws IOException {
        ObjectNode doc = mapper.convertValue(shop, ObjectNode.class);
        Request request = new Request("PUT", "/" + INDEX + "/_doc/" + shop.getId());
        request.setJsonEntity(mapper.writeValueAsString(doc));
        restClient.performRequest(request);
    }

    /**
     * 删除一条文档，幂等。
     */
    public void deleteShopDoc(Long id) throws IOException {
        Request request = new Request("DELETE", "/" + INDEX + "/_doc/" + id);
        restClient.performRequest(request);
    }

    // ==================== 全量同步 ====================

    /**
     * 批量索引全部商铺（POST /_bulk 一次请求）。
     */
    public void bulkIndexAll(List<Shop> shops) throws IOException {
        if (shops.isEmpty()) return;
        StringBuilder sb = new StringBuilder();
        for (Shop shop : shops) {
            sb.append("{\"index\":{\"_index\":\"").append(INDEX)
                    .append("\",\"_id\":\"").append(shop.getId()).append("\"}}\n");
            sb.append(mapper.writeValueAsString(shop)).append("\n");
        }
        Request request = new Request("POST", "/_bulk");
        request.setJsonEntity(sb.toString());
        restClient.performRequest(request);
    }

    // ==================== 检索 ====================

    /**
     * 分词检索 + 高亮，返回含 highlight 的 Shop 列表。
     *
     * @param keyword 搜索关键词
     * @param page    页码（从 1 开始）
     * @param size    每页条数
     */
    public List<Shop> search(String keyword, int page, int size) throws IOException {
        int from = (page - 1) * size;

        // 构造查询 JSON
        ObjectNode root = mapper.createObjectNode();
        root.put("from", from);
        root.put("size", size);

        // multi_match：name 权重 3，area 权重 2，address 权重 1
        ObjectNode multiMatch = root.putObject("query")
                .putObject("multi_match");
        multiMatch.put("query", keyword);
        multiMatch.putArray("fields")
                .add("name^3").add("area^2").add("address");

        // 高亮：name + area
        ObjectNode highlight = root.putObject("highlight");
        highlight.put("pre_tags", "<em>");
        highlight.put("post_tags", "</em>");
        ObjectNode hlFields = highlight.putObject("fields");
        hlFields.putObject("name")
                .put("fragment_size", 100).put("number_of_fragments", 1);
        hlFields.putObject("area")
                .put("fragment_size", 100).put("number_of_fragments", 1);

        // 执行
        Request request = new Request("POST", "/" + INDEX + "/_search");
        request.setJsonEntity(mapper.writeValueAsString(root));
        Response response = restClient.performRequest(request);

        return parseSearchResponse(response);
    }

    // ==================== 响应解析 ====================

    private List<Shop> parseSearchResponse(Response response) throws IOException {
        JsonNode root = mapper.readTree(response.getEntity().getContent());
        JsonNode hits = root.path("hits").path("hits");
        if (!hits.isArray()) return Collections.emptyList();

        List<Shop> result = new ArrayList<>(hits.size());
        for (JsonNode hit : hits) {
            Shop shop = mapper.convertValue(hit.get("_source"), Shop.class);
            // 提取高亮
            JsonNode hl = hit.get("highlight");
            if (hl != null) {
                JsonNode hlName = hl.get("name");
                if (hlName != null && hlName.isArray() && hlName.size() > 0) {
                    shop.setHighlightName(hlName.get(0).asText());
                }
                JsonNode hlArea = hl.get("area");
                if (hlArea != null && hlArea.isArray() && hlArea.size() > 0) {
                    shop.setHighlightArea(hlArea.get(0).asText());
                }
            }
            result.add(shop);
        }
        return result;
    }
}
