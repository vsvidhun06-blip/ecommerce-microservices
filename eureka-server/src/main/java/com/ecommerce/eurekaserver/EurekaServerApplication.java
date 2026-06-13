package com.ecommerce.eurekaserver;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.netflix.eureka.server.EnableEurekaServer;

/**
 * Netflix Eureka service-discovery server.
 *
 * Every other service registers here (eureka.client.register-with-eureka=true)
 * and the API gateway resolves {@code lb://SERVICE-NAME} routes against this
 * registry, so downstream hosts/ports stop being hard-coded. The server itself
 * does not register with or fetch from any peer — it is a standalone registry.
 */
@SpringBootApplication
@EnableEurekaServer
public class EurekaServerApplication {
    public static void main(String[] args) {
        SpringApplication.run(EurekaServerApplication.class, args);
    }
}
