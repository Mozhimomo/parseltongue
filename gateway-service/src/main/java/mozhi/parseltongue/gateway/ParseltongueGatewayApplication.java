package mozhi.parseltongue.gateway;

import mozhi.parseltongue.gateway.config.GatewayAuthProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(GatewayAuthProperties.class)
public class ParseltongueGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(ParseltongueGatewayApplication.class, args);
    }
}
