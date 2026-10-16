package vn.nutrimom.nutrition.repository;

import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import vn.nutrimom.nutrition.domain.IngredientEntity;

/** Danh mục seed, chỉ đọc sau khi loader nạp xong. */
public interface IngredientRepository extends JpaRepository<IngredientEntity, String> {

    /**
     * Tìm nguyên liệu theo tên chuẩn <strong>hoặc tên địa phương</strong>.
     *
     * <p>Phải quét cả bảng alias, không chỉ {@code canonical_name}: người dùng gõ "tôm sú" trong
     * khi seed ghi tên khác thì không tìm ra, và một nguyên liệu không tìm được là một nguyên liệu
     * không khai được vào mục cần tránh.</p>
     */
    @Query("""
            SELECT DISTINCT i FROM IngredientEntity i
            LEFT JOIN i.aliases a
            WHERE LOWER(i.canonicalName) LIKE LOWER(CONCAT('%', :term, '%'))
               OR LOWER(a) LIKE LOWER(CONCAT('%', :term, '%'))
            ORDER BY i.canonicalName ASC
            """)
    List<IngredientEntity> search(@Param("term") String term, Pageable limit);

    List<IngredientEntity> findAllByOrderByCanonicalNameAsc(Pageable limit);
}
