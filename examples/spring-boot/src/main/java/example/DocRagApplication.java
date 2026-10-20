package example;

import docrag.ResearchClient;
import docrag.ToolRegistry;
import docrag.spring.ResearchChatViewAdvice;
import docrag.spring.ResearchProperties;
import docrag.spring.ResearchSpring;
import docrag.spring.ResearchWebController;
import docrag.telemetry.ResearchTelemetry;
import io.micrometer.core.instrument.MeterRegistry;
import io.opentelemetry.api.OpenTelemetry;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

import javax.sql.DataSource;
import java.util.Map;

/**
 * Complete host example: authentication and DataSource stay outside the reusable library.
 */
@SpringBootApplication
@EnableConfigurationProperties
@Import({ResearchWebController.class, ResearchChatViewAdvice.class})
public class DocRagApplication {
    public static void main(String[] args) {
        SpringApplication.run(DocRagApplication.class, args);
    }

    @Bean
    @ConfigurationProperties("research")
    ResearchProperties researchProperties() {
        return new ResearchProperties();
    }

    @Bean
    DataSource dataSource(Environment env) {
        var source = new PGSimpleDataSource();
        source.setURL(env.getRequiredProperty("demo.jdbc-url"));
        source.setUser(env.getRequiredProperty("demo.db-user"));
        source.setPassword(env.getRequiredProperty("demo.db-password"));
        return source;
    }

    @Bean(destroyMethod = "close")
    ResearchClient researchClient(DataSource source, ResearchProperties properties, MeterRegistry metrics,
                                  ObjectProvider<OpenTelemetry> tracing) throws Exception {
        return ResearchSpring.create(properties, source, new ToolRegistry(), names -> Map.of(),
                new ResearchClient.ManagedResource() {
                }, new ResearchClient.Enrichment() {
                },
                new ResearchTelemetry(metrics, tracing.getIfAvailable(OpenTelemetry::noop)));
    }

    @Bean
    UserDetailsService users(Environment env) {
        String password = env.getRequiredProperty("demo.password");
        if (password.isBlank()) throw new IllegalArgumentException("Set DEMO_PASSWORD before starting the example");
        var encoder = PasswordEncoderFactories.createDelegatingPasswordEncoder();
        return new InMemoryUserDetailsManager(User.withUsername("demo").password(encoder.encode(password)).roles("USER").build());
    }

    @Bean
    SecurityFilterChain security(HttpSecurity http) throws Exception {
        return http.authorizeHttpRequests(a -> a.anyRequest().authenticated())
                .formLogin(Customizer.withDefaults()).httpBasic(Customizer.withDefaults()).build();
    }

    @Controller
    static class Pages {
        @GetMapping("/")
        String index() {
            return "index";
        }
    }
}
