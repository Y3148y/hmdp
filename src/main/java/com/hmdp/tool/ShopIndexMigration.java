package com.hmdp.tool;

import com.hmdp.entity.Shop;
import com.hmdp.service.IShopService;
import com.hmdp.service.ShopIndexService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.List;

/**
 * ES 商铺索引全量迁移工具。
 * <p>
 * 用法：以 migration profile 启动，跑完 Ctrl+C 停止。
 * <pre>
 *   mvn spring-boot:run -Dspring-boot.run.profiles=migration
 * </pre>
 * <p>
 * 该 profile 只在一次性数据迁移时使用，日常启动（default）不会执行。
 * 在Spring 启动收尾阶段自动调用 run ()，
 * 此时所有 Bean 加载完成、Tomcat 服务就绪后，Spring 自动遍历所有实现 CommandLineRunner 的 Bean，执行它们的 run() 方法
 */
@Slf4j
@Component
@Profile("migration")
public class ShopIndexMigration implements CommandLineRunner {

    @Resource
    private IShopService shopService;

    @Resource
    private ShopIndexService shopIndexService;

    @Override
    public void run(String... args) {
        try {
            List<Shop> allShops = shopService.list();
            log.info("开始 ES 全量同步，共 {} 条店铺", allShops.size());
            // 防御性代码，调用两次创建索引
            shopIndexService.createIndexIfNotExists();
            shopIndexService.bulkIndexAll(allShops);

            log.info("ES 全量同步完成，共 {} 条", allShops.size());
        } catch (Exception e) {
            log.error("ES 全量同步失败", e);
        }
    }
}
