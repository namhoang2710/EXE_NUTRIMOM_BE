package vn.nutrimom.file.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import vn.nutrimom.common.exception.BusinessException;
import vn.nutrimom.file.domain.StorageProvider;

class PrivateR2StorageServiceTest {
    private final S3Client client = mock(S3Client.class);
    private final PrivateR2StorageService storage = new PrivateR2StorageService(client, "private-bucket");
    private final StorageService.Location location = new StorageService.Location(
            StorageProvider.R2, "original-private-bucket", "private-files/user/file/random");

    @Test
    void uploadsExactBytesToRecordedBucketWithoutPublicAclOrCache() throws Exception {
        byte[] bytes = "%PDF-1.7 test".getBytes(StandardCharsets.US_ASCII);
        storage.put(location, bytes);
        ArgumentCaptor<PutObjectRequest> request = ArgumentCaptor.forClass(PutObjectRequest.class);
        ArgumentCaptor<RequestBody> body = ArgumentCaptor.forClass(RequestBody.class);
        verify(client).putObject(request.capture(), body.capture());
        assertThat(request.getValue().bucket()).isEqualTo("original-private-bucket");
        assertThat(request.getValue().key()).isEqualTo(location.key());
        assertThat(request.getValue().acl()).isNull();
        assertThat(request.getValue().cacheControl()).isEqualTo("private, no-store");
        assertThat(body.getValue().contentStreamProvider().newStream().readAllBytes()).isEqualTo(bytes);
    }

    @Test
    void readsExactBytesUsingPersistedBucketEvenIfDefaultBucketChanged() {
        byte[] bytes = {1, 2, 3};
        when(client.getObjectAsBytes(any(GetObjectRequest.class))).thenReturn(ResponseBytes.fromByteArray(
                GetObjectResponse.builder().contentLength(3L).build(), bytes));
        assertThat(storage.read(location)).isEqualTo(bytes);
        ArgumentCaptor<GetObjectRequest> request = ArgumentCaptor.forClass(GetObjectRequest.class);
        verify(client).getObjectAsBytes(request.capture());
        assertThat(request.getValue().bucket()).isEqualTo(location.bucket());
    }

    @Test
    void onlyMissingObjectIsTreatedAsAbsent() {
        when(client.getObjectAsBytes(any(GetObjectRequest.class)))
                .thenThrow(S3Exception.builder().statusCode(404).build());
        assertThat(storage.head(location)).isEmpty();
        assertThatThrownBy(() -> storage.read(location)).isInstanceOfSatisfying(BusinessException.class,
                error -> assertThat(error.getCode()).isEqualTo("RESOURCE_NOT_FOUND"));
        when(client.getObjectAsBytes(any(GetObjectRequest.class)))
                .thenThrow(S3Exception.builder().statusCode(403).message("credential-sensitive-error").build());
        assertThatThrownBy(() -> storage.head(location)).isInstanceOfSatisfying(BusinessException.class, error -> {
            assertThat(error.getCode()).isEqualTo("FILE_STORAGE_UNAVAILABLE");
            assertThat(error.isRetryable()).isTrue();
            assertThat(error.getMessage()).doesNotContain("credential-sensitive-error");
        });
    }

    @Test
    void uploadFailureDoesNotBecomeSuccessOrExposeProviderDetails() {
        when(client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenThrow(S3Exception.builder().statusCode(503).message("secret-provider-details").build());
        assertThatThrownBy(() -> storage.put(location, new byte[] {1}))
                .isInstanceOfSatisfying(BusinessException.class, error -> {
                    assertThat(error.getCode()).isEqualTo("FILE_STORAGE_UNAVAILABLE");
                    assertThat(error.getMessage()).doesNotContain("secret-provider-details");
                });
    }
}
