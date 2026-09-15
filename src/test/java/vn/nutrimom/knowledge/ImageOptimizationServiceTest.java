package vn.nutrimom.knowledge;

import static org.assertj.core.api.Assertions.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.Random;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import vn.nutrimom.common.exception.BusinessException;
import vn.nutrimom.knowledge.service.ImageOptimizationService;

class ImageOptimizationServiceTest {
    private final ImageOptimizationService service = new ImageOptimizationService();

    static byte[] png(int width, int height) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Random random = new Random(7);
        for (int y=0; y<height; y++) for (int x=0; x<width; x++) image.setRGB(x, y, random.nextInt(0x1000000));
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); ImageIO.write(image, "png", bytes);
        return bytes.toByteArray();
    }
    static MockMultipartFile file(byte[] bytes, String type) { return new MockMultipartFile("file", "image", type, bytes); }

    @Test void validImageIsReencodedWithoutUpscaling() throws Exception {
        var result = service.optimize(file(png(120, 80), "image/png"));
        assertThat(result.width()).isEqualTo(120); assertThat(result.height()).isEqualTo(80);
        assertThat(result.contentType()).isEqualTo("image/jpeg");
        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(result.bytes()));
        assertThat(decoded.getWidth()).isEqualTo(120);
    }
    @Test void largeImageIsResizedAndCompressed() throws Exception {
        byte[] original = png(3000, 600);
        var result = service.optimize(file(original, "image/png"));
        assertThat(result.width()).isEqualTo(2400); assertThat(result.height()).isEqualTo(480);
        assertThat(result.bytes().length).isLessThan(original.length).isLessThanOrEqualTo(ImageOptimizationService.MAX_OUTPUT_BYTES);
    }
    @Test void rejectsUnsupportedAndSpoofedType() throws Exception {
        assertCode("INVALID_FILE_TYPE", file(new byte[]{1}, "image/gif"));
        assertCode("INVALID_FILE_TYPE", file(png(10, 10), "image/jpeg"));
        assertCode("IMAGE_PROCESSING_FAILED", file(new byte[]{1, 2}, "image/png"));
    }
    @Test void rejectsMissingAndOversizedInput() {
        assertCode("INVALID_MULTIPART", file(new byte[0], "image/png"));
        assertCode("FILE_TOO_LARGE", file(new byte[(int)ImageOptimizationService.MAX_INPUT_BYTES+1], "image/png"));
    }
    @Test void rejectsHugeDimensionsBeforePixelDecode() throws Exception {
        BufferedImage image = new BufferedImage(12001, 1, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); ImageIO.write(image, "png", bytes);
        assertCode("INVALID_IMAGE_DIMENSIONS", file(bytes.toByteArray(), "image/png"));
    }
    @Test void decodesWebpUsingThePackagedPureJavaReader() {
        // TwelveMonkeys' small_1x1.webp test sample, including a harmless metadata chunk.
        byte[] webp = java.util.Base64.getDecoder().decode(
                "UklGRlYAAABXRUJQVlA4IDoAAADwAgCdASoBAAEAAEcIhYWIhYSIAgICdaoD+AP6Ag1NGAD+/vNYf/5gZt2KO//mBv/80F4SW6//zLwASUNNVAgAAAB0ZXN0MXgxAA==");
        var result = service.optimize(file(webp, "image/webp"));
        assertThat(result.width()).isEqualTo(1); assertThat(result.height()).isEqualTo(1);
        assertThat(result.extension()).isEqualTo("jpg");
    }
    @Test void flattensTransparentPngOntoWhite() throws Exception {
        BufferedImage image = new BufferedImage(20, 20, BufferedImage.TYPE_INT_ARGB);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); ImageIO.write(image, "png", bytes);
        var result = service.optimize(file(bytes.toByteArray(), "image/png"));
        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(result.bytes()));
        assertThat(decoded.getRGB(10, 10) & 0xffffff).isEqualTo(0xffffff);
    }
    private void assertCode(String code, MockMultipartFile file) {
        assertThatThrownBy(() -> service.optimize(file)).isInstanceOfSatisfying(BusinessException.class,
                ex -> assertThat(ex.getCode()).isEqualTo(code));
    }
}
