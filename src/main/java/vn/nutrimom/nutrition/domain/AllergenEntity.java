package vn.nutrimom.nutrition.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Thành phần gây dị ứng ở mức danh mục (bảng {@code app.allergens}, migration V57).
 *
 * <p>Người dùng chọn từ danh mục này ở nhánh 3, nên mã phải ổn định và không phụ thuộc cách viết
 * của từng công thức.</p>
 */
@Entity
@Table(name = "allergens", schema = "app")
public class AllergenEntity {

    @Id
    @Column(name = "id", nullable = false, length = 64)
    private String id;

    @Column(name = "name", nullable = false, length = 255)
    private String name;

    public String getId() { return id; }
    public void setId(String value) { id = value; }

    public String getName() { return name; }
    public void setName(String value) { name = value; }
}
