package com.xiaoke.admin;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Owns administrator credentials and their lifecycle; never scans buyer business code. */
@SpringBootApplication(scanBasePackages = {"com.xiaoke.admin", "com.xiaoke.common"})
public class AdminApplication {
    public static void main(String[] args) {
        var context = SpringApplication.run(AdminApplication.class, args);
        if (context.getEnvironment().getProperty("xiaoke.admin.provision.enabled", Boolean.class, false)) {
            context.close();
        }
    }
}
