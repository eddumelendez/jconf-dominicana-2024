package org.example;

import com.jayway.jsonpath.JsonPath;
import net.minidev.json.JSONArray;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.ToxiproxyContainer;
import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.exception.ApiCallTimeoutException;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

@Testcontainers
class LocalStackNetworkTest {

    private static Network network = Network.newNetwork();

    @Container
    private static final LocalStackContainer localstack = new LocalStackContainer(DockerImageName.parse("localstack/localstack:3.5.0"))
            .withNetwork(network)
            .withNetworkAliases("localstack")
            .withCopyFileToContainer(MountableFile.forClasspathResource("aws-init.sh", 0777), "/etc/localstack/init/ready.d/init-aws.sh")
            .waitingFor(
                    Wait.forHttp("/_localstack/init/ready").forStatusCode(200).forResponsePredicate(new ReadyPredicate()));

    @Container
    private static final ToxiproxyContainer toxiproxy = new ToxiproxyContainer("ghcr.io/shopify/toxiproxy:2.9.0")
            .withCopyFileToContainer(MountableFile.forClasspathResource("toxiproxy.json"), "/tmp/toxiproxy.json")
            .withCommand("-host=0.0.0.0", "-config=/tmp/toxiproxy.json")
            .withNetwork(network);

    @Test
    void test() throws URISyntaxException {
        S3Client s3 = buildS3Client();

        assertThat(s3.listBuckets().buckets()).hasSize(1);
        assertThat(s3.listBuckets().buckets().get(0).name()).isEqualTo("test-bucket");

    }

    @Test
    void withLatency() throws Exception {
        execute("./toxiproxy-cli toxic add -t latency --downstream -a latency=1600 -a jitter=100 -n latency_downstream localstack");

        S3Client s3 = buildS3Client(true);
        assertThatExceptionOfType(ApiCallTimeoutException.class)
                .isThrownBy(() -> s3.listBuckets().buckets());

        execute("./toxiproxy-cli toxic remove -n latency_downstream localstack");
    }

    S3Client buildS3Client() throws URISyntaxException {
        return buildS3Client(false);
    }

    S3Client buildS3Client(boolean enableTimeout) throws URISyntaxException {
        S3ClientBuilder s3ClientBuilder = S3Client
                .builder()
                .endpointOverride(new URI("http://%s:%d".formatted(toxiproxy.getHost(), toxiproxy.getMappedPort(8666))))
                .credentialsProvider(
                        StaticCredentialsProvider.create(
                                AwsBasicCredentials.create(localstack.getAccessKey(), localstack.getSecretKey())
                        )
                )
                .region(Region.of(localstack.getRegion()));
        if (enableTimeout) {
            s3ClientBuilder = s3ClientBuilder.overrideConfiguration(b -> b.apiCallTimeout(Duration.ofMillis(100)));
        }

        return s3ClientBuilder.build();
    }

    private static void execute(String command) throws Exception {
        org.testcontainers.containers.Container.ExecResult result = toxiproxy.execInContainer(command.split(" "));
        if (result.getExitCode() != 0) {
            throw new RuntimeException("Error executing command '%s' \nstderr: %s\nstdout: %s".formatted(command,
                    result.getStderr(), result.getStdout()));
        }
    }

    static class ReadyPredicate implements Predicate<String> {

        @Override
        public boolean test(String response) {
            JSONArray numberOfScripts = JsonPath.read(response, "$.scripts[*]");
            JSONArray scriptsProcessed = JsonPath.read(response,
                    "$.scripts[?(@.state == 'SUCCESSFUL' || @.state == 'ERROR')]");
            System.out.println("Number of scripts: " + numberOfScripts.size());
            System.out.println("Number of scripts processed: " + scriptsProcessed.size());
            return numberOfScripts.size() == scriptsProcessed.size();
        }

    }

}
