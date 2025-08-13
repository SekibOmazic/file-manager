package io.filemanager;

import com.adobe.testing.s3mock.testcontainers.S3MockContainer;
import io.filemanager.config.FileServerProperties;
import io.filemanager.config.S3Properties;
import io.filemanager.config.ScannerProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Profile;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.NginxContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;


@Slf4j
@Profile({"localdev", "test"})
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {
    private static final String S3_MOCK = "adobe/s3mock:latest";
    private static final String POSTGRES_IMAGE = "postgres:15.1-alpine";

    ///////////////
    // CONTAINERS
    ///////////////

    // A shared network is crucial for containers to communicate with each other using aliases.
    @Bean
    public Network network() {
        return Network.newNetwork();
    }

    @Bean
    public S3MockContainer s3mock(Network network) {
        log.warn("Starting S3 Mock container with image: {}", S3_MOCK);

        return new S3MockContainer(DockerImageName.parse(S3_MOCK))
                .withValidKmsKeys("arn:aws:kms:eu-central-1:1234567890:key/valid-test-key-ref")
                .withNetwork(network)
                .withNetworkAliases("s3mock"); // Alias for inter-container communication
    }

    @Bean
    @Profile("full-integration")
    public NginxContainer<?> fileServerMock(Network network) {
        log.warn("Starting Nginx container for file server mock");

        return new NginxContainer<>("nginx:alpine")
                .withNetwork(network)
                .withNetworkAliases("file-server")
                .withCopyFileToContainer(
                        MountableFile.forClasspathResource("nginx/nginx.conf"),
                        "/etc/nginx/nginx.conf"
                )
                .withCopyFileToContainer(
                        MountableFile.forClasspathResource("test-files/"),
                        "/usr/share/nginx/html/files/"
                )
                .withExposedPorts(80)
                .withReuse(true)
                .waitingFor(Wait.forHttp("/download/health").forStatusCode(200));
    }

/*
    we use sprint.sql.init.mode=true, see application-test.yaml

    @Bean
    public ConnectionFactoryInitializer initializer(ConnectionFactory connectionFactory) {
        ConnectionFactoryInitializer initializer = new ConnectionFactoryInitializer();

        initializer.setConnectionFactory(connectionFactory);
        initializer.setDatabasePopulator(
                new ResourceDatabasePopulator(new ClassPathResource("schema.sql"))
        );
        return initializer;
    }
*/

    @Bean
    @ServiceConnection
    public PostgreSQLContainer<?> postgresContainer(Network network) {
        log.warn("Starting PostgreSQL container with image: {}", POSTGRES_IMAGE);

        return new PostgreSQLContainer<>(DockerImageName.parse(POSTGRES_IMAGE))
                .withDatabaseName("testdb")
                .withUsername("testuser")
                .withPassword("testpass")
                .withExposedPorts(5432)
                .withNetwork(network)
                .withNetworkAliases("postgres");
    }


    @Bean
    @Profile("full-integration")
    @Qualifier("scannerSimulator")
    public GenericContainer<?> scannerSimulator(Network network,
                                                @Value("${server.port:8080}") int mainAppPort) {
        log.warn("Starting Scanner Simulator container from pre-built local image...");

        String simulatorImageName = "file-manager/scanner-simulator:latest";

        return new GenericContainer<>(DockerImageName.parse(simulatorImageName))
                .withNetwork(network)
                .withNetworkAliases("scanner-proxy")
                .withExposedPorts(8080)
                .withEnv("main-app.service.url", "http://host.testcontainers.internal:" + mainAppPort)
                .withExtraHost("host.testcontainers.internal", "host-gateway")
                .withReuse(true)
                .waitingFor(Wait.forHttp("/actuator/health").forStatusCode(200));
    }

    ///////////////
    // PROPERTIES
    ///////////////
    @Bean
    @ConditionalOnProperty(name = "s3.local", havingValue = "true")
    public S3Properties s3Properties(S3MockContainer s3mock,
                                     @Value("${s3.endpoint:}") String endpoint,
                                     @Value("${s3.port:}") Integer port,
                                     @Value("${s3.access-key:mock-access-key}") String accessKey,
                                     @Value("${s3.secret-key:mock-secret-key}") String secretKey,
                                     @Value("${s3.region:eu-central-1}") String region,
                                     @Value("${s3.max-connections:100}") Integer maxConnections,
                                     @Value("${s3.connection-timeout:60}") Integer connectionTimeout,
                                     @Value("${s3.socket-timeout:60}") Integer socketTimeout

    ) {
        String s3host = endpoint.isEmpty() ? s3mock.getHost() : endpoint;
        Integer s3port = port == null ? s3mock.getMappedPort(9090) : port;

        return S3Properties.builder()
                .host(s3host)
                .port(s3port)
                .accessKey(accessKey)
                .secretKey(secretKey)
                .region(region)
                .maxConnections(maxConnections)
                .connectionTimeout(connectionTimeout)
                .socketTimeout(socketTimeout)
                .build();
    }


    @Bean
    @ConditionalOnProperty(name = "scanner.local", havingValue = "true")
    public ScannerProperties scannerProperties(GenericContainer<?> scannerSimulator) {
        log.info("Configuring Scanner Properties for local testing with URL: {}", scannerSimulator.getHost() + ":" + scannerSimulator.getMappedPort(8080));

        return ScannerProperties.builder()
                .scannerHost(scannerSimulator.getHost())
                .scannerPort(scannerSimulator.getMappedPort(8080))
                .build();
    }

    @Bean
    @ConditionalOnProperty(name = "file-server.local", havingValue = "true")
    FileServerProperties fileServerProperties(NginxContainer fileServerMock) {
        log.info("Configuring File Server Properties for local testing with URL: {}", fileServerMock.getHost() + ":" + fileServerMock.getMappedPort(80));

        return FileServerProperties.builder()
                .secure(false) // Nginx is not secure in this mock setup
                .host(fileServerMock.getHost())
                .port(fileServerMock.getMappedPort(80))
                .connectionTimeoutMs(10000) // Default connection timeout
                .responseTimeoutSeconds(600) // Default response timeout
                .build();
    }
}
