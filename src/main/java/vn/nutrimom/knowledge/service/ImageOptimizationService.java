package vn.nutrimom.knowledge.service;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.*;
import java.util.concurrent.Semaphore;
import javax.imageio.*;
import javax.imageio.stream.*;
import org.slf4j.*;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import vn.nutrimom.common.exception.BusinessException;
import vn.nutrimom.common.exception.ErrorCode;

@Service
public class ImageOptimizationService {
    public static final long MAX_INPUT_BYTES = 10L * 1024 * 1024;
    public static final int MAX_OUTPUT_BYTES = 2 * 1024 * 1024;
    public static final int MAX_OUTPUT_DIMENSION = 2400;
    private static final long MAX_PIXELS = 40_000_000;
    private static final int MAX_INPUT_DIMENSION = 12000;
    private static final Logger log = LoggerFactory.getLogger(ImageOptimizationService.class);
    private final Semaphore processingSlots = new Semaphore(2);

    public record OptimizedImage(byte[] bytes, int width, int height, String contentType, String extension) {}

    public OptimizedImage optimize(MultipartFile file) {
        if (file == null || file.isEmpty()) throw error(ErrorCode.INVALID_MULTIPART, "Image file is required.");
        if (file.getSize() > MAX_INPUT_BYTES) throw error(ErrorCode.FILE_TOO_LARGE, "Image must be at most 10 MiB.");
        String mime = file.getContentType();
        if (mime == null || !Set.of("image/jpeg", "image/jpg", "image/png", "image/webp").contains(mime.toLowerCase(Locale.ROOT)))
            throw error(ErrorCode.INVALID_FILE_TYPE, "Only JPEG, PNG and WebP images are supported.");
        if (!processingSlots.tryAcquire()) throw error(ErrorCode.IMAGE_PROCESSING_BUSY, "Image processing is busy. Please retry.");
        try {
            log.info("Image optimization start inputBytes={} mime={}", file.getSize(), mime);
            byte[] input;
            try (InputStream stream = file.getInputStream()) {
                input = stream.readNBytes((int) MAX_INPUT_BYTES + 1);
            }
            if (input.length > MAX_INPUT_BYTES) throw error(ErrorCode.FILE_TOO_LARGE, "Image must be at most 10 MiB.");
            BufferedImage decoded;
            // Explicit memory streams prevent ImageIO from creating temporary files.
            try (ImageInputStream stream = new MemoryCacheImageInputStream(new ByteArrayInputStream(input))) {
                Iterator<ImageReader> readers = ImageIO.getImageReaders(stream);
                if (!readers.hasNext()) throw error(ErrorCode.IMAGE_PROCESSING_FAILED, "Image cannot be decoded.");
                ImageReader reader = readers.next();
                try {
                    reader.setInput(stream, true, true);
                    String format = reader.getFormatName().toLowerCase(Locale.ROOT);
                    String expected = mime.equalsIgnoreCase("image/png") ? "png" : mime.equalsIgnoreCase("image/webp") ? "webp" : "jpeg";
                    if (!(format.equals(expected) || expected.equals("jpeg") && format.equals("jpg")))
                        throw error(ErrorCode.INVALID_FILE_TYPE, "Image content does not match its MIME type.");
                    int width = reader.getWidth(0), height = reader.getHeight(0);
                    if (width < 1 || height < 1 || width > MAX_INPUT_DIMENSION || height > MAX_INPUT_DIMENSION
                            || (long) width * height > MAX_PIXELS)
                        throw error(ErrorCode.INVALID_IMAGE_DIMENSIONS, "Image dimensions exceed the safe processing limit.");
                    ImageReadParam read = reader.getDefaultReadParam();
                    int sample = Math.max(1, Math.max(width, height) / MAX_OUTPUT_DIMENSION);
                    read.setSourceSubsampling(sample, sample, 0, 0);
                    decoded = reader.read(0, read);
                } finally { reader.dispose(); }
            }
            if (decoded == null) throw new IOException("Decoder returned no image");
            double scale = Math.min(1.0, (double) MAX_OUTPUT_DIMENSION / Math.max(decoded.getWidth(), decoded.getHeight()));
            BufferedImage image = resize(decoded, Math.max(1, (int) Math.round(decoded.getWidth() * scale)),
                    Math.max(1, (int) Math.round(decoded.getHeight() * scale)));
            decoded.flush();
            try {
                for (int round = 0; round < 6; round++) {
                    for (float quality : new float[] {0.85f, 0.7f, 0.55f}) {
                        byte[] bytes = jpeg(image, quality);
                        if (bytes.length <= MAX_OUTPUT_BYTES) {
                            log.info("Image optimization complete width={} height={} outputBytes={}", image.getWidth(), image.getHeight(), bytes.length);
                            return new OptimizedImage(bytes, image.getWidth(), image.getHeight(), "image/jpeg", "jpg");
                        }
                    }
                    BufferedImage smaller = resize(image, Math.max(1, image.getWidth() * 3 / 4), Math.max(1, image.getHeight() * 3 / 4));
                    image.flush(); image = smaller;
                }
                throw new IOException("Cannot satisfy image output size policy");
            } finally { image.flush(); }
        } catch (BusinessException ex) { throw ex; }
        catch (IOException | RuntimeException ex) {
            log.warn("Image processing failed", ex);
            throw error(ErrorCode.IMAGE_PROCESSING_FAILED, "Image could not be processed. Please choose another image.");
        } finally { processingSlots.release(); }
    }

    private BufferedImage resize(BufferedImage input, int width, int height) {
        BufferedImage output = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = output.createGraphics();
        try {
            graphics.setColor(Color.WHITE); graphics.fillRect(0, 0, width, height);
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            graphics.drawImage(input, 0, 0, width, height, null);
        } finally { graphics.dispose(); }
        return output;
    }

    private byte[] jpeg(BufferedImage image, float quality) throws IOException {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ImageOutputStream stream = new MemoryCacheImageOutputStream(output)) {
            writer.setOutput(stream);
            ImageWriteParam params = writer.getDefaultWriteParam();
            params.setCompressionMode(ImageWriteParam.MODE_EXPLICIT); params.setCompressionQuality(quality);
            writer.write(null, new IIOImage(image, null, null), params);
            stream.flush();
        } finally { writer.dispose(); }
        return output.toByteArray();
    }

    private BusinessException error(ErrorCode code, String message) {
        return new BusinessException(code, message);
    }
}
