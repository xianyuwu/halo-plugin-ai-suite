package cn.rainwu.halo.ai.suite.endpoint;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import reactor.core.Exceptions;

class ConsoleConfigEndpointErrorTest {

    @Test
    void unwrapsRetryExhaustedCause() {
        RuntimeException providerError = new RuntimeException(
            "dimensions is not supported by this model");
        Throwable retryError = Exceptions.retryExhausted("Retries exhausted: 2/2", providerError);

        assertThat(ConsoleConfigEndpoint.extractErrorMessage(retryError))
            .isEqualTo("dimensions is not supported by this model");
    }
}
