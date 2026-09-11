package com.nocobase.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.tags.Tag;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** OpenAPI / Swagger 文档元数据(Week 14.5). */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI nocobaseOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("NocoBase API")
                        .description("""
                                dbcool — 多租户低代码 SaaS 平台 API 文档.

                                ## 核心模块
                                - **Auth** — JWT 登录 / 刷新 / 注销
                                - **Users / Roles / ACL** — 用户 / 角色 / 权限策略管理
                                - **Collections** — 数据模型 + 动态字段 + 记录 CRUD
                                - **Forms** — 表单定义 + 运行时
                                - **Views** — 视图定义(表格 / 看板 / 详情)
                                - **Workflows** — 工作流定义 / 实例 / 任务审批
                                - **Audit** — 审计日志查询
                                - **Messages** — 站内信

                                ## 鉴权
                                所有 endpoint 除 `/auth/login`、`/health`、Swagger UI 外,均需 `Authorization: Bearer {access_token}`.
                                """)
                        .version("0.0.1")
                        .contact(new Contact().name("dbcool team").email("dev@dbcool.local"))
                        .license(new License().name("MIT").url("https://opensource.org/licenses/MIT")))
                .components(new Components()
                        .addSecuritySchemes("bearer-jwt", new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("从 POST /api/auth/login 获取 access_token,放在 Authorization 头")))
                .addSecurityItem(new SecurityRequirement().addList("bearer-jwt"))
                .tags(List.of(
                        new Tag().name("Auth").description("认证(登录 / 刷新)"),
                        new Tag().name("Users").description("用户管理"),
                        new Tag().name("Roles").description("角色管理"),
                        new Tag().name("ACL").description("访问控制策略"),
                        new Tag().name("Collections").description("数据模型定义 + 动态字段 + 记录 CRUD + CSV 导入导出"),
                        new Tag().name("Forms").description("表单"),
                        new Tag().name("Views").description("视图"),
                        new Tag().name("Workflows").description("工作流"),
                        new Tag().name("Messages").description("站内信"),
                        new Tag().name("Audit").description("审计日志")
                ));
    }
}
