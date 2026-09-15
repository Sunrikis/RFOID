package cn.rfoid;
import cn.rfoid.config.AppProperties;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Set;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
@SpringBootApplication
@EnableConfigurationProperties(AppProperties.class)
public class TrackGuardApplication {
    public static void main(String[] args) throws Exception {
        Path root = Path.of("").toAbsolutePath().normalize();
        while (root.getParent() != null && !(Files.isDirectory(root.resolve("frontend")) && Files.isDirectory(root.resolve("ai-core")))) {
            root = root.getParent();
        }
        System.setProperty("rfoid.projectRoot", root.toString().replace('\\', '/'));
        Path envFile = root.resolve(".env");
        Set<String> keys = Set.of("DB_URL", "DB_USERNAME", "DB_PASSWORD", "RFOID_PYTHON", "RFOID_MODEL", "RFOID_WORKER", "RFOID_STORAGE", "RFOID_TIMEOUT", "RFOID_LOG", "SERVER_PORT", "SERVER_ADDRESS");
        if (Files.isRegularFile(envFile)) {
            for (String line : Files.readAllLines(envFile, StandardCharsets.UTF_8)) {
                if (line.isBlank() || line.stripLeading().startsWith("#")) continue;
                String[] pair = line.split("=", 2);
                if (pair.length == 2 && keys.contains(pair[0].trim()) && System.getenv(pair[0].trim()) == null) {
                    System.setProperty(pair[0].trim(), pair[1].trim());
                }
            }
        }
        SpringApplication.run(TrackGuardApplication.class, args);
    }
}
