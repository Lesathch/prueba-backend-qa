package com.accenture.franchises.infrastructure;

import java.net.URI;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.*;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;

@Configuration
public class DynamoConfiguration {
    @Bean
    DynamoDbAsyncClient dynamoClient(@Value("${inventory.dynamodb.region}") String region,
                                    @Value("${inventory.dynamodb.endpoint:}") String endpoint) {
        var builder = DynamoDbAsyncClient.builder().region(Region.of(region))
                .overrideConfiguration(config -> config.apiCallTimeout(Duration.ofSeconds(10))
                        .apiCallAttemptTimeout(Duration.ofSeconds(4)));
        if (!endpoint.isBlank()) {
            builder.endpointOverride(URI.create(endpoint)).credentialsProvider(
                    StaticCredentialsProvider.create(AwsBasicCredentials.create("local", "local")));
        } else {
            builder.credentialsProvider(DefaultCredentialsProvider.builder().build());
        }
        return builder.build();
    }
}
