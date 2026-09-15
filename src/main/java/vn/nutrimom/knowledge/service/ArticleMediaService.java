package vn.nutrimom.knowledge.service;

import java.time.*;
import java.util.UUID;
import org.slf4j.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;
import vn.nutrimom.auth.repository.UserRepository;
import vn.nutrimom.common.exception.BusinessException;
import vn.nutrimom.knowledge.domain.ArticleMedia;
import vn.nutrimom.knowledge.dto.ArticleDtos.MediaUpload;
import vn.nutrimom.knowledge.repository.ArticleMediaRepository;

@Service
public class ArticleMediaService {
    private static final Logger log = LoggerFactory.getLogger(ArticleMediaService.class);
    private final ImageOptimizationService optimizer;
    private final R2StorageService storage;
    private final ArticleMediaRepository media;
    private final UserRepository users;
    private final TransactionTemplate tx;
    public ArticleMediaService(ImageOptimizationService optimizer, R2StorageService storage,
            ArticleMediaRepository media, UserRepository users, PlatformTransactionManager transactions) {
        this.optimizer = optimizer; this.storage = storage; this.media = media; this.users = users;
        this.tx = new TransactionTemplate(transactions);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }
    public MediaUpload upload(MultipartFile file, String alt, String caption, String adminId) {
        if (alt != null && alt.length() > 500 || caption != null && caption.length() > 1000)
            throw new BusinessException(HttpStatus.UNPROCESSABLE_CONTENT, "VALIDATION_ERROR", "alt or caption exceeds the allowed length.");
        var admin = users.findById(adminId).orElseThrow(() -> new BusinessException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Account not found."));
        log.info("Media upload start adminId={}", adminId);
        var image = optimizer.optimize(file);
        String id = UUID.randomUUID().toString();
        LocalDate now = LocalDate.now(ZoneOffset.UTC);
        String key = "article/%04d/%02d/%s.%s".formatted(now.getYear(), now.getMonthValue(), id, image.extension());
        String url = storage.upload(key, image);
        try {
            // execute returns only after commit, so commit-time failures also trigger R2 cleanup.
            MediaUpload response = tx.execute(transaction -> {
                ArticleMedia record = new ArticleMedia(); record.setId(id); record.setImageKey(key); record.setImageUrl(url);
                record.setAlt(alt); record.setCaption(caption); record.setWidth(image.width()); record.setHeight(image.height());
                record.setSizeBytes(image.bytes().length); record.setContentType(image.contentType()); record.setUploadedBy(admin);
                media.saveAndFlush(record);
                return new MediaUpload(id, url, key, alt, caption, image.width(), image.height(), image.bytes().length);
            });
            log.info("Media DB persistence succeeded key={} id={}", key, id);
            return response;
        } catch (RuntimeException ex) {
            log.error("Media DB persistence failed key={}", key, ex);
            try { storage.delete(key); } catch (RuntimeException cleanup) { ex.addSuppressed(cleanup); }
            throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR, "DB_SAVE_FAILED", "Image metadata could not be saved. Please retry.", true);
        }
    }
}
