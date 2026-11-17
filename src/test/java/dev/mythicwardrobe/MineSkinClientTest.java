package dev.mythicwardrobe;

import dev.mythicwardrobe.skin.MineSkinClient;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MineSkinClientTest {

    @Test
    void parsesInvalidSkinDataErrorAndMarksRetryable() {
        String sample = """
                {"success":true,"messages":[{"code":"job_failed","message":"Job failed"}],
                 "job":{"id":"6afb9ab4549b35a7e6d22052","status":"failed","timestamp":1794874036365},
                 "errors":[{"code":"invalid_skin_data","message":"Failed to download skin texture from Mojang"}]}
                """;
        MineSkinClient.ParsedError err = MineSkinClient.parseErrorFromBody(sample);
        assertEquals("invalid_skin_data", err.code());
        assertEquals("Failed to download skin texture from Mojang", err.message());
        assertEquals("invalid_skin_data: Failed to download skin texture from Mojang", err.summary());
        assertTrue(MineSkinClient.isRetryableErrorCode(err.code()));
    }

    @Test
    void clientErrorsAreNotRetried() {
        assertFalse(MineSkinClient.isRetryableErrorCode("invalid_image"));
        assertFalse(MineSkinClient.isRetryableErrorCode("invalid_api_key"));
        assertFalse(MineSkinClient.isRetryableErrorCode("validation_error"));
        assertTrue(MineSkinClient.isRetryableErrorCode("generator_timeout"));
        assertTrue(MineSkinClient.isRetryableErrorCode("no_account_available"));
        assertTrue(MineSkinClient.isRetryableErrorCode("skin_change_failed"));
    }
}
