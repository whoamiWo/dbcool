package com.nocobase;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * NocoBase 核心引擎入口.
 *
 * <p>负责 Collection Engine / ACL Engine / Workflow Engine 等核心模块的启动.
 *
 * @author Cline
 * @since 0.0.1
 */
@SpringBootApplication
public class NocoBaseApplication {

    public static void main(String[] args) {
        SpringApplication.run(NocoBaseApplication.class, args);
    }
}
