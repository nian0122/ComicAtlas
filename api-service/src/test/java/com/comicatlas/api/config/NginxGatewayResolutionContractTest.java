package com.comicatlas.api.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Nginx Gateway 动态解析契约")
class NginxGatewayResolutionContractTest {

    @Test
    @DisplayName("Gateway容器重启换IP后Nginx仍可重新解析上游")
    void gatewayRestartWithNewIp_usesDockerDnsResolver() throws IOException {
        Path workingDirectory = Path.of("").toAbsolutePath();
        Path repositoryRoot = Files.exists(workingDirectory.resolve("nginx.conf"))
                ? workingDirectory
                : workingDirectory.getParent();
        String config = Files.readString(repositoryRoot.resolve("nginx.conf"));

        assertThat(config).contains("resolver 127.0.0.11");
        assertThat(config).contains("set $gateway_upstream http://gateway:8000;");
        assertThat(config).contains("proxy_pass $gateway_upstream;");
        assertThat(config).doesNotContain("proxy_pass http://gateway:8000;");
    }
}
