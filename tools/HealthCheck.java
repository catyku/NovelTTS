import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * 容��� healthcheck：打 actuator health endpoint。
 *
 * 容器內沒有 curl/wget，而 Docker healthcheck 預設用 sh（非 bash），
 * 無法使用 /dev/tcp。改以 Java 內建 HTTP client 實作。
 */
public class HealthCheck {

    public static void main(String[] args) {
        String url = args.length > 0 ? args[0] : "http://127.0.0.1:8090/actuator/health";
        try (HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .build()) {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200 && response.body().contains("\"status\":\"UP\"")) {
                System.exit(0);
            }
            System.err.println("healthcheck failed: HTTP " + response.statusCode()
                    + " body=" + response.body());
            System.exit(1);
        } catch (Exception e) {
            System.err.println("healthcheck error: " + e.getMessage());
            System.exit(1);
        }
    }
}
