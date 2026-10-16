package vn.nutrimom.nutrition.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Danh mục chất dinh dưỡng (bảng {@code app.nutrients}, migration V57).
 *
 * <p>Tồn tại để <strong>đơn vị không bị nhập tuỳ ý trong từng món</strong>: loader seed bắt mọi
 * dòng {@code ingredient_nutrients} phải khai đúng {@link #getUnit()} của chất tương ứng. Thiếu
 * danh mục này thì {@code mg} và {@code mcg} sẽ lẫn nhau giữa các công thức mà không ai phát hiện.</p>
 */
@Entity
@Table(name = "nutrients", schema = "app")
public class NutrientEntity {

    @Id
    @Column(name = "code", nullable = false, length = 32)
    private String code;

    @Column(name = "name", nullable = false, length = 255)
    private String name;

    @Column(name = "unit", nullable = false, length = 16)
    private String unit;

    public String getCode() { return code; }
    public void setCode(String value) { code = value; }

    public String getName() { return name; }
    public void setName(String value) { name = value; }

    public String getUnit() { return unit; }
    public void setUnit(String value) { unit = value; }
}
