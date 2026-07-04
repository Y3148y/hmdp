# 接口文档测试变更日志

## 变更内容

1. 添加Swagger文档支持
2. 创建Swagger配置类：`com.hmdp.config.SwaggerConfig`

## Swagger配置类代码

```java
package com.hmdp.config;

import io.swagger.annotations.ApiOperation;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import springfox.documentation.builders.ApiInfoBuilder;
import springfox.documentation.builders.PathSelectors;
import springfox.documentation.builders.RequestHandlerSelectors;
import springfox.documentation.service.ApiInfo;
import springfox.documentation.service.Contact;
import springfox.documentation.spi.DocumentationType;
import springfox.documentation.spring.web.plugins.Docket;
import springfox.documentation.swagger2.annotations.EnableSwagger2;

@Configuration
@EnableSwagger2
public class SwaggerConfig {
    @Bean
    public Docket createRestApi() {
        return new Docket(DocumentationType.SWAGGER_2)
            .apiInfo(apiInfo())
            .select()
            .apis(RequestHandlerSelectors.withMethodAnnotation(ApiOperation.class))
            .paths(PathSelectors.any())
            .build();
    }

    private ApiInfo apiInfo() {
        return new ApiInfoBuilder()
            .title("HM点评系统接口文档")
            .description("Redis高并发实战项目接口文档")
            .contact(new Contact("开发团队", "", "contact@example.com"))
            .version("1.0")
            .build();
    }
}
```

## 测试说明

1. 启动应用程序：
```bash
mvn spring-boot:run
```

2. 访问Swagger文档：
http://localhost:8081/doc.html

3. 验证各接口文档是否正常显示
4. 检查文档标题和描述是否符合项目要求